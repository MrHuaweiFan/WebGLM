package com.webglm.app.webview;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;

import com.webglm.app.App;
import com.webglm.app.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * First-launch welcome dialog.
 *
 * Shows a Material AlertDialog explaining that the app has a hidden Settings
 * menu (where the WebView switcher lives, etc.). The dialog is shown only
 * once — after the user taps "Understood" the pref {@code welcome_shown}
 * is set to true and the dialog never appears again.
 *
 * Call {@link #showIfNeeded(Activity)} from MainActivity.onCreate() after
 * the WebView switcher pre-launch checks pass.
 */
public final class WelcomeDialog {

    private static final String PREF_KEY = "welcome_shown";

    private WelcomeDialog() {}

    /** True if we've never shown the welcome dialog to this user before. */
    public static boolean needsToShow(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE);
        return !prefs.getBoolean(PREF_KEY, false);
    }

    /**
     * Show the welcome dialog if it hasn't been shown yet. No-op otherwise.
     * The dialog is non-cancelable — the user MUST tap "Understood" to
     * dismiss it, which is what marks it as shown.
     */
    public static void showIfNeeded(final Activity activity) {
        if (!needsToShow(activity)) return;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.welcome_title)
                .setMessage(R.string.welcome_body)
                .setCancelable(false)
                .setPositiveButton(R.string.understood, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        activity.getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean(PREF_KEY, true)
                                .apply();
                        d.dismiss();
                    }
                })
                .show();
    }
}
