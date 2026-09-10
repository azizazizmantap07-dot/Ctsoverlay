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
import java.util.concurrent.atomic.AtomicLong;

/**
 * OCR (ekstraksi teks dari gambar) dan translate, keduanya via ML Kit
 * on-device.
 *
 * Alur: OCR baca teks dari bitmap crop -> pisah per BLOK teks (paragraf/
 * baris yang dikelompokkan ML Kit, lengkap dengan boundingBox-nya) ->
 * saring blok "noise" yang terlalu kecil/tidak relevan (lihat
 * {@link #filterSignificantBlocks}) -> deteksi bahasa dari gabungan blok
 * yang tersisa (sekali saja, lebih akurat daripada per-blok) -> translate
 * SETIAP blok secara terpisah.
 *
 * Menerjemahkan per-blok (bukan menggabungkan semua teks jadi satu
 * string lalu translate sekali) penting untuk dua alasan:
 *  1. Akurasi — satu string raksasa hasil gabungan baris-baris yang
 *     tidak selalu berurutan secara logis (mis. dua kolom teks bersisian)
 *     sering membuat translator kehilangan konteks kalimat dan hasilnya
 *     ngawur/terpotong. Per-blok menjaga tiap kalimat/paragraf utuh.
 *  2. Overlay — untuk bisa menimpa teks asli di posisi yang tepat, kita
 *     butuh boundingBox tiap blok, bukan cuma satu string panjang.
 *
 * OCR dan deteksi bahasa sepenuhnya offline (model sudah termasuk dalam
 * APK/terpasang sekali). Translate butuh model bahasa yang diunduh sekali
 * per pasangan bahasa (butuh internet saat unduh pertama kali saja,
 * setelahnya tersimpan dan bekerja offline).
 *
 * CATATAN PERBAIKAN — race condition unduh model bahasa:
 * Sebelumnya, bila translate dipicu lagi (mis. user menutup lalu memicu
 * ulang overlay) SEMENTARA unduhan model dari request SEBELUMNYA masih
 * berjalan, kedua alur async berjalan berdampingan tanpa saling kenal.
 * Begitu unduhan lama akhirnya selesai, callback-nya tetap terpanggil dan
 * bisa menampilkan hasil terjemahan yang sudah basi/tidak relevan lagi ke
 * layar (terlihat seperti "overlay nyangkut/tiba-tiba muncul"). Perbaikan:
 * setiap pemanggilan {@link #recognizeAndTranslate} mendapat nomor
 * "generasi" unik; hasil hanya dikirim ke callback bila generasi tsb
 * MASIH generasi terbaru saat callback siap. Selain itu callback
 * {@link ResultCallback#onModelDownloading()} ditambahkan supaya
 * pemanggil bisa menampilkan indikator loading yang jelas selama unduhan
 * berlangsung, alih-alih layar terlihat diam/stuck tanpa umpan balik.
 */
public final class OcrTranslateHelper {

    private static final String TAG = "CircleSearch/OcrTranslate";

    /** Bahasa target translate — Indonesia, sesuai bahasa aplikasi. */
    private static final String TARGET_LANGUAGE = TranslateLanguage.INDONESIAN;

    /**
     * Blok OCR dengan tinggi kotak di bawah ini (dalam px bitmap sumber)
     * diabaikan saat membangun teks gabungan untuk DETEKSI BAHASA (tetap
     * ikut di-translate bila lolos tahap lain) — blok sangat kecil pada
     * screenshot 1-layar penuh biasanya berasal dari elemen UI ramai
     * (jam, ikon status, label tombol pendek) yang sering salah terbaca
     * atau bercampur bahasa, dan bila ikut digabung dapat membuat
     * identifier bahasa salah simpul (mis. terbaca "und"/tak dikenal atau
     * malah dianggap sudah bahasa target) sehingga SELURUH hasil gagal
     * diterjemahkan. Ini adalah penyebab utama mode "1 layar" terasa
     * kurang akurat dibanding mode lasso (yang areanya sudah dipilih
     * user sehingga nyaris tidak ada noise UI ikut terbaca).
     */
    private static final int MIN_BLOCK_HEIGHT_PX_FOR_LANG_DETECT = 18;
    /** Blok dengan teks lebih pendek dari ini (setelah trim) juga dianggap noise untuk deteksi bahasa. */
    private static final int MIN_BLOCK_CHARS_FOR_LANG_DETECT = 2;

