package com.webglm.app.webview;

/**
 * Identifier for a downloadable WebView source. Used as the key in the
 * {@code webViewsImplementation} map inside {@link WebViewManagerDialog}.
 *
 * Keep this in sync with the order in which rows appear in the downloader tab.
 */
public enum WebViewImplementation {
    GOOGLE,
    THORIUM,
    MULCH
}
