package com.webglm.app.webview;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.IBinder;

import com.webglm.app.BuildConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * The three runtime hooks that make the in-app WebView switcher work.
 *
 * <ol>
 *   <li>{@link #hookServiceManagerService()} — THE SWITCH.
 *       Replaces the cached IBinder for "webviewupdate" in
 *       {@code android.os.ServiceManager.sCache} with a Proxy. When
 *       WebViewFactory calls {@code queryLocalInterface("android.webkit.IWebViewUpdateService")}
 *       the proxy returns another Proxy that intercepts
 *       {@code waitForAndGetProvider()} and swaps the returned
 *       {@code packageInfo} field with the user's chosen PackageInfo.</li>
 *
 *   <li>{@link #hookPackageManager(Context)} — always installed.
 *       Replaces {@code ActivityThread.sPackageManager} with a Proxy that
 *       lies about {@code hasSystemFeature("android.hardware.audio.low_latency")}
 *       (returns true) and (optionally) about
 *       {@code getComponentEnabledSetting(...DeveloperModeState...)}.</li>
 *
 *   <li>{@link #hookInstallContentProviders(Context)} — optional.
 *       Reflectively invokes {@code ActivityThread.installContentProviders()}
 *       with a synthetic ProviderInfo whose authority is
 *       {@code <webview-package>.DeveloperModeContentProvider}. Chromium WebView
 *       queries this authority to read developer-mode flag overrides; we return
 *       {@code ignore-gpu-blocklist=true} and {@code WebViewSurfaceControl=true}.</li>
 * </ol>
 *
 * All three hooks are process-scoped. They do NOT affect any other app.
 */
public final class Hooker {

    private static final String TAG = "Hooker";
    private static final String WEBVIEW_UPDATE_SERVICE_NAME = "webviewupdate";

    private Hooker() {}

    // ──────────────────────────────────────────────────────────────────────────
    // 1. The WebView switch
    // ──────────────────────────────────────────────────────────────────────────

    public static void hookServiceManagerService() {
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method getService = smCls.getMethod("getService", String.class);
            Object originalBinder = getService.invoke(null, WEBVIEW_UPDATE_SERVICE_NAME);
            if (originalBinder == null) {
                android.util.Log.w(TAG, "getService(webviewupdate) returned null; not hooking");
                return;
            }
            Object binderProxy = Proxy.newProxyInstance(
                    smCls.getClassLoader(),
                    new Class[]{IBinder.class},
                    new IServiceManagerProxy((IBinder) originalBinder)
            );

            Field sCacheField = smCls.getDeclaredField("sCache");
            sCacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> cache =
                    (java.util.Map<String, Object>) sCacheField.get(null);
            cache.put(WEBVIEW_UPDATE_SERVICE_NAME, binderProxy);
            android.util.Log.i(TAG, "ServiceManager hooked for webviewupdate");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "hookServiceManagerService failed", t);
            throw new RuntimeException("Failed to hook ServiceManager", t);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. PackageManager proxy
    // ──────────────────────────────────────────────────────────────────────────

    public static void hookPackageManager(Context context) {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method currentAT = atCls.getDeclaredMethod("currentActivityThread");
            Object at = currentAT.invoke(null);

            Field sPMField = atCls.getDeclaredField("sPackageManager");
            sPMField.setAccessible(true);
            Object originalPM = sPMField.get(at);
            if (originalPM == null) {
                android.util.Log.w(TAG, "sPackageManager is null; not hooking");
                return;
            }

            Class<?> iPMCls = Class.forName("android.content.pm.IPackageManager");
            Object pmProxy = Proxy.newProxyInstance(
                    iPMCls.getClassLoader(),
                    new Class[]{iPMCls},
                    new IPackageManagerProxy(context, originalPM)
            );
            sPMField.set(at, pmProxy);

            // Also overwrite mPM on the ApplicationPackageManager instance held by `context`.
            android.content.pm.PackageManager pm = context.getPackageManager();
            try {
                Field mPMField = pm.getClass().getDeclaredField("mPM");
                mPMField.setAccessible(true);
                mPMField.set(pm, pmProxy);
            } catch (NoSuchFieldException nsfe) {
                // Some Android versions rename this field. Best-effort; not fatal.
                android.util.Log.w(TAG, "mPM field not found on " + pm.getClass().getName(), nsfe);
            }
        } catch (Throwable t) {
            android.util.Log.e(TAG, "hookPackageManager failed", t);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. Synthetic DeveloperModeContentProvider
    // ──────────────────────────────────────────────────────────────────────────

    public static void hookInstallContentProviders(Context context) {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Field sCATField = atCls.getDeclaredField("sCurrentActivityThread");
            sCATField.setAccessible(true);
            Object at = sCATField.get(null);
            if (at == null) {
                android.util.Log.w(TAG, "sCurrentActivityThread is null; not hooking provider");
                return;
            }

            PackageInfo provider = WebViewUtil.getCurrentProvider();
            if (provider == null) {
                android.util.Log.w(TAG, "No current provider; not hooking provider");
                return;
            }
            String authority = provider.packageName + ".DeveloperModeContentProvider";
            ProviderInfo info = createProviderInfo(context, authority);

            Method install = atCls.getDeclaredMethod(
                    "installContentProviders",
                    Context.class, List.class
            );
            install.setAccessible(true);
            install.invoke(at, context, java.util.Collections.singletonList(info));
            android.util.Log.i(TAG, "Installed DeveloperModeContentProvider for " + authority);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "hookInstallContentProviders failed", t);
        }
    }

    private static ProviderInfo createProviderInfo(Context context, String authority) {
        ProviderInfo info = new ProviderInfo();
        info.name = DeveloperModeContentProvider.class.getName();
        info.packageName = BuildConfig.APPLICATION_ID;
        info.authority = authority;
        try {
            info.applicationInfo = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            // Should never happen — we're asking about our own package.
            android.util.Log.wtf(TAG, "Our own package not found?", e);
            info.applicationInfo = context.getApplicationInfo();
        }
        return info;
    }
}
