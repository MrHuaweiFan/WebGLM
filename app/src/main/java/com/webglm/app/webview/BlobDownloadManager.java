package com.webglm.app.webview;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Handles blob: downloads from a WebView.
 *
 * <p>Pairs with {@code assets/blob-download-inject.js}, which:
 * <ul>
 *   <li>Overrides {@code URL.createObjectURL} so it can hand us the actual
 *       Blob bytes instead of a blob: URL string. This avoids the
 *       revoke-before-we-fetch race that breaks the naive fetch(blobUrl)
 *       approach: {@code revokeObjectURL} only deletes the URL→Blob entry
 *       in the browser's registry; it does not free a Blob object this
 *       script still holds a reference to.
 *   <li>Streams those bytes to {@code window.AndroidBlobBridge} as chunked,
 *       self-describing ArrayBuffer messages.
 * </ul>
 *
 * <p>This class registers that bridge via {@code addWebMessageListener}
 * (the currently-recommended mechanism per Android's own JS-bridge
 * guidance: origin-restricted, reaches every matching frame including
 * iframes, and carries raw byte[] instead of base64 text) and injects
 * the script via {@code addDocumentStartJavaScript}, which — unlike
 * {@code evaluateJavascript} — also reaches iframes, not just the main
 * frame.
 *
 * <p><b>IMPORTANT:</b> call {@link #attach} BEFORE {@code webView.loadUrl}.
 * Both {@code addWebMessageListener} and {@code addDocumentStartJavaScript}
 * only affect frames that begin loading <i>after</i> they're registered.
 */
public class BlobDownloadManager {

    private static final String TAG = "BlobDownloadManager";
    private static final String BRIDGE_NAME = "AndroidBlobBridge";

    public interface ResultCallback {
        /**
         * @param success true if the blob was saved to Downloads
         * @param message a user-facing message (filename + path, or error)
         */
        void onResult(boolean success, String message);
    }

    private final Context context;
    private final ResultCallback onResult;

    /** Single-thread executor — preserves chunk ordering per transfer id. */
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();

    private static class Transfer {
        final String filename;
        final String mimeType;
        OutputStream out;
        Uri uri;
        File legacyFile;

        Transfer(String filename, String mimeType) {
            this.filename = filename;
            this.mimeType = mimeType;
        }
    }

    private final ConcurrentHashMap<String, Transfer> transfers = new ConcurrentHashMap<>();

    public BlobDownloadManager(Context context, ResultCallback onResult) {
        this.context = context;
        this.onResult = onResult;
    }

    /**
     * Register the bridge and inject the script. Returns true if the bridge
     * could be registered (i.e. this WebView build supports it).
     */
    public boolean attach(WebView webView, java.util.Set<String> allowedOrigins, String injectedScript) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "WEB_MESSAGE_LISTENER unsupported on this WebView version; blob downloads disabled");
            return false;
        }

        WebViewCompat.WebMessageListener listener = new WebViewCompat.WebMessageListener() {
            @Override
            public void onPostMessage(WebView view, WebMessageCompat message,
                                      Uri sourceOrigin, boolean isMainFrame,
                                      JavaScriptReplyProxy replyProxy) {
                onMessage(message, replyProxy);
            }
        };
        try {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, allowedOrigins, listener);
        } catch (Exception e) {
            Log.e(TAG, "addWebMessageListener failed", e);
            return false;
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, injectedScript, allowedOrigins);
            } catch (Exception e) {
                Log.e(TAG, "addDocumentStartJavaScript failed", e);
            }
        } else {
            // Very old WebView fallback. evaluateJavascript only reaches the
            // main frame, so blobs created inside an iframe won't be visible
            // to this copy of the script.
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    view.evaluateJavascript(injectedScript, null);
                }
            });
        }
        return true;
    }

    private void onMessage(WebMessageCompat message, JavaScriptReplyProxy replyProxy) {
        if (message.getType() == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            handleChunk(message.getArrayBuffer(), replyProxy);
        } else if (message.getType() == WebMessageCompat.TYPE_STRING) {
            handleControlMessage(message.getData());
        }
    }

    /** Only used for JS-side error reports (see the .catch() in sendBlob() in the injected script). */
    private void handleControlMessage(String json) {
        if (json == null) return;
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.optBoolean("error", false)) {
                onResult.onResult(false, obj.optString("message", "Unknown blob transfer error"));
            }
        } catch (Exception e) {
            Log.e(TAG, "handleControlMessage parse failed", e);
        }
    }

    /**
     * Each message is: [4-byte little-endian header length][UTF-8 JSON header][raw payload bytes].
     * Every chunk carries its own header (id/filename/mimeType/last), so there's no dependency
     * on message ordering between separate "start"/"chunk"/"end" messages — only on chunks for
     * the same id arriving in order, which the single-thread executor here preserves.
     */
    private void handleChunk(byte[] bytes, JavaScriptReplyProxy replyProxy) {
        io.execute(() -> {
            try {
                if (bytes.length < 4) return;
                int headerLen = (bytes[0] & 0xFF)
                        | ((bytes[1] & 0xFF) << 8)
                        | ((bytes[2] & 0xFF) << 16)
                        | ((bytes[3] & 0xFF) << 24);
                if (4 + headerLen > bytes.length) {
                    Log.e(TAG, "Chunk header length " + headerLen + " exceeds message size " + bytes.length);
                    return;
                }
                String headerJson = new String(bytes, 4, headerLen, StandardCharsets.UTF_8);
                JSONObject header = new JSONObject(headerJson);
                int payloadOffset = 4 + headerLen;
                int payloadLen = bytes.length - payloadOffset;

                String id = header.getString("id");
                boolean isLast = header.optBoolean("last", false);
                Transfer transfer = transfers.get(id);
                if (transfer == null) {
                    transfer = new Transfer(
                            sanitizeFilename(header.optString("filename", "download")),
                            header.optString("mimeType", "application/octet-stream"));
                    transfers.put(id, transfer);
                    openOutput(transfer);
                }

                if (payloadLen > 0 && transfer.out != null) {
                    transfer.out.write(bytes, payloadOffset, payloadLen);
                }

                if (isLast) {
                    transfers.remove(id);
                    finish(transfer);
                    if (replyProxy != null) {
                        try { replyProxy.postMessage("ok:" + transfer.filename); }
                        catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Blob transfer failed", e);
                onResult.onResult(false, "Blob transfer failed: " + e.getMessage());
            }
        });
    }

    private void openOutput(Transfer t) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, t.filename);
            values.put(MediaStore.Downloads.MIME_TYPE, t.mimeType);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore insert failed for " + t.filename);
            }
            t.uri = uri;
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                throw new IOException("openOutputStream failed for " + uri);
            }
            t.out = out;
        } else {
            // API < 29 needs WRITE_EXTERNAL_STORAGE granted at runtime before this path is reached.
            @SuppressWarnings("DEPRECATION")
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, t.filename);
            t.legacyFile = file;
            t.out = new FileOutputStream(file);
        }
    }

    private void finish(Transfer t) {
        try {
            if (t.out != null) {
                t.out.flush();
                t.out.close();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (t.uri != null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(t.uri, values, null, null);
                }
            } else {
                if (t.legacyFile != null) {
                    MediaScannerConnection.scanFile(context,
                            new String[]{t.legacyFile.getAbsolutePath()},
                            new String[]{t.mimeType}, null);
                }
            }
            onResult.onResult(true, t.filename + " saved to Downloads");
        } catch (IOException e) {
            onResult.onResult(false, "Couldn't finalize " + t.filename + ": " + e.getMessage());
        }
    }

    private String sanitizeFilename(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) {
            return "download_" + System.currentTimeMillis();
        }
        return cleaned;
    }
}
