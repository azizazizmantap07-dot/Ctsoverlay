package com.israfilx.circlesearch.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.israfilx.circlesearch.service.OverlayCaptureService;

/**
 * Activity transparan untuk meminta izin MediaProjection (screen capture)
 * dari sistem. Dipakai sebagai jalur non-root ketika RootShell tidak
 * tersedia.
 *
 * Alur:
 *  1. OverlayCaptureService mendeteksi root tidak ada → start activity ini
 *  2. Activity menampilkan dialog sistem "Mulai merekam/menayangkan?"
 *  3. Hasil (resultCode + data Intent) dikirim kembali ke service lewat
 *     ACTION_PROJECTION_RESULT
 *  4. Activity langsung finish() — tidak menampilkan UI sendiri
 *
 * Theme harus transparan + noHistory agar tidak mengganggu alur assist.
 */
public class ProjectionRequestActivity extends Activity {

    private static final String TAG = "CircleSearch/ProjReq";
    private static final int REQUEST_MEDIA_PROJECTION = 1001;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Meminta izin MediaProjection…");

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "MediaProjectionManager tidak tersedia");
            finishWithFailure();
            return;
        }

        Intent captureIntent = mpm.createScreenCaptureIntent();
        try {
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menampilkan dialog MediaProjection", e);
            finishWithFailure();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            finish();
            return;
        }

        Intent serviceIntent = new Intent(this, OverlayCaptureService.class);
        serviceIntent.setAction(OverlayCaptureService.ACTION_PROJECTION_RESULT);
        serviceIntent.putExtra(EXTRA_RESULT_CODE, resultCode);

        if (resultCode == RESULT_OK && data != null) {
            Log.d(TAG, "MediaProjection diizinkan oleh pengguna");
            serviceIntent.putExtra(EXTRA_RESULT_DATA, data);
        } else {
            Log.w(TAG, "MediaProjection ditolak atau dibatalkan oleh pengguna");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        finish();
        overridePendingTransition(0, 0);
    }

    private void finishWithFailure() {
        Intent serviceIntent = new Intent(this, OverlayCaptureService.class);
        serviceIntent.setAction(OverlayCaptureService.ACTION_PROJECTION_RESULT);
        serviceIntent.putExtra(EXTRA_RESULT_CODE, RESULT_CANCELED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
