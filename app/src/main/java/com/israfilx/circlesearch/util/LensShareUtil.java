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
 * Simpan bitmap hasil crop ke cache internal, lalu kirim via
 * Intent.ACTION_SEND ke Google Lens / app Google (com.google.android.googlequicksearchbox),
 * dengan fallback ke chooser umum bila app Google tidak terpasang.
 */
public final class LensShareUtil {

    private static final String TAG = "CircleSearch/LensShare";
    private static final String GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox";

    private LensShareUtil() {}

    /**
     * @return true bila intent berhasil dikirim (activity ditemukan)
     */
    public static boolean shareToLens(Context context, Bitmap cropped) {
        File cacheDir = new File(context.getCacheDir(), "circlesearch_shares");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        File imageFile = new File(cacheDir, "crop_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(imageFile)) {
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.e(TAG, "Gagal menyimpan bitmap crop ke cache", e);
            return false;
        }

        Uri contentUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                imageFile);

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("image/png");
        sendIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Coba langsung arahkan ke app Google (yang punya fitur Lens
        // terintegrasi di search bar-nya).
        sendIntent.setPackage(GOOGLE_APP_PACKAGE);
        if (sendIntent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(sendIntent);
            return true;
        }

        // Fallback: chooser umum, biarkan user pilih app apa saja yang
        // bisa menerima gambar (Lens standalone, Chrome, dll).
        sendIntent.setPackage(null);
        Intent chooser = Intent.createChooser(sendIntent, "Cari gambar dengan...");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (chooser.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(chooser);
            return true;
        }

        Log.e(TAG, "Tidak ada aplikasi yang bisa menerima gambar untuk visual search");
        return false;
    }
}
