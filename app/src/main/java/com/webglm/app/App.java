package com.webglm.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import com.webglm.app.webview.CrashTracker;
import com.webglm.app.webview.Hooker;
import com.webglm.app.webview.WebViewUtil;

/**
 * Application subclass.
 *
 * The WebView switcher MUST be initialized here in onCreate(), before any Activity
 * has a chance to construct a WebView. Android's WebViewFactory loads the WebView
 * implementation lazily on the first `new WebView(ctx)` call; once that happens
 * the chosen provider is locked in for the process and cannot be changed without
 * killing the process.
 *
 * Boot order:
 *   1. WebViewUtil.init(ctx)        — captures default provider, reads user choice,
 *                                      installs ServiceManager hook if a custom
 *                                      provider is selected
 *   2. Hooker.hookPackageManager()  — always installed; lies about hasSystemFeature
 *   3. Hooker.hookInstallContentProviders()  — only if "webview_optimize" is on;
 *                                      installs a fake DeveloperModeContentProvider
 *                                      so the chosen WebView enables GPU blocklist
 *                                      bypass + surface control
 *   4. CrashTracker.init()          — bump crash counter (reset later on
 *                                      successful WebView load)
 */
public class App extends Application {

    private static final String TAG = "WebGLMApp";
    public static final String PREFS_NAME = "webglm_prefs";

    @Override
    public void onCreate() {
        super.onCreate();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 1. Initialize the WebView provider registry. On API < 26 this is a no-op
        //    because WebView.getCurrentWebViewPackage() doesn't exist.
        try {
            WebViewUtil.init(getApplicationContext());
        } catch (Throwable t) {
            Log.e(TAG, "WebViewUtil.init failed", t);
        }

        // 2. Always install the PackageManager proxy hook.
        try {
            Hooker.hookPackageManager(getApplicationContext());
        } catch (Throwable t) {
            Log.e(TAG, "hookPackageManager failed", t);
            Toast.makeText(this, "WebView hook failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }

        // 3. Optionally install the DeveloperModeContentProvider hook.
        if (prefs.getBoolean("webview_optimize", false)) {
            try {
                Hooker.hookInstallContentProviders(getApplicationContext());
            } catch (Throwable t) {
                Log.e(TAG, "hookInstallContentProviders failed", t);
            }
        }

        // 4. Crash tracker — bump on every cold start. Reset on successful WebView load.
        CrashTracker.init(getApplicationContext());
    }
}
