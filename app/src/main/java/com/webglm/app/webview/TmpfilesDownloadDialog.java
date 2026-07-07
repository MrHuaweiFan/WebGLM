package com.webglm.app.webview;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.webglm.app.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Material dialog for downloading a file from a tmpfiles.org/dl/ URL.
 *
 * <p>Flow:
 * <ol>
 *   <li>Show dialog with filename + "Loading file info…"</li>
 *   <li>HEAD request to get Content-Length + Content-Type</li>
 *   <li>Update dialog with size + MIME type, enable Download button</li>
 *   <li>User taps Download → GET request, stream to MediaStore.Downloads,
 *       update progress bar</li>
 *   <li>On completion: if "Open after download" is checked, call
 *       {@link FileOpenHelper#openFile} to launch the preferred app or
 *       show the custom chooser.</li>
 * </ol>
 */
public class TmpfilesDownloadDialog {

    private static final String TAG = "TmpfilesDownload";

    private final Activity activity;
    private final String downloadUrl;
    private final String filename;
    private AlertDialog dialog;

    // Dialog views
    private TextView titleText;
    private TextView sizeText;
    private TextView typeText;
    private CheckBox openAfterCheckbox;
    private ProgressBar progressBar;
    private View downloadingLabel;
    private android.widget.Button downloadBtn;
    private android.widget.Button openBtn;
    private View cancelBtn;
    private LinearLayout buttonRow;

    // Retrieved from HEAD
    private long fileSize = -1;
    private String mimeType;

    // Existing file (if already downloaded)
    private Uri existingFileUri;
    private File existingFile;

    // The real direct download URL (resolved from the /dl/<id>/<filename>
    // link, which 302-redirects to an HTML web page). The web page contains
    // the actual download link with a numeric prefix:
    // /dl/<numeric_prefix>/<id>/<filename>
    private String resolvedDownloadUrl;

    public TmpfilesDownloadDialog(Activity activity, String downloadUrl) {
        this.activity = activity;
        this.downloadUrl = downloadUrl;
        this.filename = FileOpenHelper.extractFilenameFromUrl(downloadUrl);
        this.mimeType = FileOpenHelper.guessMimeTypeFromFilename(filename);
    }

    public void show() {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_tmpfiles_download, null);

        titleText = view.findViewById(R.id.filename);
        sizeText = view.findViewById(R.id.file_size);
        typeText = view.findViewById(R.id.file_type);
        openAfterCheckbox = view.findViewById(R.id.open_after_checkbox);
        progressBar = view.findViewById(R.id.progress_bar);
        downloadingLabel = view.findViewById(R.id.downloading_label);
        downloadBtn = view.findViewById(R.id.btn_download);
        openBtn = view.findViewById(R.id.btn_open);
        cancelBtn = view.findViewById(R.id.btn_cancel);
        buttonRow = view.findViewById(R.id.button_row);

        titleText.setText(filename);
        sizeText.setText(R.string.tmpfiles_loading_info);
        typeText.setText(activity.getString(R.string.tmpfiles_type_label, mimeType));

        dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.tmpfiles_dialog_title)
                .setView(view)
                .setCancelable(false)
                .create();

        downloadBtn.setEnabled(false);
        downloadBtn.setAlpha(0.5f);

        downloadBtn.setOnClickListener(v -> startDownload());
        openBtn.setOnClickListener(v -> {
            if (existingFileUri != null || existingFile != null) {
                FileOpenHelper.openFile(activity, existingFileUri, mimeType, existingFile);
                dialog.dismiss();
            }
        });
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        dialog.show();

        // Start HEAD request to get size + real MIME type.
        // checkIfFileExists() is called from within fetchFileInfo()'s
        // UI-thread callback, so the Open button appears at the same
        // time the Download button is enabled.
        fetchFileInfo();
    }

    /**
     * Resolve the real direct download URL.
     *
     * tmpfiles.org/dl/<id>/<filename> 302-redirects to the HTML web page
     * (tmpfiles.org/<id>/<filename>), NOT to the file. The web page contains
     * the actual download link with a numeric prefix:
     * tmpfiles.org/dl/<numeric_prefix>/<id>/<filename>
     *
     * This method fetches the web page, extracts the real download URL from
     * the &lt;a class="download" href="..."&gt; tag, and returns it.
     */
    private String resolveRealDownloadUrl(String url) {
        HttpURLConnection conn = null;
        try {
            // Convert /dl/<id>/<filename> to the web page URL <id>/<filename>
            String pageUrl = url.replace("/dl/", "/");

            conn = (HttpURLConnection) new URL(pageUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            in.close();
            String html = buf.toString("UTF-8");

            // Extract the download URL from: <a class="download" href="REAL_URL">
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<a[^>]*class=\"download\"[^>]*href=\"([^\"]+)\""
            ).matcher(html);
            if (m.find()) {
                String realUrl = m.group(1);
                Log.i(TAG, "Resolved real download URL: " + realUrl);
                return realUrl;
            }

            // Fallback: try href.*dl/ pattern
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                    "href=\"(https://tmpfiles\\.org/dl/[^\"]+)\""
            ).matcher(html);
            if (m2.find()) {
                String realUrl = m2.group(1);
                Log.i(TAG, "Resolved real download URL (fallback): " + realUrl);
                return realUrl;
            }

            Log.w(TAG, "Could not find download link in web page HTML");
        } catch (Exception e) {
            Log.e(TAG, "resolveRealDownloadUrl failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /** HEAD request to get Content-Length and Content-Type before showing the Download button. */
    private void fetchFileInfo() {
        new Thread(() -> {
            // Step 1: resolve the real download URL (the /dl/<id>/<filename>
            // link redirects to HTML, not the file).
            resolvedDownloadUrl = resolveRealDownloadUrl(downloadUrl);
            if (resolvedDownloadUrl == null) {
                // Could not resolve — fall back to the original URL
                resolvedDownloadUrl = downloadUrl;
                Log.w(TAG, "Using original URL as fallback: " + resolvedDownloadUrl);
            }

            // Step 2: HEAD request on the resolved URL to get size + MIME type
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(resolvedDownloadUrl).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    long len = conn.getContentLength();
                    String type = conn.getContentType();
                    if (type != null && type.contains(";")) {
                        type = type.split(";")[0].trim();
                    }
                    final long finalLen = len;
                    final String finalType = type;
                    activity.runOnUiThread(() -> {
                        fileSize = finalLen;
                        if (finalType != null && !finalType.isEmpty()) {
                            mimeType = finalType;
                            typeText.setText(activity.getString(R.string.tmpfiles_type_label, mimeType));
                        }
                        if (fileSize > 0) {
                            sizeText.setText(activity.getString(R.string.tmpfiles_size_label,
                                    FileOpenHelper.formatFileSize(fileSize)));
                        } else {
                            sizeText.setText(activity.getString(R.string.tmpfiles_size_label,
                                    activity.getString(R.string.tmpfiles_size_unknown)));
                        }
                        downloadBtn.setEnabled(true);
                        downloadBtn.setAlpha(1.0f);
                        checkIfFileExists();
                    });
                } else {
                    // HEAD not supported (some servers return 405) — enable download anyway
                    activity.runOnUiThread(() -> {
                        sizeText.setText(activity.getString(R.string.tmpfiles_size_label,
                                activity.getString(R.string.tmpfiles_size_unknown)));
                        downloadBtn.setEnabled(true);
                        downloadBtn.setAlpha(1.0f);
                        checkIfFileExists();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "HEAD request failed", e);
                activity.runOnUiThread(() -> {
                    sizeText.setText(activity.getString(R.string.tmpfiles_size_label,
                            activity.getString(R.string.tmpfiles_size_unknown)));
                    downloadBtn.setEnabled(true);
                    downloadBtn.setAlpha(1.0f);
                    checkIfFileExists();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /**
     * Check if a file with the same name already exists in the Downloads
     * folder. If it does, show the "Open" button and change the Download
     * button text to "Download anyway".
     *
     * On Android 10+ (API 29+), files are stored via MediaStore — we query
     * MediaStore.Downloads.EXTERNAL_CONTENT_URI for a matching DISPLAY_NAME.
     * This is the same URI that both the tmpfiles download and the
     * BlobDownloadManager save files to, so it finds files from either path.
     * On older versions, we check the public Downloads directory directly.
     */
    private void checkIfFileExists() {
        new Thread(() -> {
            final String nameToFind = ensureExtension(filename, mimeType);
            Log.i(TAG, "checkIfFileExists: looking for \"" + nameToFind + "\"");
            boolean found = false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Query MediaStore.Downloads.EXTERNAL_CONTENT_URI — this is
                // the default external volume where files are actually saved
                // by both the tmpfiles download path and BlobDownloadManager.
                // Querying VOLUME_EXTERNAL_PRIMARY misses files on devices
                // where the two URIs point to different volumes.
                try {
                    String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME};
                    String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
                    String[] selectionArgs = {nameToFind};
                    android.database.Cursor cursor = activity.getContentResolver().query(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            projection, selection, selectionArgs, null);
                    if (cursor != null) {
                        Log.i(TAG, "MediaStore query returned " + cursor.getCount() + " row(s)");
                        if (cursor.moveToFirst()) {
                            long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                            existingFileUri = android.content.ContentUris.withAppendedId(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                            found = true;
                            Log.i(TAG, "Found existing file: " + existingFileUri);
                        }
                        cursor.close();
                    } else {
                        Log.w(TAG, "MediaStore query returned null cursor");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "MediaStore query failed", e);
                }

                // Fallback: if the exact-name query found nothing, try a
                // case-insensitive LIKE query. Some servers/save paths may
                // alter the filename (e.g. adding a suffix like " (1)").
                if (!found) {
                    try {
                        String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME};
                        String selection = MediaStore.Downloads.DISPLAY_NAME + " LIKE ?";
                        String[] selectionArgs = {nameToFind};
                        android.database.Cursor cursor2 = activity.getContentResolver().query(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                projection, selection, selectionArgs, null);
                        if (cursor2 != null) {
                            if (cursor2.moveToFirst()) {
                                long id = cursor2.getLong(cursor2.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                                existingFileUri = android.content.ContentUris.withAppendedId(
                                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                                found = true;
                                Log.i(TAG, "Found existing file (fallback): " + existingFileUri);
                            }
                            cursor2.close();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Fallback MediaStore query failed", e);
                    }
                }
            } else {
                // Pre-Q: check the filesystem directly
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, nameToFind);
                if (f.exists() && f.length() > 0) {
                    existingFile = f;
                    found = true;
                    Log.i(TAG, "Found existing file (pre-Q): " + f.getAbsolutePath());
                }
            }

            if (found) {
                activity.runOnUiThread(() -> {
                    openBtn.setVisibility(View.VISIBLE);
                    downloadBtn.setText(R.string.tmpfiles_btn_download_anyway);
                    // Start the marquee scrolling animation so the full
                    // "Download anyway" text is readable inside the fixed-
                    // size button. setSelected(true) is what triggers marquee.
                    downloadBtn.setSelected(true);
                    if (openAfterCheckbox != null) {
                        openAfterCheckbox.setChecked(false);
                    }
                });
            } else {
                Log.i(TAG, "No existing file found for \"" + nameToFind + "\"");
            }
        }).start();
    }

    /** Start the download: GET request, stream to MediaStore.Downloads, update progress. */
    private boolean isDownloading = false;

    private void startDownload() {
        // Prevent double-clicks — if a download is already in progress, ignore
        if (isDownloading) return;
        isDownloading = true;

        // Disable the download button immediately so the user gets clear
        // feedback that the tap was registered.
        downloadBtn.setEnabled(false);
        downloadBtn.setAlpha(0.5f);

        // Hide download/cancel buttons, show progress bar + "Downloading..." text
        buttonRow.setVisibility(View.GONE);
        downloadingLabel.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        if (fileSize > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setMax((int) (fileSize / 1024));
        } else {
            progressBar.setIndeterminate(true);
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream input = null;
            OutputStream output = null;
            Uri contentUri = null;
            File savedFile = null;
            try {
                // Use the resolved real download URL (not the /dl/<id>/<filename>
                // link, which redirects to HTML).
                String urlToDownload = resolvedDownloadUrl != null ? resolvedDownloadUrl : downloadUrl;
                conn = (HttpURLConnection) new URL(urlToDownload).openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept", "*/*");

                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) {
                    throw new Exception("HTTP " + code);
                }

                // Use the real MIME type from the GET response if available
                String realType = conn.getContentType();
                if (realType != null) {
                    if (realType.contains(";")) realType = realType.split(";")[0].trim();
                    if (!realType.isEmpty()) mimeType = realType;
                }

                long total = conn.getContentLength();
                if (total > 0) fileSize = total;

                // Ensure filename has the right extension for the MIME type
                String finalName = ensureExtension(filename, mimeType);

                input = conn.getInputStream();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = activity.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, finalName);
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    // Use EXTERNAL_CONTENT_URI (the default external volume) —
                    // this matches what checkIfFileExists() queries, so files
                    // saved here are findable on the next visit.
                    Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    contentUri = resolver.insert(collection, values);
                    if (contentUri == null) {
                        throw new Exception("Cannot create download entry");
                    }
                    output = resolver.openOutputStream(contentUri);
                    if (output == null) {
                        throw new Exception("Cannot open output stream");
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    savedFile = new File(dir, finalName);
                    output = new FileOutputStream(savedFile);
                }

                byte[] buffer = new byte[8192];
                int bytesRead;
                long written = 0;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    written += bytesRead;
                    if (fileSize > 0) {
                        final int progress = (int) (written / 1024);
                        final int max = (int) (fileSize / 1024);
                        activity.runOnUiThread(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setMax(max);
                            progressBar.setProgress(progress);
                        });
                    }
                }
                output.flush();

                // Finalize MediaStore entry
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && contentUri != null) {
                    ContentValues doneValues = new ContentValues();
                    doneValues.put(MediaStore.Downloads.IS_PENDING, 0);
                    activity.getContentResolver().update(contentUri, doneValues, null, null);
                }

                final String savedName = finalName;
                final Uri finalUri = contentUri;
                final File finalFile = savedFile;

                activity.runOnUiThread(() -> {
                    Toast.makeText(activity,
                            activity.getString(R.string.tmpfiles_download_success, savedName),
                            Toast.LENGTH_LONG).show();
                    dialog.dismiss();

                    // Open the file if the checkbox was checked
                    if (openAfterCheckbox != null && openAfterCheckbox.isChecked()) {
                        FileOpenHelper.openFile(activity, finalUri, mimeType, finalFile);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity,
                            activity.getString(R.string.tmpfiles_download_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    // Restore buttons so the user can retry
                    isDownloading = false;
                    downloadBtn.setEnabled(true);
                    downloadBtn.setAlpha(1.0f);
                    buttonRow.setVisibility(View.VISIBLE);
                    downloadingLabel.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                });
            } finally {
                if (input != null) try { input.close(); } catch (Exception ignored) {}
                if (output != null) try { output.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /**
     * Ensure the filename has an extension matching the MIME type.
     * E.g. if MIME is "text/plain" but filename is "test", return "test.txt".
     */
    private String ensureExtension(String name, String mime) {
        if (name == null || name.isEmpty()) name = "download";
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            return name; // already has an extension
        }
        if (mime != null) {
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null && !ext.isEmpty()) {
                return name + "." + ext;
            }
        }
        return name;
    }
}
