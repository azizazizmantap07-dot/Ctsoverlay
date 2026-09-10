package com.israfilx.circlesearch.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Utilitas capture satu frame layar via MediaProjection (jalur non-root).
 *
 * Alur singkat:
 *  1. Buat MediaProjection dari resultCode + data Intent (hasil dialog sistem)
 *  2. Buat ImageReader + VirtualDisplay seukuran layar penuh
 *  3. Tunggu satu frame masuk
 *  4. Konversi Image → Bitmap ARGB_8888
 *  5. Bersihkan semua resource (VirtualDisplay, ImageReader, MediaProjection)
 *
 * Dipanggil dari background thread. Timeout default 3 detik.
 */
public final class ScreenCaptureUtil {

    private static final String TAG = "CircleSearch/ScreenCap";
    private static final int TIMEOUT_MS = 3000;

    private ScreenCaptureUtil() {}

    /**
     * Capture layar penuh menggunakan MediaProjection yang sudah diizinkan user.
     *
     * @param context     context aplikasi / service
     * @param resultCode  resultCode dari Activity.RESULT_OK
     * @param data        Intent data dari onActivityResult MediaProjection
     * @return Bitmap hasil capture, atau null bila gagal / timeout
     */
    public static Bitmap capture(Context context, int resultCode, Intent data) {
        if (data == null) {
            Log.e(TAG, "Intent data MediaProjection null");
            return null;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "MediaProjectionManager null");
            return null;
        }

        MediaProjection projection;
        try {
            projection = mpm.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            Log.e(TAG, "getMediaProjection gagal", e);
            return null;
        }
        if (projection == null) {
            Log.e(TAG, "MediaProjection null setelah getMediaProjection");
            return null;
        }

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics = context.getResources().getDisplayMetrics();
        }

        final int width = metrics.widthPixels;
        final int height = metrics.heightPixels;
        final int density = metrics.densityDpi;

        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Ukuran layar tidak valid: " + width + "x" + height);
            projection.stop();
            return null;
        }

        HandlerThread handlerThread = new HandlerThread("CircleSearch-MediaProjection");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());

        ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        final Object lock = new Object();
        final Bitmap[] resultHolder = new Bitmap[1];
        final boolean[] done = {false};

        // Callback wajib di Android 14+ sebelum createVirtualDisplay
        MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection dihentikan");
            }
        };
        projection.registerCallback(projectionCallback, handler);

        VirtualDisplay virtualDisplay = null;
        try {
            virtualDisplay = projection.createVirtualDisplay(
                    "CircleSearchCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler
            );

            if (virtualDisplay == null) {
                Log.e(TAG, "createVirtualDisplay mengembalikan null");
                return null;
            }

            imageReader.setOnImageAvailableListener(reader -> {
                synchronized (lock) {
                    if (done[0]) return;
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            resultHolder[0] = imageToBitmap(image);
                            done[0] = true;
                            lock.notifyAll();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Gagal membaca Image dari ImageReader", e);
                        done[0] = true;
                        lock.notifyAll();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
            }, handler);

            // Tunggu frame masuk atau timeout
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + TIMEOUT_MS;
                while (!done[0]) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try {
                        lock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!done[0]) {
                Log.e(TAG, "Timeout menunggu frame MediaProjection (" + TIMEOUT_MS + " ms)");
            }

            return resultHolder[0];

        } catch (Exception e) {
            Log.e(TAG, "Capture MediaProjection gagal", e);
            return null;
        } finally {
            try {
                if (virtualDisplay != null) virtualDisplay.release();
            } catch (Exception ignored) {}
            try {
                imageReader.close();
            } catch (Exception ignored) {}
            try {
                projection.unregisterCallback(projectionCallback);
            } catch (Exception ignored) {}
            try {
                projection.stop();
            } catch (Exception ignored) {}
            try {
                handlerThread.quitSafely();
            } catch (Exception ignored) {}
        }
    }

    private static Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;

        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap bitmap;
        if (rowPadding == 0) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
        } else {
            // Ada padding per baris — salin baris demi baris
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            byte[] row = new byte[rowStride];
            int[] pixels = new int[width];
            for (int y = 0; y < height; y++) {
                buffer.position(y * rowStride);
                buffer.get(row, 0, rowStride);
                for (int x = 0; x < width; x++) {
                    int offset = x * pixelStride;
                    int r = row[offset] & 0xff;
                    int g = row[offset + 1] & 0xff;
                    int b = row[offset + 2] & 0xff;
                    int a = pixelStride == 4 ? (row[offset + 3] & 0xff) : 255;
                    pixels[x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                bitmap.setPixels(pixels, 0, width, 0, y, width, 1);
            }
        }
        return bitmap;
    }
}
