package com.webglm.app.webview;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.webglm.app.R;
import com.google.android.material.button.MaterialButton;

/**
 * RecyclerView adapter for the "Downloader" tab of {@link WebViewManagerDialog}.
 *
 * One row per {@link WebViewSource}. Each row shows the source name, a short
 * URL string, and a single "Download" button that triggers the source's
 * {@link WebViewSource#onDownload} callback (which opens the releases page
 * in the browser).
 */
public final class WebViewDownloaderAdapter extends RecyclerView.Adapter<WebViewDownloaderAdapter.ViewHolder> {

    private final Context context;
    private final WebViewSource[] values;

    public WebViewDownloaderAdapter(Context context, WebViewSource[] values) {
        this.context = context;
        this.values = values;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_webview_download, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        final WebViewSource src = values[position];
        holder.txtName.setText(src.name);
        holder.txtSource.setText(src.source);
        holder.btnDownload.setOnClickListener(src.onDownload);
        if (src.supported) {
            holder.btnDownload.setEnabled(true);
            holder.btnDownload.setText(context.getString(R.string.download));
        } else {
            holder.btnDownload.setEnabled(false);
            holder.btnDownload.setText(context.getString(R.string.unsupported));
        }
    }

    @Override
    public int getItemCount() {
        return values.length;
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName;
        final TextView txtSource;
        final MaterialButton btnDownload;

        ViewHolder(View v) {
            super(v);
            txtName     = v.findViewById(R.id.txt_webview_name);
            txtSource   = v.findViewById(R.id.txt_webview_source);
            btnDownload = v.findViewById(R.id.btn_download);
        }
    }
}
