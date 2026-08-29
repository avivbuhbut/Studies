package com.twofinger;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

public class ScreenCaptureActivity extends Activity {
    private static final int REQUEST_CAPTURE = 9101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ScreenRecordService.isRecording) {
            Intent stop = new Intent(this, ScreenRecordService.class).setAction(ScreenRecordService.ACTION_STOP);
            startService(stop);
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenRecordService.class)
                    .setAction(ScreenRecordService.ACTION_START)
                    .putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenRecordService.EXTRA_DATA, data);
            startForegroundService(service);
            Toast.makeText(this, "Recording started — open TwoFinger again to stop", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
