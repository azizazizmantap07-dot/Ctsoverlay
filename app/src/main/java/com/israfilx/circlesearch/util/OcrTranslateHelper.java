package com.israfilx.circlesearch.util;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OCR (ekstraksi teks dari gambar) dan translate, keduanya via ML Kit
 * on-device.
 *
 * Alur: OCR baca teks dari bitmap crop -> pisah per BLOK teks (paragraf/
 * baris yang dikelompokkan ML Kit, lengkap dengan boundingBox-nya) ->
 * deteksi bahasa dari gabungan semua blok (sekali saja, lebih akurat
 * daripada per-blok karena blok pendek/satu-dua kata sering salah
 * terdeteksi bahasanya) -> translate SETIAP blok secara terpisah.
 *
 * Menerjemahkan per-blok (bukan menggabungkan semua teks jadi satu
 * string lalu translate sekali) penting untuk dua alasan:
 *  1. Akurasi — satu string raksasa hasil gabungan baris-baris yang
 *     tidak selalu berurutan secara logis (mis. dua kolom teks bersisian)
 *     sering membuat translator kehilangan konteks kalimat dan hasilnya
 *     ngawur/terpotong. Per-blok menjaga tiap kalimat/paragraf utuh.
 *  2. Overlay — untuk bisa menimpa teks asli di posisi yang tepat
 *     (menggambar ulang terjemahan pas di atas teks aslinya), kita butuh
 *     boundingBox tiap blok, bukan cuma satu string panjang tanpa posisi.
 *
 * OCR dan deteksi bahasa sepenuhnya offline (model sudah termasuk dalam
 * APK/terpasang sekali). Translate butuh model bahasa yang diunduh sekali
 * per pasangan bahasa (butuh internet saat unduh pertama kali saja,
 * setelahnya tersimpan dan bekerja offline).
 */
public final class OcrTranslateHelper {

    private static final String TAG = "CircleSearch/OcrTranslate";

    /** Bahasa target translate — Indonesia, sesuai bahasa aplikasi. */
    private static final String TARGET_LANGUAGE = TranslateLanguage.INDONESIAN;

    /**
     * Satu blok teks hasil OCR: teks asli, teks terjemahan, dan posisinya
     * pada bitmap sumber (koordinat piksel, sama dengan koordinat bitmap
     * yang di-OCR). Dipakai untuk menggambar ulang terjemahan tepat di
     * atas teks aslinya.
     */
    public static class TranslatedBlock {
        public final String originalText;
        public final String translatedText;
        public final Rect boundingBox;

        public TranslatedBlock(String originalText, String translatedText, Rect boundingBox) {
            this.originalText = originalText;
            this.translatedText = translatedText;
            this.boundingBox = boundingBox;
        }

        public boolean wasTranslated() {
            return !originalText.trim().equalsIgnoreCase(translatedText.trim());
        }
    }

    public interface ResultCallback {
        /**
         * @param blocks daftar blok teks (dalam urutan pembacaan ML Kit),
         *               masing-masing dengan teks asli + terjemahan + posisi
         */
        void onSuccess(List<TranslatedBlock> blocks);

        void onNoTextFound();

        void onError(Exception e);
    }

    private OcrTranslateHelper() {}

