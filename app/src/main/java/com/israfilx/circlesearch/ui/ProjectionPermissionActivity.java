package com.israfilx.circlesearch.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.israfilx.circlesearch.service.OverlayCaptureService;

/**
 * Activity transparan yang menampilkan dialog izin MediaProjection
 * (Screen Capture) untuk perangkat non-root.
 *
 * Alur:
 *  1. OverlayCaptureService mendeteksi tidak ada root → start activity ini
 *  2. Activity meminta izin lewat createScreenCaptureIntent()
 *  3. User mengizinkan → hasil (resultCode + data) dikirim kembali ke
 *     OverlayCaptureService via Intent extra
 *  4. Service menangkap satu frame layar lalu menampilkan overlay seleksi
 *
 * Activity ini tidak menampilkan UI sendiri selain dialog sistem.
 */
public class ProjectionPermissionActivity extends Activity {

    private static final String TAG = "CircleSearch/ProjPerm";
    private static final int REQUEST_MEDIA_PROJECTION = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Meminta izin MediaProjection untuk capture non-root");

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "MediaProjectionManager tidak tersedia");
            finish();
            return;
        }

        Intent captureIntent = mpm.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            finish();
            return;
        }

        if (resultCode == RESULT_OK && data != null) {
            Log.d(TAG, "Izin MediaProjection diberikan — meneruskan ke service");
            Intent serviceIntent = new Intent(this, OverlayCaptureService.class);
            serviceIntent.setAction(OverlayCaptureService.ACTION_START_CAPTURE);
            serviceIntent.putExtra(OverlayCaptureService.EXTRA_PROJECTION_RESULT_CODE, resultCode);
            serviceIntent.putExtra(OverlayCaptureService.EXTRA_PROJECTION_DATA, data);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } else {
            Log.w(TAG, "Izin MediaProjection ditolak atau dibatalkan oleh user");
        }

        finish();
        overridePendingTransition(0, 0);
    }
}
