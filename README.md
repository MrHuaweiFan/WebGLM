# WebGLM

A feature-rich Android WebView wrapper that loads [Z.ai](https://chat.z.ai), designed for devices without Google Mobile Services (GMS).

> **WebGLM is an independent, unofficial client.** It is not affiliated with, endorsed by, or sponsored by Z.ai. The WebGLM name, icon (a stylized "Z" shape), and source code are this project's own — they do not use the Z.ai trademark or logo. WebGLM simply loads the public `chat.z.ai` website inside an Android WebView, the same way any general-purpose browser would.


## Features

### Core
- **WebView** wrapping `chat.z.ai` with session cookie persistence
- **Clean User-Agent** — removes Android WebView detection markers (`X-Requested-With` header override)
- **Material 3 design** — blue theme palette (from Material Theme Builder), dark mode support, Material Components throughout
- **Theme-aware backgrounds** — `#F8F8F8` in light mode, `#161616` in dark mode (status bar, app background, and loading screen all match for a seamless look)
- **Vector launcher icon** — adaptive icon with the WebGLM "Z" mark as a vector drawable (white background + black Z), no PNGs needed on API 26+
- **`window.chrome` polyfill** — injected via `addDocumentStartJavaScript` before page scripts run, so the wrapped site's feature detection doesn't render a blank page

### WebView Switcher (the headline feature)
- **In-app WebView provider picker** — switch the WebView implementation at runtime without affecting any other app on the device. Reverse-engineered from Better xCloud.
- **How it works** — swaps the cached `IBinder` for `"webviewupdate"` in `ServiceManager.sCache` with a `java.lang.reflect.Proxy` that intercepts `IWebViewUpdateService.waitForAndGetProvider()` and overwrites the returned `packageInfo` field with the user's chosen `PackageInfo`
- **13 supported providers** — Android System WebView, Chrome (stable/beta/dev/canary), Thorium, Mulch, Huawei WebView, Amazon WebView
- **Built-in downloader tab** — links to Google Play, Thorium (GitHub), and Mulch (GitLab) repository pages
- **Crash recovery** — after 3 consecutive crashes, automatically redirects to the WebView picker so the user can select a different provider
- **Developer mode flags** — optional "Optimize WebView performance" toggle installs a synthetic `DeveloperModeContentProvider` that enables `ignore-gpu-blocklist` and `WebViewSurfaceControl` on the chosen WebView

### Loading Screen
- WebGLM logo (just the Z shape, no square background) with fade in/out animation — no spin
- Theme-aware tinting: dark Z (`#2D2D2D`) on light background, white Z (`#FFFFFF`) on dark background — same Z mark in both themes
- No white flash in dark mode (1500ms overlay delay)
- Safety timeout — force-hides the overlay after 10 seconds even if `onPageFinished` never fires

### Hidden Settings (Material 3 UI)
- Accessible via the floating button at the top-right of the main screen, or via the gear icon in Android's "App info" screen
- **First-launch welcome dialog** — tells new users about the hidden settings menu. Shows once per install, dismissed with "Understood"
- **Desktop mode toggle** (Material 3 switch) — forces desktop Z.ai layout
- **Optimize WebView performance toggle** — enables GPU blocklist bypass + surface control
- **Open tmpfiles.org links in-app toggle** — intercepts `tmpfiles.org/dl/` links and downloads directly, no browser needed (on by default)
- **Default apps for file types** — opens a manager listing all stored "open with" preferences, with per-entry and "Clear all" buttons
- **Check for updates button** — checks GitHub releases for newer versions, opens the release page in the browser
- **Google sign-in troubleshooting tips**

### File Handling
- **Downloads** — saves to the public `Download/` folder with original filename (MediaStore API on Android 10+)
- **File upload** — multi-select file picker (no camera capture — Z.ai doesn't need it)

### In-App Downloads (tmpfiles.org)
- **Intercepted in-app** — `tmpfiles.org/dl/` links never open the browser. A Material dialog appears showing the filename, file size (from a HEAD request), and MIME type.
- **Download with progress** — streams the file to `MediaStore.Downloads` with a live progress bar
- **Open after download** — checkbox (on by default) opens the file immediately after download using the custom chooser
- **Already-downloaded detection** — if a file with the same name exists in Downloads, an **Open** button appears next to **Download anyway**, so you can skip re-downloading

### Blob URL Downloads
- **`URL.createObjectURL` hook** — captures every Blob the page creates at creation time, keyed by its blob: URL. This eliminates the revoke-before-fetch race that breaks naive `fetch(blobUrl)` approaches
- **`window.open` + `<a>` click interception** — blob: URLs opened via either path are routed to the download bridge instead of opening a broken popup
- **`WebMessageListener` bridge** — `AndroidBlobBridge` receives the Blob's bytes as chunked ArrayBuffer messages (1MB chunks, length-prefixed self-describing headers), written straight to `MediaStore.Downloads` (no base64 round-trip)
- **Reaches iframes** — both the document-start JS and the WebMessageListener reach embedded iframes, not just the main frame

### Open File with "Just once" / "Always"
- **Custom Material chooser** — when opening a downloaded file, lists all apps that can handle its MIME type, each with **Just once** and **Always** buttons
- **Persistent preferences** — "Always" stores the MIME type → app mapping in SharedPreferences; next time the same file type is opened, it launches directly without a chooser
- **Stale preference cleanup** — if a preferred app is uninstalled, the preference is automatically cleared
- **App Manager** — a dedicated activity (accessible from Settings → "Default apps for file types") lists all stored preferences with per-entry Clear buttons and a "Clear all" button

### Sharing
- **Share to Z.ai** — receive shared text/files from any app (`ACTION_SEND` intent-filter, labeled "Send to Z.ai")
- **Ask Z.ai** — appears in Android's text selection menu (`ACTION_PROCESS_TEXT`), copies text to clipboard for pasting
- **Copy button** — `navigator.share({ text })` override delegates to `AndroidBridge.copyToClipboard()` (fixes Z.ai's Copy button on WebViews that don't support the async Clipboard API)
- **External links** — X, Reddit, LinkedIn etc. open in the system browser

### Popup WebView
- Proper popup support for OAuth flows
- The popup is **not** added to the layout until an internal URL is loaded — external links open the browser and destroy the popup immediately, so there's no black screen when returning to the app
- Shared cookie jar via `CookieManager`
- `AndroidBridge` JS interface injected for native communication

## Differences from the ChatGPT WebApp

This app is adapted from the [ChatGPT WebApp for Android](https://github.com/MrHuaweiFan/ChatGPT-WebApp-for-Android). The following ChatGPT-specific features have been removed because Z.ai does not use them:

- **Camera** — permission, capture intent, and `uses-feature` declarations removed
- **Microphone** — `RECORD_AUDIO` / `MODIFY_AUDIO_SETTINGS` permissions and `onPermissionRequest` audio handling removed
- **Location** — `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` permissions removed
- **Custom image interface** — the long-press image context menu (Share/Download image dialog) and all related toast messages removed. The generic file download listener is retained for regular downloads.
- **Loading screen spin** — the Z.ai logo fades in/out but does not rotate

## Known Limitations

- **Google sign-in** — Google may block OAuth in Android WebViews ("browser or app may not be secure"). Workaround: retry — Google sometimes lets you through on the second attempt. Email sign-in works without issues.

## Tech Stack

| Component | Version |
|-----------|---------|
| Android Gradle Plugin | 8.2.2 |
| Gradle | 8.5 |
| JDK | 21 |
| compileSdk | 34 |
| minSdk | 21 |
| Material Components | 1.11.0 |
| androidx.recyclerview | 1.3.2 |
| androidx.webkit | 1.10.0 |
| Target SDK | 34 |
| R8 minification | Enabled |

## Build

### Prerequisites
- JDK 21
- Android SDK (platform 34, build-tools 34.0.0)
- Gradle 8.5 (via wrapper)

### Local build
```bash
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### GitHub Actions
The repo includes a GitHub Actions workflow (`.github/workflows/build-apk.yml`) that builds both debug and release APKs on every push. The release APK is signed with the bundled `test.keystore` (alias: `test`, password: `test123`).

### Signing
```bash
keytool -genkey -v -keystore test.keystore -storetype PKCS12 \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias test -storepass test123 -keypass test123 \
  -dname "CN=Zai, OU=App, O=Zai, L=City, ST=State, C=ES"
```

## Architecture

```
App (Application)
  ├─ WebViewUtil.init()        — captures default provider, scans installed providers
  ├─ Hooker.hookPackageManager() — lies about hasSystemFeature
  ├─ Hooker.hookServiceManagerService() — THE WebView switch (swaps ServiceManager.sCache)
  └─ Hooker.hookInstallContentProviders() — installs DeveloperModeContentProvider

MainActivity
  ├─ Pre-launch checks (WebView supported? Crash threshold reached?)
  ├─ WebView host, loading screen, file handling, share intents
  ├─ setupBlobDownloadBridge() — BlobDownloadManager (createObjectURL hook + WebMessageListener)
  ├─ installDocumentStartScripts() — window.chrome polyfill + navigator.share override
  ├─ shouldOverrideUrlLoading() — intercepts blob: + tmpfiles.org/dl/ URLs
  └─ WebAppInterface — @JavascriptInterface bridge
       └─ copyToClipboard() — navigator.share({text}) → Android clipboard

BlobDownloadManager
  ├─ attach() — registers AndroidBlobBridge WebMessageListener + injects blob-download-inject.js
  ├─ handleChunk() — parses length-prefixed ArrayBuffer messages, writes to MediaStore.Downloads
  └─ Single-thread executor preserves chunk ordering per transfer id

TmpfilesDownloadDialog
  ├─ fetchFileInfo() — HEAD request for Content-Length + Content-Type
  ├─ checkIfFileExists() — queries MediaStore (Q+) or filesystem (pre-Q) for existing file
  ├─ startDownload() — GET request, streams to MediaStore with progress bar
  └─ On completion → FileOpenHelper.openFile() if "Open after download" is checked

FileOpenHelper
  ├─ getPreferredApp() / setPreferredApp() — SharedPreferences-backed MIME → app mapping
  ├─ openFile() — launches preferred app directly, or shows custom chooser
  ├─ showChooser() — Material dialog with per-app "Just once" / "Always" buttons
  └─ getAllPreferences() / clearAllPreferences() — for the App Manager

SettingsActivity
  ├─ Desktop mode toggle (MaterialSwitch, persists immediately)
  ├─ Optimize WebView toggle (MaterialSwitch, persists immediately)
  ├─ tmpfiles.org in-app download toggle (MaterialSwitch, persists immediately)
  ├─ Default apps button → AppManagerActivity
  ├─ WebView Manager button → WebViewManagerDialog
  ├─ Check for updates button → UpdateChecker
  └─ Apply & restart button

AppManagerActivity
  ├─ Lists all stored MIME type → app preferences
  ├─ Per-entry Clear button
  └─ Clear all button

WebViewManagerDialog (Material AlertDialog)
  ├─ Installed tab — RecyclerView of installed WebView providers (radio list)
  └─ Downloader tab — links to Google Play / Thorium / Mulch repos

UpdateChecker
  ├─ checkForUpdates() — GitHub releases API
  └─ Opens the release page in the browser

WelcomeDialog — first-launch info dialog (shown once)
```

## License

This project is provided as-is for personal use. The Z.ai trademark and logo belong to Z.ai.
