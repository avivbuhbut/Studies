package com.twofinger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

public class ScreenRecordService extends Service {
    public static final String ACTION_START = "com.twofinger.START_RECORD";
    public static final String ACTION_STOP = "com.twofinger.STOP_RECORD";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "result_data";
    public static volatile boolean isRecording = false;

    private static final int NOTIFICATION_ID = 2201;
    private static final String CHANNEL = "twofinger_recording";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private ParcelFileDescriptor outputFd;
    private Uri outputUri;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && !isRecording) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            startForegroundCompat();
            try {
                startRecording(resultCode, data);
            } catch (Exception e) {
                cleanupFailed();
                Toast.makeText(this, "Could not start recording", Toast.LENGTH_LONG).show();
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, ScreenRecordService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("TwoFinger is recording your screen")
                .setContentText("Tap Stop to finish and save the video")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", pi).build())
                .build();
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void startRecording(int resultCode, Intent data) throws Exception {
        if (data == null) throw new IllegalArgumentException("Missing projection data");

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, "TwoFinger_" + System.currentTimeMillis() + ".mp4");
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TwoFinger");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        outputUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (outputUri == null) throw new IllegalStateException("Cannot create video");
        outputFd = getContentResolver().openFileDescriptor(outputUri, "w");
        if (outputFd == null) throw new IllegalStateException("Cannot open video");

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = getSystemService(WindowManager.class);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int width = Math.max(2, metrics.widthPixels / 2 * 2);
        int height = Math.max(2, metrics.heightPixels / 2 * 2);

        recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoEncodingBitRate(8_000_000);
        recorder.setVideoFrameRate(30);
        recorder.setVideoSize(width, height);
        recorder.setOutputFile(outputFd.getFileDescriptor());
        recorder.prepare();

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                if (isRecording) {
                    stopRecording();
                    stopSelf();
                }
            }
        }, new android.os.Handler(getMainLooper()));

        virtualDisplay = projection.createVirtualDisplay(
                "TwoFingerRecorder",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(), null, null);
        recorder.start();
        isRecording = true;
    }

    private synchronized void stopRecording() {
        if (!isRecording && recorder == null) return;
        isRecording = false;
        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.reset(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        try { if (outputFd != null) outputFd.close(); } catch (Exception ignored) {}
        outputFd = null;

        if (outputUri != null) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(outputUri, cv, null, null);
            outputUri = null;
            Toast.makeText(this, "Recording saved to Movies/TwoFinger", Toast.LENGTH_SHORT).show();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void cleanupFailed() {
        isRecording = false;
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (outputFd != null) outputFd.close(); } catch (Exception ignored) {}
        if (outputUri != null) {
            try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
        }
        recorder = null; virtualDisplay = null; projection = null; outputFd = null; outputUri = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        if (isRecording || recorder != null) stopRecording();
        super.onDestroy();
    }
}
