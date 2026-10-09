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
    suspend fun load(url: String): Article? = cache.get(url) ?: withContext(Dispatchers.IO) {
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
