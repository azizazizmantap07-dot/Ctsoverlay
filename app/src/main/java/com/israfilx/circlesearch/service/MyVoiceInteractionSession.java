package com.israfilx.circlesearch.service;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.WindowManager;

import java.io.FileOutputStream;

/**
 * Session yang mendarat setiap kali gesture assist sistem dipicu.
 *
 * Path utama non-root: sistem mengirim screenshot lewat
 * {@link #onHandleScreenshot(Bitmap)} (supportsAssist=true).
 *
 * Catatan: beberapa ROM mereuse instance session untuk gesture berikutnya.
 * Karena itu state "sudah diproses" di-reset setiap onShow, bukan sekali
 * seumur hidup session.
 */
public class MyVoiceInteractionSession extends VoiceInteractionSession {

    private static final String TAG = "CircleSearch/Session";
    private static final long FALLBACK_TIMEOUT_MS = 1500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean handledThisShow = false;
    private final Runnable fallbackRunnable = () -> {
        if (!handledThisShow) {
            Log.w(TAG, "onHandleScreenshot tidak datang — fallback tanpa bitmap sistem");
            startCaptureService(null);
        }
    };

    public MyVoiceInteractionSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(android.os.Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Log.d(TAG, "Assist gesture terdeteksi (showFlags=" + showFlags + ")");

        // Reset per-show: ROM yang reuse session tetap bisa trigger ulang
        handledThisShow = false;
        mainHandler.removeCallbacks(fallbackRunnable);

        try {
            if (getWindow() != null && getWindow().getWindow() != null) {
                getWindow().getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
        } catch (Exception ignored) {
        }

        mainHandler.postDelayed(fallbackRunnable, FALLBACK_TIMEOUT_MS);
    }

    @Override
    public void onHandleScreenshot(Bitmap screenshot) {
        super.onHandleScreenshot(screenshot);
        boolean valid = screenshot != null && !screenshot.isRecycled();
        Log.d(TAG, "onHandleScreenshot: bitmap="
                + (screenshot == null ? "null"
                : (screenshot.isRecycled() ? "RECYCLED"
                : screenshot.getWidth() + "x" + screenshot.getHeight())));

        if (handledThisShow) {
            Log.d(TAG, "Screenshot ekstra diabaikan (sudah diproses untuk show ini)");
            return;
        }
        startCaptureService(valid ? screenshot : null);
    }

    private void startCaptureService(Bitmap screenshot) {
        if (handledThisShow) {
            return;
        }
        handledThisShow = true;
        mainHandler.removeCallbacks(fallbackRunnable);

        Context ctx = getContext();
        Intent serviceIntent = new Intent(ctx, OverlayCaptureService.class);
        serviceIntent.setAction(OverlayCaptureService.ACTION_START_CAPTURE);

        if (screenshot != null) {
            // Salin ke software bitmap — bitmap sistem kadang HARDWARE
            // dan bisa di-recycle framework segera setelah callback.
            Bitmap copy = null;
            try {
                copy = screenshot.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Exception e) {
                Log.e(TAG, "Gagal copy bitmap assist", e);
            }

            if (copy != null) {
                String path = ctx.getCacheDir().getAbsolutePath() + "/assist_screenshot.png";
                try (FileOutputStream fos = new FileOutputStream(path)) {
                    copy.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    serviceIntent.putExtra(
                            OverlayCaptureService.EXTRA_ASSIST_SCREENSHOT_PATH, path);
                    Log.d(TAG, "Screenshot assist disimpan ke " + path);
                } catch (Exception e) {
                    Log.e(TAG, "Gagal menyimpan screenshot assist", e);
                } finally {
                    if (!copy.isRecycled()) {
                        copy.recycle();
                    }
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(serviceIntent);
            } else {
                ctx.startService(serviceIntent);
            }
            Log.d(TAG, "OverlayCaptureService di-start");
        } catch (Exception e) {
            Log.e(TAG, "Gagal start OverlayCaptureService", e);
        }

        try {
            hide();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onHide() {
        super.onHide();
        mainHandler.removeCallbacks(fallbackRunnable);
        Log.d(TAG, "Session disembunyikan");
    }
}
