package com.webglm.app.webview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.webglm.app.R;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages "default app for file type" preferences and the custom file-open
 * chooser dialog.
 *
 * <p>Preferences are stored in SharedPreferences as:
 * <pre>
 *   file_opener_&lt;mimetype&gt; → &lt;package name&gt;
 * </pre>
 *
 * <p>When opening a file:
 * <ol>
 *   <li>Check if a preferred app exists for the file's MIME type. If yes and
 *       the app is still installed, open the file with that app directly.</li>
 *   <li>If no preference (or the preferred app was uninstalled), show a
 *       custom Material chooser dialog listing all apps that can handle the
 *       MIME type. Each row has "Just once" and "Always" buttons.</li>
 *   <li>"Just once" opens the file with the chosen app without storing a
 *       preference. "Always" stores the preference and opens the file.</li>
 * </ol>
 */
public class FileOpenHelper {

    private static final String TAG = "FileOpenHelper";
    private static final String PREFS_NAME = "webglm_prefs";
    private static final String KEY_PREFIX = "file_opener_";
    private static final String KEY_TMPFILES_IN_APP = "tmpfiles_in_app_download";

    /** Returns true if the tmpfiles.org in-app download feature is enabled. */
    public static boolean isTmpfilesInAppEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_TMPFILES_IN_APP, true);
    }

    /** Returns the stored preferred app package name for the given MIME type, or null. */
    public static String getPreferredApp(Context context, String mimeType) {
        if (mimeType == null) return null;
        String pkg = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + mimeType, null);
        if (pkg == null) return null;
        // Verify the app is still installed
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return pkg;
        } catch (PackageManager.NameNotFoundException e) {
            // App was uninstalled — clear the stale preference
            setPreferredApp(context, mimeType, null);
            return null;
        }
    }

    /** Sets (or clears if packageName is null) the preferred app for a MIME type. */
    public static void setPreferredApp(Context context, String mimeType, String packageName) {
        if (mimeType == null) return;
        SharedPreferences.Editor ed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        if (packageName == null) {
            ed.remove(KEY_PREFIX + mimeType);
        } else {
            ed.putString(KEY_PREFIX + mimeType, packageName);
        }
        ed.apply();
    }

    /** Returns a map of all stored MIME type → package name preferences. */
    public static Map<String, String> getAllPreferences(Context context) {
        Map<String, String> result = new HashMap<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX) && entry.getValue() instanceof String) {
                String mime = entry.getKey().substring(KEY_PREFIX.length());
                result.put(mime, (String) entry.getValue());
            }
        }
        return result;
    }

    /** Clears all stored app preferences. */
    public static void clearAllPreferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        for (String key : new ArrayList<>(prefs.getAll().keySet())) {
            if (key.startsWith(KEY_PREFIX)) ed.remove(key);
        }
        ed.apply();
    }

    /**
     * Resolve the list of apps that can handle the given MIME type + URI.
     * On Android 11+ (API 30+), PackageManager.queryIntentActivities only
     * returns apps visible via the &lt;queries&gt; manifest block or apps with
     * a matching intent-filter for ACTION_VIEW + the MIME type. This works
     * without extra queries declarations because ACTION_VIEW + MIME type is
     * a standard system intent.
     */
    public static List<ResolveInfo> getAppsForMimeType(Context context, String mimeType, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType != null ? mimeType : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return context.getPackageManager().queryIntentActivities(intent, 0);
    }

    /**
     * Open a file. If a preferred app exists for the MIME type, open directly.
     * Otherwise, show a custom chooser with "Just once" / "Always" buttons.
     *
     * @param activity  the hosting activity (for showing dialogs)
     * @param uri       the content:// or file:// URI of the file to open
     * @param mimeType  the MIME type of the file
     * @param file      the underlying File (for fallback FileProvider URI on pre-Q)
     */
    public static void openFile(final Activity activity, final Uri uri, final String mimeType, final File file) {
        if (uri == null) {
            Toast.makeText(activity, R.string.tmpfiles_no_open_apps, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check for a stored preference
        String preferredPkg = getPreferredApp(activity, mimeType);
        if (preferredPkg != null) {
            if (launchApp(activity, uri, mimeType, file, preferredPkg)) {
                return;
            }
            // Launch failed — fall through to chooser
        }

        // No preference (or preferred app failed) — show custom chooser
        List<ResolveInfo> apps = getAppsForMimeType(activity, mimeType, uri);
        if (apps == null || apps.isEmpty()) {
            // Try with wildcard MIME if the specific one found nothing
            if (mimeType != null && !mimeType.equals("*/*")) {
                apps = getAppsForMimeType(activity, "*/*", uri);
            }
            if (apps == null || apps.isEmpty()) {
                Toast.makeText(activity, R.string.tmpfiles_no_open_apps, Toast.LENGTH_LONG).show();
                return;
            }
        }
        showChooser(activity, uri, mimeType, file, apps);
    }

    /**
     * Launch a specific app to view the file.
     *
     * @return true if the launch intent was successfully sent
     */
    private static boolean launchApp(Activity activity, Uri uri, String mimeType, File file, String packageName) {
        try {
            Uri shareUri = uri;
            // For file:// URIs (pre-Q), convert to a FileProvider content URI
            if (file != null && "file".equals(uri.getScheme())) {
                shareUri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", file);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(shareUri, mimeType != null ? mimeType : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setPackage(packageName);
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "launchApp failed for " + packageName, e);
            return false;
        }
    }

    /**
     * Show a custom Material chooser dialog with "Just once" / "Always" buttons.
     */
    private static void showChooser(final Activity activity, final Uri uri, final String mimeType,
                                    final File file, final List<ResolveInfo> apps) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_file_chooser, null);
        LinearLayout list = view.findViewById(R.id.chooser_list);

        final androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.chooser_title)
                        .setView(view)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();

        final PackageManager pm = activity.getPackageManager();

        for (final ResolveInfo info : apps) {
            View row = LayoutInflater.from(activity).inflate(R.layout.item_chooser_app, list, false);

            ImageView icon = row.findViewById(R.id.app_icon);
            TextView name = row.findViewById(R.id.app_name);
            View justOnce = row.findViewById(R.id.btn_just_once);
            View always = row.findViewById(R.id.btn_always);

            try {
                Drawable d = info.loadIcon(pm);
                icon.setImageDrawable(d);
            } catch (Exception e) {
                icon.setImageResource(android.R.drawable.ic_menu_more);
            }
            try {
                name.setText(info.loadLabel(pm));
            } catch (Exception e) {
                name.setText(info.activityInfo.packageName);
            }

            final String pkgName = info.activityInfo.packageName;

            justOnce.setOnClickListener(v -> {
                dialog.dismiss();
                launchApp(activity, uri, mimeType, file, pkgName);
            });

            always.setOnClickListener(v -> {
                setPreferredApp(activity, mimeType, pkgName);
                dialog.dismiss();
                launchApp(activity, uri, mimeType, file, pkgName);
            });

            list.addView(row);
        }

        dialog.show();
    }

    /**
     * Returns a friendly label for a MIME type, e.g. "application/pdf" → "PDF (.pdf)".
     * Used in the app manager.
     */
    public static String getMimeTypeLabel(Context context, String mimeType) {
        if (mimeType == null) return "Unknown";
        String ext = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType);
        if (ext != null && !ext.isEmpty()) {
            return mimeType + " (." + ext + ")";
        }
        return mimeType;
    }

    /** Returns the app's display name for a package name, or the package name if not found. */
    public static String getAppName(Context context, String packageName) {
        try {
            ApplicationInfo ai = context.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            return context.getPackageManager().getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    /** Formats a byte count as a human-readable string (e.g. "2.1 MB"). */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** Extracts the filename from a tmpfiles.org/dl/ URL. */
    public static String extractFilenameFromUrl(String url) {
        if (url == null) return "download";
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String path = uri.getPath();
            if (path != null) {
                // path is like "/dl/<id>/<filename>"
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    return path.substring(lastSlash + 1);
                }
            }
        } catch (Exception ignored) {}
        return "download";
    }

    /** Guesses the MIME type from a filename's extension. */
    public static String guessMimeTypeFromFilename(String filename) {
        if (filename == null) return "application/octet-stream";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot >= filename.length() - 1) return "application/octet-stream";
        String ext = filename.substring(dot + 1).toLowerCase();
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }
}
