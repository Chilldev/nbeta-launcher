# Nbeta Launcher

A fast Android home screen with its own Discover-style feed. Swipe right from home for the feed, swipe up for apps and search.

Kotlin + Jetpack Compose, minSdk 29, targetSdk 36. The release APK is about 4 MB.

## Features

**Home**
- Clock, date, weather, next alarm and next calendar event at a glance. Each one is tappable.
- Multiple home pages and a dock. Long-press then drag anything:
  - to reorder it, or pull it from the drawer or search onto home
  - drop it on another app to make a folder
  - hold it at the screen edge to move to (or create) another page
  - drop it on Remove / App info / Uninstall
- Folders: tap to open, rename inline, drag apps out of them. A folder with one app left dissolves on its own.
- App shortcuts pinned from the long-press menu or from other apps' "Add to home screen" requests.
- App widgets on any home page or the feed page. In edit mode, drag a handle to resize. Reorder, reconfigure, and move widgets between home and feed.
- Gestures: swipe up for the drawer, swipe down for notifications, quick settings or search, double-tap to lock.
- Media player widget, built in, in four styles (pill, compact, large with a seek bar, full-cover artwork):
  - shows which app is playing (icon and name) and opens that player on tap
  - elapsed time and time left (tap to show total length); tap or drag the bar to seek, with the time shown while dragging
  - album and genre when the player provides them, colours from the cover art
  - the player's own buttons (like, shuffle, ±15 s…) and a switcher when two players have something loaded
- Wallpaper: the phone's own Wallpaper & style screen, any gallery app (Samsung Gallery, Google Photos, Files…) with the system's crop-and-apply screen, live wallpapers and wallpaper apps.

**App drawer and search**
- Drawer: alphabetical grid with a letter fast-scroller, a "suggested" row ranked by frecency, and the keyboard opens immediately.
- Work profile tab with pause/resume. Android 15+ private space with lock/unlock.
- Universal search across:
  - apps (prefix, word, initials, CamelCase, fuzzy and one-typo matching; accents and Arabic letter variants are folded)
  - app shortcuts, contacts (call or message inline), a calculator and ~30 system settings pages
  - the web (Google, DuckDuckGo, Brave, Bing or Startpage), URLs and the Play Store
- Long-press menu: the app's latest notifications (open the exact chat, **reply inline**, mark as read, dismiss), app shortcuts, add to home or dock, app info, rename, change icon, lock, hide, uninstall.
- People and messages in search: recent chats from WhatsApp, Slack, Telegram, Messages… (conversation shortcuts) next to your contacts, and the text of current notifications.
- Icon shapes (system, circle, squircle, rounded square, teardrop), ADW/Nova icon packs, Android 13 themed icons and notification dots.
- Change icon for any single app: choose any icon from any installed pack, with search.

**Feed**
- RSS 2.0, RSS 1.0, Atom and JSON Feed. You can also paste a website, a YouTube channel or a subreddit, and the feed URL is discovered automatically.
- "For you" ordering: recency × how often you open each source, then a pass that stops one source from filling the top. There is also a plain "Latest" order.
- Unread, Saved and per-source filters. Hide a story, or see more or fewer from a source.
- Article images come from the feed (media:content, enclosures, YouTube thumbnails, inline `<img>`). If the feed has none, the page's `og:image` is fetched, only for cards on screen.
- A "Today" card with weather (Open-Meteo, no key, no account) and your next events.
- **Reader view**: a Readability-style extractor (jsoup) shows the article natively. It handles RTL, adjusts text size, prefetches the top stories on Wi‑Fi, and falls back to a pre-warmed Custom Tab when a page can't be read.
- Mute keywords: whole-word matching that ignores accents and Arabic letter variants. Mute from settings or from a story's menu.
- Breaking-news alerts: pick sources (bell icon) and/or keywords, and new matching stories are notified during background refresh, at most 3 per check. "Check now" tests it.
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

## Measured

Macrobenchmark on the Android 16 emulator (host GPU, Apple M4), release build. A physical device will differ, so treat these as relative numbers:

| | Without profile | With baseline profile |
|---|---|---|
| Cold start, time to initial display (median) | 275 ms | **218 ms** |
| Launcher journey (drawer, search, feed), frame CPU time P50 / P99 | – | 2.4 ms / 27 ms |
| Frame overrun P50 / P95 / P99 (negative = ahead of deadline) | – | −6.3 / 3.4 / 14.3 ms |

Cold start matters less for a home app than for most apps, because the system keeps the launcher process alive. The usual path is a warm HOME press, which is instant.

## Build

```bash
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk (debug-signed)
./gradlew :app:testDebugUnitTest        # matcher + calculator tests
./gradlew :app:generateReleaseBaselineProfile                 # needs a device/emulator (API 33+)
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest  # startup + frame timing
```

Release builds are signed with `signing/nbeta-release.jks` (git-ignored; password in `signing/keystore.properties`). **Back both up somewhere safe.** Without them, future updates can't install over the current app.

Install it, then choose **Nbeta** as the home app (Settings › Apps › Default apps › Home app, or use the banner in Nbeta settings). App shortcuts, pinning, work-profile pause and private space only work while Nbeta is the default home app. That is an Android rule.

Optional permissions are requested only when you turn on the matching feature:
- contacts (search)
- calendar and approximate location (glance and feed)
- notification access (dots)
- the "Nbeta gestures" accessibility service (double-tap to lock). It reads no screen content.

## Updates

Nbeta checks this repository's [latest release](../../releases/latest) once a day and from Settings › Updates & about.
A release's APK asset must be named `nbeta-<versionCode>.apk`. Nbeta only installs it if it's signed with the same
key as the installed app.

To publish one, bump `versionCode`/`versionName` in `app/build.gradle.kts`, commit, then run `./scripts/release.sh`.

## Reddit

Reddit now requires OAuth and approves Data API access per app ([Responsible Builder Policy](https://support.reddithelp.com/hc/en-us/articles/42728983564564-Responsible-Builder-Policy)). Nbeta signs in the sanctioned way, with your own "installed app" client ID:

1. Request API access with Reddit's [form](https://support.reddithelp.com/hc/en-us/requests/new?ticket_form_id=14868593862164) (personal, non-commercial feed reader).
2. Once approved, create an **installed app** at <https://www.reddit.com/prefs/apps> with redirect URI `nbeta://reddit-auth`.
3. In Nbeta: Settings › Feed › Reddit › Sign in with Reddit, then paste the client ID.

Once signed in:
- Subreddit, user and front-page sources load from `oauth.reddit.com` (100 requests/min, with a policy-compliant User-Agent).
- You can add your Reddit home feed in one tap.
- Reddit posts are kept for at most 48 hours, and each refresh replaces them, so posts deleted on Reddit disappear.
- Tokens are stored in `noBackupFilesDir`, so they are never backed up or exported. Sign-out revokes them.

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
- Reddit blocks or throttles unauthenticated feed requests. See "Reddit" below.
- UI strings are inline English.
