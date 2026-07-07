package com.webglm.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.widget.ImageViewCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.webglm.app.webview.CrashTracker;
import com.webglm.app.webview.WebViewManagerDialog;
import com.webglm.app.webview.WebViewUtil;
import com.webglm.app.webview.WelcomeDialog;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "WebGLMApp";
    private static final String PREFS_NAME = "webglm_prefs";
    private static final String KEY_DESKTOP_MODE = "desktop_mode";

    private static final String URL = "https://chat.z.ai/";

    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final int REQUEST_FILE_CHOOSER = 54321;
    private static final int REQUEST_STORAGE_PERM = 1003;

    /** Safety timeout: force-hide the loading overlay after this many ms,
     *  even if onPageFinished never fires (e.g. page hangs or errors silently). */
    private static final int LOADING_SAFETY_TIMEOUT_MS = 10_000;

    WebView webview;
    ViewGroup rootLayout;
    View loadingOverlay;
    ImageView loadingLogo;
    boolean desktopMode;
    boolean initialLoadComplete = false;
    private final List<WebView> popupViews = new ArrayList<>();

    private Map<String, String> extraHeaders;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingShareFileUri;

    /** Manages blob: URL downloads via WebMessageListener + document-start JS. */
    private com.webglm.app.webview.BlobDownloadManager blobDownloadManager;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ─── WebView switcher pre-launch checks ────────────────────────────
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!WebViewUtil.isSupported()) {
                final WebViewManagerDialog[] dlg = new WebViewManagerDialog[1];
                dlg[0] = new WebViewManagerDialog(this,
                        new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface d) {
                                if (dlg[0] != null && dlg[0].changedWebView()) {
                                    Intent i = getPackageManager()
                                            .getLaunchIntentForPackage(getPackageName());
                                    if (i != null) {
                                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                | Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                        startActivity(i);
                                    }
                                    finishAffinity();
                                    android.os.Process.killProcess(android.os.Process.myPid());
                                } else {
                                    finish();
                                }
                            }
                        });
                dlg[0].setCancelable(false);
                dlg[0].show();
                return;
            }

            if (CrashTracker.hasCrashes()) {
                Log.w(TAG, "Crash threshold reached; bouncing to WebView Manager");
                Toast.makeText(this, R.string.webview_pick_another, Toast.LENGTH_LONG).show();
                CrashTracker.reset(true);
                Intent i = new Intent(this, SettingsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
                return;
            }
        }
        // ─── End WebView switcher pre-launch checks ────────────────────────

        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        desktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false);
        Log.i(TAG, "Launching with desktop_mode=" + desktopMode);

        extraHeaders = new HashMap<>();
        extraHeaders.put("X-Requested-With", "");

        webview = findViewById(R.id.activity_main_webview);
        rootLayout = (ViewGroup) webview.getParent();
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingLogo = findViewById(R.id.loading_logo);

        boolean isDark = isDarkMode();

        // Z.ai logo — just the Z shape (no square background). Tint it based
        // on the device theme: dark Z (#2D2D2D) on light background,
        // white Z (#FFFFFF) on dark background.
        loadingLogo.setImageResource(R.drawable.ic_zai_logo);
        ImageViewCompat.setImageTintList(loadingLogo,
                ColorStateList.valueOf(isDark ? 0xFFFFFFFF : 0xFF2D2D2D));

        // Fade animation only (no spin — the Z.ai logo stays upright).
        Animation fadeInOut = AnimationUtils.loadAnimation(this, R.anim.fade_in_out);
        loadingLogo.startAnimation(fadeInOut);

        // Set WebView background to match the theme — prevents a white flash
        // when the overlay fades out and before the page paints its own bg.
        webview.setBackgroundColor(isDark ? 0xFF161616 : 0xFFF8F8F8);

        // Hardware acceleration
        webview.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        configureWebView(webview.getSettings());

        // Install JS polyfills (window.chrome etc.) as early as possible —
        // BEFORE the page's own scripts run. This uses addDocumentStartJavaScript
        // (API 24+ / modern WebView). Falls back to onPageStarted injection on
        // older devices. Critical for the blank-page fix: Z.ai's JS checks for
        // window.chrome and may refuse to render without it.
        installDocumentStartScripts();

        // Attach the blob-download bridge BEFORE loadUrl. Both
        // addWebMessageListener and addDocumentStartJavaScript only affect
        // frames that begin loading AFTER they're registered. This captures
        // Blob objects at createObjectURL time so revoke-before-fetch races
        // can't break downloads.
        setupBlobDownloadBridge(webview);

        setupClients(webview);
        setupDownloads(webview);

        WelcomeDialog.showIfNeeded(this);

        Intent launchIntent = getIntent();
        if (launchIntent != null) {
            handleShareIntent(launchIntent);
        }

        if (desktopMode) {
            CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() {
                @Override
                public void onReceiveValue(Boolean value) {
                    CookieManager.getInstance().flush();
                    webview.clearCache(true);
                    webview.clearHistory();
                    WebStorage.getInstance().deleteAllData();
                    loadUrlWithHeaders(webview, URL);
                }
            });
        } else {
            loadUrlWithHeaders(webview, URL);
        }

        // Safety timeout — if onPageFinished doesn't fire within 10 seconds
        // (e.g. the page hangs, or a silent error), force-hide the loading
        // overlay so the user isn't stuck staring at a loading screen.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!initialLoadComplete) {
                Log.w(TAG, "Safety timeout — forcing loading overlay hide");
                hideLoadingOverlay();
            }
        }, LOADING_SAFETY_TIMEOUT_MS);
    }

    private boolean isDarkMode() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Hide the loading overlay with a fade-out animation. Safe to call
     * multiple times — subsequent calls are no-ops once initialLoadComplete
     * is true.
     */
    private void hideLoadingOverlay() {
        if (loadingOverlay == null || initialLoadComplete) return;
        initialLoadComplete = true;
        Animation fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}
            @Override
            public void onAnimationEnd(Animation animation) {
                loadingOverlay.setVisibility(View.GONE);
                if (loadingLogo != null) loadingLogo.clearAnimation();
            }
            @Override
            public void onAnimationRepeat(Animation animation) {}
        });
        loadingOverlay.startAnimation(fadeOut);
    }

    /**
     * Install JS overrides using addDocumentStartJavaScript (API 24+ with
     * modern WebView). This guarantees the script runs BEFORE any page
     * script, which is critical for the window.chrome polyfill — without
     * it, Z.ai's feature detection may fail and the page renders blank.
     */
    private void installDocumentStartScripts() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webview,
                        getOverrideJs(), Collections.singleton("*"));
                Log.i(TAG, "Document start scripts installed");
            } catch (Exception e) {
                Log.e(TAG, "addDocumentStartJavaScript failed", e);
            }
        } else {
            Log.i(TAG, "DOCUMENT_START_SCRIPT not supported — using onPageStarted fallback");
        }
    }

    /**
     * Returns the full JS override string. Used by both addDocumentStartScripts
     * (pre-page, API 24+) and injectAllOverrides (post-page, fallback).
     *
     * NOTE: blob: URL downloads are handled separately by BlobDownloadManager
     * (which injects assets/blob-download-inject.js via its own document-start
     * script + a WebMessageListener bridge). Do NOT add blob handling here —
     * it would conflict with the BlobDownloadManager's hooks.
     *
     * Contents:
     * 1. window.chrome polyfill — WebView doesn't define window.chrome, which
     *    some sites use for feature detection. Without it the site may refuse
     *    to render (blank page).
     * 2. navigator.share override — delegates text→clipboard via AndroidBridge.
     * 3. document.execCommand('copy') fallback — same delegation for legacy paths.
     */
    private String getOverrideJs() {
        return "(function(){" +
            // === window.chrome polyfill ===
            "  try {" +
            "    if (!window.chrome) { window.chrome = {}; }" +
            "    if (!window.chrome.runtime) { window.chrome.runtime = {}; }" +
            "  } catch(e) {}" +
            // === navigator.share override (text→clipboard only) ===
            "  try {" +
            "    if (!window._shareOverridden) {" +
            "      window._shareOverridden = true;" +
            "      navigator.share = function(data) {" +
            "        try {" +
            "          var text = (data && data.text) ? data.text : '';" +
            "          if (text && window.AndroidBridge) {" +
            "            window.AndroidBridge.copyToClipboard(String(text));" +
            "            return Promise.resolve();" +
            "          }" +
            "          return Promise.reject(new Error('Nothing to copy'));" +
            "        } catch(e) { return Promise.reject(e); }" +
            "      };" +
            "      navigator.canShare = function() { return true; };" +
            "    }" +
            "  } catch(e) {}" +
            // === document.execCommand('copy') fallback ===
            "  try {" +
            "    if (!window._execOverridden) {" +
            "      window._execOverridden = true;" +
            "      var origExec = document.execCommand.bind(document);" +
            "      document.execCommand = function(cmd, showUI, value) {" +
            "        if (cmd === 'copy') {" +
            "          var sel = window.getSelection();" +
            "          if (sel && sel.toString()) {" +
            "            try {" +
            "              if (window.AndroidBridge) window.AndroidBridge.copyToClipboard(sel.toString());" +
            "            } catch(e) {}" +
            "            return true;" +
            "          }" +
            "        }" +
            "        return origExec(cmd, showUI, value);" +
            "      };" +
            "    }" +
            "  } catch(e) {}" +
            "})();";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i(TAG, "handleShareIntent: action=" + action + ", type=" + intent.getType());

        String sharedText = null;
        Uri sharedFileUri = null;
        String sharedFileMime = null;

        if (Intent.ACTION_SEND.equals(action)) {
            String type = intent.getType();
            if (type != null && type.startsWith("text/") && intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            } else {
                Uri fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (fileUri != null) {
                    sharedFileUri = fileUri;
                    sharedFileMime = type != null ? type : "*/*";
                }
            }
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (text != null) {
                sharedText = text.toString();
            }
        }

        if (sharedText != null) {
            handleSharedText(sharedText);
        } else if (sharedFileUri != null) {
            handleSharedFile(sharedFileUri, sharedFileMime);
        }
    }

    private void handleSharedText(String text) {
        Log.i(TAG, "handleSharedText: " + (text.length() > 80 ? text.substring(0, 80) + "..." : text));
        copyToClipboard(text);
        loadUrlWithHeaders(webview, URL);
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                android.content.ClipData clip = android.content.ClipData.newPlainText("WebGLM", text);
                clipboard.setPrimaryClip(clip);
                Log.i(TAG, "Text copied to clipboard");
                Toast.makeText(this, "Text copied — paste it into WebGLM", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "copyToClipboard failed", e);
        }
    }

    private void handleSharedFile(Uri fileUri, String mime) {
        Log.i(TAG, "handleSharedFile: " + fileUri + " (" + mime + ")");
        new Thread(() -> {
            try {
                String fileName = "shared_file_" + System.currentTimeMillis();
                String originalName = getFileNameFromUri(fileUri);
                if (originalName != null && !originalName.isEmpty()) {
                    fileName = originalName;
                } else {
                    String ext = android.webkit.MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(mime);
                    if (ext != null && !ext.isEmpty()) {
                        fileName += "." + ext;
                    }
                }
                File outFile = new File(getCacheDir(), fileName);
                InputStream in = getContentResolver().openInputStream(fileUri);
                if (in == null) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Cannot read the shared file", Toast.LENGTH_LONG).show());
                    return;
                }
                java.io.OutputStream out = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
                out.close();
                in.close();
                final Uri sharedUri = androidx.core.content.FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", outFile);
                pendingShareFileUri = sharedUri;

                // Tell the user to attach the file manually via the + button.
                // We can't auto-attach because Z.ai's + button opens a menu,
                // not directly the file picker — the user has to tap it.
                final String finalFileName = fileName;
                runOnUiThread(() -> Toast.makeText(this,
                        "Tap + in WebGLM to attach: " + finalFileName,
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "handleSharedFile failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Failed to process file: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(
                    uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        result = cursor.getString(idx);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }

    private void loadUrlWithHeaders(WebView view, String url) {
        view.loadUrl(url, extraHeaders);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBlockNetworkImage(false);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBlockNetworkLoads(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        settings.setUserAgentString(desktopMode ? UA_DESKTOP : UA_MOBILE);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(true);
        settings.setDisplayZoomControls(false);

        webview.requestFocusFromTouch();
        CookieManager.getInstance().setAcceptThirdPartyCookies(webview, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        if (desktopMode) {
            webview.setInitialScale(33);
        } else {
            webview.setInitialScale(0);
        }

        webview.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");
    }

    public static class WebAppInterface {
        private final MainActivity activity;

        WebAppInterface(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void copyToClipboard(final String text) {
            activity.runOnUiThread(() -> {
                try {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        android.content.ClipData clip =
                                android.content.ClipData.newPlainText("WebGLM", text);
                        clipboard.setPrimaryClip(clip);
                        Log.i("WebGLMApp", "Text copied to clipboard via JS override");
                    }
                } catch (Exception e) {
                    Log.e("WebGLMApp", "copyToClipboard (JS) failed", e);
                }
            });
        }
    }

    private void openUrlInBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "openUrlInBrowser failed", e);
        }
    }

    private boolean isInternalHost(String host) {
        if (host == null) return false;
        return host.endsWith("z.ai")
                || host.equals("accounts.google.com")
                || host.endsWith(".accounts.google.com")
                || host.endsWith("auth0.com");
    }

    /**
     * Returns true if the URL is a tmpfiles.org direct download link
     * (https://tmpfiles.org/dl/<id>/<filename>) AND the in-app download
     * feature is enabled in settings.
     */
    private boolean isTmpfilesDirectDownload(String url) {
        if (url == null) return false;
        if (!com.webglm.app.webview.FileOpenHelper.isTmpfilesInAppEnabled(this)) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && host.equalsIgnoreCase("tmpfiles.org")
                    && path != null && path.startsWith("/dl/");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Show the tmpfiles download dialog for the given URL.
     */
    private void showTmpfilesDownloadDialog(String url) {
        Log.i(TAG, "Showing tmpfiles download dialog for: " + url);
        new com.webglm.app.webview.TmpfilesDownloadDialog(this, url).show();
    }

    private void setupClients(final WebView webView) {
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, cm.message() + " -- line " + cm.lineNumber() + " of " + cm.sourceId());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView w,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                if (pendingShareFileUri != null) {
                    Log.i(TAG, "onShowFileChooser: returning pending shared file " + pendingShareFileUri);
                    filePathCallback.onReceiveValue(new Uri[]{pendingShareFileUri});
                    filePathCallback = null;
                    pendingShareFileUri = null;
                    return true;
                }
                Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentIntent.setType("*/*");
                contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                Intent chooser = Intent.createChooser(contentIntent, "Select file");
                try {
                    startActivityForResult(chooser, REQUEST_FILE_CHOOSER);
                } catch (Exception e) {
                    Log.e(TAG, "File chooser failed", e);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                return createPopup(resultMsg);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            // ─── New shouldOverrideUrlLoading (API 24+) ───────────────
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return shouldOverrideUrlLoading(v, request.getUrl().toString());
            }

            // ─── Legacy shouldOverrideUrlLoading (used as fallback) ───
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return false;
                // Blob URLs → download via in-page JS. The blob only exists
                // in this page's JS context, so we must fetch it here.
                if (url.startsWith("blob:")) {
                    Log.i(TAG, "Blob URL intercepted in main WebView: " + url);
                    downloadBlobUrl(v, url, null, null);
                    return true;
                }
                // tmpfiles.org/dl/ links → show the in-app download dialog
                if (isTmpfilesDirectDownload(url)) {
                    showTmpfilesDownloadDialog(url);
                    return true;
                }
                Uri uri;
                try { uri = Uri.parse(url); } catch (Exception e) { return false; }
                String host = uri.getHost();
                if (!isInternalHost(host)) {
                    openUrlInBrowser(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                // Inject overrides early — this is the fallback for API 21-23
                // where addDocumentStartJavaScript isn't available. On API 24+
                // the document start script handles this, but injecting here
                // too is harmless (the JS is idempotent).
                v.evaluateJavascript(getOverrideJs(), null);
                // Only show the loading overlay during the INITIAL page load.
                if (!initialLoadComplete && loadingOverlay != null
                        && loadingOverlay.getVisibility() != View.VISIBLE) {
                    loadingOverlay.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                CrashTracker.reset(true);
                CookieManager.getInstance().flush();
                injectAllOverrides(v);
                if (initialLoadComplete) return;
                // Delay the fade-out slightly so the page has a chance to
                // paint its own background, preventing a flash.
                webview.postDelayed(MainActivity.this::hideLoadingOverlay, 1500);
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            // ─── Error handling: hide loading overlay on failure ───────────
            // These ensure the user never gets stuck on a loading screen if
            // the page fails to load (network error, HTTP error, etc.).

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView v, int errorCode, String description, String failingUrl) {
                super.onReceivedError(v, errorCode, description, failingUrl);
                Log.e(TAG, "onReceivedError: " + errorCode + " " + description + " (" + failingUrl + ")");
                hideLoadingOverlay();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(v, request, error);
                if (request.isForMainFrame()) {
                    Log.e(TAG, "onReceivedError(main): " + error.getErrorCode() + " " + error.getDescription());
                    hideLoadingOverlay();
                }
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(v, request, errorResponse);
                if (request.isForMainFrame()) {
                    Log.e(TAG, "onReceivedHttpError(main): HTTP " + errorResponse.getStatusCode());
                    hideLoadingOverlay();
                }
            }
        });
    }

    /**
     * Post-page JS injection (fallback). The primary injection happens via
     * addDocumentStartScripts() (pre-page). This call ensures the overrides
     * are also applied after navigation completes, covering any edge cases.
     */
    private void injectAllOverrides(WebView v) {
        v.evaluateJavascript(getOverrideJs(), null);
    }

    /**
     * Attach the BlobDownloadManager — registers a WebMessageListener bridge
     * ("AndroidBlobBridge") and injects assets/blob-download-inject.js via
     * addDocumentStartJavaScript. The JS hooks URL.createObjectURL to capture
     * every Blob the page creates, then streams its bytes to native when a
     * blob: URL is opened via window.open or an <a download> click.
     *
     * This avoids the revoke-before-fetch race: revokeObjectURL only deletes
     * the URL→Blob registry entry; the Blob object survives because the JS
     * holds a separate reference to it.
     */
    private void setupBlobDownloadBridge(WebView webView) {
        try {
            String script = readAsset("blob-download-inject.js");
            if (script == null) {
                Log.e(TAG, "blob-download-inject.js not found in assets — blob downloads disabled");
                return;
            }
            java.util.Set<String> origins = new java.util.HashSet<>();
            origins.add("https://chat.z.ai");
            origins.add("https://z.ai");

            blobDownloadManager = new com.webglm.app.webview.BlobDownloadManager(
                    this, (success, message) -> runOnUiThread(() ->
                            Toast.makeText(this, message,
                                    success ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show()));
            boolean ok = blobDownloadManager.attach(webView, origins, script);
            Log.i(TAG, "BlobDownloadManager.attach → " + ok);
        } catch (Exception e) {
            Log.e(TAG, "setupBlobDownloadBridge failed", e);
        }
    }

    /** Read a file from app/src/main/assets/ as a UTF-8 string. */
    private String readAsset(String name) {
        try (InputStream is = getAssets().open(name)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
            return buf.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "readAsset failed: " + name, e);
            return null;
        }
    }

    private void setupDownloads(WebView webView) {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            Log.i(TAG, "Download requested: " + url + " (mime: " + mimetype + ")");
            if (url != null && url.startsWith("blob:")) {
                // Blob URLs can't be fetched via HttpURLConnection — they only
                // exist inside the page's JS context. Fetch the bytes via
                // in-page JavaScript and pass them back as base64.
                downloadBlobUrl(webView, url, contentDisposition, mimetype);
                return;
            }
            downloadWithCookies(url, userAgent, contentDisposition, mimetype);
        });
    }

    /**
     * Download a blob: URL by fetching it via in-page JavaScript and passing
     * the base64-encoded bytes back to native code via the evaluateJavascript
     * callback. Self-contained — no persistent JS globals left behind.
     *
     * This is required because Z.ai generates blob: URLs for file downloads
     * (e.g. attachments, exported chats). HttpURLConnection cannot fetch a
     * blob: URL — the bytes only exist inside the page's JS context.
     */
    private void downloadBlobUrl(WebView webView, String blobUrl,
                                 String contentDisposition, String mimetype) {
        final String filename = guessFilenameFromDownload(blobUrl, contentDisposition, mimetype);
        final String finalMime = mimetype != null ? mimetype : "application/octet-stream";
        Toast.makeText(this, "Downloading " + filename + "...", Toast.LENGTH_SHORT).show();

        // Fetch the blob via fetch(), read as data URL, store on window.__blobResult.
        String js = "(function(){" +
                "  try {" +
                "    fetch('" + blobUrl.replace("'", "\\'") + "')" +
                "      .then(function(r){return r.blob();})" +
                "      .then(function(b){" +
                "        var fr = new FileReader();" +
                "        fr.onloadend = function(){" +
                "          try { window.__blobResult = fr.result; } catch(e) { window.__blobResult = ''; }" +
                "        };" +
                "        fr.readAsDataURL(b);" +
                "      })" +
                "      .catch(function(){ window.__blobResult = ''; });" +
                "  } catch(e) { window.__blobResult = ''; }" +
                "})();";

        // Poll window.__blobResult a few times — FileReader is async.
        final android.os.Handler h = new android.os.Handler(Looper.getMainLooper());
        final int[] tries = {0};
        final Runnable[] pollHolder = new Runnable[1];
        Runnable poll = new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript(
                        "(function(){var v=window.__blobResult||null; if(v!==null){window.__blobResult=null;} return v;})();",
                        value -> {
                            tries[0]++;
                            if (value != null && !value.equals("null") && !value.isEmpty()) {
                                // value is a JS string literal: "data:...;base64,XXXX"
                                String s = value;
                                if (s.startsWith("\"") && s.endsWith("\"")) {
                                    s = s.substring(1, s.length() - 1);
                                    s = s.replace("\\\"", "\"").replace("\\\\", "\\");
                                }
                                if (s.startsWith("data:")) {
                                    int comma = s.indexOf(',');
                                    if (comma > 0) {
                                        String b64 = s.substring(comma + 1);
                                        // Extract mime type from data URL:
                                        // data:<mime>;base64,<data>
                                        String header = s.substring(5, comma);
                                        String dataMime = finalMime;
                                        int semi = header.indexOf(';');
                                        if (semi > 0) {
                                            String mimePart = header.substring(0, semi);
                                            if (!mimePart.isEmpty()) dataMime = mimePart;
                                        } else if (!header.isEmpty() && !header.equals("base64")) {
                                            dataMime = header;
                                        }
                                        // If we didn't have a mime type from
                                        // the caller, add the right extension.
                                        String finalName = filename;
                                        if (mimetype == null && dataMime != null
                                                && !dataMime.equals("application/octet-stream")) {
                                            String ext = android.webkit.MimeTypeMap.getSingleton()
                                                    .getExtensionFromMimeType(dataMime);
                                            if (ext != null && !ext.isEmpty()
                                                    && !finalName.endsWith("." + ext)) {
                                                if (!finalName.contains(".")) {
                                                    finalName = finalName + "." + ext;
                                                }
                                            }
                                        }
                                        saveBlobBase64(b64, finalName, dataMime);
                                        return;
                                    }
                                }
                                // Empty data URL → blob was empty or fetch failed.
                                Log.e(TAG, "Blob fetch returned empty result");
                                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                        "Download failed: empty data", Toast.LENGTH_LONG).show());
                            } else if (tries[0] < 15) {
                                h.postDelayed(pollHolder[0], 200);
                            } else {
                                Log.e(TAG, "Blob fetch timed out after " + tries[0] + " polls");
                                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                        "Download failed: timeout", Toast.LENGTH_LONG).show());
                            }
                        });
            }
        };
        pollHolder[0] = poll;
        webView.evaluateJavascript(js, null);
        h.postDelayed(poll, 200);
    }

    /**
     * Decode a base64-encoded blob and save it to the public Download/ folder.
     * Uses MediaStore on Android 10+ and direct file write on older versions.
     */
    private void saveBlobBase64(String b64, String fileName, String mime) {
        new Thread(() -> {
            try {
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentResolver resolver = getContentResolver();
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    Uri itemUri = resolver.insert(collection, values);
                    if (itemUri == null) {
                        Log.e(TAG, "Failed to create download entry for blob");
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Download failed: cannot create entry", Toast.LENGTH_LONG).show());
                        return;
                    }
                    java.io.OutputStream out = resolver.openOutputStream(itemUri);
                    if (out == null) {
                        Log.e(TAG, "Failed to open output stream for blob");
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Download failed: cannot open stream", Toast.LENGTH_LONG).show());
                        return;
                    }
                    out.write(bytes);
                    out.flush();
                    out.close();
                    Log.i(TAG, "Blob saved to Download/" + fileName + " (" + bytes.length + " bytes)");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Saved to Download/" + fileName, Toast.LENGTH_LONG).show());
                } else {
                    File dl = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!dl.exists()) dl.mkdirs();
                    new java.io.FileOutputStream(new File(dl, fileName)).write(bytes);
                    Log.i(TAG, "Blob saved to Download/" + fileName + " (" + bytes.length + " bytes)");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Saved to Download/" + fileName, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "saveBlobBase64 failed", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void downloadWithCookies(String url, String userAgent, String contentDisposition,
                                     String mimetype) {
        final String fileName = guessFilenameFromDownload(url, contentDisposition, mimetype);
        final String cookies = CookieManager.getInstance().getCookie(url);
        final String finalMimetype = mimetype != null ? mimetype : "application/octet-stream";

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERM);
            return;
        }

        saveFileWithCookies(url, userAgent, cookies, fileName, finalMimetype);
    }

    private void saveFileWithCookies(String url, String userAgent, String cookies,
                                     String fileName, String mimetype) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream input = null;
            java.io.OutputStream output = null;
            try {
                URL urlObj = new URL(url);
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", userAgent != null ? userAgent :
                        (desktopMode ? UA_DESKTOP : UA_MOBILE));
                if (cookies != null && !cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookies);
                }
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Referer", "https://chat.z.ai/");

                int responseCode = conn.getResponseCode();
                if (responseCode < 200 || responseCode >= 400) {
                    Log.e(TAG, "Download failed: HTTP " + responseCode);
                    return;
                }

                String realMime = conn.getContentType();
                if (realMime != null && realMime.contains("/")) {
                    realMime = realMime.split(";")[0].trim();
                } else {
                    realMime = mimetype;
                }

                input = conn.getInputStream();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String mimeExtension = android.webkit.MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(realMime);
                    String displayName = fileName;
                    String effectiveMime = realMime;
                    if (mimeExtension != null && !mimeExtension.isEmpty()) {
                        int lastDot = displayName.lastIndexOf('.');
                        if (lastDot > 0) {
                            displayName = displayName.substring(0, lastDot);
                        }
                    } else {
                        effectiveMime = null;
                    }
                    android.content.ContentResolver resolver = getContentResolver();
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName);
                    if (effectiveMime != null) {
                        values.put(android.provider.MediaStore.Downloads.MIME_TYPE, effectiveMime);
                    }
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    Uri itemUri = resolver.insert(collection, values);
                    if (itemUri == null) { Log.e(TAG, "Failed to create download entry"); return; }
                    output = resolver.openOutputStream(itemUri);
                    if (output == null) { Log.e(TAG, "Failed to open output stream"); return; }
                } else {
                    File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    output = new java.io.FileOutputStream(new File(downloadsDir, fileName));
                }

                byte[] buffer = new byte[8192];
                int bytesRead;
                long total = 0;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    total += bytesRead;
                }
                output.flush();
                Log.i(TAG, "Downloaded " + total + " bytes (" + fileName + ")");
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
            } finally {
                if (input != null) try { input.close(); } catch (Exception ignored) {}
                if (output != null) try { output.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private String guessFilenameFromDownload(String url, String contentDisposition, String mimetype) {
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(contentDisposition);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty()) {
                    return sanitizeFilename(name);
                }
            }
        }
        return sanitizeFilename(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype));
    }

    private String sanitizeFilename(String name) {
        name = name.replaceAll("[/\\\\]", "_").trim();
        if (name.length() > 200) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                ext = name.substring(dot);
                name = name.substring(0, 200 - ext.length()) + ext;
            } else {
                name = name.substring(0, 200);
            }
        }
        return name.isEmpty() ? "download" : name;
    }

    private boolean createPopup(Message resultMsg) {
        final WebView popup = new WebView(this);
        popup.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        popup.setBackgroundColor(isDarkMode() ? 0xFF161616 : 0xFFF8F8F8);

        WebSettings ps = popup.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        ps.setDatabaseEnabled(true);
        ps.setSupportMultipleWindows(true);
        ps.setJavaScriptCanOpenWindowsAutomatically(true);
        ps.setUserAgentString(webview.getSettings().getUserAgentString());
        ps.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ps.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
        popup.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        popup.setWebViewClient(new WebViewClient() {
            // ─── New shouldOverrideUrlLoading (API 24+) ───────────────
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return shouldOverrideUrlLoading(v, request.getUrl().toString());
            }

            // ─── Legacy shouldOverrideUrlLoading (used as fallback) ───
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return false;
                // Blob URLs → download via the MAIN WebView's JS context.
                // The blob was created by the main page; this popup (a
                // separate WebView) can't access it.
                if (url.startsWith("blob:")) {
                    Log.i(TAG, "Blob URL intercepted in popup: " + url);
                    downloadBlobUrl(webview, url, null, null);
                    removePopup(popup);
                    return true;
                }
                // tmpfiles.org/dl/ links → show the in-app download dialog
                if (isTmpfilesDirectDownload(url)) {
                    showTmpfilesDownloadDialog(url);
                    removePopup(popup);
                    return true;
                }
                Uri uri;
                try { uri = Uri.parse(url); } catch (Exception e) { return false; }
                String host = uri.getHost();
                if (!isInternalHost(host)) {
                    // External → open in browser. Destroy the popup
                    // IMMEDIATELY (direct call, not post()) so the user
                    // never sees a black screen when returning from
                    // the browser. The popup was never added to the
                    // layout, so there's nothing visible to remove.
                    openUrlInBrowser(url);
                    removePopup(popup);
                    return true;
                }
                // Internal URL (OAuth etc.) → make the popup visible
                // so the user can interact with it.
                if (popup.getParent() == null) {
                    rootLayout.addView(popup);
                    popupViews.add(popup);
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                // Fallback: if shouldOverrideUrlLoading didn't catch a
                // blob URL (possible for non-HTTP schemes), catch it here.
                if (url != null && url.startsWith("blob:")) {
                    Log.i(TAG, "Blob URL in popup onPageStarted: " + url);
                    downloadBlobUrl(webview, url, null, null);
                    removePopup(popup);
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                CookieManager.getInstance().flush();
                injectAllOverrides(v);
                Uri uri = Uri.parse(url);
                String host = uri.getHost();
                if (host != null && host.endsWith("z.ai") && !url.contains("/auth/")) {
                    loadUrlWithHeaders(webview, url);
                    removePopup(popup);
                }
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        popup.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                removePopup(popup);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, "[popup] " + cm.message());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView w,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                if (pendingShareFileUri != null) {
                    filePathCallback.onReceiveValue(new Uri[]{pendingShareFileUri});
                    filePathCallback = null;
                    pendingShareFileUri = null;
                    return true;
                }
                Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentIntent.setType("*/*");
                contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                Intent chooser = Intent.createChooser(contentIntent, "Select file");
                try {
                    startActivityForResult(chooser, REQUEST_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // NOTE: popup is NOT added to the layout here. It's only added
        // (in shouldOverrideUrlLoading) when an internal URL is loaded
        // (e.g. OAuth). For external/blob URLs, the popup is destroyed
        // without ever becoming visible — no black screen.

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popup);
        resultMsg.sendToTarget();
        return true;
    }

    private void removePopup(WebView popup) {
        try {
            rootLayout.removeView(popup);
            popupViews.remove(popup);
            popup.destroy();
        } catch (Exception e) {
            Log.e(TAG, "Error removing popup", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            if (resultCode != RESULT_OK) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
                return;
            }
            Uri[] results = null;
            if (data != null) {
                android.content.ClipData clipData = data.getClipData();
                if (clipData != null) {
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (!popupViews.isEmpty()) {
            WebView top = popupViews.remove(popupViews.size() - 1);
            removePopup(top);
            return;
        }
        if (webview != null && webview.canGoBack()) {
            webview.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Fix for black screen when returning to the app from the background.
        // The WebView's hardware-accelerated surface sometimes doesn't redraw
        // automatically when the activity comes back to the foreground
        // (especially on certain GPU/Android versions). Forcing a redraw
        // here ensures the page is visible immediately.
        if (webview != null) {
            // Resume the WebView (resumes timers + painting)
            webview.onResume();
            // Force a redraw of the WebView surface
            webview.invalidate();
            webview.requestLayout();
            // Force the parent to redraw too — sometimes the WebView itself
            // redraws but the parent FrameLayout has a stale surface.
            if (rootLayout != null) {
                rootLayout.invalidate();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pause the WebView to save battery/CPU when the app goes to the
        // background. The WebView will be resumed in onResume().
        if (webview != null) {
            webview.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        for (WebView popup : new ArrayList<>(popupViews)) {
            removePopup(popup);
        }
        if (loadingLogo != null) {
            loadingLogo.clearAnimation();
        }
        if (webview != null) {
            webview.destroy();
        }
        super.onDestroy();
    }
}
