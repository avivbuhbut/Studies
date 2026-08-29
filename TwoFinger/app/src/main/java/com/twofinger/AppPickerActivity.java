package com.twofinger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppPickerActivity extends Activity {
    private final List<AppItem> apps = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        selected.addAll(prefs.getStringSet("selected_apps", Collections.emptySet()));
        loadApps();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 246, 248));

        TextView header = new TextView(this);
        header.setText("Choose apps\nTap any app to add/remove it");
        header.setTextSize(21);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setTextColor(Color.rgb(25,25,28));
        header.setPadding(dp(20), dp(20), dp(20), dp(14));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        list.setDividerHeight(1);
        list.setAdapter(new AppsAdapter());
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(intent, 0);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new AppItem(pkg, r.loadLabel(pm).toString(), r));
        }
        apps.sort(Comparator.comparing(a -> a.label.toLowerCase()));
    }

    private void toggle(String pkg, boolean on) {
        if (on) selected.add(pkg); else selected.remove(pkg);
        prefs.edit().putStringSet("selected_apps", new HashSet<>(selected)).apply();
    }

    private class AppsAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppItem item = apps.get(position);
            LinearLayout row = new LinearLayout(AppPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(15), dp(9), dp(15), dp(9));

            ImageView icon = new ImageView(AppPickerActivity.this);
            icon.setImageDrawable(item.resolve.loadIcon(getPackageManager()));
            row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

            TextView label = new TextView(AppPickerActivity.this);
            label.setText(item.label);
            label.setTextSize(16);
            label.setTextColor(Color.rgb(30,30,33));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = dp(13);
            row.addView(label, lp);

            CheckBox cb = new CheckBox(AppPickerActivity.this);
            cb.setChecked(selected.contains(item.pkg));
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> toggle(item.pkg, isChecked));
            row.addView(cb);
            row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            return row;
        }
    }

    private static class AppItem {
        final String pkg;
        final String label;
        final ResolveInfo resolve;
        AppItem(String pkg, String label, ResolveInfo resolve) {
            this.pkg = pkg; this.label = label; this.resolve = resolve;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
