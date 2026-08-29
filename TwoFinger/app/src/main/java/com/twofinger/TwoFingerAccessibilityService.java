package com.twofinger;

import android.Manifest;
import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.TouchInteractionController;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.HardwareBuffer;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.accessibility.AccessibilityEvent;

import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TwoFingerAccessibilityService extends AccessibilityService {
    public static TwoFingerAccessibilityService instance;

    private WindowManager windowManager;
    private LinearLayout menuView;
    private TouchInteractionController touchController;
    private TouchInteractionController.Callback touchCallback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Runnable delegateRunnable;
    private Runnable holdRunnable;
    private boolean candidate = false;
    private boolean triggered = false;
    private float firstX0, firstY0, firstX1, firstY1;
    private int activePointers = 0;
    private boolean torchOn = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 33) setupCustomHoldDetector();
        Toast.makeText(this, "TwoFinger is active", Toast.LENGTH_SHORT).show();
    }

    private void setupCustomHoldDetector() {
        touchController = getTouchInteractionController(Display.DEFAULT_DISPLAY);
        touchCallback = new TouchInteractionController.Callback() {
            @Override public void onMotionEvent(MotionEvent event) { handleGlobalTouch(event); }
            @Override public void onStateChanged(int state) {
                if (state == TouchInteractionController.STATE_CLEAR) resetGesture();
            }
        };
        touchController.registerCallback(getMainExecutor(), touchCallback);
    }

    private void handleGlobalTouch(MotionEvent e) {
        final int action = e.getActionMasked();
        activePointers = e.getPointerCount();

        if (action == MotionEvent.ACTION_DOWN) {
            resetGesture();
            activePointers = 1;
            firstX0 = e.getX(0);
            firstY0 = e.getY(0);
            delegateRunnable = () -> {
                if (!candidate && touchController != null) safeDelegate();
            };
            main.postDelayed(delegateRunnable, 150);
            return;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
            if (delegateRunnable != null) main.removeCallbacks(delegateRunnable);
            candidate = true;
            triggered = false;
            firstX0 = e.getX(0); firstY0 = e.getY(0);
            firstX1 = e.getX(1); firstY1 = e.getY(1);
            int holdMs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt("hold_ms", 600);
            holdRunnable = () -> {
                if (candidate && activePointers >= 2 && !triggered) {
                    triggered = true;
                    candidate = false;
                    showQuickMenu();
                    try { touchController.requestTouchExploration(); } catch (Exception ignored) {}
                }
            };
            main.postDelayed(holdRunnable, holdMs);
            return;
        }

        if (action == MotionEvent.ACTION_MOVE && candidate && e.getPointerCount() >= 2) {
            float slop = dp(26);
            boolean moved = distance(e.getX(0), e.getY(0), firstX0, firstY0) > slop ||
                    distance(e.getX(1), e.getY(1), firstX1, firstY1) > slop;
            if (moved) {
                cancelHold();
                candidate = false;
                safeDelegate();
            }
            return;
        }

        if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!triggered) {
                cancelHold();
                candidate = false;
                safeDelegate();
            }
        }
    }

    private float distance(float x, float y, float x0, float y0) {
        float dx = x - x0, dy = y - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void safeDelegate() {
        try { if (touchController != null) touchController.requestDelegating(); } catch (Exception ignored) {}
    }

    private void cancelHold() {
        if (holdRunnable != null) main.removeCallbacks(holdRunnable);
        holdRunnable = null;
    }

    private void resetGesture() {
        if (delegateRunnable != null) main.removeCallbacks(delegateRunnable);
        cancelHold();
        candidate = false;
        triggered = false;
        activePointers = 0;
    }

    @Override
    public boolean onGesture(AccessibilityGestureEvent gestureEvent) {
        if (Build.VERSION.SDK_INT < 33 && gestureEvent.getGestureId() == GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD) {
            showQuickMenu();
            return true;
        }
        return super.onGesture(gestureEvent);
    }

    public void showQuickMenu() {
        main.post(() -> {
            hideQuickMenu();
            SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

            LinearLayout shell = new LinearLayout(this);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setPadding(dp(14), dp(13), dp(14), dp(14));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.argb(248, 28, 28, 32));
            bg.setCornerRadius(dp(24));
            shell.setBackground(bg);

            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(this);
            title.setText("TwoFinger");
            title.setTextColor(Color.WHITE);
            title.setTextSize(17);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button close = smallButton("✕");
            close.setOnClickListener(v -> hideQuickMenu());
            top.addView(close, new LinearLayout.LayoutParams(dp(54), dp(48)));
            shell.addView(top);

            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(3);
            grid.setPadding(0, dp(7), 0, 0);
            addBuiltins(grid, prefs);
            addSelectedApps(grid, prefs);
            shell.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Button edit = smallButton("⚙  Edit menu");
            edit.setOnClickListener(v -> {
                hideQuickMenu();
                Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            });
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            ep.topMargin = dp(8);
            shell.addView(edit, ep);

            menuView = shell;
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.BOTTOM;
            p.x = 0;
            p.y = dp(18);
            windowManager.addView(shell, p);
        });
    }

    private void addBuiltins(GridLayout grid, SharedPreferences prefs) {
        if (prefs.getBoolean("action_record", true)) addAction(grid, "⏺\nRecord", this::startRecordingFlow);
        if (prefs.getBoolean("action_screenshot", true)) addAction(grid, "▣\nScreenshot", this::captureScreenshot);
        if (prefs.getBoolean("action_back", true)) addAction(grid, "‹\nBack", () -> performGlobalAction(GLOBAL_ACTION_BACK));
        if (prefs.getBoolean("action_home", true)) addAction(grid, "⌂\nHome", () -> performGlobalAction(GLOBAL_ACTION_HOME));
        if (prefs.getBoolean("action_recents", true)) addAction(grid, "▤\nRecents", () -> performGlobalAction(GLOBAL_ACTION_RECENTS));
        if (prefs.getBoolean("action_notifications", false)) addAction(grid, "●\nNotifications", () -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS));
        if (prefs.getBoolean("action_quicksettings", false)) addAction(grid, "⚙\nQuick settings", () -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS));
        if (prefs.getBoolean("action_torch", false)) addAction(grid, "✦\nFlashlight", this::toggleTorch);
    }

    private void addSelectedApps(GridLayout grid, SharedPreferences prefs) {
        Set<String> selected = prefs.getStringSet("selected_apps", Collections.emptySet());
        PackageManager pm = getPackageManager();
        int count = 0;
        for (String pkg : new HashSet<>(selected)) {
            if (count >= 6) break;
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            String label = pkg;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
            final String target = pkg;
            String shortLabel = label.length() > 12 ? label.substring(0, 12) + "…" : label;
            addAction(grid, "◉\n" + shortLabel, () -> launchPackage(target));
            count++;
        }
    }

    private void addAction(GridLayout grid, String label, Runnable action) {
        Button b = smallButton(label);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> {
            hideQuickMenu();
            main.postDelayed(action, 70);
        });
        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = 0;
        gp.height = dp(78);
        gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        gp.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(b, gp);
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setBackgroundColor(Color.rgb(55, 55, 61));
        b.setPadding(dp(5), dp(3), dp(5), dp(3));
        return b;
    }

    private void hideQuickMenu() {
        if (menuView != null && windowManager != null) {
            try { windowManager.removeView(menuView); } catch (Exception ignored) {}
            menuView = null;
        }
    }

    private void startRecordingFlow() {
        Intent i = new Intent(this, ScreenCaptureActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    private void launchPackage(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } else toast("App is no longer available");
    }

    private void captureScreenshot() {
        takeScreenshot(Display.DEFAULT_DISPLAY, io, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult screenshot) {
                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (hw == null) throw new IllegalStateException("Unable to create bitmap");
                    Bitmap bitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
                    saveScreenshot(bitmap);
                    bitmap.recycle();
                    toast("Screenshot saved");
                } catch (Exception e) {
                    toast("Screenshot failed");
                } finally {
                    try { buffer.close(); } catch (Exception ignored) {}
                }
            }
            @Override public void onFailure(int errorCode) { toast("Screenshot failed (" + errorCode + ")"); }
        });
    }

    private void saveScreenshot(Bitmap bitmap) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, "TwoFinger_" + System.currentTimeMillis() + ".png");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TwoFinger");
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IllegalStateException("Cannot create image");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Cannot save image");
        }
        cv.clear();
        cv.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, cv, null, null);
    }

    private void toggleTorch() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("Open TwoFinger and allow Camera for flashlight");
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String target = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { target = id; break; }
            }
            if (target == null) { toast("No flashlight found"); return; }
            torchOn = !torchOn;
            cm.setTorchMode(target, torchOn);
        } catch (Exception e) { toast("Flashlight unavailable"); }
    }

    private void toast(String s) { main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        hideQuickMenu();
        if (Build.VERSION.SDK_INT >= 33 && touchController != null && touchCallback != null) {
            try { touchController.unregisterCallback(touchCallback); } catch (Exception ignored) {}
        }
        io.shutdownNow();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
