package com.twofinger;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    public static final String PREFS = "twofinger_prefs";
    private SharedPreferences prefs;
    private TextView serviceStatus;
    private TextView holdLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 30);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(30));
        root.setBackgroundColor(Color.rgb(246, 246, 248));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("TwoFinger", 30, true);
        root.addView(title);
        TextView subtitle = text("Hold 2 fingers anywhere → your quick menu.", 16, false);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(5), 0, dp(20));
        root.addView(subtitle);

        serviceStatus = text("", 16, true);
        serviceStatus.setPadding(dp(14), dp(13), dp(14), dp(13));
        serviceStatus.setBackground(roundRect(Color.WHITE, 18));
        root.addView(serviceStatus, matchWrap());

        Button enable = button("1. Enable TwoFinger accessibility");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(enable, topMargin(14));

        Button pickApps = button("2. Choose apps in the menu");
        pickApps.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        root.addView(pickApps, topMargin(10));

        Button test = button("Test quick menu now");
        test.setOnClickListener(v -> {
            if (TwoFingerAccessibilityService.instance != null) {
                TwoFingerAccessibilityService.instance.showQuickMenu();
            } else {
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(test, topMargin(10));

        TextView builtins = text("Built-in actions", 19, true);
        builtins.setPadding(0, dp(24), 0, dp(8));
        root.addView(builtins);

        addToggle(root, "Screen recording", "action_record", true);
        addToggle(root, "Screenshot", "action_screenshot", true);
        addToggle(root, "Back", "action_back", true);
        addToggle(root, "Home", "action_home", true);
        addToggle(root, "Recent apps", "action_recents", true);
        addToggle(root, "Notifications", "action_notifications", false);
        addToggle(root, "Quick Settings", "action_quicksettings", false);
        addToggle(root, "Flashlight", "action_torch", false);

        TextView gesture = text("Gesture sensitivity", 19, true);
        gesture.setPadding(0, dp(24), 0, dp(3));
        root.addView(gesture);
        holdLabel = text("", 14, false);
        holdLabel.setTextColor(Color.DKGRAY);
        root.addView(holdLabel);

        SeekBar hold = new SeekBar(this);
        hold.setMax(500);
        int current = prefs.getInt("hold_ms", 600);
        hold.setProgress(current - 400);
        updateHoldLabel(current);
        hold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 400 + progress;
                updateHoldLabel(value);
                if (fromUser) prefs.edit().putInt("hold_ms", value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(hold, matchWrap());

        TextView note = text("Android 13+: TwoFinger uses a custom global two-finger hold detector. Android 12: it falls back to Android's two-finger double-tap-and-hold accessibility gesture. Screen recording always uses Android's official confirmation screen.", 13, false);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void addToggle(LinearLayout root, String label, String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(16);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setPadding(dp(4), dp(5), dp(4), dp(5));
        cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
            if ("action_torch".equals(key) && isChecked && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 31);
            }
        });
        root.addView(cb, matchWrap());
    }

    private void updateHoldLabel(int ms) {
        holdLabel.setText("Hold for " + ms + " ms");
    }

    private void updateServiceStatus() {
        boolean enabled = false;
        AccessibilityManager manager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String idNeedle = getPackageName() + "/" + TwoFingerAccessibilityService.class.getName();
        for (AccessibilityServiceInfo info : services) {
            if (info.getId() != null && (info.getId().equals(idNeedle) || info.getId().contains(getPackageName()))) {
                enabled = true;
                break;
            }
        }
        serviceStatus.setText(enabled ? "✓ TwoFinger is ON" : "○ TwoFinger is OFF — enable it below");
        serviceStatus.setTextColor(enabled ? Color.rgb(23, 115, 65) : Color.rgb(150, 70, 30));
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(28, 28, 31));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinHeight(dp(54));
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(marginDp);
        return p;
    }

    private android.graphics.drawable.GradientDrawable roundRect(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
