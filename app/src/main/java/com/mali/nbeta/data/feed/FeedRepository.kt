package com.mali.nbeta.data.feed

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.mali.nbeta.R
import com.mali.nbeta.data.FeedOrder
import com.mali.nbeta.data.FeedSource
import com.mali.nbeta.data.JsonStore
import com.mali.nbeta.data.SettingsRepository
import com.mali.nbeta.data.reddit.RedditClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln

@Immutable
@Serializable
data class FeedItem(
    val id: String,
    val sourceId: String,
    val title: String,
    val link: String,
    val summary: String? = null,
    val imageUrl: String? = null,
    val author: String? = null,
    val published: Long,
    val imageTried: Boolean = false,
)

@Serializable
data class SourceStatus(val lastFetch: Long = 0, val error: String? = null, val count: Int = 0)

@Serializable
data class FeedCache(
    val items: List<FeedItem> = emptyList(),
    val read: Set<String> = emptySet(),
    val saved: List<FeedItem> = emptyList(),
    val dismissed: Set<String> = emptySet(),
    val mutedSources: Set<String> = emptySet(),
    val status: Map<String, SourceStatus> = emptyMap(),
    /** How often each source was opened; the "For you" order learns from it. */
    val opens: Map<String, Int> = emptyMap(),
    val lastRefresh: Long = 0,
    /** Stories already announced in a notification. */
    val notified: Set<String> = emptySet(),
)

sealed interface FeedFilter {
    data object All : FeedFilter
    data object Unread : FeedFilter
    data object Saved : FeedFilter
    data class Source(val id: String) : FeedFilter
}

class FeedRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    httpProvider: () -> OkHttpClient,
    private val reddit: RedditClient,
) {
    // Built on first network use (always on an IO thread), never on the main thread at startup.
    private val http by lazy(httpProvider)
    private val store = JsonStore(File(context.filesDir, "feed.json"), FeedCache.serializer(), { FeedCache() }, scope, debounceMs = 800)
    val cache: StateFlow<FeedCache> = store.flow

    /** The feed page's current filter; lives here so it survives the page leaving composition. */
    val filter = MutableStateFlow<FeedFilter>(FeedFilter.All)

    /**
     * The visible list, ranked off the main thread. The order is frozen between refreshes: marking read or resolving
     * an image updates cards in place instead of reshuffling what is under the user's thumb.
     */
    val arranged: StateFlow<List<FeedItem>> = run {
        var lastKey: Any? = null
        var lastOrder: List<String> = emptyList()
        combine(
            cache,
            filter,
            settings.flow.map { it.feedOrder }.distinctUntilChanged(),
            settings.flow.map { it.mutedKeywords }.distinctUntilChanged(),
        ) { c, f, o, muted ->
            val key = listOf(c.items.size, c.items.sumOf { it.id.hashCode().toLong() }, c.dismissed.size, c.mutedSources, f, o, muted, if (f == FeedFilter.Saved) c.saved.size else 0)
            if (key != lastKey) {
                lastKey = key
                lastOrder = arrange(c, o, f, KeywordMatcher(muted)).map { it.id }
            }
            val byId = HashMap<String, FeedItem>(c.items.size + c.saved.size)
            c.saved.forEach { byId[it.id] = it }
            c.items.forEach { byId[it.id] = it }
            lastOrder.mapNotNull { byId[it] }
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    private val notifier = NewsNotifier(context)
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val mutex = Mutex()

    fun refreshIfStale(maxAgeMs: Long = 20 * 60_000L) {
        if (System.currentTimeMillis() - cache.value.lastRefresh > maxAgeMs && !_refreshing.value) {
            scope.launch { refresh() }
        }
    }

    /** [notify]: announce new stories that match the user's alerts (background refreshes only). */
    suspend fun refresh(onlySource: String? = null, notify: Boolean = false) {
        if (!mutex.tryLock()) return
        _refreshing.value = true
        try {
            val sources = settings.value.feedSources.filter { it.enabled && (onlySource == null || it.id == onlySource) }
            val start = System.currentTimeMillis()
            val results = coroutineScope {
                sources.map { src -> async(Dispatchers.IO) { src to runCatching { fetch(src) } } }.awaitAll()
            }
            Log.d(TAG, "Fetched ${sources.size} feeds in ${System.currentTimeMillis() - start} ms")
            val before = cache.value.items.mapTo(HashSet()) { it.id }
            withContext(Dispatchers.Default) { merge(results, onlySource == null) }
            if (notify) {
                val fresh = cache.value.items.filter { it.id !in before }
                val picked = NewsNotifier.select(fresh, settings.value, cache.value.notified)
                if (picked.isNotEmpty()) {
                    notifier.post(picked, settings.value)
                    store.update { c -> c.copy(notified = (c.notified + picked.map { it.id }).toList().takeLast(500).toSet()) }
                }
            }
        } finally {
            _refreshing.value = false
            mutex.unlock()
        }
    }

    private fun fetch(src: FeedSource): ParsedFeed {
        if (reddit.handles(src.url)) return reddit.fetchListing(src.url, src.id)
        val req = Request.Builder().url(src.url)
            // Revalidate through OkHttp's cache: unchanged feeds come back as a cheap 304.
            .header("Cache-Control", "no-cache")
            .header("Accept", "application/rss+xml, application/atom+xml, application/feed+json, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 429 && reddit.listingPath(src.url) != null) {
                    throw IllegalStateException(context.getString(R.string.feed_error_rate_limited))
                }
                throw IllegalStateException("HTTP ${resp.code}")
            }
            return FeedParser.parse(resp.body.byteStream(), src.id, resp.request.url.toString(), resp.header("Content-Type"))
        }
    }

    private fun merge(results: List<Pair<FeedSource, Result<ParsedFeed>>>, full: Boolean) {
        val now = System.currentTimeMillis()
        val configured = settings.value.feedSources.map { it.id }.toSet()
        store.update { c ->
            val byId = LinkedHashMap<String, FeedItem>(c.items.size + 256)
            for (it in c.items) if (it.sourceId in configured) byId[it.id] = it
            val status = c.status.toMutableMap()
            for ((src, res) in results) {
                res.onSuccess { feed ->
                    // Reddit content must not outlive its deletion on Reddit: a successful fetch replaces the source's
                    // items wholesale, so removed posts disappear on the next refresh.
                    if (reddit.listingPath(src.url) != null) byId.values.removeAll { it.sourceId == src.id }
                    for (item in feed.items) {
                        val old = byId[item.id]
                        // Keep a resolved og:image and the original timestamp across refreshes.
                        byId[item.id] = if (old != null) item.copy(
                            imageUrl = item.imageUrl ?: old.imageUrl,
                            imageTried = old.imageTried,
                            published = minOf(old.published, item.published),
                        ) else item
                    }
                    status[src.id] = SourceStatus(now, null, feed.items.size)
                }.onFailure { e ->
                    Log.w(TAG, "Feed ${src.url} failed: ${e.message}")
                    status[src.id] = (status[src.id] ?: SourceStatus()).copy(lastFetch = now, error = e.message ?: e.javaClass.simpleName)
                }
            }
            val cutoff = now - 21L * 24 * 3600_000
            val redditCutoff = now - 48L * 3600_000 // Reddit asks clients to drop stored content within 48 hours
            val redditSources = settings.value.feedSources.filter { reddit.listingPath(it.url) != null }.mapTo(HashSet()) { it.id }
            val seenLinks = HashSet<String>()
            val items = byId.values
                .filter { it.published > if (it.sourceId in redditSources) redditCutoff else cutoff }
                .sortedByDescending { it.published }
                .filter { seenLinks.add(canonical(it.link)) }
                .groupBy { it.sourceId }.values.flatMap { it.take(60) }
                .sortedByDescending { it.published }
                .take(900)
            val ids = items.mapTo(HashSet()) { it.id }
            c.copy(
                items = items,
                read = c.read.filterTo(HashSet()) { it in ids },
                dismissed = c.dismissed.filterTo(HashSet()) { it in ids },
                status = status.filterKeys { it in configured },
                lastRefresh = if (full) now else c.lastRefresh,
            )
        }
    }

    private fun canonical(link: String) = link.substringBefore('#').substringBefore("?utm_").removeSuffix("/").lowercase()

    fun markRead(item: FeedItem) = store.update { c ->
        c.copy(read = c.read + item.id, opens = c.opens + (item.sourceId to (c.opens[item.sourceId] ?: 0) + 1))
    }

    fun toggleSaved(item: FeedItem) = store.update { c ->
        if (c.saved.any { it.id == item.id }) c.copy(saved = c.saved.filterNot { it.id == item.id })
        else c.copy(saved = listOf(item) + c.saved)
    }

    fun dismiss(item: FeedItem) = store.update { c -> c.copy(dismissed = c.dismissed + item.id) }

    fun undismiss(item: FeedItem) = store.update { c -> c.copy(dismissed = c.dismissed - item.id) }

    fun muteSource(id: String, muted: Boolean) = store.update { c ->
        c.copy(mutedSources = if (muted) c.mutedSources + id else c.mutedSources - id)
    }

    fun markAllRead() = store.update { c -> c.copy(read = c.items.mapTo(HashSet()) { it.id }) }

    fun clear() = store.update { c -> FeedCache(saved = c.saved) }

    /**
     * Builds the visible list. "For you" is recency with a learnt per-source boost, then a greedy pass that stops one
     * source from dominating a run of cards (Discover-style variety).
     */
    fun arrange(c: FeedCache, order: FeedOrder, filter: FeedFilter, muted: KeywordMatcher = KeywordMatcher(emptyList())): List<FeedItem> {
        val base = when (filter) {
            FeedFilter.Saved -> return c.saved
            FeedFilter.Unread -> c.items.filter { it.id !in c.read && it.id !in c.dismissed && it.sourceId !in c.mutedSources && !muted.matches(it) }
            is FeedFilter.Source -> return c.items.filter { it.sourceId == filter.id && it.id !in c.dismissed && !muted.matches(it) }
            FeedFilter.All -> c.items.filter { it.id !in c.dismissed && it.sourceId !in c.mutedSources && !muted.matches(it) }
        }
        if (order == FeedOrder.Latest) return base
        val now = System.currentTimeMillis()
        val totalOpens = c.opens.values.sum().coerceAtLeast(1)
        fun score(it: FeedItem): Double {
            val ageH = ((now - it.published).coerceAtLeast(0)) / 3_600_000.0
            val affinity = 1 + 0.6 * ln(1.0 + 10.0 * (c.opens[it.sourceId] ?: 0) / totalOpens)
            val read = if (it.id in c.read) 0.3 else 1.0
            val visual = if (it.imageUrl != null) 1.12 else 1.0
            return exp(-ageH / 20.0) * affinity * read * visual
        }
        val queues = base.groupBy { it.sourceId }.mapValues { (_, l) -> ArrayDeque(l.map { it to score(it) }.sortedByDescending { it.second }) }
        val recent = ArrayDeque<String>()
        val out = ArrayList<FeedItem>(base.size)
        while (out.size < base.size) {
            var best: String? = null
            var bestScore = -1.0
            for ((src, q) in queues) {
                val head = q.firstOrNull() ?: continue
                val repeats = recent.count { it == src }
                val s = head.second * Math.pow(0.5, repeats.toDouble())
                if (s > bestScore) {
                    bestScore = s
                    best = src
                }
            }
            val src = best ?: break
            out += queues.getValue(src).removeFirst().first
            recent.addLast(src)
            if (recent.size > 3) recent.removeFirst()
        }
        return out
    }

    // --- Article images for items whose feed has none (og:image), fetched lazily for visible cards only ---

    private val ogSemaphore = Semaphore(4)
    private val ogInFlight = ConcurrentHashMap.newKeySet<String>()
    private val ogMeta = Regex(
        "<meta[^>]+(?:property|name)\\s*=\\s*[\"'](?:og:image|twitter:image)(?::src)?[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']" +
            "|<meta[^>]+content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*(?:property|name)\\s*=\\s*[\"'](?:og:image|twitter:image)[\"']",
        RegexOption.IGNORE_CASE,
    )

    fun requestImage(item: FeedItem) {
        if (item.imageUrl != null || item.imageTried || !settings.value.feedFetchImages || !ogInFlight.add(item.id)) return
        scope.launch(Dispatchers.IO) {
            val url = ogSemaphore.withPermit { runCatching { fetchOgImage(item.link) }.getOrNull() }
            store.update { c ->
                fun FeedItem.resolved() = if (id == item.id) copy(imageUrl = url, imageTried = true) else this
                c.copy(items = c.items.map { it.resolved() }, saved = c.saved.map { it.resolved() })
            }
            ogInFlight.remove(item.id)
        }
    }

    private fun fetchOgImage(link: String): String? {
        val req = Request.Builder().url(link).header("Accept", "text/html").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            // The <head> is enough; never download whole article pages.
            val source = resp.body.source()
            source.request(96 * 1024)
            val head = source.buffer.snapshot().utf8()
            val m = ogMeta.find(head) ?: return null
            val raw = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace("&amp;", "&")
            return FeedParser.resolve(resp.request.url.toString(), raw)
        }
    }

    // --- Adding sources ---

    /** Turns whatever the user typed (site, YouTube channel, subreddit, or feed URL) into a working feed. */
    suspend fun discover(input: String): FeedSource = withContext(Dispatchers.IO) {
        var url = input.trim()
        if (!url.contains("://")) url = "https://$url"
        val sub = Regex("^https?://(www\\.|old\\.|new\\.)?reddit\\.com/r/([^/?#]+)").find(url)
        if (sub != null) url = "https://www.reddit.com/r/${sub.groupValues[2]}/.rss"
        if (reddit.handles(url)) {
            // Signed in: validate through the API instead of the throttled public RSS.
            val id = FeedParser.hash(url)
            reddit.fetchListing(url, id)
            return@withContext FeedSource(id, url, reddit.describe(url) ?: "Reddit", "https://www.reddit.com" + (reddit.listingPath(url)?.substringBefore('?')?.substringBeforeLast('/') ?: ""))
        }

        tryFeed(url)?.let { return@withContext it }
        val html = http.newCall(Request.Builder().url(url).header("Accept", "text/html").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            url = r.request.url.toString()
            r.body.string()
        }
        val linkTag = Regex("<link[^>]+>", RegexOption.IGNORE_CASE)
        val candidates = linkTag.findAll(html).map { it.value }.filter {
            it.contains("alternate", true) && (it.contains("rss+xml", true) || it.contains("atom+xml", true) || it.contains("feed+json", true))
        }.mapNotNull { Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            .map { FeedParser.resolve(url, it.replace("&amp;", "&")) }
            .toList()
        val base = URI(url).let { "${it.scheme}://${it.host}" }
        val guesses = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml", "/feed/").map { base + it }
        for (c in (candidates + guesses).distinct()) tryFeed(c)?.let { return@withContext it }
        throw IllegalStateException(context.getString(R.string.feed_error_no_feed, input))
    }

    private fun tryFeed(url: String): FeedSource? = try {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            if (!r.isSuccessful || type.contains("html")) return null
            val id = FeedParser.hash(url)
            val feed = FeedParser.parse(r.body.byteStream(), id, url, type)
            if (feed.items.isEmpty() && feed.title == null) return null
            FeedSource(id, url, feed.title?.takeIf { it.isNotBlank() } ?: URI(url).host.removePrefix("www."), feed.siteUrl)
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "Feed"
    }
}