    public static void recognizeAndTranslate(Bitmap bitmap, ResultCallback callback) {
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    List<Text.TextBlock> textBlocks = visionText.getTextBlocks();
                    if (textBlocks.isEmpty()) {
                        callback.onNoTextFound();
                        return;
                    }
                    Log.d(TAG, "OCR berhasil, jumlah blok=" + textBlocks.size());
                    detectLanguageAndTranslateBlocks(textBlocks, callback);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR gagal", e);
                    callback.onError(e);
                });
    }

    private static void detectLanguageAndTranslateBlocks(List<Text.TextBlock> textBlocks, ResultCallback callback) {
        // Deteksi bahasa dipakai dari gabungan seluruh teks (bukan per-blok)
        // supaya identifier punya cukup konteks — blok pendek (satu-dua
        // kata, mis. judul tombol) sering salah terdeteksi bahasanya bila
        // diperiksa sendirian.
        StringBuilder combined = new StringBuilder();
        for (Text.TextBlock block : textBlocks) {
            combined.append(block.getText()).append("\n");
        }

        LanguageIdentifier identifier = LanguageIdentification.getClient();
        identifier.identifyLanguage(combined.toString())
                .addOnSuccessListener(languageCode -> {
                    Log.d(TAG, "Bahasa terdeteksi: " + languageCode);

                    if ("und".equals(languageCode) || TARGET_LANGUAGE.equals(languageCode)) {
                        // Bahasa tidak terdeteksi, atau sudah dalam bahasa
                        // target — tampilkan blok apa adanya tanpa translate.
                        callback.onSuccess(toUntranslatedBlocks(textBlocks));
                        return;
                    }

                    translateBlocks(textBlocks, languageCode, callback);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Deteksi bahasa gagal, tampilkan blok OCR apa adanya", e);
                    callback.onSuccess(toUntranslatedBlocks(textBlocks));
                });
    }

    private static List<TranslatedBlock> toUntranslatedBlocks(List<Text.TextBlock> textBlocks) {
        List<TranslatedBlock> result = new ArrayList<>();
        for (Text.TextBlock block : textBlocks) {
            Rect box = block.getBoundingBox();
            if (box == null) continue;
            String text = block.getText();
            result.add(new TranslatedBlock(text, text, box));
        }
        return result;
    }

    private static void translateBlocks(List<Text.TextBlock> textBlocks, String sourceLanguageCode, ResultCallback callback) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguageCode)
                .setTargetLanguage(TARGET_LANGUAGE)
                .build();
        Translator translator = Translation.getClient(options);

        DownloadConditions downloadConditions = new DownloadConditions.Builder()
                .build();

        translator.downloadModelIfNeeded(downloadConditions)
                .addOnSuccessListener(unused -> translateEachBlock(translator, textBlocks, callback))
                .addOnFailureListener(e -> {
                    // Kemungkinan besar tidak ada internet untuk unduh model
                    // bahasa. Tampilkan blok OCR apa adanya sebagai fallback.
                    Log.e(TAG, "Gagal unduh model translate (cek koneksi internet), tampilkan blok OCR apa adanya", e);
                    callback.onSuccess(toUntranslatedBlocks(textBlocks));
                    translator.close();
                });
    }

    /**
     * Translate tiap blok satu per satu (paralel, ditunggu semua selesai)
     * supaya tiap blok tetap dapat konteks kalimatnya sendiri yang utuh,
     * lalu dipasangkan kembali dengan boundingBox masing-masing untuk
     * keperluan overlay menimpa teks asli.
     */
    private static void translateEachBlock(Translator translator, List<Text.TextBlock> textBlocks, ResultCallback callback) {
        // Saring dulu blok yang punya boundingBox valid — blok tanpa posisi
        // tidak bisa dipakai untuk overlay menimpa teks asli.
        List<Text.TextBlock> validBlocks = new ArrayList<>();
        for (Text.TextBlock block : textBlocks) {
            if (block.getBoundingBox() != null && !block.getText().trim().isEmpty()) {
                validBlocks.add(block);
            }
        }

        if (validBlocks.isEmpty()) {
            callback.onNoTextFound();
            translator.close();
            return;
        }

        TranslatedBlock[] results = new TranslatedBlock[validBlocks.size()];
        AtomicInteger remaining = new AtomicInteger(validBlocks.size());

        for (int i = 0; i < validBlocks.size(); i++) {
            final int idx = i;
            Text.TextBlock block = validBlocks.get(i);
            String originalText = block.getText();
            Rect box = block.getBoundingBox();

            translator.translate(originalText)
                    .addOnSuccessListener(translated -> {
                        results[idx] = new TranslatedBlock(originalText, translated, box);
                        if (remaining.decrementAndGet() == 0) {
                            finishTranslateEachBlock(translator, results, callback);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Translate blok #" + idx + " gagal, tampilkan teks asli untuk blok ini", e);
                        // Fallback per-blok: blok yang gagal ditampilkan
                        // apa adanya, blok lain yang berhasil tetap dipakai.
                        results[idx] = new TranslatedBlock(originalText, originalText, box);
                        if (remaining.decrementAndGet() == 0) {
                            finishTranslateEachBlock(translator, results, callback);
                        }
                    });
        }
    }

    private static void finishTranslateEachBlock(Translator translator, TranslatedBlock[] results, ResultCallback callback) {
        List<TranslatedBlock> list = new ArrayList<>();
        for (TranslatedBlock r : results) {
            if (r != null) list.add(r);
        }
        Log.d(TAG, "Translate per-blok selesai, jumlah blok=" + list.size());
        callback.onSuccess(list);
        translator.close();
    }
}
