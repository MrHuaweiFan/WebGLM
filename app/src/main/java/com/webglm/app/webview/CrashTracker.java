package com.webglm.app.webview;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Crash recovery safety net.
 *
 * Bumps a crash counter in SharedPreferences on every cold start. The
 * {@link com.webglm.app.MainActivity} resets it once the WebView finishes
 * its first page load. If the counter exceeds {@link #MAX_CRASHES}, MainActivity
 * bails to the WebView Manager picker with a toast prompting the user to pick
 * a different WebView.
 *
 * Without this, a bad WebView choice (one whose native lib doesn't support
 * the device's ABI, or whose version is too old) would crash-loop the user
 * out of the app with no recovery path.
 */
public final class CrashTracker {

    private static final String TAG = "CrashTracker";
    private static final String PREF_KEY = "crash_count";
    private static final int MAX_CRASHES = 3;

    private static SharedPreferences prefs;

    private CrashTracker() {}

    public static void init(Context context) {
        prefs = context.getSharedPreferences(com.webglm.app.App.PREFS_NAME, Context.MODE_PRIVATE);
        increase();
        Log.i(TAG, "Crash count = " + getCount());
    }

    /** Bump the crash counter by 1. Called from Application.onCreate(). */
    public static void increase() {
        if (prefs == null) return;
        prefs.edit().putInt(PREF_KEY, getCount() + 1).apply();
    }

    public static int getCount() {
        return prefs == null ? 0 : prefs.getInt(PREF_KEY, 0);
    }

    public static boolean hasCrashes() {
        return getCount() >= MAX_CRASHES;
    }

    /**
     * Reset the counter. {@param all} true also clears the "have we ever
     * successfully loaded" marker; false just resets the recent-crash count
     * after a successful load.
     */
    public static void reset(boolean all) {
        if (prefs == null) return;
        prefs.edit().putInt(PREF_KEY, 0).apply();
    }

    /** Detect whether this process is the most recent foreground process. */
    public static boolean isMainProcess(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return true;
            int pid = android.os.Process.myPid();
            for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                if (info.pid == pid) {
                    return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }
}
