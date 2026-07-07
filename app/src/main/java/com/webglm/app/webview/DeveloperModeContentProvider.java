package com.webglm.app.webview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fake "developer mode" flags provider.
 *
 * Chromium WebView (and other compatible implementations) queries a content
 * provider with authority {@code <own-package>.DeveloperModeContentProvider}
 * to read developer-mode flag overrides. We install our own provider with
 * the *chosen WebView package's* name as the prefix, so we get to fabricate
 * the answers.
 *
 * Two flags are returned as enabled:
 * <ul>
 *   <li>{@code ignore-gpu-blocklist} — forces GPU acceleration even on
 *       blocklisted GPU drivers. Useful on Android TV / low-end devices
 *       where the default WebView would otherwise fall back to software rendering.</li>
 *   <li>{@code WebViewSurfaceControl} — uses SurfaceControl for compositing,
 *       which is faster than the default path on devices that support it.</li>
 * </ul>
 *
 * {@code insert/delete/update} are intentionally not implemented — Chromium
 * only ever calls {@code query} on this provider.
 */
public final class DeveloperModeContentProvider extends ContentProvider {

    private static final String TAG = "DevModeContentProvider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String[] columns = {"flagName", "flagState"};
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("ignore-gpu-blocklist", true);
        flags.put("WebViewSurfaceControl", true);

        MatrixCursor cursor = new MatrixCursor(columns, flags.size());
        for (Map.Entry<String, Boolean> e : flags.entrySet()) {
            cursor.addRow(new Object[]{e.getKey(), e.getValue() ? 1 : 0});
        }
        Log.i(TAG, "Returning flags: " + flags);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "content";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("insert not supported");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("delete not supported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("update not supported");
    }
}
