package com.israfilx.circlesearch.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

/**
 * Crop bitmap mengikuti bentuk path lasso freeform (bukan sekadar
 * bounding box persegi) — area di luar coretan dibuat transparan,
 * lalu dipotong pas ke bounding box supaya ukuran file hasil minimal.
 */
public final class BitmapCropUtil {

    private BitmapCropUtil() {}

    /**
     * @param source      bitmap sumber (screenshot penuh)
     * @param lassoPath   path coretan, dalam koordinat yang sama dengan source
     * @param bounds      bounding box dari path (sudah di-clamp ke batas view)
     * @return bitmap hasil crop dengan area di luar lasso transparan,
     *         atau null bila bounds tidak valid
     */
    public static Bitmap cropToPath(Bitmap source, Path lassoPath, RectF bounds) {
        int left = Math.max(0, (int) bounds.left);
        int top = Math.max(0, (int) bounds.top);
        int right = Math.min(source.getWidth(), (int) bounds.right);
        int bottom = Math.min(source.getHeight(), (int) bounds.bottom);

        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) return null;

        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        // Gambar mask (bentuk lasso) dulu, geser ke origin (0,0) relatif
        // terhadap bounding box.
        Path shiftedPath = new Path(lassoPath);
        shiftedPath.offset(-left, -top);

        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawPath(shiftedPath, maskPaint);

        // Gambar bitmap sumber (bagian yang relevan saja) dengan mode
        // SRC_IN supaya hanya area yang sudah di-mask yang terisi.
        Paint srcInPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        srcInPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));

        android.graphics.Rect srcRect = new android.graphics.Rect(left, top, right, bottom);
        android.graphics.Rect dstRect = new android.graphics.Rect(0, 0, width, height);
        canvas.drawBitmap(source, srcRect, dstRect, srcInPaint);

        return result;
    }

    /**
     * Versi sederhana: crop persegi murni (bounding box), tanpa masking
     * bentuk lasso. Dipakai sebagai fallback bila cropToPath gagal, atau
     * untuk kasus yang butuh gambar utuh persegi (mis. OCR — teks yang
     * terpotong bentuk lasso bisa mengurangi akurasi baca).
     */
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
