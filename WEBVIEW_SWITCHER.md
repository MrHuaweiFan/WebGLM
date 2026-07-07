# WebView Switcher — Implementation Summary

This document describes what was added to the WebGLM (v5.4)
to implement the in-app WebView selector/switcher reverse-engineered from
Better xCloud.

## What was added

### New Java source files (16)

**`com.webglm.app` package:**
- `App.java` — `Application` subclass. Installs the three runtime hooks in
  `onCreate()` BEFORE any Activity constructs a WebView. This is critical:
  Android's `WebViewFactory` loads the WebView implementation lazily on the
  first `new WebView(ctx)` call, and once loaded it can't be swapped without
  killing the process.

**`com.webglm.app.webview` package:**
- `WebViewUtil.java` — provider registry. Captures the system default WebView
  via `WebView.getCurrentWebViewPackage()`, scans a hardcoded list of 13
  package names (`ALLOWED_PROVIDERS`) for installed alternatives, reads the
  user's choice from SharedPreferences under `webview_implementation`, and
  calls `Hooker.hookServiceManagerService()` if a custom provider is selected.
- `Hooker.java` — three runtime hooks:
  - `hookServiceManagerService()` — THE SWITCH. Replaces
    `ServiceManager.sCache["webviewupdate"]` with a Proxy.
  - `hookPackageManager()` — lies about `hasSystemFeature("audio.low_latency")`.
  - `hookInstallContentProviders()` — installs a fake
    `DeveloperModeContentProvider` with the chosen WebView package's authority
    so Chromium enables `ignore-gpu-blocklist` + `WebViewSurfaceControl`.
- `IServiceManagerProxy.java` — `InvocationHandler` for the proxied `IBinder`.
  Intercepts `queryLocalInterface("android.webkit.IWebViewUpdateService")` and
  returns our service proxy.
- `IWebViewUpdateServiceProxy.java` — `InvocationHandler` for
  `IWebViewUpdateService`. Intercepts `waitForAndGetProvider()` and
  reflectively overwrites the returned object's `packageInfo` field with the
  user's chosen `PackageInfo`.
- `IPackageManagerProxy.java` — `InvocationHandler` for `IPackageManager`.
- `DeveloperModeContentProvider.java` — fake content provider that returns
  dev-mode flag overrides.
- `CrashTracker.java` — SharedPreferences-backed crash counter. Bumps on every
  cold start, resets on successful page load. After 3 crashes, MainActivity
  bounces the user to the WebView Manager picker.
- `DownloadManagerUtil.java` — thin wrapper around system `DownloadManager`
  for fetching WebView APKs.
- `WebViewManagerDialog.java` — Material AlertDialog hosting the picker UI.
  Two tabs: "Installed" (radio list of installed providers) and "Downloader"
  (Google Play / Thorium / Mulch download sources).
- `WebViewInstalledAdapter.java` — RecyclerView adapter for the Installed tab.
- `WebViewDownloaderAdapter.java` — RecyclerView adapter for the Downloader tab.
- `WebViewSource.java` — data class for a download source.
- `WebViewImplementation.java` — enum (GOOGLE / THORIUM / MULCH).

### New layout files (3)
- `res/layout/dialog_webview_manager.xml` — the dialog with tab toggle.
- `res/layout/item_webview_installed.xml` — row layout for an installed provider.
- `res/layout/item_webview_download.xml` — row layout for a download source.

### Modified files
- `AndroidManifest.xml` — added `<queries>` block (13 WebView package names),
  `android:name=".App"` on `<application>`, and registered
  `DeveloperModeContentProvider` as a fallback manifest entry.
- `app/build.gradle` — added `androidx.recyclerview:recyclerview:1.3.2` and
  `androidx.webkit:webkit:1.10.0` dependencies. Bumped versionCode 42→43.
- `res/values/strings.xml` — added 11 new strings for the picker UI.
- `res/layout/activity_settings.xml` — added a new "WebView" section with a
  WebView Manager button + an Optimize WebView toggle.
- `SettingsActivity.java` — wired up the WebView Manager button to open the
  picker dialog and force a restart when the selection changes. Wired up the
  Optimize WebView toggle to persist its state.
