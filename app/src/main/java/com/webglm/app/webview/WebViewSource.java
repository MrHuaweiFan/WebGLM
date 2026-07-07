package com.webglm.app.webview;

import android.view.View;

/**
 * Description of one downloadable WebView source, shown as a row in the
 * downloader tab of {@link WebViewManagerDialog}.
 */
public final class WebViewSource {

    public final WebViewImplementation id;
    public final String name;       // e.g. "Thorium WebView"
    public final String source;     // short display URL, e.g. "github.com/Alex313031/Thorium-Android"
    public final View.OnClickListener onDownload;
    public final boolean supported; // false → button shows "Unsupported" and is disabled

    public WebViewSource(WebViewImplementation id, String name, String source,
                         View.OnClickListener onDownload, boolean supported) {
        this.id = id;
        this.name = name;
        this.source = source;
        this.onDownload = onDownload;
        this.supported = supported;
    }
}
