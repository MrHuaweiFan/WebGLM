package com.webglm.app.webview;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.webglm.app.App;
import com.webglm.app.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RecyclerView adapter for the "Installed" tab of {@link WebViewManagerDialog}.
 *
 * One row per installed WebView provider (including the default). Each row
 * shows the provider's app icon, name, package name, version, and a radio
 * button. Tapping anywhere on the row selects it (writes the package name
 * to SharedPreferences under {@link WebViewUtil#PREF_WEBVIEW_IMPL}) and
 * re-renders all rows so the new selection's radio button is checked.
 */
public final class WebViewInstalledAdapter extends RecyclerView.Adapter<WebViewInstalledAdapter.ViewHolder> {

    private final Context context;
    private final RecyclerView recyclerView;
    private final View emptyView;
    private final List<CharSequence> entries = new ArrayList<>();
    private final List<PackageInfo> values = new ArrayList<>();
    private final SharedPreferences prefs;
    private PackageInfo selectedWebView;

    public WebViewInstalledAdapter(Context context, RecyclerView recyclerView, View emptyView) {
        this.context = context;
        this.recyclerView = recyclerView;
        this.emptyView = emptyView;
        this.prefs = context.getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE);
        reloadData();
    }

    public PackageInfo getSelectedWebView() {
        return selectedWebView;
    }

    /** Rescan installed providers + restore the saved selection. */
    public void reloadData() {
        WebViewUtil.reloadProviderList(context);
        entries.clear();
        values.clear();
        for (Map.Entry<String, PackageInfo> e : WebViewUtil.getAvailableProviders().entrySet()) {
            entries.add(WebViewUtil.toString(context, e.getValue()));
            values.add(e.getValue());
        }
        String saved = prefs.getString(WebViewUtil.PREF_WEBVIEW_IMPL,
                WebViewUtil.getDefaultProvider() != null
                        ? WebViewUtil.getDefaultProvider().packageName
                        : "");
        selectedWebView = null;
        for (PackageInfo pi : values) {
            if (pi.packageName.equals(saved)) {
                selectedWebView = pi;
                break;
            }
        }
        if (selectedWebView == null && !values.isEmpty()) {
            selectedWebView = values.get(0);
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_webview_installed, parent, false);
        final ViewHolder vh = new ViewHolder(v);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int pos = vh.getAdapterPosition();
                if (pos < 0 || pos >= values.size()) return;
                PackageInfo pi = values.get(pos);
                selectedWebView = pi;
                prefs.edit()
                        .putString(WebViewUtil.PREF_WEBVIEW_IMPL, pi.packageName)
                        .commit();
                notifyItemRangeChanged(0, values.size());
            }
        });
        // The RadioButton itself isn't clickable — the whole row is the touch target.
        vh.rdoCheck.setClickable(false);
        return vh;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        PackageInfo pi = values.get(position);
        PackageManager pm = context.getPackageManager();
        try {
            holder.txtName.setText(pm.getApplicationLabel(pi.applicationInfo));
        } catch (Throwable t) {
            holder.txtName.setText(pi.packageName);
        }
        holder.txtPackage.setText(pi.packageName);
        holder.txtVersion.setText(pi.versionName + " (" + pi.versionCode + ")");
        try {
            holder.imgIcon.setImageDrawable(pm.getApplicationIcon(pi.applicationInfo));
        } catch (Throwable t) {
            holder.imgIcon.setImageDrawable(null);
        }
        holder.rdoCheck.setChecked(selectedWebView != null
                && selectedWebView.packageName.equals(pi.packageName));
    }

    @Override
    public int getItemCount() {
        int n = values.size();
        emptyView.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        return n;
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final TextView txtName;
        final TextView txtPackage;
        final TextView txtVersion;
        final ImageView imgIcon;
        final RadioButton rdoCheck;

        ViewHolder(View v) {
            super(v);
            txtName    = v.findViewById(R.id.txt_webview_name);
            txtPackage = v.findViewById(R.id.txt_webview_package);
            txtVersion = v.findViewById(R.id.txt_webview_version);
            imgIcon    = v.findViewById(R.id.img_icon);
            rdoCheck   = v.findViewById(R.id.rdo_check);
        }
    }
}
