package com.webglm.app.webview;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks GitHub releases for a newer version of the app.
 *
 * <p>When an update is available, the caller opens the release page in the
 * user's default browser — no in-app APK download or install. The browser
 * handles the download and the system package installer handles the install,
 * which is more reliable than doing it in-app (especially on Huawei/Xiaomi
 * devices where PackageInstaller and FileProvider-based installs crash).
 *
 * <p>Releases page: https://github.com/MrHuaweiFan/WebGLM/releases
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    private static final String REPO_API =
            "https://api.github.com/repos/MrHuaweiFan/WebGLM/releases";
    private static final String USER_AGENT = "WebGLM-Android-Updater/1.0";

    public interface Callback {
        /**
         * @param updateAvailable  true if a newer release exists on GitHub
         * @param latestVersion    normalized version string (e.g. "6.18")
         * @param releaseUrl       URL to open in the browser (release page or direct APK)
         * @param errorMessage     non-null if the check failed
         */
        void onResult(boolean updateAvailable, String latestVersion, String releaseUrl,
                      String errorMessage);
    }

    private UpdateChecker() {}

    /**
     * Hit the GitHub releases API and compare the latest tag against the
     * running version. The callback is invoked on a worker thread.
     */
    public static void checkForUpdates(final String currentVersion, final Callback cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(REPO_API);
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);

                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();

                JSONArray releases = new JSONArray(sb.toString());
                if (releases.length() == 0) {
                    cb.onResult(false, currentVersion, null, "No releases found");
                    return;
                }
                JSONObject latest = null;
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject rel = releases.optJSONObject(i);
                    if (rel == null) continue;
                    if (!rel.optBoolean("prerelease", false)) {
                        latest = rel;
                        break;
                    }
                }
                if (latest == null) latest = releases.optJSONObject(0);
                if (latest == null) {
                    cb.onResult(false, currentVersion, null, "Could not parse releases");
                    return;
                }

                String tagName = latest.optString("tag_name", "");
                String latestVer = normalizeVersion(tagName);
                // Look for an asset named app-release.apk. If found, use its
                // browser_download_url. Otherwise, fall back to the release's
                // HTML page so the user can pick the asset manually.
                String releaseUrl = latest.optString("html_url", "");
                JSONArray assets = latest.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) continue;
                        String name = a.optString("name", "");
                        if ("app-release.apk".equals(name)) {
                            String dl = a.optString("browser_download_url", "");
                            if (!dl.isEmpty()) {
                                releaseUrl = dl;
                                break;
                            }
                        }
                    }
                }

                boolean newer = compareVersions(latestVer, currentVersion) > 0;
                cb.onResult(newer, latestVer, releaseUrl, null);
            } catch (Exception e) {
                Log.e(TAG, "checkForUpdates failed", e);
                cb.onResult(false, currentVersion, null, e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Stale APK cleanup (for backwards compat with old in-app downloads)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Find any leftover APK from the old in-app download flow. Used by
     * SettingsActivity to clean up stale files on launch. The current update
     * flow opens the browser instead, so no new APKs are created in-app.
     */
    public static java.io.File findDownloadedApk(android.content.Context context) {
        java.io.File dir = context.getExternalFilesDir(null);
        if (dir == null) return null;
        java.io.File[] apks = dir.listFiles((d, name) ->
                name.startsWith("v") && name.endsWith(".apk"));
        if (apks == null || apks.length == 0) return null;
        return apks[0];
    }

    // ──────────────────────────────────────────────────────────────────────
    // Version helpers
    // ──────────────────────────────────────────────────────────────────────

    /** "v6.18-release" → "6.18", "v6.18" → "6.18", "6.18" → "6.18". */
    static String normalizeVersion(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
        int dash = t.indexOf('-');
        if (dash > 0) t = t.substring(0, dash);
        return t.trim();
    }

    static int compareVersions(String a, String b) {
        String[] as = normalizeVersion(a).split("\\.");
        String[] bs = normalizeVersion(b).split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int ai = i < as.length ? parseIntSafe(as[i]) : 0;
            int bi = i < bs.length ? parseIntSafe(bs[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (Character.isDigit(c)) sb.append(c);
                else break;
            }
            return sb.length() > 0 ? Integer.parseInt(sb.toString()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
