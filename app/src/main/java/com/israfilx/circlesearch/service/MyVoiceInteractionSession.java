package com.israfilx.circlesearch.service;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.WindowManager;

/**
 * Session yang mendarat setiap kali gesture assist sistem dipicu
 * (swipe sudut bawah / long-press power, tergantung ROM).
 *
 * Alur capture (prioritas, sama seperti AKS-Labs CircleToSearch):
 *  1. Sistem mengirim screenshot lewat {@link #onHandleScreenshot(Bitmap)}
 *     karena supportsAssist=true di interaction_service.xml — TANPA dialog
 *     MediaProjection, TANPA root.
 *  2. Bitmap disimpan ke cache, lalu OverlayCaptureService dijalankan.
 *  3. Session disembunyikan secepat mungkin agar tidak ada flash UI.
 *
 * Fallback (jika onHandleScreenshot tidak dipanggil / bitmap null) ditangani
 * di OverlayCaptureService: root screencap → MediaProjection.
 */
public class MyVoiceInteractionSession extends VoiceInteractionSession {

    private static final String TAG = "CircleSearch/Session";
    private boolean captureStarted = false;

    public MyVoiceInteractionSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(android.os.Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Log.d(TAG, "Assist gesture terdeteksi (showFlags=" + showFlags + ")");

        // Jangan intercept touch ke app di bawah
        try {
            if (getWindow() != null && getWindow().getWindow() != null) {
                getWindow().getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
        } catch (Exception ignored) {
        }

        // Jangan start capture di sini — tunggu onHandleScreenshot.
        // Beberapa ROM memanggil onHandleScreenshot setelah onShow;
        // beri timeout singkat bila screenshot tidak datang.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!captureStarted) {
                Log.w(TAG, "onHandleScreenshot tidak datang — fallback tanpa bitmap sistem");
                startCaptureService(null);
            }
        }, 800);
    }

    /**
     * Dipanggil sistem dengan screenshot layar saat ini.
     * Ini path utama non-root tanpa dialog izin berulang.
     */
    @Override
    public void onHandleScreenshot(Bitmap screenshot) {
        super.onHandleScreenshot(screenshot);
        Log.d(TAG, "onHandleScreenshot: bitmap="
                + (screenshot == null ? "null"
                : screenshot.getWidth() + "x" + screenshot.getHeight()));

        if (captureStarted) {
            return;
        }
        startCaptureService(screenshot);
    }

    private void startCaptureService(Bitmap screenshot) {
        if (captureStarted) return;
        captureStarted = true;

        Context ctx = getContext();
        Intent serviceIntent = new Intent(ctx, OverlayCaptureService.class);
        serviceIntent.setAction(OverlayCaptureService.ACTION_START_CAPTURE);

        if (screenshot != null && !screenshot.isRecycled()) {
            // Simpan ke cache internal; service akan memuat ulang
            String path = ctx.getCacheDir().getAbsolutePath() + "/assist_screenshot.png";
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(path);
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                serviceIntent.putExtra(OverlayCaptureService.EXTRA_ASSIST_SCREENSHOT_PATH, path);
                Log.d(TAG, "Screenshot assist disimpan ke " + path);
            } catch (Exception e) {
                Log.e(TAG, "Gagal menyimpan screenshot assist", e);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(serviceIntent);
        } else {
            ctx.startService(serviceIntent);
        }

        hide();
    }

    @Override
    public void onHide() {
        super.onHide();
        Log.d(TAG, "Session disembunyikan");
    }
}
