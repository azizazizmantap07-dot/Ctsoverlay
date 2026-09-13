package com.israfilx.circlesearch.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.ResultIterator;

/**
 * OCR skrip Thai via Tesseract (bukan ML Kit).
 *
 * KENAPA: sama seperti kasus Arabic (lihat javadoc
 * {@link TesseractArabicRecognizer}), ML Kit Text Recognition tidak
 * menyediakan model on-device untuk skrip Thai sama sekali. Sebelum kelas
 * ini ada, screenshot berisi teks Thai selalu salah-baca oleh kelima
 * recognizer ML Kit yang tersedia (Latin/Chinese/Japanese/Korean/
 * Devanagari), paling sering "berhasil" secara teknis lewat recognizer
 * Latin tapi menghasilkan sampah karakter Latin acak dari lengkungan
 * aksara Thai, yang lalu membuat deteksi bahasa nyasar — persis pola
 * kegagalan yang sama yang mendorong ditambahkannya recognizer Arab.
 *
 * Kelas ini dipakai sebagai recognizer KE-7 (setelah 5 recognizer ML Kit
 * + Tesseract Arabic), berjalan paralel dengan semuanya di
 * {@link OcrTranslateHelper}, lalu hasilnya divalidasi dengan cara yang
 * sama (dominasi karakter skrip Thai) sebelum dianggap sebagai kandidat
 * "OCR terbaik".
 *
 * Model bahasa (tha.traineddata, dari tessdata_fast) di-BUNDLE langsung
 * di assets/tessdata/ — tidak perlu diunduh terpisah seperti model
 * Translate ML Kit. Saat pertama dipakai, file ini disalin sekali ke
 * penyimpanan privat app (syarat Tesseract4Android: path harus bisa
 * dibaca langsung dari filesystem, bukan dari dalam APK).
 */
public final class TesseractThaiRecognizer {

    private static final String TAG = "CircleSearch/OcrTranslate";
    private static final String LANG_CODE = "tha";
    private static final String TESSDATA_ASSET_DIR = "tessdata";

    private TesseractThaiRecognizer() {}

    /**
     * Pastikan tha.traineddata sudah ada di penyimpanan privat app.
     * Aman dipanggil berkali-kali — hanya menyalin bila file belum ada
     * atau ukurannya tidak cocok dengan yang ada di assets (mis. build
     * baru mengganti versi model).
     *
     * HARUS dipanggil dari background thread (I/O file).
     */
    private static File ensureTessdataExtracted(Context context) throws IOException {
        File tessRoot = new File(context.getFilesDir(), "tesseract");
        File tessdataDir = new File(tessRoot, TESSDATA_ASSET_DIR);
        if (!tessdataDir.exists() && !tessdataDir.mkdirs()) {
            throw new IOException("Gagal membuat direktori tessdata: " + tessdataDir);
        }

        File destFile = new File(tessdataDir, LANG_CODE + ".traineddata");
        AssetManager assets = context.getAssets();
        String assetPath = TESSDATA_ASSET_DIR + "/" + LANG_CODE + ".traineddata";

        long assetSize = -1;
        try (InputStream probe = assets.open(assetPath)) {
            assetSize = probe.available();
        } catch (IOException e) {
            Log.e(TAG, "Asset " + assetPath + " tidak ditemukan di APK", e);
            throw e;
        }

        if (destFile.exists() && destFile.length() == assetSize) {
            // Sudah ada dan ukurannya cocok, tidak perlu salin ulang.
            return tessRoot;
        }

        Log.d(TAG, "Menyalin " + assetPath + " ke penyimpanan privat (" + assetSize + " bytes)");
        try (InputStream in = assets.open(assetPath);
             OutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        return tessRoot;
    }

    /**
     * Jalankan OCR Thai secara SINKRON. Harus dipanggil dari background
     * thread (I/O ekstraksi asset + inferensi Tesseract, keduanya blocking).
     *
     * Mengembalikan list kosong bila gagal (mis. init Tesseract gagal) —
     * TIDAK melempar exception ke pemanggil, konsisten dengan bagaimana
     * OcrTranslateHelper memperlakukan kegagalan satu recognizer sebagai
     * "list kosong" dan tetap melanjutkan proses dengan recognizer lain.
     */
    public static List<OcrBlock> recognize(Context context, Bitmap bitmap) {
        List<OcrBlock> result = new ArrayList<>();
        TessBaseAPI tessApi = null;
        try {
            File tessRoot = ensureTessdataExtracted(context);
            tessApi = new TessBaseAPI();
            boolean initOk = tessApi.init(tessRoot.getAbsolutePath(), LANG_CODE);
            if (!initOk) {
                Log.w(TAG, "Init Tesseract Thai gagal (tessdata path=" + tessRoot.getAbsolutePath() + ")");
                return result;
            }

            tessApi.setImage(bitmap);
            // Memicu layout analysis + recognition; hasil teks penuh tidak
            // dipakai langsung (kita ambil per-baris lewat ResultIterator
            // agar dapat boundingBox per blok, konsisten dengan ML Kit).
            tessApi.getUTF8Text();

            ResultIterator iterator = tessApi.getResultIterator();
            if (iterator != null) {
                iterator.begin();
                do {
                    String lineText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                    Rect box = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                    if (lineText != null && !lineText.trim().isEmpty() && box != null) {
                        result.add(new OcrBlock(lineText.trim(), box));
                    }
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE));
                iterator.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "OCR Tesseract Thai gagal: " + e.getMessage());
            return new ArrayList<>();
        } finally {
            if (tessApi != null) {
                try { tessApi.recycle(); } catch (Exception ignored) {}
            }
        }
        return result;
    }
}
