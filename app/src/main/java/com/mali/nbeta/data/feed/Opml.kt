package com.mali.nbeta.data.feed

import android.content.Context
import android.util.Xml
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mali.nbeta.NbetaApp
import com.mali.nbeta.data.FeedSource
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.concurrent.TimeUnit

object Opml {
    fun export(sources: List<FeedSource>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n  <head><title>Nbeta feeds</title></head>\n  <body>\n")
        for (s in sources) {
            append("    <outline type=\"rss\" text=\"").append(esc(s.title)).append("\" title=\"").append(esc(s.title))
                .append("\" xmlUrl=\"").append(esc(s.url)).append('"')
            s.siteUrl?.let { append(" htmlUrl=\"").append(esc(it)).append('"') }
            append("/>\n")
        }
        append("  </body>\n</opml>\n")
    }

    fun import(input: InputStream): List<FeedSource> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val out = ArrayList<FeedSource>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name.equals("outline", true)) {
                val url = p.getAttributeValue(null, "xmlUrl")
                if (!url.isNullOrBlank()) {
                    val title = p.getAttributeValue(null, "title") ?: p.getAttributeValue(null, "text") ?: url
                    out += FeedSource(FeedParser.hash(url), url, title, p.getAttributeValue(null, "htmlUrl"))
                }
            }
            ev = p.next()
        }
        return out.distinctBy { it.url }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}

class FeedRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as NbetaApp).graph
        if (!graph.settings.value.feedEnabled) return Result.success()
        graph.feed.refresh(notify = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "feed-refresh"

        fun schedule(context: Context, hours: Int, wifiOnly: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (hours <= 0) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
