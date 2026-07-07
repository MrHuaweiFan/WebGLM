package com.webglm.app.webview;

import android.content.Context;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * InvocationHandler for {@code android.content.pm.IPackageManager}.
 *
 * Lies about two things:
 * <ul>
 *   <li>{@code hasSystemFeature("android.hardware.audio.low_latency")} → true
 *       (Chromium WebView unconditionally enables low-latency audio when this is set).</li>
 *   <li>{@code getComponentEnabledSetting(...DeveloperModeState...)} →
 *       COMPONENT_ENABLED_STATE_ENABLED (only when the user has turned on
 *       "Optimize WebView performance" in the picker).</li>
 * </ul>
 *
 * Every other method is delegated to the real IPackageManager.
 */
public final class IPackageManagerProxy implements InvocationHandler {

    private final Context context;
    private final Object packageManager;  // the real IPackageManager

    public IPackageManagerProxy(Context context, Object packageManager) {
        this.context = context;
        this.packageManager = packageManager;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args == null) {
            return method.invoke(packageManager);
        }
        String name = method.getName();
        if ("hasSystemFeature".equals(name) && args.length >= 1
                && "android.hardware.audio.low_latency".equals(String.valueOf(args[0]))) {
            return Boolean.TRUE;
        }
        if ("getComponentEnabledSetting".equals(name) && args.length >= 1) {
            String comp = String.valueOf(args[0]);
            if (comp.contains("devui.DeveloperModeState")) {
                boolean optimize = context.getSharedPreferences(
                        com.webglm.app.App.PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean("webview_optimize", false);
                if (optimize) {
                    return 1;  // COMPONENT_ENABLED_STATE_ENABLED
                }
            }
        }
        return method.invoke(packageManager, Arrays.copyOf(args, args.length));
    }
}
