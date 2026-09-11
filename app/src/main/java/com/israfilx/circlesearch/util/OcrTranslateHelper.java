package com.israfilx.circlesearch.util;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.IdentifiedLanguage;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * PERUBAHAN PENTING — unduhan model bahasa sekarang MANUAL saja:
 * Model tidak lagi diunduh otomatis saat translate. Jika model untuk
 * pasangan bahasa yang dibutuhkan belum tersedia, translate dilewati
 * dan teks asli ditampilkan. Pengguna harus mengunduh model terlebih
 * dahulu lewat menu di MainActivity (bisa unduh satu per satu atau
 * semua bahasa yang didukung sekaligus).
 *
 * CATATAN PERBAIKAN — race condition unduh model bahasa:
 * Setiap pemanggilan {@link #recognizeAndTranslate} mendapat nomor
 * "generasi" unik; hasil hanya dikirim ke callback bila generasi tsb
 * MASIH generasi terbaru saat callback siap.
 */
public final class OcrTranslateHelper {

    private static final String TAG = "CircleSearch/OcrTranslate";

    /** Bahasa target translate — Indonesia, sesuai bahasa aplikasi. */
    public static final String TARGET_LANGUAGE = TranslateLanguage.INDONESIAN;

    /**
     * Daftar bahasa sumber yang didukung untuk diunduh manual.
     * Model Translate ML Kit bersifat per-bahasa (bukan per-pasangan);
     * model bahasa X + model bahasa target (Indonesia) memungkinkan
     * terjemahan X → Indonesia.
     */
    public static final List<LanguageInfo> SUPPORTED_SOURCE_LANGUAGES = Collections.unmodifiableList(Arrays.asList(
            new LanguageInfo(TranslateLanguage.ENGLISH, "English (Inggris)"),
            new LanguageInfo(TranslateLanguage.CHINESE, "Chinese (Mandarin)"),
            new LanguageInfo(TranslateLanguage.JAPANESE, "Japanese (Jepang)"),
            new LanguageInfo(TranslateLanguage.KOREAN, "Korean (Korea)"),
            new LanguageInfo(TranslateLanguage.ARABIC, "Arabic (Arab)"),
            new LanguageInfo(TranslateLanguage.SPANISH, "Spanish (Spanyol)"),
            new LanguageInfo(TranslateLanguage.FRENCH, "French (Prancis)"),
            new LanguageInfo(TranslateLanguage.GERMAN, "German (Jerman)"),
            new LanguageInfo(TranslateLanguage.PORTUGUESE, "Portuguese (Portugis)"),
            new LanguageInfo(TranslateLanguage.RUSSIAN, "Russian (Rusia)"),
            new LanguageInfo(TranslateLanguage.THAI, "Thai (Thailand)"),
            new LanguageInfo(TranslateLanguage.VIETNAMESE, "Vietnamese (Vietnam)"),
            new LanguageInfo(TranslateLanguage.HINDI, "Hindi"),
            new LanguageInfo(TranslateLanguage.TURKISH, "Turkish (Turki)"),
            new LanguageInfo(TranslateLanguage.ITALIAN, "Italian (Italia)"),
            new LanguageInfo(TranslateLanguage.DUTCH, "Dutch (Belanda)"),
            new LanguageInfo(TranslateLanguage.POLISH, "Polish (Polandia)"),
            new LanguageInfo(TranslateLanguage.UKRAINIAN, "Ukrainian (Ukraina)"),
            new LanguageInfo(TranslateLanguage.MALAY, "Malay (Melayu)"),
            new LanguageInfo(TranslateLanguage.TAGALOG, "Filipino / Tagalog")
    ));

    /**
     * Blok OCR dengan tinggi kotak di bawah ini (dalam px bitmap sumber)
     * diabaikan saat membangun teks gabungan untuk DETEKSI BAHASA.
     *
     * DINAIKKAN 18 -> 26 -> 34: mode "1 layar" perlu menyaring elemen UI
     * kecil (label ikon, badge notifikasi, watermark kecil) seagresif
     * mungkin dari sinyal deteksi bahasa. Aman dinaikkan karena ada
     * fallback di {@link #detectLanguageAndTranslateBlocks} — bila SEMUA
     * blok kebuang filter ini, kode otomatis balik memakai seluruh blok
     * OCR apa adanya (lihat blocksForLangDetect), jadi tidak akan pernah
     * membuat translate "kehabisan teks untuk dideteksi".
     */
    private static final int MIN_BLOCK_HEIGHT_PX_FOR_LANG_DETECT = 34;
    /**
     * Blok dengan teks lebih pendek dari ini (setelah trim) juga dianggap
     * noise untuk deteksi bahasa.
     *
     * DINAIKKAN 2 -> 4 -> 6: string pendek (jam, baterai, badge, nomor
     * urut, singkatan) nyaris tidak membawa sinyal bahasa yang berguna.
     * 6 karakter cukup ketat untuk membuang noise semacam itu, tapi masih
     * meloloskan frasa pendek valid ("Selamat", "Battery", dst).
     */
    private static final int MIN_BLOCK_CHARS_FOR_LANG_DETECT = 6;
    /**
     * Ambang confidence untuk {@link LanguageIdentifier}, DITURUNKAN dari
     * default 0.5 menjadi 0.35. Screenshot "1 layar" hampir selalu berisi
     * campuran teks aplikasi + elemen UI sistem (jam, status bar, watermark
     * wallpaper, dsb), yang membuat confidence deteksi bahasa gabungan
     * secara alami lebih rendah dibanding teks bersih hasil seleksi lasso.
     * Ambang default 0.5 terlalu mudah menghasilkan "und" (tidak
     * terdeteksi) pada kasus ini, yang membuat translate dilewati sama
     * sekali meski sebenarnya teksnya jelas satu bahasa.
     *
     * SENGAJA TIDAK diperketat lebih lanjut (mis. dinaikkan ke 0.45-0.5)
     * meski filter-filter di atas sudah diperketat — parameter ini paling
     * sensitif terhadap bug baru: menaikkannya kembali membuat translate
     * mode "1 layar" mudah jatuh ke "und" lagi (bug awal yang sudah
     * diperbaiki), sementara menurunkannya lebih jauh dari 0.35 membuat
     * hasil deteksi makin sering "percaya diri" pada tebakan yang salah.
     * 0.35 adalah titik tengah yang sudah diuji; jangan digeser tanpa
     * pengujian nyata di berbagai jenis screenshot.
     */
    private static final float LANGUAGE_CONFIDENCE_THRESHOLD = 0.35f;

    private static final AtomicLong requestGeneration = new AtomicLong(0);

    public static class LanguageInfo {
        public final String code;
        public final String displayName;

        public LanguageInfo(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }
    }

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
        void onSuccess(List<TranslatedBlock> blocks);
        void onNoTextFound();
        void onError(Exception e);

        /**
         * Dipanggil bila model bahasa yang dibutuhkan belum diunduh.
         * Translate dilewati; teks asli akan ditampilkan.
         * Default: no-op.
         */
        default void onModelNotDownloaded(String sourceLanguageCode) {
        }
    }

    public interface ModelCallback {
        void onSuccess();
        void onFailure(Exception e);
        default void onProgress(String message) {}
    }

    private OcrTranslateHelper() {}

    /** Jalankan OCR + translate ke {@link #TARGET_LANGUAGE}. */
    public static void recognizeAndTranslate(Bitmap bitmap, ResultCallback callback) {
        recognizeInternal(bitmap, callback, true);
    }

    /**
     * Jalankan OCR SAJA tanpa translate sama sekali — dipakai untuk fitur
     * "Salin Teks".
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
        List<Text.TextBlock> significantForDetection = filterSignificantBlocks(textBlocks);
        List<Text.TextBlock> blocksForLangDetect =
                significantForDetection.isEmpty() ? textBlocks : significantForDetection;

        // Urutkan dari teks TERPANJANG ke terpendek sebelum digabung.
        // Pada mode "1 layar" jumlah blok kecil (label ikon, sisa noise
        // yang lolos filter) bisa jauh lebih banyak daripada blok konten
        // sungguhan; menggabung apa adanya (urutan posisi di layar)
        // membiarkan blok-blok kecil itu mendominasi porsi string yang
        // dikirim ke language identifier. Mendahulukan blok terpanjang
        // memastikan konten sungguhan yang paling menentukan hasil deteksi.
        //
        // PENTING: identifyLanguage()/identifyPossibleLanguages() ML Kit
        // memotong input yang lebih dari 200 karakter dan HANYA memakai
        // 200 karakter PERTAMA dari string yang dikirim. Tanpa pengurutan
        // ini, pada layar berisi banyak teks (mis. artikel panjang), 200
        // karakter pertama yang benar-benar dipakai untuk deteksi bisa
        // saja seluruhnya berasal dari blok kecil/noise di awal urutan
        // posisi layar — sementara konten sungguhan yang panjang tidak
        // pernah "terlihat" oleh detektor sama sekali. Mengurutkan blok
        // terpanjang ke depan memastikan 200 karakter yang dipakai ML Kit
        // berasal dari konten yang benar-benar relevan.
        List<Text.TextBlock> sortedForDetect = new ArrayList<>(blocksForLangDetect);
        Collections.sort(sortedForDetect, (a, b) ->
                Integer.compare(b.getText().trim().length(), a.getText().trim().length()));

        StringBuilder combined = new StringBuilder();
        for (Text.TextBlock block : sortedForDetect) {
            combined.append(block.getText()).append("\n");
        }

        LanguageIdentificationOptions options = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(LANGUAGE_CONFIDENCE_THRESHOLD)
                .build();
        LanguageIdentifier identifier = LanguageIdentification.getClient(options);
        identifier.identifyLanguage(combined.toString())
                .addOnSuccessListener(languageCode -> {
                    if (!isCurrent(generation)) return;
                    Log.d(TAG, "Bahasa terdeteksi (gabungan): " + languageCode);

                    if ("und".equals(languageCode)) {
                        // Fallback: gabungan gagal (campuran macam-macam
                        // elemen UI menurunkan confidence di bawah ambang).
                        // Coba lagi HANYA pada blok teks terpanjang sendirian
                        // — teks tunggal yang lebih "bersih" tanpa campuran
                        // sering kali cukup untuk lolos ambang confidence.
                        detectLanguageFromLongestBlockFallback(sortedForDetect, textBlocks, callback, generation);
                        return;
                    }
                    if (TARGET_LANGUAGE.equals(languageCode)) {
                        callback.onSuccess(toUntranslatedBlocks(textBlocks));
                        return;
                    }

                    translateBlocksIfModelAvailable(textBlocks, languageCode, callback, generation);
                })
                .addOnFailureListener(e -> {
                    if (!isCurrent(generation)) return;
                    Log.e(TAG, "Deteksi bahasa gagal, tampilkan blok OCR apa adanya", e);
                    callback.onSuccess(toUntranslatedBlocks(textBlocks));
                });
    }

    /**
     * Fallback saat deteksi bahasa dari string gabungan menghasilkan "und".
     * Dua lapis, dari yang paling "murni" ke paling "luas":
     *  1. Coba identifyPossibleLanguages pada SATU blok teks terpanjang
     *     saja — paling mungkin konten aplikasi sungguhan, bebas campuran.
     *  2. Bila itu juga gagal (mis. blok terpanjang ternyata masih pendek
     *     karena filter noise sudah agresif), coba lagi dengan gabungan
     *     2-3 blok terpanjang — jaring pengaman supaya filter yang ketat
     *     di atas tidak membuat translate jadi lebih sering menyerah.
     * Jika kedua lapis tetap gagal, baru benar-benar menampilkan teks asli.
     */
    private static void detectLanguageFromLongestBlockFallback(
            List<Text.TextBlock> sortedForDetect, List<Text.TextBlock> allBlocks,
            ResultCallback callback, long generation) {
        if (sortedForDetect.isEmpty()) {
            callback.onSuccess(toUntranslatedBlocks(allBlocks));
            return;
        }
        String longestText = sortedForDetect.get(0).getText().trim();
        if (longestText.isEmpty()) {
            callback.onSuccess(toUntranslatedBlocks(allBlocks));
            return;
        }

        LanguageIdentificationOptions options = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(LANGUAGE_CONFIDENCE_THRESHOLD)
                .build();
        LanguageIdentifier identifier = LanguageIdentification.getClient(options);
        identifier.identifyPossibleLanguages(longestText)
                .addOnSuccessListener(candidates -> {
                    if (!isCurrent(generation)) return;

                    String best = pickBestCandidate(candidates);
                    if (best != null) {
                        Log.d(TAG, "Bahasa terdeteksi (fallback lapis-1, blok terpanjang): " + best);
                        finishLanguageDetected(best, allBlocks, callback, generation);
                        return;
                    }

                    // Lapis-2: gabungan 2-3 blok terpanjang (bukan cuma satu).
                    detectLanguageFromTopBlocksFallback(sortedForDetect, allBlocks, callback, generation);
                })
                .addOnFailureListener(e -> {
                    if (!isCurrent(generation)) return;
                    Log.e(TAG, "Fallback lapis-1 gagal (exception), coba lapis-2", e);
                    detectLanguageFromTopBlocksFallback(sortedForDetect, allBlocks, callback, generation);
                });
    }

    /** Fallback lapis-2: gabungan 2-3 blok terpanjang. Lihat javadoc di atas. */
    private static void detectLanguageFromTopBlocksFallback(
            List<Text.TextBlock> sortedForDetect, List<Text.TextBlock> allBlocks,
            ResultCallback callback, long generation) {
        int take = Math.min(3, sortedForDetect.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < take; i++) {
            sb.append(sortedForDetect.get(i).getText().trim()).append("\n");
        }
        String topBlocksText = sb.toString().trim();
        if (topBlocksText.isEmpty()) {
            callback.onSuccess(toUntranslatedBlocks(allBlocks));
            return;
        }

        LanguageIdentificationOptions options = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(LANGUAGE_CONFIDENCE_THRESHOLD)
                .build();
        LanguageIdentifier identifier = LanguageIdentification.getClient(options);
        identifier.identifyPossibleLanguages(topBlocksText)
                .addOnSuccessListener(candidates -> {
                    if (!isCurrent(generation)) return;

                    String best = pickBestCandidate(candidates);
                    if (best == null) {
                        Log.d(TAG, "Fallback lapis-2 juga gagal, tampilkan teks asli");
                        callback.onSuccess(toUntranslatedBlocks(allBlocks));
                        return;
                    }
                    Log.d(TAG, "Bahasa terdeteksi (fallback lapis-2, top-" + take + " blok): " + best);
                    finishLanguageDetected(best, allBlocks, callback, generation);
                })
                .addOnFailureListener(e -> {
                    if (!isCurrent(generation)) return;
                    Log.e(TAG, "Fallback lapis-2 gagal (exception), tampilkan teks asli", e);
                    callback.onSuccess(toUntranslatedBlocks(allBlocks));
                });
    }

    /** Ambil kandidat dengan confidence tertinggi selain "und"; null bila tidak ada. */
    private static String pickBestCandidate(List<IdentifiedLanguage> candidates) {
        String best = null;
        float bestConfidence = 0f;
        for (IdentifiedLanguage candidate : candidates) {
            String code = candidate.getLanguageTag();
            if ("und".equals(code)) continue;
            if (candidate.getConfidence() > bestConfidence) {
                bestConfidence = candidate.getConfidence();
                best = code;
            }
        }
        return best;
    }

    /** Lanjutkan ke translate (atau tampilkan asli bila bahasa terdeteksi = target). */
    private static void finishLanguageDetected(
            String languageCode, List<Text.TextBlock> allBlocks,
            ResultCallback callback, long generation) {
        if (TARGET_LANGUAGE.equals(languageCode)) {
            callback.onSuccess(toUntranslatedBlocks(allBlocks));
            return;
        }
        translateBlocksIfModelAvailable(allBlocks, languageCode, callback, generation);
    }

    private static List<Text.TextBlock> filterSignificantBlocks(List<Text.TextBlock> textBlocks) {
        List<Text.TextBlock> result = new ArrayList<>();
        for (Text.TextBlock block : textBlocks) {
            Rect box = block.getBoundingBox();
            String text = block.getText();
            if (box == null || text == null) continue;
            String trimmed = text.trim();
            if (box.height() < MIN_BLOCK_HEIGHT_PX_FOR_LANG_DETECT) continue;
            if (trimmed.length() < MIN_BLOCK_CHARS_FOR_LANG_DETECT) continue;
            if (isMostlyNonAlphabetic(trimmed)) continue;
            result.add(block);
        }
        return result;
    }

    /**
     * True bila blok teks sebagian besar terdiri dari digit/simbol/spasi
     * (mis. jam "12.34", tanggal, persentase baterai "76%", nomor urut,
     * atau string campuran seperti "v2.3.1", "Level 76").
     * Blok semacam ini nyaris tidak membawa sinyal bahasa dan sering
     * berasal dari elemen UI sistem (status bar, jam) bukan konten
     * aplikasi — harus disingkirkan dari deteksi bahasa mode "1 layar"
     * supaya tidak mengotori hasil deteksi.
     *
     * Ambang rasio huruf DINAIKKAN 0.5 -> 0.7 (lebih agresif): sebelumnya
     * blok campuran huruf+angka seperti "Level 76" (rasio huruf ~0.6)
     * masih lolos; sekarang blok semacam itu ikut disaring, sementara teks
     * asli berbahasa apapun (termasuk CJK/Arab, yang seluruh karakternya
     * dihitung "huruf" oleh Character.isLetter) tetap rasio 1.0 dan lolos
     * tanpa terpengaruh.
     */
    private static boolean isMostlyNonAlphabetic(String text) {
        int letters = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (Character.isLetter(c)) letters++;
        }
        if (total == 0) return true;
        // Kurang dari 70% karakter (non-spasi) berupa huruf -> anggap noise.
        return letters < total * 0.7;
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

    /**
     * Translate HANYA jika model sudah tersedia secara lokal.
     * Tidak ada unduhan otomatis — jika model belum diunduh, teks asli
     * ditampilkan dan {@link ResultCallback#onModelNotDownloaded} dipanggil.
     */
    private static void translateBlocksIfModelAvailable(
            List<Text.TextBlock> textBlocks, String sourceLanguageCode,
            ResultCallback callback, long generation) {

        isModelDownloaded(sourceLanguageCode, new ModelCallback() {
            @Override
            public void onSuccess() {
                // Model tersedia → lanjut translate
                if (!isCurrent(generation)) return;
                doTranslateBlocks(textBlocks, sourceLanguageCode, callback, generation);
            }

            @Override
            public void onFailure(Exception e) {
                // Model belum diunduh atau gagal cek → tampilkan teks asli
                if (!isCurrent(generation)) return;
                Log.w(TAG, "Model bahasa " + sourceLanguageCode + " belum diunduh, tampilkan teks asli");
                callback.onModelNotDownloaded(sourceLanguageCode);
                callback.onSuccess(toUntranslatedBlocks(textBlocks));
            }
        });
    }

    private static void doTranslateBlocks(
            List<Text.TextBlock> textBlocks, String sourceLanguageCode,
            ResultCallback callback, long generation) {

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguageCode)
                .setTargetLanguage(TARGET_LANGUAGE)
                .build();
        Translator translator = Translation.getClient(options);

        // Tidak memanggil downloadModelIfNeeded — model harus sudah ada.
        // Kita langsung translate; bila model benar-benar tidak ada,
        // translate akan gagal dan kita fallback ke teks asli.
        translateEachBlock(translator, textBlocks, callback, generation);
    }

    private static void translateEachBlock(
            Translator translator, List<Text.TextBlock> textBlocks,
            ResultCallback callback, long generation) {
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
            Translator translator, TranslatedBlock[] results,
            ResultCallback callback, long generation) {
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

    // -------------------------------------------------------------------------
    // Manajemen model bahasa (manual download / cek status / hapus)
    // -------------------------------------------------------------------------

    /**
     * Cek apakah model untuk bahasa tertentu sudah diunduh.
     * Target (Indonesia) juga dicek — keduanya diperlukan.
     */
    public static void isModelDownloaded(String languageCode, ModelCallback callback) {
        RemoteModelManager manager = RemoteModelManager.getInstance();
        TranslateRemoteModel model = new TranslateRemoteModel.Builder(languageCode).build();
        TranslateRemoteModel targetModel = new TranslateRemoteModel.Builder(TARGET_LANGUAGE).build();

        Task<Boolean> sourceTask = manager.isModelDownloaded(model);
        Task<Boolean> targetTask = manager.isModelDownloaded(targetModel);

        Tasks.whenAll(sourceTask, targetTask)
                .addOnSuccessListener(unused -> {
                    boolean sourceOk = Boolean.TRUE.equals(sourceTask.getResult());
                    boolean targetOk = Boolean.TRUE.equals(targetTask.getResult());
                    if (sourceOk && targetOk) {
                        callback.onSuccess();
                    } else {
                        callback.onFailure(new Exception("Model belum diunduh (source=" + sourceOk + ", target=" + targetOk + ")"));
                    }
                })
                .addOnFailureListener(callback::onFailure);
    }

    /**
     * Unduh model untuk satu bahasa sumber + model target (Indonesia)
     * jika belum tersedia.
     */
    public static void downloadModel(String languageCode, ModelCallback callback) {
        RemoteModelManager manager = RemoteModelManager.getInstance();
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        TranslateRemoteModel sourceModel = new TranslateRemoteModel.Builder(languageCode).build();
        TranslateRemoteModel targetModel = new TranslateRemoteModel.Builder(TARGET_LANGUAGE).build();

        callback.onProgress("Mengunduh model " + languageCode + "…");

        // Unduh source dulu, lalu target (bila perlu)
        manager.download(sourceModel, conditions)
                .addOnSuccessListener(unused -> {
                    callback.onProgress("Mengunduh model target (Indonesia)…");
                    manager.download(targetModel, conditions)
                            .addOnSuccessListener(u2 -> callback.onSuccess())
                            .addOnFailureListener(callback::onFailure);
                })
                .addOnFailureListener(callback::onFailure);
    }

    /**
     * Unduh SEMUA bahasa yang didukung secara berurutan.
     * Progress dilaporkan lewat {@link ModelCallback#onProgress}.
     */
    public static void downloadAllModels(ModelCallback callback) {
        List<LanguageInfo> list = SUPPORTED_SOURCE_LANGUAGES;
        downloadNext(list, 0, callback);
    }

    private static void downloadNext(List<LanguageInfo> list, int index, ModelCallback callback) {
        if (index >= list.size()) {
            // Pastikan model target juga ada
            downloadModel(TARGET_LANGUAGE, new ModelCallback() {
                @Override
                public void onSuccess() {
                    callback.onSuccess();
                }
                @Override
                public void onFailure(Exception e) {
                    // Target mungkin sudah ada; anggap sukses jika hanya ini yang gagal
                    callback.onSuccess();
                }
            });
            return;
        }

        LanguageInfo info = list.get(index);
        callback.onProgress("Mengunduh " + info.displayName + " (" + (index + 1) + "/" + list.size() + ")…");

        isModelDownloaded(info.code, new ModelCallback() {
            @Override
            public void onSuccess() {
                // Sudah ada, lanjut berikutnya
                downloadNext(list, index + 1, callback);
            }
            @Override
            public void onFailure(Exception e) {
                downloadModel(info.code, new ModelCallback() {
                    @Override
                    public void onSuccess() {
                        downloadNext(list, index + 1, callback);
                    }
                    @Override
                    public void onFailure(Exception e2) {
                        Log.e(TAG, "Gagal unduh model " + info.code, e2);
                        // Lanjut ke bahasa berikutnya meski gagal
                        downloadNext(list, index + 1, callback);
                    }
                    @Override
                    public void onProgress(String message) {
                        callback.onProgress(message);
                    }
                });
            }
        });
    }

    /**
     * Ambil daftar kode bahasa yang sudah diunduh.
     */
    public static void getDownloadedLanguageCodes(ModelCallbackWithList callback) {
        RemoteModelManager manager = RemoteModelManager.getInstance();
        manager.getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(models -> {
                    Set<String> codes = new HashSet<>();
                    for (TranslateRemoteModel m : models) {
                        codes.add(m.getLanguage());
                    }
                    callback.onSuccess(new ArrayList<>(codes));
                })
                .addOnFailureListener(callback::onFailure);
    }

    public interface ModelCallbackWithList {
        void onSuccess(List<String> languageCodes);
        void onFailure(Exception e);
    }

    /**
     * Hapus model bahasa tertentu (opsional, untuk menghemat ruang).
     */
    public static void deleteModel(String languageCode, ModelCallback callback) {
        if (TARGET_LANGUAGE.equals(languageCode)) {
            // Jangan hapus model target — dibutuhkan untuk semua terjemahan
            callback.onFailure(new Exception("Model bahasa target (Indonesia) tidak boleh dihapus"));
            return;
        }
        RemoteModelManager manager = RemoteModelManager.getInstance();
        TranslateRemoteModel model = new TranslateRemoteModel.Builder(languageCode).build();
        manager.deleteDownloadedModel(model)
                .addOnSuccessListener(unused -> callback.onSuccess())
                .addOnFailureListener(callback::onFailure);
    }
}
