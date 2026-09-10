package com.israfilx.circlesearch.service;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AccessibilityService minimal — hanya untuk
 * {@link AccessibilityService#takeScreenshot} di Android 11+
 * saat trigger dari floating button (perangkat tanpa Default Assistant).
 *
 * Tidak membaca konten app lain; tidak memonitor event.
 * Pola sama seperti AKS-Labs CircleToSearch (path accessibility).
 */
public class ScreenshotAccessibilityService extends AccessibilityService {

    private static final String TAG = "CircleSearch/A11y";
    private static ScreenshotAccessibilityService instance;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public interface ScreenshotCallback {
        void onSuccess(Bitmap bitmap);
        void onFailure(String reason);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "ScreenshotAccessibilityService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Tidak dipakai
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * Ambil screenshot layar. Callback dipanggil di thread background
     * (bitmap sudah di-copy ke ARGB_8888 software).
     */
    public static void takeScreenshot(ScreenshotCallback callback) {
        ScreenshotAccessibilityService svc = instance;
        if (svc == null) {
            callback.onFailure("Accessibility service tidak aktif");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.onFailure("takeScreenshot butuh Android 11+");
            return;
        }
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.executor,
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(
                                    result.getHardwareBuffer(), result.getColorSpace());
                            if (hw == null) {
                                result.getHardwareBuffer().close();
                                callback.onFailure("wrapHardwareBuffer null");
                                return;
                            }
                            Bitmap copy = hw.copy(Bitmap.Config.ARGB_8888, false);
                            result.getHardwareBuffer().close();
                            if (copy == null) {
                                callback.onFailure("copy bitmap gagal");
                                return;
                            }
                            callback.onSuccess(copy);
                        } catch (Exception e) {
                            Log.e(TAG, "Screenshot process error", e);
                            callback.onFailure(e.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.e(TAG, "takeScreenshot gagal code=" + errorCode);
                        callback.onFailure("errorCode=" + errorCode);
                    }
                });
    }
}
