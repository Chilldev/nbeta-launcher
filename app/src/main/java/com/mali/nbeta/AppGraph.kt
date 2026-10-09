package com.mali.nbeta

import android.app.Application
import com.mali.nbeta.data.SettingsRepository
import com.mali.nbeta.data.apps.AppRepository
import com.mali.nbeta.data.apps.IconRepository
import com.mali.nbeta.data.apps.ShortcutRepository
import com.mali.nbeta.data.feed.FeedRefreshWorker
import com.mali.nbeta.data.feed.FeedRepository
import com.mali.nbeta.data.glance.GlanceRepository
import com.mali.nbeta.data.reddit.RedditClient
import com.mali.nbeta.data.reader.ReaderRepository
import com.mali.nbeta.data.media.MediaRepository
import com.mali.nbeta.data.search.SearchEngine
import com.mali.nbeta.data.widgets.WidgetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies: no reflection or annotation processing on the startup path. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(app, scope)
    val apps = AppRepository(app, scope, settings)
    val icons = IconRepository(app) { settings.value.iconOverrides }
    val shortcuts = ShortcutRepository(app, scope, apps)
    val search = SearchEngine(app, scope, apps, shortcuts, settings)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(app.cacheDir, "http"), 32L * 1024 * 1024))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(
                    if (req.header("User-Agent") != null) req
                    else req.newBuilder().header("User-Agent", "Mozilla/5.0 (compatible; NbetaLauncher/${BuildConfig.VERSION_NAME}; Android)").build(),
                )
            }
            .build()
    }

    val reddit: RedditClient by lazy { RedditClient(app, scope) { http } }
    val feed: FeedRepository by lazy { FeedRepository(app, scope, settings, { http }, reddit) }
    val media: MediaRepository by lazy { MediaRepository(app) }
    val reader: ReaderRepository by lazy { ReaderRepository(app, scope) { http } }
    val glance: GlanceRepository by lazy { GlanceRepository(app, scope, settings) { http } }
    val widgets: WidgetRepository by lazy { WidgetRepository(app, settings) }

    fun start() {
        apps.start()
        scope.launch(Dispatchers.IO) {
            // Pay for JSON/HTTP setup off the main thread, before the user swipes to the feed.
            feed
            glance
        }
        scope.launch {
            delay(5_000)
            val s = settings.value
            FeedRefreshWorker.schedule(app, if (s.feedEnabled) s.feedRefreshHours else 0, s.feedWifiOnly)
            widgets.cleanupOrphans()
            settings.flow.map { Triple(it.feedEnabled, it.feedRefreshHours, it.feedWifiOnly) }.distinctUntilChanged().drop(1).collect { (on, h, wifi) ->
                FeedRefreshWorker.schedule(app, if (on) h else 0, wifi)
            }
        }
    }
}
