package com.mali.nbeta.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class ThemeMode { System, Light, Dark }
enum class TextOnWallpaper { Auto, Light, Dark }
enum class IconShape { System, Circle, Squircle, RoundedSquare, Teardrop }
enum class SwipeDownAction { Notifications, QuickSettings, Search, None }
enum class DoubleTapAction { LockScreen, None }
enum class DrawerSort { Alphabetical, MostUsed }
enum class FeedOrder { ForYou, Latest }
enum class WebEngine(val label: String, val template: String) {
    Google("Google", "https://www.google.com/search?q=%s"),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    Brave("Brave", "https://search.brave.com/search?q=%s"),
    Bing("Bing", "https://www.bing.com/search?q=%s"),
    Startpage("Startpage", "https://www.startpage.com/do/search?q=%s"),
}
enum class LinkOpener { CustomTab, Browser }
enum class TempUnit { Celsius, Fahrenheit }
enum class WidgetPlacement { Home, Feed }

/** Something placed on the home grid or dock. */
@Immutable
@Serializable
sealed interface HomeItem {
    @Serializable
    @kotlinx.serialization.SerialName("app")
    data class App(val key: String) : HomeItem

    @Serializable
    @kotlinx.serialization.SerialName("shortcut")
    data class Shortcut(val packageName: String, val id: String, val userSerial: Long, val label: String) : HomeItem
}

@Immutable
@Serializable
data class WidgetSlot(
    val id: Int,
    val placement: WidgetPlacement = WidgetPlacement.Home,
    val heightDp: Int = 0, // 0 = provider's default height
)

@Immutable
@Serializable
data class FeedSource(
    val id: String,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val enabled: Boolean = true,
)

@Immutable
@Serializable
data class LauncherSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val textOnWallpaper: TextOnWallpaper = TextOnWallpaper.Auto,
    val wallpaperDim: Float = 0f,
    val drawerOpacity: Float = 0.94f,
    // Home
    val homeColumns: Int = 4,
    val iconSizeDp: Int = 56,
    val homeLabels: Boolean = true,
    val showDock: Boolean = true,
    val showHomeSearch: Boolean = true,
    val glanceClock: Boolean = true,
    val glanceWeather: Boolean = true,
    val glanceCalendar: Boolean = true,
    val glanceAlarm: Boolean = true,
    val homeItems: List<HomeItem> = emptyList(),
    val dockItems: List<HomeItem> = emptyList(),
    val layoutInitialized: Boolean = false,
    // Drawer
    val drawerColumns: Int = 5,
    val drawerLabels: Boolean = true,
    val autoKeyboard: Boolean = true,
    val showSuggestions: Boolean = true,
    val drawerSort: DrawerSort = DrawerSort.Alphabetical,
    val hiddenApps: Set<String> = emptySet(),
    val renamedApps: Map<String, String> = emptyMap(),
    // Icons
    val iconShape: IconShape = IconShape.System,
    val iconPack: String? = null,
    val themedIcons: Boolean = false,
    val notificationDots: Boolean = true,
    // Gestures
    val swipeDown: SwipeDownAction = SwipeDownAction.Notifications,
    val doubleTap: DoubleTapAction = DoubleTapAction.LockScreen,
    // Search
    val searchContacts: Boolean = true,
    val searchCalculator: Boolean = true,
    val searchShortcuts: Boolean = true,
    val searchSettings: Boolean = true,
    val webEngine: WebEngine = WebEngine.Google,
    // Feed
    val feedEnabled: Boolean = true,
    val feedSources: List<FeedSource> = DefaultFeeds.sources,
    val feedOrder: FeedOrder = FeedOrder.ForYou,
    val feedRefreshHours: Int = 2,
    val feedWifiOnly: Boolean = false,
    val feedFetchImages: Boolean = true,
    val linkOpener: LinkOpener = LinkOpener.CustomTab,
    // Weather
    val tempUnit: TempUnit = TempUnit.Celsius,
    val weatherCity: String? = null, // null = device location
    val weatherLat: Double? = null,
    val weatherLon: Double? = null,
    // Widgets
    val widgets: List<WidgetSlot> = emptyList(),
)

