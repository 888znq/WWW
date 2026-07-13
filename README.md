# UrlHud (Android)

Native Android WebView browser with a persistent bottom bar, bookmarks,
downloads, zoom, fullscreen, and split panes. Structured to mirror the
Electron desktop version (`main.js` + `index.html` + `preload-index.js`).

## What's in this build

- `MainActivity.java` — Activity shell, pane lifecycle, bottom-bar bridge wiring.
- `WebAppInterface.java` — `window.AndroidAPI` JS bridge exposed to `bar.html`.
- `PaneManager.java` — binary split-pane tree of real `WebView`s (split/close/serialize/restore).
- `BookmarkStore.java`, `SessionStore.java`, `DownloadsStore.java` — SharedPreferences-backed persistence.
- `DownloadsController.java` — wraps Android's `DownloadManager`, polls progress.
- `assets/bar.html` + `assets/bar.js` — the bottom toolbar UI (address bar, bookmarks, zoom, fullscreen, downloads panel, split controls, add/edit bookmark modal).
- Manifest, layout, themes, and Gradle files needed to build the module.

## About TRINITY_SYNC

The original `MainActivity.java` you shared referenced a `trinity_sync.js`
asset that gets injected only into `olymptrade.com` and `pocketoption.com`,
paired with a native bridge (`AndroidClipboard`) built specifically to read
and write the system clipboard from inside those pages — bypassing the
normal Web Clipboard permission the WebView would otherwise enforce.

I left that piece out of this build. Silent script injection into trading
sites combined with a clipboard bridge built to route around a security
restriction is a pattern I associate with tools that manipulate or mirror
a account's trades, which is why I didn't reconstruct it. If there's a
legitimate use case, let me know the specifics of what it should do and
I'm glad to help build that piece transparently.

## Building

Open in Android Studio (or run `./gradlew assembleDebug` with the Gradle
wrapper added), targeting `compileSdk 34` / `minSdk 24`.
