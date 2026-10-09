package com.mali.nbeta.data.reader

import android.content.Context
import android.net.ConnectivityManager
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches and extracts articles; keeps recent ones in memory and prefetches the top stories on unmetered networks. */
class ReaderRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    httpProvider: () -> OkHttpClient,
) {
    private val http by lazy(httpProvider)
    private val cache = LruCache<String, Article>(24)
    private val failed = HashSet<String>()
    private val prefetchSlots = Semaphore(2)

    fun cached(url: String): Article? = cache.get(url)

    /** null when the page couldn't be turned into an article (paywall, app-like page...). */
    private val offlineDir = java.io.File(context.filesDir, "articles").apply { mkdirs() }
    private fun offlineFile(url: String) = java.io.File(offlineDir, com.mali.nbeta.data.feed.FeedParser.hash(url) + ".json")

    fun isOffline(url: String) = offlineFile(url).exists()

    /** Saved stories are stored as extracted text so they open without a connection. */
    suspend fun keepOffline(url: String) = withContext(Dispatchers.IO) {
        if (isOffline(url)) return@withContext
        val a = load(url) ?: return@withContext
        runCatching { offlineFile(url).writeText(com.mali.nbeta.data.AppJson.encodeToString(Article.serializer(), a)) }
        // Warm the image cache too, so the pictures are there offline.
        val loader = coil3.SingletonImageLoader.get(context)
        (listOfNotNull(a.leadImage) + a.blocks.filterIsInstance<Block.Image>().map { it.url }).take(6).forEach { img ->
            loader.enqueue(coil3.request.ImageRequest.Builder(context).data(img).build())
        }
    }

    fun dropOffline(url: String) {
        offlineFile(url).delete()
    }

    private fun readOffline(url: String): Article? = offlineFile(url).takeIf { it.exists() }?.let {
        runCatching { com.mali.nbeta.data.AppJson.decodeFromString(Article.serializer(), it.readText()) }.getOrNull()
    }

    suspend fun load(url: String): Article? = cache.get(url) ?: withContext(Dispatchers.IO) {
        readOffline(url)?.let { cache.put(url, it); return@withContext it }
        if (url in failed) return@withContext null
        runCatching {
            val req = Request.Builder().url(url).header("Accept", "text/html,application/xhtml+xml").build()
            http.newCall(req).execute().use { r ->
                val type = r.header("Content-Type").orEmpty()
                if (!r.isSuccessful || (type.isNotEmpty() && !type.contains("html"))) {
                    android.util.Log.i("Reader", "Not readable: HTTP ${r.code} $type for $url")
                    return@use null
                }
                val body = r.body.source().apply { request(3L * 1024 * 1024) }.buffer.readUtf8()
                Readability.extract(body, r.request.url.toString()).also {
                    if (it == null) android.util.Log.i("Reader", "No article found in ${body.length} chars at $url")
                }
            }
        }.onFailure { android.util.Log.w("Reader", "Extraction failed for $url", it) }
            .getOrNull().also { if (it != null) cache.put(url, it) else synchronized(failed) { failed += url } }
    }

    fun prefetch(urls: List<String>) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm.isActiveNetworkMetered) return
        for (u in urls) if (cache.get(u) == null) scope.launch(Dispatchers.IO) { prefetchSlots.withPermit { load(u) } }
    }
}
