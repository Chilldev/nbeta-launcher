# Nbeta Launcher

A fast Android home screen with its own Discover-style feed. Swipe right from home for the feed, swipe up for apps and search.

Kotlin + Jetpack Compose, minSdk 29, targetSdk 36. The release APK is about 4 MB.

## Features

**Home**
- Clock, date, weather, next alarm and next calendar event at a glance. Each one is tappable.
- Favourites grid and dock. Drag to reorder in edit mode (long-press empty space › Edit home screen).
- App shortcuts pinned from the long-press menu or from other apps' "Add to home screen" requests.
- App widgets on the home screen or the feed page: resize taller or shorter, reorder, reconfigure, move between pages.
- Gestures: swipe up for the drawer, swipe down for notifications, quick settings or search, double-tap to lock.

**App drawer and search**
- Drawer: alphabetical grid with a letter fast-scroller, a "suggested" row ranked by frecency, and the keyboard opens immediately.
- Work profile tab with pause/resume. Android 15+ private space with lock/unlock.
- Universal search across:
  - apps (prefix, word, initials, CamelCase, fuzzy and one-typo matching; accents and Arabic letter variants are folded)
  - app shortcuts, contacts (call or message inline), a calculator and ~30 system settings pages
  - the web (Google, DuckDuckGo, Brave, Bing or Startpage), URLs and the Play Store
- Long-press menu: app shortcuts, add to home or dock, app info, rename, hide, uninstall.
- Icon shapes (system, circle, squircle, rounded square, teardrop), ADW/Nova icon packs, Android 13 themed icons and notification dots.

**Feed**
- RSS 2.0, RSS 1.0, Atom and JSON Feed. You can also paste a website, a YouTube channel or a subreddit, and the feed URL is discovered automatically.
- "For you" ordering: recency × how often you open each source, then a pass that stops one source from filling the top. There is also a plain "Latest" order.
- Unread, Saved and per-source filters. Hide a story, or see more or fewer from a source.
- Article images come from the feed (media:content, enclosures, YouTube thumbnails, inline `<img>`). If the feed has none, the page's `og:image` is fetched, only for cards on screen.
- A "Today" card with weather (Open-Meteo, no key, no account) and your next events.
- Stories open in a pre-warmed Custom Tab.
- Background refresh with WorkManager (interval and Wi‑Fi-only are configurable). OPML import and export.

**Settings**: appearance (Material You, wallpaper dimming, text colour on wallpaper), grid and icon sizes, drawer, icons, gestures, search providers, feed sources, weather location, and JSON backup/restore.

## Speed techniques

A launcher is judged on how it feels on HOME and swipe-up, not on cold start alone. What the code does for that:

| Technique | Where | Why |
|---|---|---|
| App list snapshot on disk | `AppRepository` | The first frame after process death shows the real home without waiting ~50–150 ms for `LauncherApps`. The live list replaces it a moment later. |
| Pre-rendered icon cache (Launcher3 approach) | `IconRepository` | Each icon is shaped and badged once and stored as lossless WebP. After that it's a single decode into a **hardware bitmap**, served from an LRU. Nothing touches PackageManager on the UI path. |
| Icon prewarm, home first | `LauncherRoot` | Every icon in the drawer is already in memory before you open it. |
| Drag state read only in `graphicsLayer {}` | `SheetController`, `HomePage`, `AppDrawer` | Dragging the drawer redraws without recomposing anything. |
| Click bounds captured in `onPlaced` | `BoundsHolder` | Cheap field writes instead of `onGloballyPositioned` on every scroll frame. The launch animation still starts from the icon. |
| Search index precomputed | `SearchEngine`, `Searchable` | Folding and word splitting run once per list change, so a keystroke is a linear scan on `Dispatchers.Default`. |
| Feed parsing with `XmlPullParser` | `FeedParser` | One streaming pass, no DOM, no reflection. |
| Feed ranking off the main thread | `FeedPage` | The list only recomposes with the finished result. |
| HTTP cache with revalidation | `FeedRepository` | Unchanged feeds come back as a 304. Sources are fetched in parallel. |
| Lazy lists with `key` and `contentType` | everywhere | Item reuse instead of recreation. |
| Custom Tabs warm-up and `mayLaunchUrl` | `CustomTabs` | Opening a story skips most of the browser's cold start. |
| No DI framework, no annotation processing | `AppGraph` | Nothing reflective at startup. |
| WorkManager initialised on demand, Coil created lazily | `NbetaApp` | Neither is on the cold-start path. |
| Small JSON stores, debounced off-thread writes | `JsonStore` | Settings are read synchronously (a few KB) so the first frame has your layout. |
| `singleTask`, `stateNotNeeded`, `resumeWhilePausing`, broad `configChanges`, no starting window | manifest | HOME is instant, and rotation or dark mode changes don't recreate the activity. |
| R8 full mode + resource shrinking | `app/build.gradle.kts` | Smaller, faster code. Always measure release builds. |
| **Baseline Profile + Startup Profile** | `:baselineprofile` | The home, drawer, search and feed paths are AOT-compiled at install, and DEX layout is optimised for startup. |

## Build

```bash
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk (debug-signed)
./gradlew :app:testDebugUnitTest        # matcher + calculator tests
./gradlew :app:generateReleaseBaselineProfile                 # needs a device/emulator (API 33+)
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest  # startup + frame timing
```

Install it, then choose **Nbeta** as the home app (Settings › Apps › Default apps › Home app, or use the banner in Nbeta settings). App shortcuts, pinning, work-profile pause and private space only work while Nbeta is the default home app. That is an Android rule.

Optional permissions are requested only when you turn on the matching feature:
- contacts (search)
- calendar and approximate location (glance and feed)
- notification access (dots)
- the "Nbeta gestures" accessibility service (double-tap to lock). It reads no screen content.

## Layout

```
app/src/main/java/com/mali/nbeta/
  NbetaApp.kt, AppGraph.kt      application + hand-wired dependencies
  data/                         settings store, apps/icons/shortcuts, search, feed, glance, widgets
  system/                       notification-dot listener, accessibility global actions, status-bar expand
  ui/                           LauncherActivity/Root/Controller, home/, drawer/, feed/, menu/, widgets/, settings/
baselineprofile/                profile generator + macrobenchmarks
```

## Known limits

- Recents (Overview) and the swipe-up-to-home animation belong to the system launcher (Quickstep). No third-party launcher can replace them without root.
- Some feeds rate-limit unauthenticated readers (Reddit in particular). Cached stories stay visible when that happens.
- UI strings are inline English.
