package com.israfilx.circlesearch.util;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * OCR (ekstraksi teks dari gambar) dan translate, keduanya via ML Kit
 * on-device.
 *
 * Alur: OCR baca teks dari bitmap crop -> deteksi bahasa teks itu ->
 * translate ke bahasa target (default Indonesia). OCR dan deteksi
 * bahasa sepenuhnya offline (model sudah termasuk dalam APK/terpasang
 * sekali). Translate butuh model bahasa yang diunduh sekali per
 * pasangan bahasa (butuh internet saat unduh pertama kali saja,
 * setelahnya tersimpan dan bekerja offline).
 */
public final class OcrTranslateHelper {

    private static final String TAG = "CircleSearch/OcrTranslate";

    /** Bahasa target translate — Indonesia, sesuai bahasa aplikasi. */
    private static final String TARGET_LANGUAGE = TranslateLanguage.INDONESIAN;

    public interface ResultCallback {
        /**
         * @param originalText   teks hasil OCR (sebelum translate)
         * @param translatedText teks setelah diterjemahkan, atau sama
         *                       dengan originalText bila bahasa sumber
         *                       sudah Indonesia (tidak perlu translate)
         */
        void onSuccess(String originalText, String translatedText);

        void onNoTextFound();

        void onError(Exception e);
    }

    private OcrTranslateHelper() {}

    public static void recognizeAndTranslate(Bitmap bitmap, ResultCallback callback) {
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String text = visionText.getText().trim();
                    if (text.isEmpty()) {
                        callback.onNoTextFound();
                        return;
                    }
                    Log.d(TAG, "OCR berhasil, panjang teks=" + text.length());
                    detectLanguageAndTranslate(text, callback);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR gagal", e);
                    callback.onError(e);
                });
    }

    private static void detectLanguageAndTranslate(String text, ResultCallback callback) {
        LanguageIdentifier identifier = LanguageIdentification.getClient();
        identifier.identifyLanguage(text)
                .addOnSuccessListener(languageCode -> {
                    Log.d(TAG, "Bahasa terdeteksi: " + languageCode);

                    if ("und".equals(languageCode)) {
                        // Bahasa tidak terdeteksi — tampilkan teks OCR apa
                        // adanya tanpa translate, daripada menebak salah.
                        callback.onSuccess(text, text);
                        return;
                    }

                    if (TARGET_LANGUAGE.equals(languageCode)) {
                        // Sudah dalam bahasa target, tidak perlu translate.
                        callback.onSuccess(text, text);
                        return;
                    }

                    translateText(text, languageCode, callback);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Deteksi bahasa gagal, tampilkan teks OCR apa adanya", e);
                    callback.onSuccess(text, text);
                });
    }

    private static void translateText(String text, String sourceLanguageCode, ResultCallback callback) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguageCode)
                .setTargetLanguage(TARGET_LANGUAGE)
                .build();
        Translator translator = Translation.getClient(options);

        DownloadConditions downloadConditions = new DownloadConditions.Builder()
                .build();

        translator.downloadModelIfNeeded(downloadConditions)
                .addOnSuccessListener(unused -> {
                    translator.translate(text)
                            .addOnSuccessListener(translatedText -> {
                                Log.d(TAG, "Translate berhasil");
                                callback.onSuccess(text, translatedText);
                                translator.close();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Translate gagal, tampilkan teks OCR apa adanya", e);
                                callback.onSuccess(text, text);
                                translator.close();
                            });
                })
                .addOnFailureListener(e -> {
                    // Kemungkinan besar tidak ada internet untuk unduh model
                    // bahasa. Tampilkan teks OCR apa adanya sebagai fallback.
                    Log.e(TAG, "Gagal unduh model translate (cek koneksi internet), tampilkan teks OCR apa adanya", e);
                    callback.onSuccess(text, text);
                    translator.close();
                });
    }
}