    // Penomor generasi request — dipakai untuk membuang hasil "basi" dari
    // request lama yang masih berjalan (biasanya sedang menunggu unduhan
    // model bahasa) saat request baru sudah dimulai.
    private static final AtomicLong requestGeneration = new AtomicLong(0);

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

        /**
         * Dipanggil sekali bila proses ini perlu mengunduh model bahasa
         * dulu (belum pernah diunduh sebelumnya untuk pasangan bahasa
         * ini) SEBELUM translate bisa dimulai. Pemanggil disarankan
         * menampilkan indikator loading yang jelas ("Mengunduh bahasa…")
         * selama rentang ini, supaya user tidak mengira overlay macet.
         * Default kosong (no-op) via metode statis {@link ResultCallback#noopDownloading()}
         * tidak disediakan — implementasikan langsung di pemanggil.
         */
        default void onModelDownloading() {
        }
    }

    private OcrTranslateHelper() {}

    /** Jalankan OCR + translate ke {@link #TARGET_LANGUAGE}. */
    public static void recognizeAndTranslate(Bitmap bitmap, ResultCallback callback) {
        recognizeInternal(bitmap, callback, true);
    }

    /**
     * Jalankan OCR SAJA tanpa translate sama sekali — dipakai untuk fitur
     * "Salin Teks" di mana user hanya butuh teks apa adanya dari gambar,
     * tanpa perlu menunggu/menerjemahkan (dan tanpa perlu unduh model
     * bahasa apapun). Blok yang dikembalikan punya originalText ==
     * translatedText (sama persis), sehingga tetap kompatibel dipakai
     * oleh kode yang mengharapkan {@link TranslatedBlock}.
     */
    public static void recognizeTextOnly(Bitmap bitmap, ResultCallback callback) {
        recognizeInternal(bitmap, callback, false);
    }

    private static void recognizeInternal(Bitmap bitmap, ResultCallback callback, boolean alsoTranslate) {
        final long myGeneration = requestGeneration.incrementAndGet();

        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    if (!isCurrent(myGeneration)) return;

                    List<Text.TextBlock> textBlocks = visionText.getTextBlocks();
                    if (textBlocks.isEmpty()) {
                        callback.onNoTextFound();
                        return;
                    }
                    Log.d(TAG, "OCR berhasil, jumlah blok=" + textBlocks.size());

                    if (!alsoTranslate) {
                        callback.onSuccess(toUntranslatedBlocks(textBlocks));
                        return;
                    }

                    detectLanguageAndTranslateBlocks(textBlocks, callback, myGeneration);
                })
                .addOnFailureListener(e -> {
                    if (!isCurrent(myGeneration)) return;
                    Log.e(TAG, "OCR gagal", e);
                    callback.onError(e);
                });
    }

    private static boolean isCurrent(long generation) {
        return requestGeneration.get() == generation;
    }

    private static void detectLanguageAndTranslateBlocks(
            List<Text.TextBlock> textBlocks, ResultCallback callback, long generation) {
        // Gabungkan hanya blok yang "signifikan" (lihat filterSignificantBlocks)
        // untuk deteksi bahasa — blok noise (sangat kecil/sangat pendek,
        // umum pada elemen UI ramai di mode 1-layar) dibuang dari teks
        // gabungan supaya tidak mengacaukan hasil deteksi, meski blok
        // tersebut tetap ikut ditranslate nanti bila lolos tahap translate.
        List<Text.TextBlock> significantForDetection = filterSignificantBlocks(textBlocks);
        List<Text.TextBlock> blocksForLangDetect =
                significantForDetection.isEmpty() ? textBlocks : significantForDetection;

        StringBuilder combined = new StringBuilder();
        for (Text.TextBlock block : blocksForLangDetect) {
            combined.append(block.getText()).append("\n");
        }

        LanguageIdentifier identifier = LanguageIdentification.getClient();
        identifier.identifyLanguage(combined.toString())
                .addOnSuccessListener(languageCode -> {
                    if (!isCurrent(generation)) return;
                    Log.d(TAG, "Bahasa terdeteksi: " + languageCode);

                    if ("und".equals(languageCode) || TARGET_LANGUAGE.equals(languageCode)) {
                        // Bahasa tidak terdeteksi, atau sudah dalam bahasa
                        // target — tampilkan blok apa adanya tanpa translate.
                        callback.onSuccess(toUntranslatedBlocks(textBlocks));
                        return;
                    }

                    translateBlocks(textBlocks, languageCode, callback, generation);
                })
                .addOnFailureListener(e -> {
                    if (!isCurrent(generation)) return;
                    Log.e(TAG, "Deteksi bahasa gagal, tampilkan blok OCR apa adanya", e);
                    callback.onSuccess(toUntranslatedBlocks(textBlocks));
                });
    }

    /**
     * Saring blok yang cukup "besar"/"panjang" untuk dipakai sebagai
     * dasar deteksi bahasa — buang blok mini (ikon status, angka jam,
     * dsb.) yang lebih mungkin noise daripada representasi bahasa asli
     * konten. Tidak mempengaruhi blok mana yang akhirnya ditranslate;
     * hanya mempengaruhi teks gabungan yang dipakai untuk identifikasi
     * bahasa.
     */
    private static List<Text.TextBlock> filterSignificantBlocks(List<Text.TextBlock> textBlocks) {
        List<Text.TextBlock> result = new ArrayList<>();
        for (Text.TextBlock block : textBlocks) {
            Rect box = block.getBoundingBox();
            String text = block.getText();
            if (box == null || text == null) continue;
            if (box.height() < MIN_BLOCK_HEIGHT_PX_FOR_LANG_DETECT) continue;
            if (text.trim().length() < MIN_BLOCK_CHARS_FOR_LANG_DETECT) continue;
            result.add(block);
        }
        return result;
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

    private static void translateBlocks(
            List<Text.TextBlock> textBlocks, String sourceLanguageCode, ResultCallback callback, long generation) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguageCode)
                .setTargetLanguage(TARGET_LANGUAGE)
                .build();
        Translator translator = Translation.getClient(options);

        DownloadConditions downloadConditions = new DownloadConditions.Builder()
                .build();

        // Beri tahu pemanggil BEGITU kita tahu proses translate akan
        // dimulai (mencakup kemungkinan unduh model) — UI dapat langsung
        // menampilkan indikator loading sejak titik ini, bukan menunggu
        // diam tanpa umpan balik sampai unduhan (yang bisa perlu beberapa
        // detik pada koneksi lambat) selesai.
        if (isCurrent(generation)) {
            callback.onModelDownloading();
        }

        translator.downloadModelIfNeeded(downloadConditions)
                .addOnSuccessListener(unused -> {
                    if (!isCurrent(generation)) {
                        // Request ini sudah digantikan oleh request yang
                        // lebih baru (mis. user menutup lalu memicu ulang
                        // overlay) SEMENTARA unduhan model masih berjalan.
                        // Buang hasilnya — jangan sampai layar terjemahan
                        // basi tiba-tiba muncul menimpa overlay yang
                        // sedang aktif sekarang.
                        Log.d(TAG, "Unduhan model selesai tapi request sudah basi (generation=" + generation + "), diabaikan");
                        translator.close();
                        return;
                    }
                    translateEachBlock(translator, textBlocks, callback, generation);
                })
                .addOnFailureListener(e -> {
                    if (!isCurrent(generation)) {
                        translator.close();
                        return;
                    }
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
    private static void translateEachBlock(
            Translator translator, List<Text.TextBlock> textBlocks, ResultCallback callback, long generation) {
        List<Text.TextBlock> validBlocks = new ArrayList<>();
        for (Text.TextBlock block : textBlocks) {
            if (block.getBoundingBox() != null && !block.getText().trim().isEmpty()) {
                validBlocks.add(block);
            }
        }

        if (validBlocks.isEmpty()) {
            if (isCurrent(generation)) callback.onNoTextFound();
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
                            finishTranslateEachBlock(translator, results, callback, generation);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Translate blok #" + idx + " gagal, tampilkan teks asli untuk blok ini", e);
                        results[idx] = new TranslatedBlock(originalText, originalText, box);
                        if (remaining.decrementAndGet() == 0) {
                            finishTranslateEachBlock(translator, results, callback, generation);
                        }
                    });
        }
    }

    private static void finishTranslateEachBlock(
            Translator translator, TranslatedBlock[] results, ResultCallback callback, long generation) {
        translator.close();
        if (!isCurrent(generation)) {
            Log.d(TAG, "Translate per-blok selesai tapi request sudah basi (generation=" + generation + "), diabaikan");
            return;
        }
        List<TranslatedBlock> list = new ArrayList<>();
        for (TranslatedBlock r : results) {
            if (r != null) list.add(r);
        }
        Log.d(TAG, "Translate per-blok selesai, jumlah blok=" + list.size());
        callback.onSuccess(list);
    }
}
