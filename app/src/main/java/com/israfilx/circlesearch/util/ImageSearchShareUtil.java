package com.israfilx.circlesearch.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Simpan bitmap hasil crop ke cache internal, lalu kirim via
 * Intent.ACTION_SEND untuk pencarian visual (cari gambar mirip/cari info
 * dari gambar).
 *
 * Urutan preferensi (dari yang paling diutamakan ke paling akhir):
 *  1. App Yandex (Yandex Search atau Yandex Browser) bila terpasang —
 *     mesin pencari non-Google dengan reverse image search yang kuat.
 *  2. App Bing / Microsoft Start bila terpasang — alternatif non-Google
 *     kedua.
 *  3. Google Lens / app Google (jika terpasang) — dikembalikan sebagai
 *     pilihan, TAPI sengaja diberi prioritas PALING BAWAH di antara
 *     opsi bertarget spesifik, dicoba hanya setelah Yandex dan Bing
 *     tidak tersedia/tidak terpasang.
 *  4. Chooser (pemilih aplikasi) BAWAAN ANDROID tanpa target spesifik —
 *     murni daftar semua app yang bisa menerima gambar, dan user sendiri
 *     yang memilih. Fallback paling netral bila tidak ada app spesifik
 *     di atas yang terpasang.
 *
 * Catatan privasi: keputusan app tujuan mana yang akhirnya dipakai tetap
 * di tangan user lewat chooser sistem — util ini hanya menentukan URUTAN
 * mana yang dicoba lebih dulu bila lebih dari satu app spesifik terpasang
 * bersamaan, bukan memaksa satu layanan tertentu.
 */
public final class ImageSearchShareUtil {

    private static final String TAG = "CircleSearch/ImageShare";

    // Package name resmi Yandex Search (punya fitur "Cari lewat gambar"
    // terintegrasi) dan Yandex Browser (juga menerima share gambar untuk
    // reverse image search via yandex.com/images).
    private static final String YANDEX_SEARCH_PACKAGE = "ru.yandex.searchplugin";
    private static final String YANDEX_BROWSER_PACKAGE = "ru.yandex.browser";

    // Package name Microsoft Bing Search / Bing app (punya Bing Visual
    // Search terintegrasi) dan Microsoft Start.
    private static final String BING_APP_PACKAGE = "com.microsoft.bing";
    private static final String MICROSOFT_START_PACKAGE = "com.microsoft.amp.apps.bingfeed";

    // Package name app Google (rumah dari Google Lens) dan Google Lens
    // versi standalone (jarang terpasang terpisah, tapi dicoba juga demi
    // kelengkapan). Sengaja dicoba PALING TERAKHIR di antara opsi
    // bertarget spesifik, sesuai preferensi non-Google terlebih dulu.
    private static final String GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox";
    private static final String GOOGLE_LENS_PACKAGE = "com.google.ar.lens";

    private ImageSearchShareUtil() {}

    /**
     * @return true bila intent berhasil dikirim (activity ditemukan)
     */
    public static boolean shareForVisualSearch(Context context, Bitmap cropped) {
        Uri contentUri = saveToCacheAndGetUri(context, cropped);
        if (contentUri == null) return false;

        // 1. Coba Yandex dulu (search app, lalu browser).
        if (tryPackage(context, contentUri, YANDEX_SEARCH_PACKAGE)) return true;
        if (tryPackage(context, contentUri, YANDEX_BROWSER_PACKAGE)) return true;

        // 2. Coba Bing / Microsoft Start.
        if (tryPackage(context, contentUri, BING_APP_PACKAGE)) return true;
        if (tryPackage(context, contentUri, MICROSOFT_START_PACKAGE)) return true;

        // 3. Google Lens / app Google — prioritas paling bawah di antara
        //    opsi bertarget spesifik, dicoba hanya bila Yandex & Bing
        //    keduanya tidak tersedia.
        if (tryPackage(context, contentUri, GOOGLE_LENS_PACKAGE)) return true;
        if (tryPackage(context, contentUri, GOOGLE_APP_PACKAGE)) return true;

        // 4. Fallback: chooser umum tanpa target spesifik apapun — user
        //    bebas pilih dari semua app yang terpasang dan bisa menerima
        //    gambar.
        Intent sendIntent = buildSendIntent(contentUri);
        Intent chooser = Intent.createChooser(sendIntent, "Cari gambar dengan...");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (chooser.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(chooser);
            return true;
        }

        Log.e(TAG, "Tidak ada aplikasi yang bisa menerima gambar untuk pencarian visual");
        return false;
    }

    private static boolean tryPackage(Context context, Uri contentUri, String packageName) {
        if (!isPackageInstalled(context, packageName)) return false;

        Intent sendIntent = buildSendIntent(contentUri);
        sendIntent.setPackage(packageName);
        if (sendIntent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(sendIntent);
            return true;
        }
        return false;
    }

    private static boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static Intent buildSendIntent(Uri contentUri) {
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("image/png");
        sendIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return sendIntent;
    }

    private static Uri saveToCacheAndGetUri(Context context, Bitmap bitmap) {
        File cacheDir = new File(context.getCacheDir(), "circlesearch_shares");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        File imageFile = new File(cacheDir, "crop_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(imageFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
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
