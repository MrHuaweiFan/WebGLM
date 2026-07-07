package com.webglm.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.google.android.material.appbar.MaterialToolbar;
import com.webglm.app.webview.FileOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * App Manager — lists all stored "default app for file type" preferences
 * and lets the user clear individual preferences or all of them.
 *
 * <p>Each row shows the MIME type (with extension), the app name, and a
 * "Clear" button. Tapping "Clear" removes the preference so the chooser
 * dialog will appear next time a file of that type is opened.
 */
public class AppManagerActivity extends Activity {

    private LinearLayout preferencesList;
    private TextView emptyText;
    private View clearAllButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_manager);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        preferencesList = findViewById(R.id.preferences_list);
        emptyText = findViewById(R.id.empty_text);
        clearAllButton = findViewById(R.id.btn_clear_all);

        clearAllButton.setOnClickListener(v -> {
            FileOpenHelper.clearAllPreferences(this);
            refreshList();
            Toast.makeText(this, R.string.app_manager_clear_all, Toast.LENGTH_SHORT).show();
        });

        refreshList();
    }

    private void refreshList() {
        preferencesList.removeAllViews();

        Map<String, String> prefs = FileOpenHelper.getAllPreferences(this);
        if (prefs.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            clearAllButton.setVisibility(View.GONE);
            return;
        }

        emptyText.setVisibility(View.GONE);
        clearAllButton.setVisibility(View.VISIBLE);

        // Sort by MIME type for a stable display order
        List<String> mimeTypes = new ArrayList<>(prefs.keySet());
        Collections.sort(mimeTypes);

        PackageManager pm = getPackageManager();
        for (String mime : mimeTypes) {
            String pkg = prefs.get(mime);
            View row = LayoutInflater.from(this).inflate(R.layout.item_app_preference, preferencesList, false);

            ImageView icon = row.findViewById(R.id.pref_icon);
            TextView mimeLabel = row.findViewById(R.id.pref_mime_label);
            TextView appLabel = row.findViewById(R.id.pref_app_label);
            View clearBtn = row.findViewById(R.id.btn_clear);

            mimeLabel.setText(FileOpenHelper.getMimeTypeLabel(this, mime));

            // Try to load the app's icon + label
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                appLabel.setText(pm.getApplicationLabel(ai));
                Drawable d = pm.getApplicationIcon(ai);
                icon.setImageDrawable(d);
            } catch (PackageManager.NameNotFoundException e) {
                // App was uninstalled — show package name + clear the stale pref
                appLabel.setText(pkg);
                FileOpenHelper.setPreferredApp(this, mime, null);
            }

            final String finalMime = mime;
            clearBtn.setOnClickListener(v -> {
                FileOpenHelper.setPreferredApp(this, finalMime, null);
                refreshList();
            });

            preferencesList.addView(row);
        }
    }
}