internal val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    coerceInputValues = true
    explicitNulls = false
}

/**
 * A small JSON file read synchronously once (it is a few KB, well under a millisecond) so the first
 * frame already has the user's layout. Writes are debounced and happen off the main thread.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: () -> T,
    scope: CoroutineScope,
    debounceMs: Long = 300,
) {
    private val state = MutableStateFlow(read() ?: default())
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    init {
        state.drop(1).debounce(debounceMs).onEach { write(it) }.launchIn(CoroutineScope(scope.coroutineContext + Dispatchers.IO))
    }

    fun update(transform: (T) -> T) = state.update(transform)

    fun replace(value: T) {
        state.value = value
    }

    private fun read(): T? = try {
        if (file.exists()) AppJson.decodeFromString(serializer, file.readText()) else null
    } catch (e: Exception) {
        Log.w("JsonStore", "Discarding unreadable ${file.name}", e)
        null
    }

    private fun write(value: T) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AppJson.encodeToString(serializer, value))
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.e("JsonStore", "Could not save ${file.name}", e)
        }
    }
}

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = JsonStore(File(context.filesDir, "settings.json"), LauncherSettings.serializer(), { LauncherSettings() }, scope)
    val flow: StateFlow<LauncherSettings> = store.flow
    val value: LauncherSettings get() = store.value
    fun update(transform: (LauncherSettings) -> LauncherSettings) = store.update(transform)

    fun export(): String = AppJson.encodeToString(LauncherSettings.serializer(), value)
    fun import(json: String) {
        store.replace(AppJson.decodeFromString(LauncherSettings.serializer(), json))
    }
}

object DefaultFeeds {
    val sources = listOf(
        FeedSource("verge", "https://www.theverge.com/rss/index.xml", "The Verge", "https://www.theverge.com"),
        FeedSource("ars", "https://feeds.arstechnica.com/arstechnica/index", "Ars Technica", "https://arstechnica.com"),
        FeedSource("bbc", "https://feeds.bbci.co.uk/news/world/rss.xml", "BBC World", "https://www.bbc.com/news"),
        FeedSource("aje", "https://www.aljazeera.com/xml/rss/all.xml", "Al Jazeera", "https://www.aljazeera.com"),
        FeedSource("hn", "https://hnrss.org/frontpage?points=100", "Hacker News", "https://news.ycombinator.com"),
        FeedSource("ap", "https://www.androidpolice.com/feed/", "Android Police", "https://www.androidpolice.com"),
    )

    /** One-tap suggestions in settings. */
    val catalog = listOf(
        FeedSource("nasa", "https://www.nasa.gov/feed/", "NASA", "https://www.nasa.gov"),
        FeedSource("guardian", "https://www.theguardian.com/world/rss", "The Guardian – World", "https://www.theguardian.com"),
        FeedSource("engadget", "https://www.engadget.com/rss.xml", "Engadget", "https://www.engadget.com"),
        FeedSource("9to5google", "https://9to5google.com/feed/", "9to5Google", "https://9to5google.com"),
        FeedSource("9to5mac", "https://9to5mac.com/feed/", "9to5Mac", "https://9to5mac.com"),
        FeedSource("wired", "https://www.wired.com/feed/rss", "Wired", "https://www.wired.com"),
        FeedSource("techcrunch", "https://techcrunch.com/feed/", "TechCrunch", "https://techcrunch.com"),
        FeedSource("natgeo", "https://www.nationalgeographic.com/pages/topic/latest-stories/feed", "National Geographic", "https://www.nationalgeographic.com"),
        FeedSource("espn", "https://www.espn.com/espn/rss/news", "ESPN", "https://www.espn.com"),
        FeedSource("r-android", "https://www.reddit.com/r/Android/.rss", "r/Android", "https://www.reddit.com/r/Android"),
        FeedSource("quanta", "https://www.quantamagazine.org/feed/", "Quanta Magazine", "https://www.quantamagazine.org"),
        FeedSource("lwn", "https://lwn.net/headlines/rss", "LWN.net", "https://lwn.net"),
    )
}
