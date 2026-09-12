package com.israfilx.circlesearch.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;


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
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
            new LanguageInfo(TranslateLanguage.TAGALOG, "Filipino / Tagalog"),
            new LanguageInfo(TranslateLanguage.BENGALI, "Bengali"),
            new LanguageInfo(TranslateLanguage.TAMIL, "Tamil"),
            new LanguageInfo(TranslateLanguage.TELUGU, "Telugu"),
            new LanguageInfo(TranslateLanguage.GUJARATI, "Gujarati"),
            new LanguageInfo(TranslateLanguage.KANNADA, "Kannada"),
            new LanguageInfo(TranslateLanguage.MARATHI, "Marathi"),
            new LanguageInfo(TranslateLanguage.URDU, "Urdu"),
            new LanguageInfo(TranslateLanguage.PERSIAN, "Persian (Farsi)"),
            new LanguageInfo(TranslateLanguage.HEBREW, "Hebrew (Ibrani)"),
            new LanguageInfo(TranslateLanguage.GREEK, "Greek (Yunani)"),
            new LanguageInfo(TranslateLanguage.CZECH, "Czech (Ceko)"),
            new LanguageInfo(TranslateLanguage.ROMANIAN, "Romanian (Rumania)"),
            new LanguageInfo(TranslateLanguage.HUNGARIAN, "Hungarian (Hungaria)"),
            new LanguageInfo(TranslateLanguage.SWEDISH, "Swedish (Swedia)"),
            new LanguageInfo(TranslateLanguage.FINNISH, "Finnish (Finlandia)"),
            new LanguageInfo(TranslateLanguage.DANISH, "Danish (Denmark)"),
            new LanguageInfo(TranslateLanguage.NORWEGIAN, "Norwegian (Norwegia)"),
            new LanguageInfo(TranslateLanguage.SLOVAK, "Slovak"),
            new LanguageInfo(TranslateLanguage.BULGARIAN, "Bulgarian (Bulgaria)"),
            new LanguageInfo(TranslateLanguage.CROATIAN, "Croatian (Kroasia)"),
            new LanguageInfo(TranslateLanguage.GEORGIAN, "Georgian (Georgia)")
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
     * Skor OCR (dari {@link #scoreOcrBlocks}) di bawah ini dianggap TERLALU
     * LEMAH untuk dipercaya sebagai konten sungguhan — biasanya 1 blok kecil
     * berisi noise UI (jam, ikon, badge, watermark) yang kebetulan
     * di-OCR/diklasifikasikan sebagai aksara non-Latin oleh salah satu
     * recognizer ML Kit.
     *
     * PERBAIKAN BUG — sebelum ada ambang ini, blok tunggal dengan skor
     * serendah 4-30 tetap "dipaksa" ke bahasa tertentu (mis. hi) hanya
     * karena mengandung 1-2 karakter aksara Devanagari palsu, menyebabkan
     * teks yang bukan bahasa itu sama sekali ikut ditranslate/dipaksa.
     * Lihat log: skor=14/22/28/48/52/56/58 jumlah blok=1 semuanya salah
     * dipaksa "hi" sebelum perbaikan ini.
     *
     * Dipakai untuk skrip yang RAWAN false-positive dari 1-2 karakter
     * (Devanagari dkk, dan aksara lain yang dideteksi murni dari rentang
     * Unicode tanpa OCR khusus). Skrip yang OCR-nya sendiri sudah punya
     * recognizer khusus dan routing ketat (CJK/Korean — lihat
     * {@link #MIN_OCR_SCORE_FOR_FORCED_LANGUAGE_CJK}) memakai ambang
     * yang jauh lebih rendah karena jauh lebih jarang salah.
     *
     * Di bawah ambang ini, translate TETAP dicoba tapi lewat jalur
     * LanguageIdentifier biasa (yang sudah disanitasi ketat), BUKAN
     * lewat forced-language dari aksara/skrip OCR.
     */
    private static final int MIN_OCR_SCORE_FOR_FORCED_LANGUAGE = 40;

    /**
     * Ambang lebih rendah khusus untuk hasil OCR CJK/Korean (recognizer
     * khusus ML Kit, bukan tebakan rentang Unicode generik). Recognizer
     * Chinese/Japanese/Korean ML Kit sudah cukup andal bahkan pada blok
     * pendek (lihat log: Korean skor=38 jumlah blok=1 adalah hasil valid,
     * bukan noise) — beda dengan Devanagari yang sering salah-klasifikasi
     * elemen UI kecil sebagai aksara Hindi.
     */
    private static final int MIN_OCR_SCORE_FOR_FORCED_LANGUAGE_CJK = 15;

    /**
     * Jumlah karakter aksara non-Latin minimum di dalam teks gabungan
     * sebelum {@link #detectScriptLanguage} boleh memaksa bahasa hanya
     * berdasarkan 1 karakter yang match suatu rentang Unicode. Menaikkan
     * ini dari efektif "1" mencegah 1 karakter noise/salah-OCR memaksa
     * seluruh blok diterjemahkan ke bahasa yang salah.
     */
    private static final int MIN_SCRIPT_CHARS_TO_FORCE_LANGUAGE = 3;
    /**
     * PERBAIKAN — ambang lebih ketat, dipakai {@link #detectScriptLanguage}
     * hanya ketika sumber teks berasal dari SATU blok OCR saja. Kasus blok
     * tunggal (mis. hasil crop kecil, atau 1 elemen UI yang lolos filter)
     * tidak punya blok lain sebagai "sanity check" — 3 karakter noise pada
     * blok tunggal jauh lebih mungkin murni salah-OCR dibanding 3 karakter
     * yang muncul di antara banyak blok konten lain yang valid.
     */
    private static final int MIN_SCRIPT_CHARS_TO_FORCE_LANGUAGE_SINGLE_BLOCK = 6;
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
        /**
         * PERBAIKAN — confidence asli OCR per baris/blok, dalam skala 0-100
         * (langsung dari {@code TessBaseAPI.confidence()} untuk hasil
         * Tesseract). -1 berarti tidak diketahui/tidak berlaku (mis. hasil
         * ML Kit, yang tidak mengekspos confidence per baris dengan cara
         * yang sama).
         *
         * Ini SINYAL YANG SUDAH ADA dari Tesseract tapi sebelumnya dibaca
         * lalu dibuang setelah filter per-baris — hasil scoring akhir
         * (scoreTranslatedBlocksForLang) hanya melihat PANJANG teks, buta
         * terhadap seberapa yakin Tesseract terhadap teks itu. Akibatnya,
         * halusinasi model LSTM (yang menghasilkan kata "valid" secara
         * struktur tapi dengan confidence rendah) tidak bisa dibedakan dari
         * bacaan asli yang confidence-nya tinggi — hanya lewat panjang teks.
         * Field ini mengalirkan sinyal itu ke keputusan akhir alih-alih
         * terus menambah aturan denylist manual untuk tiap kasus baru.
         */
        public final float ocrConfidence;

        public TranslatedBlock(String originalText, String translatedText, Rect boundingBox) {
            this(originalText, translatedText, boundingBox, -1f);
        }

        public TranslatedBlock(String originalText, String translatedText, Rect boundingBox, float ocrConfidence) {
            this.originalText = originalText;
            this.translatedText = translatedText;
            this.boundingBox = boundingBox;
            this.ocrConfidence = ocrConfidence;
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
    public static void recognizeAndTranslate(Context context, Bitmap bitmap, ResultCallback callback) {
        recognizeInternal(context, bitmap, callback, true);
    }

    /**
     * Jalankan OCR SAJA tanpa translate sama sekali — dipakai untuk fitur
     * "Salin Teks".
     */
    public static void recognizeTextOnly(Context context, Bitmap bitmap, ResultCallback callback) {
        recognizeInternal(context, bitmap, callback, false);
    }

    /**
     * OCR multi-skrip: jalankan Latin + Chinese + Japanese + Korean + Devanagari
     * secara paralel, lalu pilih hasil terbaik (paling banyak karakter signifikan).
     * Ini memperbaiki deteksi teks non-Latin (Cina, Jepang, Korea, Hindi, dll)
     * yang sebelumnya gagal karena hanya memakai TextRecognizer Latin.
     *
     * Catatan: Arab & Thai tidak punya model on-device gratis di ML Kit Text
     * Recognition saat ini — untuk bahasa tersebut OCR Latin biasanya gagal /
     * kosong. Vietnamese memakai aksara Latin (dengan diakritik) jadi seharusnya
     * terdeteksi oleh recognizer Latin.
     */
    private static void recognizeInternal(Context context, Bitmap bitmap, ResultCallback callback, boolean alsoTranslate) {
        final long myGeneration = requestGeneration.incrementAndGet();
        final InputImage image = InputImage.fromBitmap(bitmap, 0);

        // Daftar recognizer multi-skrip (Latin selalu dijalankan; lainnya untuk
        // skrip non-Latin). Semua dijalankan paralel, hasil terbaik dipilih.
        final TextRecognizer[] recognizers = new TextRecognizer[] {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
                TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build())
        };
        final String[] scriptNames = {"Latin", "Chinese", "Japanese", "Korean", "Devanagari"};

        final List<Text.TextBlock>[] results = new List[recognizers.length];
        final AtomicInteger remaining = new AtomicInteger(recognizers.length);
        final AtomicInteger anySuccess = new AtomicInteger(0);

        for (int i = 0; i < recognizers.length; i++) {
            final int idx = i;
            final TextRecognizer recognizer = recognizers[i];
            recognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        if (!isCurrent(myGeneration)) {
                            recognizer.close();
                            return;
                        }
                        List<Text.TextBlock> blocks = visionText.getTextBlocks();
                        results[idx] = blocks != null ? blocks : Collections.emptyList();
                        anySuccess.incrementAndGet();
                        Log.d(TAG, "OCR " + scriptNames[idx] + " selesai, blok=" + results[idx].size());
                        finishMultiOcrIfDone(context, recognizers, results, remaining, anySuccess,
                                callback, alsoTranslate, myGeneration, bitmap);
                    })
                    .addOnFailureListener(e -> {
                        if (!isCurrent(myGeneration)) {
                            recognizer.close();
                            return;
                        }
                        Log.w(TAG, "OCR " + scriptNames[idx] + " gagal: " + e.getMessage());
                        results[idx] = Collections.emptyList();
                        finishMultiOcrIfDone(context, recognizers, results, remaining, anySuccess,
                                callback, alsoTranslate, myGeneration, bitmap);
                    });
        }
    }

    private static void finishMultiOcrIfDone(
            final Context context,
            TextRecognizer[] recognizers,
            List<Text.TextBlock>[] results,
            AtomicInteger remaining,
            AtomicInteger anySuccess,
            final ResultCallback callback,
            final boolean alsoTranslate,
            final long generation,
            final Bitmap bitmap) {
        if (remaining.decrementAndGet() != 0) return;
        for (TextRecognizer r : recognizers) {
            try { r.close(); } catch (Exception ignored) {}
        }
        if (!isCurrent(generation)) return;

        if (anySuccess.get() == 0) {
            Log.e(TAG, "Semua OCR gagal");
            // Coba Tesseract sebagai last resort
            if (context != null && bitmap != null) {
                tryTesseractFallback(context, bitmap, alsoTranslate, callback, generation,
                        null, 0, "none");
                return;
            }
            callback.onError(new Exception("OCR gagal pada semua skrip"));
            return;
        }

        List<Text.TextBlock> bestBlocks = Collections.emptyList();
        int bestScore = -1;
        String bestScript = "none";
        for (int i = 0; i < results.length; i++) {
            List<Text.TextBlock> blocks = results[i];
            if (blocks == null || blocks.isEmpty()) continue;
            int score = scoreOcrBlocks(blocks);
            if (score > bestScore) {
                bestScore = score;
                bestBlocks = blocks;
                bestScript = new String[]{"Latin", "Chinese", "Japanese", "Korean", "Devanagari"}[i];
            }
        }

        Log.d(TAG, "OCR terbaik: skrip=" + bestScript + ", skor=" + bestScore
                + ", jumlah blok=" + (bestBlocks == null ? 0 : bestBlocks.size()));

        // === ROUTING DETERMINISTIK (bukan perang skor) ===
        // CJK/Korea/Jepang: ML Kit selalu menang jika ada hasil.
        // Latin: menang hanya jika teksnya benar-benar huruf Latin (bukan noise).
        // Devanagari kuat (>=40): ML Kit (Hindi dll).
        // Sisanya (lemah / none): boleh probe Tesseract untuk Arab/Thai.
        boolean useMlKitOnly = false;
        if (bestScore > 0 && bestScript != null) {
            if ("Chinese".equals(bestScript) || "Japanese".equals(bestScript)
                    || "Korean".equals(bestScript)) {
                useMlKitOnly = true;
            } else if ("Latin".equals(bestScript) && mlKitBlocksMostlyLatin(bestBlocks)) {
                // PERBAIKAN — jangan probe Tesseract sama sekali kalau ML Kit
                // Latin sudah jelas-jelas huruf Latin asli (bukan noise),
                // BERAPAPUN skornya. Sebelumnya syarat ini hanya menang atas
                // Tesseract secara skor di tryTesseractFallback, tapi
                // Tesseract tetap DIPANGGIL lebih dulu untuk kasus skor
                // Latin rendah (mis. blok pendek "Hello" / "OK Google").
                // Tesseract lalu ber-"halusinasi" teks Arab/Thai dari bitmap
                // yang di-scale-up agresif, dan pada beberapa kasus histori
                // log menang lawan ML Kit karena skor panjang teksnya
                // kebetulan lebih besar. Kalau ML Kit sudah pasti Latin
                // valid, Tesseract (yang hanya mengerti ara/tha) tidak
                // mungkin memberi jawaban yang lebih benar — jadi lewati.
                useMlKitOnly = true;
            } else if ("Devanagari".equals(bestScript) && bestScore >= 40) {
                useMlKitOnly = true;
            }
        }

        if (context != null && bitmap != null && !useMlKitOnly) {
            final List<Text.TextBlock> mlKitBlocks = bestBlocks;
            final int mlKitScore = bestScore;
            final String mlKitScript = bestScript;
            tryTesseractFallback(context, bitmap, alsoTranslate, callback, generation,
                    mlKitBlocks, mlKitScore, mlKitScript);
            return;
        }
        if (useMlKitOnly) {
            Log.d(TAG, "Routing: ML Kit only (" + bestScript + " skor=" + bestScore + ")");
        }

        if (bestBlocks == null || bestBlocks.isEmpty() || bestScore <= 0) {
            Log.d(TAG, "Tidak ada teks signifikan dari OCR");
            callback.onNoTextFound();
            return;
        }

        if (!alsoTranslate) {
            callback.onSuccess(toUntranslatedBlocks(bestBlocks));
            return;
        }
        detectLanguageAndTranslateBlocks(bestBlocks, callback, generation, bestScript, bestScore);
    }

    /**
     * Jalankan Tesseract (ara + tha) dan bandingkan dengan hasil ML Kit.
     * Prioritas: teks yang mengandung Unicode Arab/Thai > skor numerik.
     */
    private static void tryTesseractFallback(final Context context, final Bitmap bitmap,
            final boolean alsoTranslate, final ResultCallback callback, final long generation,
            final List<Text.TextBlock> mlKitBlocks, final int mlKitScore, final String mlKitScript) {
        ensureTessdata(context, new ModelCallback() {
            @Override
            public void onSuccess() {
                if (!isCurrent(generation)) return;
                List<TranslatedBlock> tessBlocks = runTesseractOcr(context, bitmap);
                int tessScore = scoreTranslatedBlocks(tessBlocks);
                boolean tessHasScript = containsArabicOrThai(tessBlocks);
                boolean mlKitHasScript = containsArabicOrThaiFromMlKit(mlKitBlocks);

                Log.d(TAG, "Perbandingan: MLKit skor=" + mlKitScore + " script=" + mlKitScript
                        + " hasArabThai=" + mlKitHasScript
                        + " | Tesseract skor=" + tessScore + " hasArabThai=" + tessHasScript);

                // Aturan ketat: Tesseract hanya jika ada aksara Arab/Thai nyata
                // DAN ML Kit tidak punya hasil Latin/CJK yang valid.
                boolean preferTess = false;
                int arabThaiChars = countArabicThaiChars(tessBlocks);
                // PERBAIKAN — naikkan syarat minimal huruf skrip Arab/Thai dari
                // 5 menjadi 8 KHUSUS bila hasil Tesseract cuma 1 blok. Blok
                // tunggal pendek adalah kasus paling rawan halusinasi Tesseract
                // (lihat log: "สวัสดิีตอนเย็น" / "คุณสบายดีไหม" muncul dari
                // screenshot yang kemungkinan bukan Thai sama sekali — kata
                // Thai umum yang "kebetulan" match pola visual noise setelah
                // bitmap di-scale-up 1.5x-3.5x). Kalimat Thai/Arab asli hampir
                // selalu menghasilkan >1 baris atau kata yang lebih panjang;
                // ambang lebih ketat untuk kasus blok=1 tidak mengorbankan
                // teks singkat asli (mis. sapaan) karena tetap dicek dominasi
                // skrip lewat scoreTranslatedBlocksForLang di langkah berikutnya.
                int minArabThaiChars = (tessBlocks != null && tessBlocks.size() <= 1) ? 8 : 5;
                // PERBAIKAN — selain jumlah total karakter skrip, syaratkan
                // juga ada rangkaian berurutan minimal 3 karakter (kata nyata),
                // bukan sekadar karakter skrip yang tersebar di antara noise.
                boolean hasRealWord = hasArabicOrThaiRun(tessBlocks, 3);
                // PERBAIKAN — tolak hasil yang cocok dengan frasa halusinasi
                // Tesseract yang sudah dikenal (lihat KNOWN_TESS_HALLUCINATION_ROOTS)
                // KHUSUS untuk kasus blok tunggal. Frasa umum ini terbukti dari
                // log lapangan muncul berulang dari screenshot yang berbeda-beda
                // dan kemungkinan besar tidak mengandung Thai/Arab sama sekali;
                // ambang panjang/rangkaian di atas tidak bisa menyaringnya karena
                // hasilnya secara struktur adalah kata yang sah, bukan garbage.
                // Multi-blok tidak kena aturan ini — di sana frasa umum ini bisa
                // saja memang bagian sah dari kalimat/percakapan lebih panjang.
                boolean isHallucination = (tessBlocks != null && tessBlocks.size() <= 1)
                        && isKnownTessHallucination(tessBlocks);
                // PERBAIKAN STRUKTURAL — gerbang confidence eksplisit,
                // menggantikan pendekatan "tambal tiap frasa halusinasi
                // baru satu per satu" yang tidak pernah benar-benar selesai.
                //
                // avgTessConfidence dihitung dari TessBaseAPI.confidence()
                // ASLI per baris (sinyal yang sudah dihasilkan Tesseract,
                // sebelumnya dibuang setelah filter garis — lihat javadoc
                // TranslatedBlock.ocrConfidence). Untuk kasus blok tunggal
                // (paling rawan halusinasi), disyaratkan confidence rata-rata
                // di atas ambang SOFT (28) — level yang sama dipakai untuk
                // memutuskan apakah sebuah baris "cukup yakin" di filter awal.
                // Ini general: menekan SEMUA halusinasi rendah-confidence,
                // dikenal atau belum, bukan hanya frasa yang sudah didaftar.
                float avgTessConfidence = averageConfidence(tessBlocks);
                boolean confidentEnough = (tessBlocks == null || tessBlocks.size() > 1)
                        || avgTessConfidence < 0f // tidak diketahui (jalur lama) -> jangan blokir
                        || avgTessConfidence >= MIN_TESS_LINE_CONFIDENCE_SOFT;
                boolean realArabThai = arabThaiChars >= minArabThaiChars && hasRealWord
                        && !isHallucination && confidentEnough;

                if (tessBlocks != null && !tessBlocks.isEmpty() && realArabThai) {
                    if (mlKitBlocks == null || mlKitBlocks.isEmpty() || mlKitScore <= 0
                            || "none".equals(mlKitScript)) {
                        preferTess = true;
                    } else if ("Latin".equals(mlKitScript)
                            || "Chinese".equals(mlKitScript)
                            || "Japanese".equals(mlKitScript)
                            || "Korean".equals(mlKitScript)) {
                        // JANGAN TIMPA — meskipun skor ML Kit rendah
                        preferTess = false;
                    } else if ("Devanagari".equals(mlKitScript) && mlKitScore < 40) {
                        preferTess = true;
                    } else if (mlKitScore < 15) {
                        preferTess = true;
                    }
                }

                Log.d(TAG, "preferTess=" + preferTess
                        + " arabThaiChars=" + arabThaiChars
                        + " minRequired=" + minArabThaiChars
                        + " hasRealWord=" + hasRealWord
                        + " isHallucination=" + isHallucination
                        + " avgTessConfidence=" + avgTessConfidence
                        + " confidentEnough=" + confidentEnough
                        + " mlKit=" + mlKitScript + "/" + mlKitScore);

                if (preferTess) {
                    Log.d(TAG, "Memakai hasil Tesseract");
                    if (!alsoTranslate) {
                        callback.onSuccess(tessBlocks);
                        return;
                    }
                    translateTranslatedBlocks(tessBlocks, callback, generation);
                    return;
                }

                if (mlKitBlocks == null || mlKitBlocks.isEmpty() || mlKitScore <= 0) {
                    if (tessBlocks != null && !tessBlocks.isEmpty()) {
                        if (!alsoTranslate) {
                            callback.onSuccess(tessBlocks);
                            return;
                        }
                        translateTranslatedBlocks(tessBlocks, callback, generation);
                        return;
                    }
                    callback.onNoTextFound();
                    return;
                }
                if (!alsoTranslate) {
                    callback.onSuccess(toUntranslatedBlocks(mlKitBlocks));
                    return;
                }
                detectLanguageAndTranslateBlocks(mlKitBlocks, callback, generation, mlKitScript, mlKitScore);
            }
            @Override
            public void onFailure(Exception e) {
                if (!isCurrent(generation)) return;
                Log.w(TAG, "Tesseract tidak tersedia: " + e.getMessage());
                if (mlKitBlocks == null || mlKitBlocks.isEmpty() || mlKitScore <= 0) {
                    callback.onNoTextFound();
                    return;
                }
                if (!alsoTranslate) {
                    callback.onSuccess(toUntranslatedBlocks(mlKitBlocks));
                    return;
                }
                detectLanguageAndTranslateBlocks(mlKitBlocks, callback, generation, mlKitScript, mlKitScore);
            }
            @Override
            public void onProgress(String message) {
                Log.d(TAG, "Tessdata: " + message);
            }
        });
    }

    /**
     * Rata-rata {@link TranslatedBlock#ocrConfidence} dari blok yang punya
     * nilai valid (>= 0). Mengembalikan -1 bila tidak ada satupun blok yang
     * punya confidence tercatat (mis. hasil dari jalur ML Kit, atau versi
     * lama sebelum field ini ada) — pemanggil HARUS menganggap -1 sebagai
     * "tidak diketahui", bukan "confidence nol/buruk".
     */
    private static float averageConfidence(List<TranslatedBlock> blocks) {
        if (blocks == null) return -1f;
        double sum = 0;
        int count = 0;
        for (TranslatedBlock b : blocks) {
            if (b != null && b.ocrConfidence >= 0f) {
                sum += b.ocrConfidence;
                count++;
            }
        }
        return count > 0 ? (float) (sum / count) : -1f;
    }

    private static boolean containsArabicOrThai(List<TranslatedBlock> blocks) {
        return countArabicThaiChars(blocks) >= 3;
    }

    /**
     * PERBAIKAN — validasi tambahan: apakah ada setidaknya satu rangkaian
     * karakter Arab/Thai BERURUTAN (boleh diselingi spasi) sepanjang minimal
     * {@code minRunLength}, alih-alih hanya menghitung total karakter skrip
     * yang tersebar di seluruh blok.
     *
     * Ini penting karena {@link #countArabicThaiChars} bisa lolos ambang
     * hanya dari beberapa karakter skrip yang terselip di antara banyak
     * noise/simbol acak (khas hasil Tesseract dari gambar yang sebenarnya
     * bukan Arab/Thai). Kalimat/kata Arab atau Thai ASLI hampir selalu
     * membentuk rangkaian huruf berurutan yang cukup panjang (kata nyata
     * jarang di bawah 3 huruf berturut-turut untuk Thai/Arab), sedangkan
     * halusinasi OCR pada noise cenderung menghasilkan karakter skrip yang
     * terputus-putus oleh simbol/tanda baca aneh.
     */
    private static boolean hasArabicOrThaiRun(List<TranslatedBlock> blocks, int minRunLength) {
        if (blocks == null) return false;
        for (TranslatedBlock b : blocks) {
            if (b == null || b.originalText == null) continue;
            String t = b.originalText;
            int run = 0;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                boolean isScriptChar = (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                        || (c >= 0x08A0 && c <= 0x08FF) || (c >= 0xFB50 && c <= 0xFDFF)
                        || (c >= 0xFE70 && c <= 0xFEFF) || (c >= 0x0E00 && c <= 0x0E7F);
                if (isScriptChar) {
                    run++;
                    if (run >= minRunLength) return true;
                } else if (!Character.isWhitespace(c)) {
                    // simbol/noise/karakter lain memutus rangkaian
                    run = 0;
                }
                // spasi tidak memutus rangkaian (kata bisa dipisah OCR jadi 2 token)
            }
        }
        return false;
    }

    /**
     * PERBAIKAN — daftar tolak (denylist) frasa Thai/Arab yang TERBUKTI dari
     * log lapangan berulang kali "dihalusinasikan" Tesseract dari screenshot
     * yang jelas-jelas berbeda konten (blok=1, MLKit skor Devanagari rendah
     * 12-34, dari sumber yang kemungkinan besar bukan Thai/Arab sama sekali).
     *
     * Root cause: model LSTM Tesseract-Thai/Arab cenderung "menormalkan"
     * pola visual noise generik (ikon kecil, watermark, karakter UI yang
     * di-scale-up 1.5x-3.5x) menjadi frasa umum yang sering muncul di data
     * training-nya — sapaan/basa-basi sehari-hari seperti "selamat sore"
     * atau "apa kabar". Ini BUKAN random garbage sehingga tidak tertangkap
     * oleh noiseRatioOf()/hasArabicOrThaiRun() (secara struktur, hasilnya
     * memang kata Thai/Arab yang valid dan rapi).
     *
     * Perbandingan dilakukan setelah menghapus spasi & tanda baca ringan
     * supaya variasi kecil (mis. "สวัสดิ" vs "สวัสดิี" vs "สวัสดิตอนเย็น")
     * tetap tertangkap sebagai variasi dari akar frasa yang sama.
     *
     * CATATAN PERAWATAN: bila false-positive baru muncul berulang di masa
     * depan (pola sama: blok=1, frasa umum, konsisten di banyak screenshot
     * tak berhubungan), tambahkan akar frasanya ke sini.
     */
    private static final String[] KNOWN_TESS_HALLUCINATION_ROOTS = new String[] {
            // PERBAIKAN — dipendekkan dari "สวัสดิ" ke "สวัสด" (buang 1
            // karakter vokal penutup). Log lapangan menunjukkan Tesseract
            // berhalusinasi frasa yang SAMA dengan ejaan vokal akhir yang
            // BERBEDA-BEDA antar kejadian ("สวัสดิ" dengan sara-i pendek
            // U+0E34 pada satu screenshot, "สวัสดี" dengan sara-i panjang
            // U+0E35 pada screenshot lain) — variasi ini wajar karena LSTM
            // Tesseract menghasilkan vokal akhir yang sedikit berbeda tiap
            // kali "menormalkan" noise yang berbeda ke frasa umum yang
            // sama. Root "สวัสด" (5 konsonan sebelum vokal yang bervariasi)
            // menangkap kedua varian tanpa perlu mendaftar tiap kombinasi
            // vokal satu per satu.
            "สวัสด",        // "selamat" (pagi/siang/sore/malam) — root umum halusinasi
            "คุณสบายดีไหม", // "apa kabar"
    };

    /**
     * True bila SATU-SATUNYA sinyal skrip nyata pada hasil Tesseract berasal
     * dari frasa yang sudah dikenal sebagai halusinasi (lihat
     * {@link #KNOWN_TESS_HALLUCINATION_ROOTS}). Hanya dipakai sebagai syarat
     * tambahan untuk kasus rawan (blok tunggal); tidak dipakai untuk menolak
     * teks panjang/multi-baris karena di sana frasa umum ini bisa saja
     * memang bagian sah dari kalimat lebih panjang.
     */
    private static boolean isKnownTessHallucination(List<TranslatedBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        for (TranslatedBlock b : blocks) {
            if (b == null || b.originalText == null) continue;
            sb.append(b.originalText.trim());
        }
        String combined = sb.toString();
        if (combined.isEmpty()) return false;
        for (String root : KNOWN_TESS_HALLUCINATION_ROOTS) {
            if (combined.contains(root)) return true;
        }
        return false;
    }

    /** True jika hasil ML Kit Latin didominasi huruf A–Z (bukan noise). */
    private static boolean mlKitBlocksMostlyLatin(List<Text.TextBlock> blocks) {
        if (blocks == null) return false;
        int latin = 0, other = 0;
        for (Text.TextBlock b : blocks) {
            if (b == null || b.getText() == null) continue;
            String t = b.getText();
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (Character.isWhitespace(c) || Character.isDigit(c)) continue;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= 0x00C0 && c <= 0x024F)) latin++;
                else other++;
            }
        }
        return latin >= 3 && latin >= other;
    }

    private static int countArabicThaiChars(List<TranslatedBlock> blocks) {
        if (blocks == null) return 0;
        int n = 0;
        for (TranslatedBlock b : blocks) {
            if (b == null || b.originalText == null) continue;
            n += countArabicThaiInString(b.originalText);
        }
        return n;
    }

    private static boolean containsArabicOrThaiFromMlKit(List<Text.TextBlock> blocks) {
        if (blocks == null) return false;
        int n = 0;
        for (Text.TextBlock b : blocks) {
            if (b == null || b.getText() == null) continue;
            n += countArabicThaiInString(b.getText());
            if (n >= 3) return true;
        }
        return n >= 3;
    }

    private static int countArabicThaiInString(String text) {
        if (text == null) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                    || (c >= 0x08A0 && c <= 0x08FF) || (c >= 0xFB50 && c <= 0xFDFF)
                    || (c >= 0xFE70 && c <= 0xFEFF) || (c >= 0x0E00 && c <= 0x0E7F)) {
                n++;
            }
        }
        return n;
    }

    private static boolean textHasArabicOrThai(String text) {
        return countArabicThaiInString(text) >= 1;
    }

    private static int scoreTranslatedBlocks(List<TranslatedBlock> blocks) {
        if (blocks == null) return 0;
        int score = 0;
        for (TranslatedBlock b : blocks) {
            if (b == null || b.originalText == null) continue;
            String t = b.originalText.trim();
            if (t.isEmpty()) continue;
            int len = t.length();
            if (textHasArabicOrThai(t)) len *= 4;
            Rect box = b.boundingBox;
            if (box != null && box.height() >= 16) score += len * 2;
            else score += len;
        }
        // PERBAIKAN BUG: skor ini menentukan tessScore yang dibandingkan
        // langsung dengan mlKitScore untuk memutuskan preferTess (lihat
        // tryTesseractFallback). Tanpa penalti noise, 1 baris garbage
        // panjang (mis. "‏اح‎" dicampur simbol acak) bisa mengalahkan
        // hasil ML Kit yang valid hanya karena panjang teksnya besar.
        double noiseRatio = noiseRatioOf(blocks);
        if (noiseRatio >= 0.35) {
            score = (int) (score * 0.15);
        } else if (noiseRatio >= 0.20) {
            score = (int) (score * 0.5);
        }
        return score;
    }

    /** Proporsi karakter "aneh" (bukan huruf/angka/tanda baca wajar/spasi). */
    private static double noiseRatioOf(List<TranslatedBlock> blocks) {
        if (blocks == null) return 0;
        int noise = 0, nonSpace = 0;
        for (TranslatedBlock b : blocks) {
            if (b == null || b.originalText == null) continue;
            String t = b.originalText;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (Character.isWhitespace(c)) continue;
                nonSpace++;
                boolean isScriptChar = (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                        || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)
                        || (c >= 0x0E00 && c <= 0x0E7F);
                if (isScriptChar || Character.isLetterOrDigit(c) || isBenignPunctuation(c)) continue;
                noise++;
            }
        }
        return nonSpace == 0 ? 0 : (double) noise / nonSpace;
    }

    private static File getTessdataDir(Context context) {
        File dir = new File(context.getFilesDir(), "tessdata");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Folder backup Tesseract — sengaja BUKAN di {@link Context#getFilesDir()}
     * (yang ikut terhapus saat uninstall/clear-data, dan itulah masalah yang
     * fitur ini pecahkan) melainkan di penyimpanan eksternal app-scoped
     * ({@link Context#getExternalFilesDir}). Folder ini:
     *  - TIDAK butuh permission storage (app-scoped sejak Android 4.4+),
     *  - bertahan lewat uninstall biasa pada banyak versi Android/vendor
     *    (walau TIDAK dijamin 100% oleh seluruh OEM — beberapa custom ROM
     *    ikut membersihkan folder ini juga; ini best-effort, bukan garansi),
     *  - bisa diakses lewat `adb pull`/`adb push` tanpa root untuk backup
     *    manual di luar app bila diperlukan saat debug.
     */
    private static File getTessdataBackupDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            // Penyimpanan eksternal tidak tersedia (mis. dicabut) — fallback
            // ke internal; lebih baik backup gagal-tapi-jelas daripada
            // NullPointerException di tengah proses.
            base = context.getFilesDir();
        }
        File dir = new File(base, "tessdata_backup");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * ara.traineddata "best" ~12MB, tha.traineddata "best" ~7–10MB.
     * Keduanya jauh lebih akurat dari varian "fast".
     */
    private static final long MIN_ARA_TRAINEDDATA_BYTES = 3_000_000L; // bedakan fast vs best
    private static final long MIN_THA_TRAINEDDATA_BYTES = 2_000_000L; // fast ~1MB, best lebih besar

    private static boolean isTessdataReady(Context context) {
        File dir = getTessdataDir(context);
        File ara = new File(dir, "ara.traineddata");
        File tha = new File(dir, "tha.traineddata");
        return ara.exists() && ara.length() >= MIN_ARA_TRAINEDDATA_BYTES
                && tha.exists() && tha.length() >= MIN_THA_TRAINEDDATA_BYTES;
    }

    /**
     * True bila backup lokal (di {@link #getTessdataBackupDir}) lengkap dan
     * valid (ukuran file sudah di atas ambang "best", bukan sisa versi
     * "fast" lama atau file korup/terpotong).
     */
    public static boolean isTessdataBackupAvailable(Context context) {
        File dir = getTessdataBackupDir(context);
        File ara = new File(dir, "ara.traineddata");
        File tha = new File(dir, "tha.traineddata");
        return ara.exists() && ara.length() >= MIN_ARA_TRAINEDDATA_BYTES
                && tha.exists() && tha.length() >= MIN_THA_TRAINEDDATA_BYTES;
    }

    /**
     * Salin ara.traineddata + tha.traineddata yang SUDAH terunduh (di
     * {@link #getTessdataDir}) ke folder backup app-scoped yang bertahan
     * lewat uninstall/clear-data (lihat javadoc {@link #getTessdataBackupDir}).
     *
     * Tidak melakukan apa pun lewat jaringan — murni copy file lokal ke
     * lokal, sehingga instan dan tidak butuh {@link ModelCallback#onProgress}
     * yang berarti (dipanggil sekali di awal/akhir saja untuk konsistensi
     * antarmuka dengan fungsi model lain).
     */
    public static void backupTessdata(Context context, ModelCallback callback) {
        new Thread(() -> {
            try {
                if (!isTessdataReady(context)) {
                    callback.onFailure(new Exception(
                            "Model Arab/Thai belum diunduh di app ini — tidak ada yang bisa di-backup"));
                    return;
                }
                callback.onProgress("Menyalin model Arab/Thai ke folder backup…");
                File src = getTessdataDir(context);
                File dst = getTessdataBackupDir(context);
                copyFile(new File(src, "ara.traineddata"), new File(dst, "ara.traineddata"));
                copyFile(new File(src, "tha.traineddata"), new File(dst, "tha.traineddata"));
                if (isTessdataBackupAvailable(context)) {
                    Log.d(TAG, "Backup tessdata berhasil ke " + dst.getAbsolutePath());
                    callback.onSuccess();
                } else {
                    callback.onFailure(new Exception("Backup selesai tapi file hasil tidak valid"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Gagal backup tessdata", e);
                callback.onFailure(e);
            }
        }).start();
    }

    /**
     * Salin balik ara.traineddata + tha.traineddata dari folder backup ke
     * lokasi aktif ({@link #getTessdataDir}) — dipakai supaya setelah
     * reinstall/clear-data untuk keperluan debug, Tesseract langsung siap
     * pakai tanpa mengunduh ulang ~19MB dari GitHub.
     */
    public static void restoreTessdata(Context context, ModelCallback callback) {
        new Thread(() -> {
            try {
                if (!isTessdataBackupAvailable(context)) {
                    callback.onFailure(new Exception(
                            "Tidak ada backup yang valid — lakukan backup dulu selagi model sudah terunduh"));
                    return;
                }
                callback.onProgress("Memulihkan model Arab/Thai dari backup…");
                File src = getTessdataBackupDir(context);
                File dst = getTessdataDir(context);
                copyFile(new File(src, "ara.traineddata"), new File(dst, "ara.traineddata"));
                copyFile(new File(src, "tha.traineddata"), new File(dst, "tha.traineddata"));
                if (isTessdataReady(context)) {
                    Log.d(TAG, "Restore tessdata berhasil dari " + src.getAbsolutePath());
                    callback.onSuccess();
                } else {
                    callback.onFailure(new Exception("Restore selesai tapi file hasil tidak valid"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Gagal restore tessdata", e);
                callback.onFailure(e);
            }
        }).start();
    }

    /** Copy file sederhana, buffer 8KB, menimpa tujuan bila sudah ada. */
    private static void copyFile(File src, File dst) throws Exception {
        if (!src.exists()) {
            throw new Exception("Sumber tidak ditemukan: " + src.getName());
        }
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(src));
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    public static void ensureTessdata(Context context, ModelCallback callback) {
        if (isTessdataReady(context)) {
            callback.onSuccess();
            return;
        }
        // PERBAIKAN — sebelum mengunduh dari GitHub (~19MB, butuh internet),
        // cek dulu apakah ada backup lokal valid dari instalasi sebelumnya
        // (lihat backupTessdata/restoreTessdata). Skenario utama: developer
        // clear-data/reinstall berulang saat debug — tanpa ini, setiap kali
        // harus menunggu unduhan ulang penuh walau modelnya sebenarnya masih
        // ada di penyimpanan eksternal app-scoped milik app ini.
        if (isTessdataBackupAvailable(context)) {
            Log.d(TAG, "Tessdata belum ada tapi backup lokal ditemukan, restore alih-alih unduh ulang");
            restoreTessdata(context, callback);
            return;
        }
        new Thread(() -> {
            try {
                File dir = getTessdataDir(context);
                File ara = new File(dir, "ara.traineddata");
                File tha = new File(dir, "tha.traineddata");

                // Upgrade: hapus model "fast" lama yang terlalu kecil/akurasi buruk
                if (ara.exists() && ara.length() < MIN_ARA_TRAINEDDATA_BYTES) {
                    Log.d(TAG, "Mengganti ara.traineddata fast (" + ara.length()
                            + " byte) dengan versi best…");
                    //noinspection ResultOfMethodCallIgnored
                    ara.delete();
                }
                if (tha.exists() && tha.length() < MIN_THA_TRAINEDDATA_BYTES) {
                    Log.d(TAG, "Mengganti tha.traineddata fast (" + tha.length()
                            + " byte) dengan versi best…");
                    //noinspection ResultOfMethodCallIgnored
                    tha.delete();
                }

                // Arab + Thai: tessdata_best
                String bestBase = "https://github.com/tesseract-ocr/tessdata_best/raw/main/";
                downloadFile(bestBase + "ara.traineddata", ara, callback,
                        "Arabic OCR (best, ~12MB)", MIN_ARA_TRAINEDDATA_BYTES);
                downloadFile(bestBase + "tha.traineddata", tha, callback,
                        "Thai OCR (best, ~7MB)", MIN_THA_TRAINEDDATA_BYTES);

                if (isTessdataReady(context)) {
                    callback.onSuccess();
                } else {
                    callback.onFailure(new Exception("Gagal mengunduh traineddata Arab/Thai"));
                }
            } catch (Exception e) {
                callback.onFailure(e);
            }
        }).start();
    }

    private static void downloadFile(String urlStr, File out, ModelCallback callback,
            String label, long minBytes) throws Exception {
        if (out.exists() && out.length() >= minBytes) return;
        if (out.exists()) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
        callback.onProgress("Mengunduh " + label + "…");
        Log.d(TAG, "Downloading " + label + " from " + urlStr);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(180000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CircleSearch/1.9");
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.flush();
            Log.d(TAG, "Downloaded " + out.getName() + " size=" + total);
        } finally {
            conn.disconnect();
        }
        if (out.length() < minBytes) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new Exception("File " + out.getName() + " terlalu kecil setelah unduh");
        }
    }

    private static List<TranslatedBlock> runTesseractOcr(Context context, Bitmap bitmap) {
        Bitmap work = prepareBitmapForTesseract(bitmap);
        List<TranslatedBlock> best = null;
        int bestScore = -1;
        String bestLang = null;

        // WAJIB coba SEMUA bahasa — jangan break lebih awal.
        // Bug sebelumnya: tha dicoba dulu, jika skor > 30 langsung stop,
        // sehingga teks Arab tidak pernah memakai model ara → hasil kacau.
        String[] langs = new String[] { "ara", "tha", "ara+tha" };
        for (String lang : langs) {
            List<TranslatedBlock> result = runTesseractWithLang(context, work, lang);
            if (result == null || result.isEmpty()) {
                Log.d(TAG, "Tesseract lang=" + lang + " skor=0 (kosong)");
                continue;
            }
            int sc = scoreTranslatedBlocksForLang(result, lang);
            String sample = sampleText(result, 60);
            float avgConf = averageConfidence(result);
            Log.d(TAG, "Tesseract lang=" + lang + " skor=" + sc
                    + " avgConf=" + avgConf
                    + " hasScript=" + containsArabicOrThai(result)
                    + " sample=[" + sample + "]");
            if (sc > bestScore) {
                bestScore = sc;
                best = result;
                bestLang = lang;
            }
        }
        Log.d(TAG, "Tesseract terbaik: lang=" + bestLang + " skor=" + bestScore);

        if (work != bitmap && work != null && !work.isRecycled()) {
            try { work.recycle(); } catch (Exception ignored) {}
        }
        return best;
    }

    /** Cuplikan teks untuk log (max n karakter). */
    private static String sampleText(List<TranslatedBlock> blocks, int maxLen) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (TranslatedBlock b : blocks) {
            if (b == null || b.originalText == null) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(b.originalText.trim());
            if (sb.length() >= maxLen) break;
        }
        String s = sb.toString();
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    /**
     * Skor disesuaikan bahasa model yang dipakai.
     * Model ara harus diunggulkan jika banyak karakter Arab;
     * model tha diunggulkan jika banyak karakter Thai.
     * Ini mencegah model tha "menang" saat membaca teks Arab.
     */
    private static int scoreTranslatedBlocksForLang(List<TranslatedBlock> blocks, String lang) {
        if (blocks == null) return 0;
        int arab = 0, thai = 0, other = 0, noise = 0, nonSpace = 0;
        for (TranslatedBlock b : blocks) {
            if (b == null || b.originalText == null) continue;
            String t = b.originalText;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (Character.isWhitespace(c)) continue;
                nonSpace++;
                if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                        || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) {
                    arab++;
                } else if (c >= 0x0E00 && c <= 0x0E7F) {
                    thai++;
                } else if (Character.isLetterOrDigit(c)) {
                    other++;
                } else if (!isBenignPunctuation(c)) {
                    // Simbol acak, tanda RTL/kontrol, replacement char, dsb.
                    // Sample garbage khas Tesseract mengandung banyak ini
                    // (mis. "‏‎", "|", "٠" tunggal di antara noise, dst).
                    noise++;
                }
            }
        }
        int scriptChars = arab + thai;
        if (scriptChars == 0 && other == 0) return 0;

        int score;
        if ("ara".equals(lang)) {
            // Model Arab: karakter Arab sangat berharga, Thai hampir tidak
            score = arab * 8 + thai * 1 + other * 1;
        } else if ("tha".equals(lang)) {
            score = thai * 8 + arab * 1 + other * 1;
        } else {
            // ara+tha: kedua skrip dihargai
            score = arab * 5 + thai * 5 + other * 1;
        }
        // Bonus jika dominan skrip sesuai model
        if ("ara".equals(lang) && arab > thai * 2 && arab >= 3) score += 50;
        if ("tha".equals(lang) && thai > arab * 2 && thai >= 3) score += 50;
        // Penalti kuat jika model salah skrip (mis. tha menghasilkan banyak Arab palsu, atau sebaliknya)
        if ("ara".equals(lang) && thai > arab) score = score / 4;
        if ("tha".equals(lang) && arab > thai) score = score / 4;

        // PERBAIKAN BUG — penalti kepadatan noise: Tesseract kadang
        // menghasilkan sample yang secara teknis punya banyak karakter
        // aksara Arab/Thai tapi sebenarnya garbage (mis. "رو اح م09ر"
        // atau baris penuh simbol RTL/kontrol tercampur). Bila proporsi
        // karakter "aneh" (bukan huruf/angka/tanda baca wajar) terlalu
        // tinggi dibanding total karakter non-spasi, skor dipotong tajam
        // supaya hasil semacam ini tidak "menang" lawan ML Kit hanya
        // karena panjang teksnya kebetulan besar.
        if (nonSpace > 0) {
            double noiseRatio = (double) noise / nonSpace;
            if (noiseRatio >= 0.35) {
                score = (int) (score * 0.15);
            } else if (noiseRatio >= 0.20) {
                score = (int) (score * 0.5);
            }
        }

        // PERBAIKAN STRUKTURAL — pengali berbasis confidence ASLI Tesseract
        // (TessBaseAPI.confidence() per baris), bukan lagi hanya panjang
        // teks / rasio noise karakter. Ini sinyal yang SUDAH DIHASILKAN
        // Tesseract sejak awal (lihat runTesseractWithLang, dipakai untuk
        // filter "Skip baris conf=...") tapi sebelumnya dibuang begitu
        // saja setelah lolos filter awal.
        //
        // Kenapa ini penting: halusinasi LSTM Tesseract (menghasilkan kata
        // yang valid secara struktur dari noise visual) hampir selalu
        // punya confidence LEBIH RENDAH daripada bacaan asli yang benar,
        // meski secara "bentuk" hasilnya sama-sama kata Thai/Arab yang sah.
        // Pendekatan lama (menambah aturan/denylist untuk tiap frasa
        // halusinasi yang ditemukan) tidak pernah "selesai" karena frasa
        // halusinasi baru bisa selalu muncul; confidence adalah sinyal
        // yang sudah general, bukan spesifik-per-kasus, sehingga menekan
        // SEMUA halusinasi rendah-confidence sekaligus, dikenal atau tidak.
        double confSum = 0;
        int confCount = 0;
        for (TranslatedBlock b : blocks) {
            if (b != null && b.ocrConfidence >= 0f) {
                confSum += b.ocrConfidence;
                confCount++;
            }
        }
        if (confCount > 0) {
            double avgConf = confSum / confCount;
            // Ambang dan faktor selaras dengan MIN_TESS_LINE_CONFIDENCE_HARD/SOFT
            // yang sudah dipakai untuk filter per-baris — di bawah confidence
            // "soft" (28), skor ditekan makin tajam makin rendah confidence-nya,
            // supaya hasil yang lolos filter garis tipis tetap kalah lawan
            // kandidat lain yang confidence-nya jauh lebih meyakinkan.
            if (avgConf < MIN_TESS_LINE_CONFIDENCE_SOFT) {
                // avgConf=12 (batas hard) -> faktor ~0.25; avgConf=28 (batas soft) -> faktor 1.0
                double factor = 0.25 + 0.75 * Math.max(0.0,
                        (avgConf - MIN_TESS_LINE_CONFIDENCE_HARD)
                                / (MIN_TESS_LINE_CONFIDENCE_SOFT - MIN_TESS_LINE_CONFIDENCE_HARD));
                factor = Math.max(0.25, Math.min(1.0, factor));
                score = (int) (score * factor);
            }
        }
        return score;
    }

    /** Tanda baca umum yang wajar muncul di teks nyata (jangan dihitung noise). */
    private static boolean isBenignPunctuation(char c) {
        switch (c) {
            case '.': case ',': case '!': case '?': case ':': case ';':
            case '-': case '_': case '\'': case '"': case '(': case ')':
            case '[': case ']': case '/': case '\\': case '%': case '&':
            case '+': case '=': case '@': case '#': case '*':
            case '،': // koma Arab
            case '؛': // titik koma Arab
            case '؟': // tanda tanya Arab
            case '๐': case '๑': case '๒': case '๓': case '๔':
            case '๕': case '๖': case '๗': case '๘': case '๙': // angka Thai
                return true;
            default:
                return false;
        }
    }

    private static Bitmap prepareBitmapForTesseract(Bitmap src) {
        if (src == null) return null;
        // Scale-up agresif: teks di screenshot HP sering terlalu kecil untuk Tesseract
        float scale = 1f;
        if (src.getWidth() < 800 || src.getHeight() < 400) {
            scale = Math.max(800f / Math.max(src.getWidth(), 1),
                    400f / Math.max(src.getHeight(), 1));
            scale = Math.min(Math.max(scale, 1.5f), 3.5f);
        } else if (src.getWidth() < 1200) {
            scale = 1.5f;
        }
        Bitmap scaled = src;
        boolean scaledOwned = false;
        if (scale > 1.05f) {
            int w = Math.round(src.getWidth() * scale);
            int h = Math.round(src.getHeight() * scale);
            scaled = Bitmap.createScaledBitmap(src, w, h, true);
            scaledOwned = true;
        }
        // Grayscale + contrast stretch: sangat membantu OCR Arab/Thai di screenshot
        int w = scaled.getWidth();
        int h = scaled.getHeight();
        int[] pixels = new int[w * h];
        scaled.getPixels(pixels, 0, w, 0, 0, w, h);
        int minG = 255, maxG = 0;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int g = ((p >> 16) & 0xFF) * 30 + ((p >> 8) & 0xFF) * 59 + (p & 0xFF) * 11;
            g /= 100;
            pixels[i] = g; // sementara simpan gray di channel
            if (g < minG) minG = g;
            if (g > maxG) maxG = g;
        }
        int range = Math.max(1, maxG - minG);
        for (int i = 0; i < pixels.length; i++) {
            int g = pixels[i];
            // stretch + sedikit boost kontras
            int v = (g - minG) * 255 / range;
            // threshold lembut: dorong ke hitam/putih agar stroke huruf lebih tajam
            if (v < 90) v = Math.max(0, v - 30);
            else if (v > 165) v = Math.min(255, v + 30);
            pixels[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        if (scaledOwned && scaled != src && !scaled.isRecycled()) {
            try { scaled.recycle(); } catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * Confidence filter longgar untuk Arab:
     * OCR Arab (terutama berharakat) sering conf 15–35 meski teksnya benar.
     * Filter ketat 40 membuat baris valid ikut dibuang (lihat log Skip baris conf=16…).
     */
    private static final float MIN_TESS_LINE_CONFIDENCE_HARD = 12f;
    private static final float MIN_TESS_LINE_CONFIDENCE_SOFT = 28f;

    private static List<TranslatedBlock> runTesseractWithLang(Context context, Bitmap bitmap, String lang) {
        TessBaseAPI tess = null;
        try {
            tess = new TessBaseAPI();
            String dataPath = context.getFilesDir().getAbsolutePath() + "/";
            boolean ok;
            try {
                ok = tess.init(dataPath, lang, TessBaseAPI.OEM_LSTM_ONLY);
            } catch (Throwable t) {
                ok = tess.init(dataPath, lang);
            }
            if (!ok) {
                Log.w(TAG, "TessBaseAPI.init gagal untuk " + lang);
                return null;
            }
            // Arab: SINGLE_BLOCK sering lebih stabil untuk paragraf;
            // Thai/campuran: AUTO
            if (lang != null && lang.startsWith("ara")) {
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK);
            } else {
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            }
            // Pertahankan spasi antar kata (penting untuk Arab)
            try {
                tess.setVariable("preserve_interword_spaces", "1");
            } catch (Throwable ignored) {}
            tess.setImage(bitmap);

            String fullText = tess.getUTF8Text();
            if (fullText == null) fullText = "";

            List<TranslatedBlock> blocks = new ArrayList<>();
            ResultIterator it = tess.getResultIterator();
            if (it != null) {
                try {
                    it.begin();
                    do {
                        String text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        if (text == null) continue;
                        text = text.trim();
                        if (text.isEmpty()) continue;
                        float conf = 0f;
                        try {
                            conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        } catch (Throwable ignored) {}
                        boolean hasScript = textHasArabicOrThai(text);
                        // Filter confidence:
                        // - conf sangat rendah (<12) → buang (noise murni)
                        // - conf rendah (12–28) → buang HANYA jika bukan aksara Arab/Thai
                        // - teks Arab/Thai dengan conf sedang tetap dipertahankan
                        if (conf > 0f && conf < MIN_TESS_LINE_CONFIDENCE_HARD) {
                            Log.d(TAG, "Skip baris conf=" + conf + " (hard) text=[" +
                                    (text.length() > 40 ? text.substring(0, 40) + "…" : text) + "]");
                            continue;
                        }
                        if (conf > 0f && conf < MIN_TESS_LINE_CONFIDENCE_SOFT && !hasScript) {
                            Log.d(TAG, "Skip baris conf=" + conf + " (soft, no script) text=[" +
                                    (text.length() > 40 ? text.substring(0, 40) + "…" : text) + "]");
                            continue;
                        }
                        if (isMostlyGarbageLine(text)) continue;
                        Rect rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        if (rect != null && rect.width() > 2 && rect.height() > 2) {
                            blocks.add(new TranslatedBlock(text, text, new Rect(rect), conf));
                        } else {
                            blocks.add(new TranslatedBlock(text, text,
                                    new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), conf));
                        }
                    } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE));
                } finally {
                    it.delete();
                }
            }

            if (blocks.isEmpty() && !fullText.trim().isEmpty()) {
                String cleaned = fullText.trim();
                if (!isMostlyGarbageLine(cleaned)) {
                    float meanConf = -1f;
                    try {
                        meanConf = tess.meanConfidence();
                    } catch (Throwable ignored) {}
                    blocks.add(new TranslatedBlock(cleaned, cleaned,
                            new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), meanConf));
                }
            }
            return blocks.isEmpty() ? null : blocks;
        } catch (Exception e) {
            Log.e(TAG, "runTesseractWithLang(" + lang + ") error", e);
            return null;
        } finally {
            if (tess != null) {
                try { tess.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean isMostlyGarbageLine(String text) {
        if (text == null || text.length() < 2) return true;
        int letters = 0;
        int arabicThai = 0;
        int weird = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            // Arab + harakat + presentation forms + Thai
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                    || (c >= 0x08A0 && c <= 0x08FF) || (c >= 0xFB50 && c <= 0xFDFF)
                    || (c >= 0xFE70 && c <= 0xFEFF) || (c >= 0x0E00 && c <= 0x0E7F)) {
                arabicThai++;
                letters++;
            } else if (Character.isLetter(c)) {
                letters++;
            } else if (c == 0xFFFD || (c > 0x024F && c < 0x0600)) {
                weird++;
            }
            // digit/punctuation biasa tidak dihitung weird
        }
        if (total == 0) return true;
        // Ada aksara Arab/Thai yang cukup → jangan anggap garbage
        if (arabicThai >= 2 || (arabicThai > 0 && arabicThai * 2 >= total)) return false;
        if (weird * 2 >= total) return true;
        if (letters * 3 < total) return true;
        return false;
    }

    /**
     * Deteksi bahasa + translate dari List&lt;TranslatedBlock&gt; (hasil Tesseract).
     * Mirip detectLanguageAndTranslateBlocks tapi inputnya sudah TranslatedBlock.
     */
    private static void translateTranslatedBlocks(
            List<TranslatedBlock> blocks, ResultCallback callback, long generation) {
        if (blocks == null || blocks.isEmpty()) {
            callback.onNoTextFound();
            return;
        }
        // Gabungkan teks untuk language ID (prioritas blok terpanjang)
        List<TranslatedBlock> sorted = new ArrayList<>(blocks);
        Collections.sort(sorted, (a, b) ->
                Integer.compare(b.originalText.trim().length(), a.originalText.trim().length()));
        StringBuilder sb = new StringBuilder();
        for (TranslatedBlock b : sorted) {
            sb.append(b.originalText).append("\n");
            if (sb.length() > 400) break;
        }
        String combined = sb.toString().trim();
        if (combined.isEmpty()) {
            callback.onSuccess(blocks);
            return;
        }

        // Paksa bahasa berdasarkan aksara Unicode — lebih andal daripada
        // language-id ML Kit yang sering gagal pada teks Arab/Thai pendek.
        String forced = detectScriptLanguage(combined, blocks.size());
        if (forced != null) {
            Log.d(TAG, "Bahasa dipaksa dari aksara: " + forced);
            if (TARGET_LANGUAGE.equals(forced)) {
                callback.onSuccess(blocks);
                return;
            }
            doTranslateOnTranslatedBlocks(blocks, forced, callback, generation);
            return;
        }

        LanguageIdentificationOptions options = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(LANGUAGE_CONFIDENCE_THRESHOLD)
                .build();
        LanguageIdentifier identifier = LanguageIdentification.getClient(options);
        identifier.identifyLanguage(combined)
                .addOnSuccessListener(langCode -> {
                    if (!isCurrent(generation)) return;
                    // PERBAIKAN BUG: sanitized == null harus SELALU berarti
                    // "kode ini tidak boleh dipakai apa adanya", terlepas dari
                    // apakah langCode mentahnya kebetulan ada di daftar
                    // isUnreliableLatinGuess() atau tidak. Sebelumnya, bila
                    // sanitized null TAPI langCode mentah tidak ada di daftar
                    // itu (mis. kode 2-huruf lain yang tidak dikenal), kode
                    // lanjut memakai langCode mentah tanpa validasi lebih
                    // lanjut. Sekarang null dari sanitasi selalu memicu fallback
                    // ke identifyPossibleLanguages.
                    String sanitized = sanitizeDetectedLanguage(combined, langCode);
                    boolean rejected = (sanitized == null) || "und".equals(sanitized);
                    if (!rejected) langCode = sanitized;
                    if (rejected) {
                        identifier.identifyPossibleLanguages(combined)
                                .addOnSuccessListener(cands -> {
                                    if (!isCurrent(generation)) return;
                                    String best = pickBestCandidate(cands);
                                    if (best != null) {
                                        String s2 = sanitizeDetectedLanguage(combined, best);
                                        best = s2; // null bila kandidat ini juga ditolak
                                    }
                                    if (best == null || "und".equals(best)) {
                                        callback.onSuccess(blocks);
                                        return;
                                    }
                                    doTranslateOnTranslatedBlocks(blocks, best, callback, generation);
                                })
                                .addOnFailureListener(e -> {
                                    if (isCurrent(generation)) callback.onSuccess(blocks);
                                });
                        return;
                    }
                    if (TARGET_LANGUAGE.equals(langCode)) {
                        callback.onSuccess(blocks);
                        return;
                    }
                    doTranslateOnTranslatedBlocks(blocks, langCode, callback, generation);
                })
                .addOnFailureListener(e -> {
                    if (isCurrent(generation)) callback.onSuccess(blocks);
                });
    }

    /**
     * Deteksi bahasa dari rentang Unicode.
     * Lebih andal daripada LanguageIdentifier untuk skrip non-Latin.
     */
    private static String detectScriptLanguage(String text) {
        return detectScriptLanguage(text, Integer.MAX_VALUE);
    }

    /**
     * @param blockCount jumlah blok OCR sumber yang digabung jadi {@code text}.
     *        Bila hanya 1 (atau tidak diketahui → panggil overload tanpa
     *        parameter ini, yang memakai Integer.MAX_VALUE / selalu longgar),
     *        ambang jumlah karakter aksara yang dibutuhkan untuk memaksa
     *        bahasa dinaikkan (lihat requiredScriptChars di bawah).
     */
    private static String detectScriptLanguage(String text, int blockCount) {
        if (text == null || text.isEmpty()) return null;
        int thai = 0, arab = 0, hangul = 0, kana = 0, cjk = 0, deva = 0;
        int cyril = 0, greek = 0, hebrew = 0, beng = 0, tamil = 0, telugu = 0;
        int gujarati = 0, kannada = 0, georgian = 0, latinExt = 0, latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isDigit(c)) continue;
            if (c >= 0x0E00 && c <= 0x0E7F) thai++;
            else if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                    || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) arab++;
            else if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x1100 && c <= 0x11FF)) hangul++;
            else if (c >= 0x3040 && c <= 0x30FF) kana++;
            else if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else if (c >= 0x0900 && c <= 0x097F) deva++;
            else if (c >= 0x0400 && c <= 0x04FF) cyril++;
            else if (c >= 0x0370 && c <= 0x03FF) greek++;
            else if (c >= 0x0590 && c <= 0x05FF) hebrew++;
            else if (c >= 0x0980 && c <= 0x09FF) beng++;
            else if (c >= 0x0B80 && c <= 0x0BFF) tamil++;
            else if (c >= 0x0C00 && c <= 0x0C7F) telugu++;
            else if (c >= 0x0A80 && c <= 0x0AFF) gujarati++;
            else if (c >= 0x0C80 && c <= 0x0CFF) kannada++;
            else if (c >= 0x10A0 && c <= 0x10FF) georgian++;
            else if ((c >= 0x00C0 && c <= 0x024F) || (c >= 0x1E00 && c <= 0x1EFF)) latinExt++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
        }

        // Skrip non-Latin yang spesifik — prioritaskan yang paling dominan
        int best = 0;
        String lang = null;
        if (thai > best) { best = thai; lang = TranslateLanguage.THAI; }
        if (arab > best) { best = arab; lang = TranslateLanguage.ARABIC; }
        if (hangul > best) { best = hangul; lang = TranslateLanguage.KOREAN; }
        if (kana > best) { best = kana; lang = TranslateLanguage.JAPANESE; }
        if (deva > best) { best = deva; lang = TranslateLanguage.HINDI; }
        if (cyril > best) { best = cyril; lang = TranslateLanguage.RUSSIAN; }
        if (greek > best) { best = greek; lang = TranslateLanguage.GREEK; }
        if (hebrew > best) { best = hebrew; lang = TranslateLanguage.HEBREW; }
        if (beng > best) { best = beng; lang = TranslateLanguage.BENGALI; }
        if (tamil > best) { best = tamil; lang = TranslateLanguage.TAMIL; }
        if (telugu > best) { best = telugu; lang = TranslateLanguage.TELUGU; }
        if (gujarati > best) { best = gujarati; lang = TranslateLanguage.GUJARATI; }
        if (kannada > best) { best = kannada; lang = TranslateLanguage.KANNADA; }
        if (georgian > best) { best = georgian; lang = TranslateLanguage.GEORGIAN; }

        // PERBAIKAN — ambang jumlah karakter aksara yang dibutuhkan untuk
        // memaksa bahasa dinaikkan KHUSUS bila sumbernya cuma 1 blok OCR.
        // Blok tunggal (biasanya hasil crop kecil / 1 elemen UI) jauh lebih
        // rawan salah-klasifikasi Unicode dibanding gabungan banyak blok
        // (mis. mode 1-layar penuh), karena tidak ada blok lain yang bisa
        // "menenggelamkan" 1-2 karakter noise. Lihat histori log: blok=1
        // dengan skor OCR rendah berulang kali salah dipaksa ke bahasa yang
        // salah walau sudah lolos ambang MIN_SCRIPT_CHARS_TO_FORCE_LANGUAGE.
        int requiredScriptChars = (blockCount <= 1)
                ? MIN_SCRIPT_CHARS_TO_FORCE_LANGUAGE_SINGLE_BLOCK
                : MIN_SCRIPT_CHARS_TO_FORCE_LANGUAGE;

        // CJK: kana menang → Jepang; kalau hanya Hanzi → Cina
        if (kana >= 2) return TranslateLanguage.JAPANESE;
        if (cjk >= 3 && cjk >= best) return TranslateLanguage.CHINESE;
        if (best >= requiredScriptChars) return lang;
        // PERBAIKAN BUG: sebelumnya "best >= 1" cukup untuk memaksa bahasa
        // asal jumlah huruf Latin di sekitarnya tidak lebih dari 2x lipatnya.
        // Ini membuat 1 karakter aksara non-Latin yang salah-OCR (noise UI,
        // ikon, watermark) memaksa SELURUH blok diterjemahkan ke bahasa
        // yang salah (lihat log: skor OCR serendah 4-30 dengan 1 blok tetap
        // dipaksa "hi"). Sekarang minimal requiredScriptChars karakter aksara
        // sebelum dipaksa; di bawah itu, biarkan pipeline jatuh ke
        // LanguageIdentifier biasa yang sudah disanitasi ketat.
        if (cjk >= 1 && cjk >= requiredScriptChars) return TranslateLanguage.CHINESE;
        return null;
    }

    /** Map nama skrip OCR ML Kit → kode bahasa Translate. */
    private static String languageFromOcrScript(String script) {
        if (script == null) return null;
        switch (script) {
            case "Chinese": return TranslateLanguage.CHINESE;
            case "Japanese": return TranslateLanguage.JAPANESE;
            case "Korean": return TranslateLanguage.KOREAN;
            case "Devanagari": return TranslateLanguage.HINDI;
            default: return null;
        }
    }

    /**
     * Validasi hasil LanguageIdentifier terhadap isi teks.
     * Menolak hasil absurd (mis. teks Cina → "ca", teks Cyrillic → "ig").
     */
    /**
     * Samakan kode Language Identifier dengan kode TranslateLanguage ML Kit.
     * Contoh: fil→tl, iw→he, zh-cn→zh, nb→no.
     */
    private static String normalizeLanguageCode(String code) {
        if (code == null || code.isEmpty() || "und".equals(code)) return null;
        String c = code.trim().toLowerCase(java.util.Locale.US);
        // Buang region/script: zh-cn, zh-Hans, pt-BR → ambil bagian depan
        int dash = c.indexOf('-');
        if (dash > 0) {
            String base = c.substring(0, dash);
            String rest = c.substring(dash + 1);
            // khusus Cina
            if ("zh".equals(base)) return TranslateLanguage.CHINESE;
            // pt-BR / pt-PT
            if ("pt".equals(base)) return TranslateLanguage.PORTUGUESE;
            c = base;
        }
        switch (c) {
            case "fil": // Language ID
            case "tl":  // Translate
            case "tgl":
            case "ceb": // Cebuano → Tagalog (paling dekat yang didukung)
            case "ilo": // Ilokano
            case "war": // Waray
            case "pam": // Kapampangan
            case "bik": // Bikol
            case "hil": // Hiligaynon
                return TranslateLanguage.TAGALOG;
            case "iw": // kode lama Ibrani
                return TranslateLanguage.HEBREW;
            case "he":
                return TranslateLanguage.HEBREW;
            case "nb": // Bokmål
            case "nn": // Nynorsk
            case "no":
                return TranslateLanguage.NORWEGIAN;
            case "jw": // kadang muncul untuk Jawa — tidak didukung; jangan pakai
                return null;
            case "in": // kode lama Indonesia
                return TranslateLanguage.INDONESIAN;
            case "fa":
                return TranslateLanguage.PERSIAN;
            case "ur":
                return TranslateLanguage.URDU;
            case "uk":
                return TranslateLanguage.UKRAINIAN;
            case "vi":
                return TranslateLanguage.VIETNAMESE;
            case "es":
                return TranslateLanguage.SPANISH;
            case "tr":
                return TranslateLanguage.TURKISH;
            case "ms":
            case "msa":
                return TranslateLanguage.MALAY;
            case "id":
                return TranslateLanguage.INDONESIAN;
            case "en":
                return TranslateLanguage.ENGLISH;
            case "fr":
                return TranslateLanguage.FRENCH;
            case "de":
                return TranslateLanguage.GERMAN;
            case "it":
                return TranslateLanguage.ITALIAN;
            case "nl":
                return TranslateLanguage.DUTCH;
            case "pl":
                return TranslateLanguage.POLISH;
            case "ru":
                return TranslateLanguage.RUSSIAN;
            case "ar":
                return TranslateLanguage.ARABIC;
            case "th":
                return TranslateLanguage.THAI;
            case "hi":
                return TranslateLanguage.HINDI;
            case "ja":
                return TranslateLanguage.JAPANESE;
            case "ko":
                return TranslateLanguage.KOREAN;
            case "zh":
                return TranslateLanguage.CHINESE;
            case "pt":
                return TranslateLanguage.PORTUGUESE;
            case "bn":
                return TranslateLanguage.BENGALI;
            case "ta":
                return TranslateLanguage.TAMIL;
            case "te":
                return TranslateLanguage.TELUGU;
            case "gu":
                return TranslateLanguage.GUJARATI;
            case "kn":
                return TranslateLanguage.KANNADA;
            case "mr":
                return TranslateLanguage.MARATHI;
            case "el":
                return TranslateLanguage.GREEK;
            case "cs":
                return TranslateLanguage.CZECH;
            case "ro":
                return TranslateLanguage.ROMANIAN;
            case "hu":
                return TranslateLanguage.HUNGARIAN;
            case "sv":
                return TranslateLanguage.SWEDISH;
            case "fi":
                return TranslateLanguage.FINNISH;
            case "da":
                return TranslateLanguage.DANISH;
            case "sk":
                return TranslateLanguage.SLOVAK;
            case "bg":
                return TranslateLanguage.BULGARIAN;
            case "hr":
                return TranslateLanguage.CROATIAN;
            case "ka":
                return TranslateLanguage.GEORGIAN;
            default:
                // Kode tidak dikenal / tidak didukung Translate ML Kit (ha, yo, mg, …)
                return null;
        }
    }

    /** True jika kode bahasa didukung model Translate ML Kit yang kita pakai. */
    private static boolean isSupportedTranslateLanguage(String code) {
        if (code == null) return false;
        switch (code) {
            case "en": case "zh": case "ja": case "ko": case "ar": case "es":
            case "fr": case "de": case "pt": case "ru": case "th": case "vi":
            case "hi": case "tr": case "it": case "nl": case "pl": case "uk":
            case "ms": case "tl": case "bn": case "ta": case "te": case "gu":
            case "kn": case "mr": case "ur": case "fa": case "he": case "el":
            case "cs": case "ro": case "hu": case "sv": case "fi": case "da":
            case "no": case "sk": case "bg": case "hr": case "ka": case "id":
                return true;
            default:
                return false;
        }
    }

    private static String sanitizeDetectedLanguage(String text, String detected) {
        if (detected == null || "und".equals(detected)) return null;
        detected = normalizeLanguageCode(detected);
        if (detected == null) return null;
        String fromScript = detectScriptLanguage(text);
        if (fromScript != null) {
            // Skrip non-Latin menang mutlak atas LanguageIdentifier
            if (!fromScript.equals(detected)) {
                Log.d(TAG, "LanguageIdentifier='" + detected
                        + "' ditolak, pakai skrip='" + fromScript + "'");
            }
            return fromScript;
        }
        // Latin: tolak deteksi tidak andal ATAU tidak didukung Translate
        if (isUnreliableLatinGuess(detected) || !isSupportedTranslateLanguage(detected)) {
            int latinLetters = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latinLetters++;
            }
            if (latinLetters >= 6) {
                Log.d(TAG, "LanguageIdentifier='" + detected
                        + "' tidak didukung/andal, pakai en");
                return TranslateLanguage.ENGLISH;
            }
            return null;
        }
        return detected;
    }

    /** Kode yang sering salah deteksi ML Kit pada teks UI / campuran. */
    private static boolean isUnreliableLatinGuess(String code) {
        if (code == null) return true;
        switch (code) {
            case "ca": // Catalan
            case "gl": // Galician
            case "eo": // Esperanto
            case "ht": // Haitian
            case "ig": // Igbo
            case "ga": // Irish
            case "cy": // Welsh
            case "sq": // Albanian
            case "mt": // Maltese
            case "is": // Icelandic
            case "af": // Afrikaans
            case "sw": // Swahili
            case "ha": // Hausa
            case "yo": // Yoruba
            case "mg": // Malagasy
            case "so": // Somali
            case "zu": // Zulu
            case "xh": // Xhosa
            case "ny": // Chichewa
            case "sn": // Shona
            case "st": // Sesotho
            case "tn": // Tswana
            case "lg": // Ganda
            case "rw": // Kinyarwanda
                return true;
            default:
                return false;
        }
    }

    private static void doTranslateOnTranslatedBlocks(
            List<TranslatedBlock> blocks, String sourceLang,
            ResultCallback callback, long generation) {
        String norm = normalizeLanguageCode(sourceLang);
        final String lang = (norm != null) ? norm : sourceLang;
        isModelDownloaded(lang, new ModelCallback() {
            @Override
            public void onSuccess() {
                if (!isCurrent(generation)) return;
                TranslatorOptions opts = new TranslatorOptions.Builder()
                        .setSourceLanguage(lang)
                        .setTargetLanguage(TARGET_LANGUAGE)
                        .build();
                Translator translator = Translation.getClient(opts);
                TranslatedBlock[] results = new TranslatedBlock[blocks.size()];
                AtomicInteger remaining = new AtomicInteger(blocks.size());
                for (int i = 0; i < blocks.size(); i++) {
                    final int idx = i;
                    TranslatedBlock b = blocks.get(i);
                    translator.translate(b.originalText)
                            .addOnSuccessListener(translated -> {
                                results[idx] = new TranslatedBlock(b.originalText, translated, b.boundingBox, b.ocrConfidence);
                                if (remaining.decrementAndGet() == 0) {
                                    translator.close();
                                    if (isCurrent(generation)) {
                                        List<TranslatedBlock> list = new ArrayList<>();
                                        for (TranslatedBlock r : results) if (r != null) list.add(r);
                                        callback.onSuccess(list);
                                    }
                                }
                            })
                            .addOnFailureListener(e -> {
                                results[idx] = b; // keep original
                                if (remaining.decrementAndGet() == 0) {
                                    translator.close();
                                    if (isCurrent(generation)) {
                                        List<TranslatedBlock> list = new ArrayList<>();
                                        for (TranslatedBlock r : results) if (r != null) list.add(r);
                                        callback.onSuccess(list);
                                    }
                                }
                            });
                }
            }
            @Override
            public void onFailure(Exception e) {
                if (!isCurrent(generation)) return;
                Log.w(TAG, "Model " + lang + " belum diunduh untuk hasil Tesseract");
                callback.onModelNotDownloaded(lang);
                callback.onSuccess(blocks);
            }
        });
    }

    /** Skor sederhana: jumlah karakter dari blok yang lolos filter tinggi/panjang. */
    private static int scoreOcrBlocks(List<Text.TextBlock> blocks) {
        int score = 0;
        for (Text.TextBlock block : blocks) {
            Rect box = block.getBoundingBox();
            String text = block.getText();
            if (box == null || text == null) continue;
            String trimmed = text.trim();
            if (trimmed.isEmpty()) continue;
            // Beri bobot lebih ke blok yang cukup tinggi (bukan status bar kecil)
            if (box.height() >= 20) {
                score += trimmed.length() * 2;
            } else {
                score += trimmed.length();
            }
        }
        return score;
    }

    private static boolean isCurrent(long generation) {
        return requestGeneration.get() == generation;
    }

    private static void detectLanguageAndTranslateBlocks(
            List<Text.TextBlock> textBlocks, ResultCallback callback, long generation) {
        detectLanguageAndTranslateBlocks(textBlocks, callback, generation, null, Integer.MAX_VALUE);
    }

    private static void detectLanguageAndTranslateBlocks(
            List<Text.TextBlock> textBlocks, ResultCallback callback, long generation,
            String ocrScript, int ocrScore) {
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

        // Paksa bahasa dari aksara Unicode / skrip OCR (CJK, Hindi, Arab, Thai).
        // LanguageIdentifier sering salah (und/ca/ig) untuk teks non-Latin.
        // blockCount dari blocksForLangDetect (bukan textBlocks mentah) —
        // itulah himpunan blok yang benar-benar dipakai membentuk 'combined'.
        String forcedLang = detectScriptLanguage(combined.toString(), blocksForLangDetect.size());
        String fromOcr = languageFromOcrScript(ocrScript);
        // Devanagari OCR sering salah diklasifikasi Unicode sebagai Bengali.
        // Percayai skrip OCR Devanagari → Hindi, kecuali teks jelas Bengali murni.
        if ("Devanagari".equals(ocrScript) && fromOcr != null) {
            if (forcedLang == null
                    || TranslateLanguage.BENGALI.equals(forcedLang)
                    || TranslateLanguage.HINDI.equals(forcedLang)
                    || TranslateLanguage.MARATHI.equals(forcedLang)) {
                forcedLang = fromOcr; // hi
            }
        }
        if (forcedLang == null) {
            forcedLang = fromOcr;
        }
        // PERBAIKAN BUG: sebelumnya forcedLang langsung dipakai tanpa
        // melihat seberapa kuat sinyal OCR-nya. Blok tunggal kecil
        // (noise UI: jam, ikon, badge) dengan skor rendah tapi kebetulan
        // diklasifikasikan sebagai aksara non-Latin oleh salah satu
        // recognizer akan memaksa translate ke bahasa yang salah.
        // Sekarang forced-language hanya dipakai bila skor OCR blok
        // sumber cukup meyakinkan. Ambang lebih rendah untuk CJK/Korean
        // (recognizer khusus, jarang salah) dibanding skrip lain seperti
        // Devanagari (sering salah-klasifikasi elemen UI kecil).
        boolean isCjkScript = "Chinese".equals(ocrScript) || "Japanese".equals(ocrScript)
                || "Korean".equals(ocrScript);
        int requiredScore = isCjkScript
                ? MIN_OCR_SCORE_FOR_FORCED_LANGUAGE_CJK
                : MIN_OCR_SCORE_FOR_FORCED_LANGUAGE;
        if (forcedLang != null && ocrScore >= requiredScore) {
            Log.d(TAG, "Bahasa dipaksa dari skrip/OCR: " + forcedLang
                    + " (ocrScript=" + ocrScript + ", ocrScore=" + ocrScore + ")");
            if (TARGET_LANGUAGE.equals(forcedLang)) {
                callback.onSuccess(toUntranslatedBlocks(textBlocks));
                return;
            }
            translateBlocksIfModelAvailable(textBlocks, forcedLang, callback, generation);
            return;
        } else if (forcedLang != null) {
            Log.d(TAG, "Bahasa TIDAK dipaksa meski skrip=" + forcedLang
                    + " (ocrScript=" + ocrScript + ", ocrScore=" + ocrScore
                    + " < ambang " + requiredScore
                    + "), turun ke LanguageIdentifier");
        }

        LanguageIdentificationOptions options = new LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(LANGUAGE_CONFIDENCE_THRESHOLD)
                .build();
        LanguageIdentifier identifier = LanguageIdentification.getClient(options);
        final String combinedText = combined.toString();
        identifier.identifyLanguage(combinedText)
                .addOnSuccessListener(languageCode -> {
                    if (!isCurrent(generation)) return;
                    Log.d(TAG, "Bahasa terdeteksi (gabungan): " + languageCode);

                    String sanitized = sanitizeDetectedLanguage(combinedText, languageCode);
                    // PERBAIKAN BUG: sanitizeDetectedLanguage() mengembalikan null
                    // untuk DUA alasan berbeda — (a) kode tidak dikenal/tidak
                    // didukung ML Kit Translate sama sekali, atau (b) kode
                    // "unreliable" (mis. ha, ceb, sw) dengan sinyal Latin yang
                    // terlalu lemah (<6 huruf) untuk dipercaya sebagai Inggris.
                    // Versi lama HANYA jatuh ke fallback bila kondisi tambahan
                    // isUnreliableLatinGuess(languageCode) juga true — tapi
                    // languageCode di titik itu adalah kode MENTAH sebelum
                    // dinormalisasi, jadi kode yang tidak dikenal sama sekali
                    // (bukan salah satu string di daftar isUnreliableLatinGuess)
                    // lolos begitu saja dan dipakai apa adanya untuk translate.
                    // Sekarang: null dari sanitasi SELALU berarti "jangan pakai
                    // kode ini apa adanya" → selalu turun ke fallback.
                    if (sanitized != null) {
                        languageCode = sanitized;
                        Log.d(TAG, "Bahasa setelah sanitasi: " + languageCode);
                    } else {
                        Log.d(TAG, "Bahasa '" + languageCode + "' ditolak sanitasi, coba fallback");
                        detectLanguageFromLongestBlockFallback(sortedForDetect, textBlocks, callback, generation);
                        return;
                    }

                    if ("und".equals(languageCode)) {
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
            if (isUnreliableLatinGuess(code)) continue;
            if (candidate.getConfidence() > bestConfidence) {
                bestConfidence = candidate.getConfidence();
                best = code;
            }
        }
        // Jika semua kandidat "tidak andal", ambil yang tertinggi meski unreliable
        // (akan disanitasi di finishLanguageDetected)
        if (best == null) {
            for (IdentifiedLanguage candidate : candidates) {
                String code = candidate.getLanguageTag();
                if ("und".equals(code)) continue;
                if (candidate.getConfidence() > bestConfidence) {
                    bestConfidence = candidate.getConfidence();
                    best = code;
                }
            }
        }
        return best;
    }

    /** Lanjutkan ke translate (atau tampilkan asli bila bahasa terdeteksi = target). */
    private static void finishLanguageDetected(
            String languageCode, List<Text.TextBlock> allBlocks,
            ResultCallback callback, long generation) {
        finishLanguageDetected(languageCode, allBlocks, callback, generation, null);
    }

    private static void finishLanguageDetected(
            String languageCode, List<Text.TextBlock> allBlocks,
            ResultCallback callback, long generation, String sampleText) {
        // PERBAIKAN BUG: null dari sanitizeDetectedLanguage() SELALU berarti
        // "tolak", baik karena kode ada di daftar isUnreliableLatinGuess()
        // maupun karena kode itu sama sekali tidak dikenal oleh
        // normalizeLanguageCode()/isSupportedTranslateLanguage(). Versi lama
        // hanya menolak saat isUnreliableLatinGuess(languageCode) juga true,
        // sehingga kode tak-dikenal lain bisa lolos memakai languageCode
        // mentah tanpa tersanitasi.
        if (sampleText != null && !sampleText.isEmpty()) {
            String sanitized = sanitizeDetectedLanguage(sampleText, languageCode);
            if (sanitized != null) {
                languageCode = sanitized;
            } else {
                Log.d(TAG, "Tolak deteksi tidak andal: " + languageCode);
                callback.onSuccess(toUntranslatedBlocks(allBlocks));
                return;
            }
        } else {
            // Tanpa sample: coba skrip dari semua blok
            StringBuilder sb = new StringBuilder();
            for (Text.TextBlock b : allBlocks) {
                if (b != null && b.getText() != null) sb.append(b.getText()).append("\n");
            }
            String sanitized = sanitizeDetectedLanguage(sb.toString(), languageCode);
            if (sanitized != null) {
                languageCode = sanitized;
            } else {
                Log.d(TAG, "Tolak deteksi tidak andal: " + languageCode);
                callback.onSuccess(toUntranslatedBlocks(allBlocks));
                return;
            }
        }
        if (TARGET_LANGUAGE.equals(languageCode)) {
            callback.onSuccess(toUntranslatedBlocks(allBlocks));
            return;
        }
        Log.d(TAG, "Lanjut translate dengan bahasa: " + languageCode);
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

        String normalized = normalizeLanguageCode(sourceLanguageCode);
        final String lang = (normalized != null) ? normalized : sourceLanguageCode;
        Log.d(TAG, "translateBlocksIfModelAvailable lang=" + lang);

        isModelDownloaded(lang, new ModelCallback() {
            @Override
            public void onSuccess() {
                // Model tersedia → lanjut translate
                if (!isCurrent(generation)) return;
                doTranslateBlocks(textBlocks, lang, callback, generation);
            }

            @Override
            public void onFailure(Exception e) {
                // Model belum diunduh atau gagal cek → tampilkan teks asli
                if (!isCurrent(generation)) return;
                Log.w(TAG, "Model bahasa " + lang + " belum diunduh, tampilkan teks asli");
                callback.onModelNotDownloaded(lang);
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

    // -------------------------------------------------------------------------
    // PERBAIKAN — pencatatan "daftar bahasa yang pernah diunduh", dipakai
    // untuk fitur restore ML Kit. Model Translate ML Kit disimpan di
    // storage PRIVAT milik Google Play Services (bukan folder app ini),
    // sehingga TIDAK BISA di-backup/restore sebagai file langsung dari
    // dalam app biasa (tanpa root). Yang REALISTIS dilakukan: simpan daftar
    // kode bahasa yang pernah berhasil diunduh ke SharedPreferences (yang
    // ukurannya kecil dan ikut tertangkap adb backup bila allowBackup=true),
    // lalu saat restore, tawarkan re-download otomatis HANYA untuk bahasa
    // yang ada di daftar itu — developer tidak perlu ingat-ingat manual
    // bahasa apa saja yang tadinya sudah diunduh sebelum clear-data/uninstall.
    // -------------------------------------------------------------------------

    private static final String PREFS_NAME = "ocr_translate_prefs";
    private static final String PREF_KEY_DOWNLOADED_HISTORY = "downloaded_language_history";

    /** Tambahkan kode bahasa ke riwayat unduhan (dipanggil setiap unduhan sukses). */
    private static void recordLanguageDownloaded(Context context, String languageCode) {
        if (context == null || languageCode == null) return;
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> history = new HashSet<>(
                    prefs.getStringSet(PREF_KEY_DOWNLOADED_HISTORY, Collections.emptySet()));
            if (history.add(languageCode)) {
                prefs.edit().putStringSet(PREF_KEY_DOWNLOADED_HISTORY, history).apply();
            }
        } catch (Exception e) {
            // Non-fatal — riwayat hanya kenyamanan restore, bukan jalur kritikal.
            Log.w(TAG, "Gagal mencatat riwayat unduhan bahasa: " + e.getMessage());
        }
    }

    /**
     * Ambil daftar kode bahasa yang PERNAH berhasil diunduh di perangkat ini
     * (riwayat SharedPreferences), TERLEPAS dari apakah modelnya saat ini
     * masih benar-benar ada di Play Services atau tidak (itulah gunanya —
     * daftar ini dipakai justru ketika model sudah hilang, mis. setelah
     * clear-data, dan restore perlu tahu apa yang harus diunduh ulang).
     */
    public static List<String> getDownloadHistory(Context context) {
        if (context == null) return Collections.emptyList();
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return new ArrayList<>(prefs.getStringSet(PREF_KEY_DOWNLOADED_HISTORY, Collections.emptySet()));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Unduh ulang seluruh bahasa yang tercatat di {@link #getDownloadHistory}
     * tapi SAAT INI belum ada di Play Services (mis. setelah clear-data).
     * Dipakai tombol "Restore bahasa dari riwayat" di menu bahasa.
     *
     * Beda dengan {@link #downloadAllModels}: ini hanya mengunduh bahasa
     * yang MEMANG pernah dipakai user, bukan seluruh 38 bahasa yang
     * didukung — jauh lebih hemat kuota/waktu untuk skenario debug.
     */
    public static void restoreLanguagesFromHistory(Context context, ModelCallback callback) {
        List<String> history = getDownloadHistory(context);
        if (history.isEmpty()) {
            callback.onFailure(new Exception(
                    "Belum ada riwayat bahasa yang pernah diunduh di perangkat ini"));
            return;
        }
        restoreHistoryNext(context, history, 0, callback);
    }

    private static void restoreHistoryNext(
            Context context, List<String> history, int index, ModelCallback callback) {
        if (index >= history.size()) {
            callback.onSuccess();
            return;
        }
        String code = history.get(index);
        callback.onProgress("Memulihkan " + code + " (" + (index + 1) + "/" + history.size() + ")…");
        isModelDownloaded(code, new ModelCallback() {
            @Override
            public void onSuccess() {
                // Sudah ada (mis. baru saja diunduh manual), lanjut berikutnya
                restoreHistoryNext(context, history, index + 1, callback);
            }
            @Override
            public void onFailure(Exception e) {
                downloadModel(context, code, new ModelCallback() {
                    @Override
                    public void onSuccess() {
                        restoreHistoryNext(context, history, index + 1, callback);
                    }
                    @Override
                    public void onFailure(Exception e2) {
                        Log.e(TAG, "Gagal restore model " + code, e2);
                        // Lanjut ke bahasa berikutnya meski satu gagal —
                        // sama seperti downloadAllModels, jangan berhenti total.
                        restoreHistoryNext(context, history, index + 1, callback);
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
     *
     * @deprecated tanpa Context, riwayat unduhan tidak tercatat sehingga
     * fitur restore ({@link #restoreLanguagesFromHistory}) tidak akan tahu
     * bahasa ini pernah diunduh. Pakai {@link #downloadModel(Context, String, ModelCallback)}
     * bila memungkinkan. Overload ini dipertahankan agar caller lama tidak
     * perlu diubah paksa.
     */
    @Deprecated
    public static void downloadModel(String languageCode, ModelCallback callback) {
        downloadModel(null, languageCode, callback);
    }

    /**
     * Sama seperti {@link #downloadModel(String, ModelCallback)}, tapi juga
     * mencatat riwayat unduhan (bila {@code context} tidak null) supaya
     * bahasa ini bisa dipulihkan otomatis lewat
     * {@link #restoreLanguagesFromHistory} setelah clear-data/reinstall.
     */
    public static void downloadModel(Context context, String languageCode, ModelCallback callback) {
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
                            .addOnSuccessListener(u2 -> {
                                recordLanguageDownloaded(context, languageCode);
                                recordLanguageDownloaded(context, TARGET_LANGUAGE);
                                callback.onSuccess();
                            })
                            .addOnFailureListener(callback::onFailure);
                })
                .addOnFailureListener(callback::onFailure);
    }

    /**
     * Unduh SEMUA bahasa yang didukung secara berurutan.
     * Progress dilaporkan lewat {@link ModelCallback#onProgress}.
     *
     * @deprecated tanpa Context, riwayat unduhan tidak tercatat. Pakai
     * {@link #downloadAllModels(Context, ModelCallback)} bila memungkinkan.
     */
    @Deprecated
    public static void downloadAllModels(ModelCallback callback) {
        downloadAllModels(null, callback);
    }

    /** Sama seperti {@link #downloadAllModels(ModelCallback)}, tapi mencatat riwayat unduhan. */
    public static void downloadAllModels(Context context, ModelCallback callback) {
        List<LanguageInfo> list = SUPPORTED_SOURCE_LANGUAGES;
        downloadNext(context, list, 0, callback);
    }

    private static void downloadNext(Context context, List<LanguageInfo> list, int index, ModelCallback callback) {
        if (index >= list.size()) {
            // Pastikan model target juga ada
            downloadModel(context, TARGET_LANGUAGE, new ModelCallback() {
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
                downloadNext(context, list, index + 1, callback);
            }
            @Override
            public void onFailure(Exception e) {
                downloadModel(context, info.code, new ModelCallback() {
                    @Override
                    public void onSuccess() {
                        downloadNext(context, list, index + 1, callback);
                    }
                    @Override
                    public void onFailure(Exception e2) {
                        Log.e(TAG, "Gagal unduh model " + info.code, e2);
                        // Lanjut ke bahasa berikutnya meski gagal
                        downloadNext(context, list, index + 1, callback);
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
