package com.webglm.app.webview;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

/**
 * Thin wrapper around {@link DownloadManager} for fetching WebView APKs.
 *
 * The download is enqueued with {@link DownloadManager.Request#setDestinationInExternalPublicDir}
 * pointing at the public Downloads directory so the user can find the APK
 * afterwards. {@link DownloadManager.Request#setNotificationVisibility} is set
 * to {@link DownloadManager.Request#VISIBILITY_VISIBLE} so the user gets a
 * system notification with the progress.
 *
 * Once the APK finishes downloading the user has to tap the notification
 * (or open the Downloads app) to install it manually — Android won't let us
 * auto-install APKs without the INSTALL_PACKAGES permission (which only system
 * apps can hold).
 */
public final class DownloadManagerUtil {

    private static final String TAG = "DownloadManagerUtil";

    private DownloadManagerUtil() {}

    /**
     * Enqueue a download.
     *
     * @param context     any context
     * @param downloadUrl URL to the APK file
     * @param fileName    file name to save as (visible to the user)
     * @param desc        human-readable description shown in the notification
     * @return the download ID (can be used to track completion), or -1 on failure
     */
    public static long download(Context context, String downloadUrl, String fileName, String desc) {
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Log.e(TAG, "DownloadManager not available");
                return -1;
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(downloadUrl));
            req.setTitle(fileName);
            req.setDescription(desc);
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            // WebView APKs are typically large (>100 MB); allow roaming data.
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(true);
            return dm.enqueue(req);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to enqueue download", t);
            return -1;
        }
    }
}