- `MainActivity.java` — added pre-launch checks in `onCreate()`:
  1. If the current WebView doesn't support `DOCUMENT_START_SCRIPT`, force-open
     the picker non-cancelable.
  2. If `CrashTracker` reports 3+ crashes, bounce to Settings.
  Also reset `CrashTracker` on `onPageFinished()`.

## How the user interacts with it

1. Open Settings → scroll to the new **WebView** section.
2. Tap **WebView Manager**.
3. The dialog shows two tabs:
   - **Installed** — every WebView package currently installed on the device.
     Each row shows app icon, name, package name, version, and a radio button.
     Tap a row to select it. The selection is saved immediately to
     SharedPreferences.
   - **Downloader** — three download sources (Google Play Store, Thorium via
     GitHub releases, Mulch via GitLab raw). Tap Download to enqueue the APK
     via `DownloadManager` (visible as a system notification).
4. After dismissing the dialog with a different selection, the app shows a
   "Restart required" toast and force-restarts so the new WebView gets loaded.
5. Toggle **Optimize WebView performance** to also install the
   `DeveloperModeContentProvider` hook (forces `ignore-gpu-blocklist` and
   `WebViewSurfaceControl` on the chosen WebView).

## Key invariants

- **Boot order matters**: `WebViewUtil.init()` MUST run in `Application.onCreate()`
  before any `new WebView(ctx)`. Otherwise `WebViewFactory` will already have
  cached the real binder and the swap won't take effect.
- **Process restart required on selection change**: the WebView native lib
  can't be unloaded, so changing the selection requires killing the process.
  The dialog + `SettingsActivity` handle this automatically via
  `finishAffinity()` + `Process.killProcess(myPid())`.
- **API 26+ only**: `WebView.getCurrentWebViewPackage()` was added in API 26.
  On older devices the switcher is silently disabled (`WebViewUtil.init()` is
  a no-op).
- **Process-scoped**: the swap does NOT affect any other app on the device —
  it only changes what `WebViewFactory` loads in the calling process.

## Dependencies added

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `androidx.recyclerview:recyclerview` | 1.3.2 | Lists in the picker dialog |
| `androidx.webkit:webkit` | 1.10.0 | `WebViewFeature.isFeatureSupported()` check |

No HTTP/JSON libraries were added — the Thorium release-API call uses the
built-in `HttpURLConnection` + `org.json.JSONObject` to keep the dependency
footprint minimal.

## File map

```
app/src/main/
├── AndroidManifest.xml                          ← modified (queries, App, provider)
├── java/com/zai/app/
│   ├── App.java                                 ← NEW (Application subclass)
│   ├── MainActivity.java                        ← modified (pre-launch checks)
│   ├── SettingsActivity.java                    ← modified (WebView Manager button)
│   └── webview/
│       ├── WebViewUtil.java                     ← NEW
│       ├── Hooker.java                          ← NEW
│       ├── IServiceManagerProxy.java            ← NEW
│       ├── IWebViewUpdateServiceProxy.java      ← NEW (THE actual swap)
│       ├── IPackageManagerProxy.java            ← NEW
│       ├── DeveloperModeContentProvider.java    ← NEW
│       ├── CrashTracker.java                    ← NEW
│       ├── DownloadManagerUtil.java             ← NEW
│       ├── WebViewManagerDialog.java            ← NEW
│       ├── WebViewInstalledAdapter.java         ← NEW
│       ├── WebViewDownloaderAdapter.java        ← NEW
│       ├── WebViewSource.java                   ← NEW
│       └── WebViewImplementation.java           ← NEW
├── res/
│   ├── layout/
│   │   ├── dialog_webview_manager.xml           ← NEW
│   │   ├── item_webview_installed.xml           ← NEW
│   │   ├── item_webview_download.xml            ← NEW
│   │   └── activity_settings.xml                ← modified (WebView section)
│   └── values/
│       └── strings.xml                          ← modified (11 new strings)
└── build.gradle                                 ← modified (2 new deps)
```

## How to build

Same as before — no changes to the build system besides the two new
dependencies. With Android Studio: open the project, sync Gradle, press Run.
With the command line:

```bash
./gradlew assembleRelease
# or
./gradlew assembleDebug
```

The signed release APK will be at `app/build/outputs/apk/release/app-release.apk`.
