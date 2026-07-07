package com.webglm.app.webview;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.webkit.WebViewFeature;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebView provider registry + selection logic.
 *
 * On API 26+ Android lets an app ask "which WebView package would the system
 * load right now" via {@link WebView#getCurrentWebViewPackage()}. This class
 * captures that as DEFAULT_PROVIDER, then scans a hardcoded list of allowed
 * provider package names to discover what else is installed
 * ({@link #AVAILABLE_PROVIDERS}).
 *
 * The user's choice is persisted in SharedPreferences under the key
 * "webview_implementation". When a custom (non-default) provider is selected
 * we call {@link Hooker#hookServiceManagerService()} to swap the cached
 * "webviewupdate" service binder in {@code android.os.ServiceManager.sCache}
 * with a Proxy that intercepts {@code IWebViewUpdateService.waitForAndGetProvider()}
 * and replaces the returned {@code packageInfo} field with our chosen package.
 *
 * The hook is process-scoped — it does NOT affect any other app on the device.
 */
public final class WebViewUtil {

    private static final String TAG = "WebViewUtil";

    /** SharedPreferences key for the user's chosen WebView package name. */
    public static final String PREF_WEBVIEW_IMPL = "webview_implementation";

    /**
     * Hardcoded list of packages we recognise as WebView providers. This MUST
     * be kept in sync with the {@code <queries>} block in AndroidManifest.xml,
     * otherwise getPackageInfo() will throw NameNotFoundException on Android 11+.
     */
    public static final String[] ALLOWED_PROVIDERS = new String[]{
            "com.google.android.webview",
            "com.google.android.webview.beta",
            "com.google.android.webview.dev",
            "com.google.android.webview.canary",
            "com.android.webview",
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.amazon.webview.chromium",
            "com.huawei.webview",
            "us.spotco.mulch_wv",
            "com.thorium.webview",
    };

    /** LinkedHashMap so the iteration order shown in the picker is stable. */
    private static final Map<String, PackageInfo> AVAILABLE_PROVIDERS = new LinkedHashMap<>();

    private static PackageInfo DEFAULT_PROVIDER = null;   // what Android would use by default
    private static PackageInfo CUSTOM_PROVIDER = null;    // user's choice (null = use default)
    private static PackageInfo CURRENT_PROVIDER = null;   // what's actually in use right now
    private static boolean initialized = false;

    private WebViewUtil() {}

    /**
     * Capture the default provider, scan for installed alternatives, read the
     * user's selection from prefs, and (if a non-default provider was chosen)
     * install the ServiceManager hook.
     *
     * Must run in Application.onCreate() before any WebView is constructed.
     * No-op on API < 26 (WebView.getCurrentWebViewPackage doesn't exist).
     */
    public static synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // getCurrentWebViewPackage() requires API 26. Bail out gracefully —
            // the switcher simply isn't available on older devices.
            Log.i(TAG, "API < 26; WebView switcher disabled.");
            return;
        }

        PackageInfo current = WebView.getCurrentWebViewPackage();
        if (current == null) {
            Log.w(TAG, "WebView.getCurrentWebViewPackage() returned null");
            return;
        }
        DEFAULT_PROVIDER = current;
        reloadProviderList(context);

        SharedPreferences prefs = context.getSharedPreferences(
                com.webglm.app.App.PREFS_NAME, Context.MODE_PRIVATE);
        String selected = prefs.getString(PREF_WEBVIEW_IMPL, DEFAULT_PROVIDER.packageName);
        if ("default".equals(selected)) {
            selected = DEFAULT_PROVIDER.packageName;
        }

        if (selected.equals(DEFAULT_PROVIDER.packageName) || !AVAILABLE_PROVIDERS.containsKey(selected)) {
            // Fall back to default — no hook needed.
            CURRENT_PROVIDER = DEFAULT_PROVIDER;
        } else {
            CUSTOM_PROVIDER = AVAILABLE_PROVIDERS.get(selected);
            CURRENT_PROVIDER = CUSTOM_PROVIDER;
            try {
                Hooker.hookServiceManagerService();
            } catch (Throwable t) {
                Log.e(TAG, "hookServiceManagerService failed; falling back to default", t);
                CUSTOM_PROVIDER = null;
                CURRENT_PROVIDER = DEFAULT_PROVIDER;
                Toast.makeText(context,
                        "Failed to switch WebView: " + t.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }
        Log.i(TAG, "Selected WebView: " + (CURRENT_PROVIDER != null ? CURRENT_PROVIDER.packageName : "?"));
    }

    /**
     * Rescan installed providers. Call this when the user might have installed/uninstalled one.
     *
     * <p>The flag value 1216 matches BetterXC's implementation and is the OR of:
     * <ul>
     *   <li>{@link PackageManager#GET_META_DATA} (128) — needed to read the
     *       {@code com.android.webview.WebViewLibrary} meta-data that marks
     *       a package as a WebView provider.</li>
     *   <li>{@link PackageManager#GET_SHARED_LIBRARY_FILES} (1024) — CRITICAL:
     *       {@code WebViewFactory.getProviderByPath()} builds the WebView's
     *       ClassLoader by concatenating {@code applicationInfo.sourceDir}
     *       with {@code applicationInfo.sharedLibraryFiles}. If this field is
     *       null (i.e. the flag wasn't passed), the classloader is missing
     *       shared libraries that the WebView APK depends on, and loading
     *       crashes with a {@code ClassNotFoundException} or similar.</li>
     *   <li>{@link PackageManager#GET_SIGNATURES} (64) — populated for parity
     *       with BetterXC; some framework paths may verify the WebView's
     *       signature before loading.</li>
     * </ul>
     */
    public static void reloadProviderList(Context context) {
        AVAILABLE_PROVIDERS.clear();
        if (DEFAULT_PROVIDER != null) {
            AVAILABLE_PROVIDERS.put(DEFAULT_PROVIDER.packageName, DEFAULT_PROVIDER);
        }
        final int flags = PackageManager.GET_META_DATA
                | PackageManager.GET_SHARED_LIBRARY_FILES
                | PackageManager.GET_SIGNATURES;  // = 1216
        for (String pkg : ALLOWED_PROVIDERS) {
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(pkg, flags);
                ApplicationInfo app = info.applicationInfo;
                if (app == null || !app.enabled) continue;
                // The official marker Android uses to recognise a WebView provider.
                if (app.metaData == null
                        || app.metaData.getString("com.android.webview.WebViewLibrary") == null) {
                    continue;
                }
                AVAILABLE_PROVIDERS.put(pkg, info);
            } catch (PackageManager.NameNotFoundException e) {
                // Not installed — skip silently.
            }
        }
    }

    /** True if the current WebView supports DOCUMENT_START_SCRIPT (a proxy for "new enough"). */
    public static boolean isSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        } catch (Throwable t) {
            return false;
        }
    }

    public static Map<String, PackageInfo> getAvailableProviders() {
        return AVAILABLE_PROVIDERS;
    }

    public static PackageInfo getDefaultProvider() {
        return DEFAULT_PROVIDER;
    }

    public static PackageInfo getCustomProvider() {
        return CUSTOM_PROVIDER;
    }

    public static PackageInfo getCurrentProvider() {
        return CURRENT_PROVIDER != null ? CURRENT_PROVIDER : DEFAULT_PROVIDER;
    }

    /** Format a provider for display, e.g. "Android System WebView 130.0.6723.107". */
    public static String toString(Context context, PackageInfo packageInfo) {
        if (packageInfo == null) return "(unknown)";
        PackageManager pm = context.getPackageManager();
        CharSequence label;
        try {
            label = pm.getApplicationLabel(packageInfo.applicationInfo);
        } catch (Throwable t) {
            label = packageInfo.packageName;
        }
        return label + " " + packageInfo.versionName;
    }
}
