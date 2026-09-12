package com.israfilx.circlesearch.util;

import android.graphics.Bitmap;
import android.graphics.RectF;

/**
 * Crop bitmap ke persegi panjang (bounding box) sesuai area seleksi.
 */
public final class BitmapCropUtil {

    private BitmapCropUtil() {}

    /** Crop persegi murni sesuai bounds — dipakai untuk semua aksi (cari, OCR, translate). */
    public static Bitmap cropToRect(Bitmap source, RectF bounds) {
        int left = Math.max(0, (int) bounds.left);
        int top = Math.max(0, (int) bounds.top);
        int right = Math.min(source.getWidth(), (int) bounds.right);
        int bottom = Math.min(source.getHeight(), (int) bounds.bottom);

        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) return null;

        return Bitmap.createBitmap(source, left, top, width, height);
    }
}
