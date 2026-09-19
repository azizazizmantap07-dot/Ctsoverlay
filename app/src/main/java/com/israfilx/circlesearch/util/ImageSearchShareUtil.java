package com.israfilx.circlesearch.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Simpan bitmap hasil crop ke cache internal, lalu buka hasil pencarian
 * visual di WebView in-app ({@code ImageSearchWebActivity}).
 *
 * Jalur Intent ke aplikasi eksternal (Yandex / Bing / Lens / chooser)
 * sudah dihapus — WebView in-app menjadi satu-satunya mode.
 */
public final class ImageSearchShareUtil {

    private static final String TAG = "CircleSearch/ImageShare";

    /** Nama SharedPreferences (dipakai juga opsi lain di app). */
    public static final String PREFS_NAME = "circlesearch_prefs";

    private ImageSearchShareUtil() {}

    /**
     * Entry point utama: selalu buka ImageSearchWebActivity.
     *
     * @return true bila activity berhasil diluncurkan
     */
    public static boolean shareForVisualSearch(Context context, Bitmap cropped) {
        return openInAppWebView(context, cropped);
    }

    /**
     * Buka ImageSearchWebActivity dengan Uri gambar yang sudah di-cache.
     */
    public static boolean openInAppWebView(Context context, Bitmap cropped) {
        Uri contentUri = saveToCacheAndGetUri(context, cropped);
        if (contentUri == null) return false;

        Intent intent = new Intent(context, com.israfilx.circlesearch.ui.ImageSearchWebActivity.class);
        intent.putExtra(com.israfilx.circlesearch.ui.ImageSearchWebActivity.EXTRA_IMAGE_URI,
                contentUri.toString());
        // NEW_TASK + affinity kosong (lihat Manifest) → task terpisah dari
        // MainActivity CTS. Saat WebView ditutup (finishAndRemoveTask),
        // user kembali ke app yang tadi di-overlay, bukan ke CTS.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Gagal membuka ImageSearchWebActivity", e);
            return false;
        }
    }

    /**
     * Simpan bitmap ke cache internal dan kembalikan content:// Uri
     * lewat FileProvider.
     */
    public static Uri saveToCacheAndGetUri(Context context, Bitmap bitmap) {
        File cacheDir = new File(context.getCacheDir(), "circlesearch_shares");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        // JPEG lebih kecil/cepat dibaca dibanding PNG 100% — upload host juga JPEG.
        File imageFile = new File(cacheDir, "crop_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(imageFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                Log.e(TAG, "Gagal compress JPEG crop");
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Gagal menyimpan bitmap crop ke cache", e);
            return null;
        }

        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                imageFile);
    }
}
