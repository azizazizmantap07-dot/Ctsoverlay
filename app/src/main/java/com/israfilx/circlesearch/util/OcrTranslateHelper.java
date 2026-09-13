package com.israfilx.circlesearch.util;

import android.content.Context;
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
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
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
 * OCR (ekstraksi teks dari gambar) dan translate, keduanya on-device.
 * OCR memakai ML Kit Text Recognition untuk skrip Latin/Chinese/Japanese/
 * Korean/Devanagari, DITAMBAH Tesseract ({@link TesseractArabicRecognizer}
 * dan {@link TesseractThaiRecognizer}) khusus untuk skrip Arabic dan Thai
 * — ML Kit tidak menyediakan recognizer untuk kedua skrip itu sama sekali.
 * Translate tetap sepenuhnya ML Kit untuk semua bahasa.
 *
 * Alur: OCR baca teks dari bitmap crop -> pisah per BLOK teks (paragraf/
 * baris yang dikelompokkan recognizer, lengkap dengan boundingBox-nya,
 * dinormalisasi ke {@link OcrBlock} agar netral terhadap library OCR
 * asalnya) -> saring blok "noise" yang terlalu kecil/tidak relevan (lihat
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
     * Peta alias kode BCP-47 dari LanguageIdentifier ke kode konstanta
     * TranslateLanguage, untuk kasus di mana keduanya BUKAN string yang
     * sama persis.
     *
     * DITAMBAHKAN setelah ditemukan di device asli bahwa
     * TranslateLanguage.fromLanguageTag("fil") mengembalikan NULL,
     * bertentangan dengan dokumentasi resmi Google yang menyarankan
     * fromLanguageTag() untuk konversi ini. Rupanya fromLanguageTag()
     * hanya cocok untuk tag yang PERSIS sama dengan salah satu konstanta
     * TranslateLanguage (mis. "tl" cocok karena TranslateLanguage.TAGALOG
     * = "tl"), bukan untuk semua alias BCP-47 yang merujuk bahasa yang
     * sama (mis. "fil" adalah alias/kode ISO 639-2 untuk Filipino yang
     * dipakai LanguageIdentifier, sedangkan Translate API memakai kode
     * makro-bahasa "tl").
     *
     * Tambahkan entri baru di sini bila di masa depan ditemukan bahasa
     * lain dengan pola serupa (LanguageIdentifier mengembalikan kode yang
     * fromLanguageTag() tidak kenali padahal bahasanya didukung Translate
     * API) — cek dulu apakah kode itu sebenarnya benar-benar tidak
     * didukung, atau cuma alias yang belum dipetakan.
     */
    private static final java.util.Map<String, String> LANGUAGE_CODE_ALIASES = new java.util.HashMap<>();
    static {
        LANGUAGE_CODE_ALIASES.put("fil", TranslateLanguage.TAGALOG); // Filipino -> "tl"
    }

    /**
     * Normalisasi kode BCP-47 mentah dari LanguageIdentifier ke kode
     * konstanta TranslateLanguage yang benar. Coba alias eksplisit dulu
     * (untuk kasus yang terbukti tidak dikenali fromLanguageTag()), baru
     * fromLanguageTag() sebagai fallback umum untuk kode lain.
     * Null bila memang tidak didukung Translate API sama sekali.
     */
    private static String normalizeToTranslateLanguage(String rawLanguageCode) {
        if (rawLanguageCode == null) return null;
        String aliased = LANGUAGE_CODE_ALIASES.get(rawLanguageCode);
        if (aliased != null) return aliased;
        return TranslateLanguage.fromLanguageTag(rawLanguageCode);
    }

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
     * Executor khusus untuk recognizer Tesseract (Arabic) — TessBaseAPI
     * bersifat blocking/sinkron (bukan Task-based seperti ML Kit), jadi
     * harus dijalankan di thread terpisah agar tetap paralel dengan
     * recognizer ML Kit lainnya alih-alih menunggu berurutan.
     */
    private static final java.util.concurrent.ExecutorService TESSERACT_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final android.os.Handler MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * OCR multi-skrip: jalankan Latin + Chinese + Japanese + Korean + Devanagari
     * (ML Kit) + Arabic + Thai (keduanya Tesseract) secara paralel, lalu pilih
     * hasil terbaik (paling banyak karakter signifikan). Ini memperbaiki
     * deteksi teks non-Latin (Cina, Jepang, Korea, Hindi, Arab, Thai, dll)
     * yang sebelumnya gagal karena hanya memakai TextRecognizer Latin.
     *
     * Catatan: Arab dan Thai SUDAH ditangani lewat
     * {@link TesseractArabicRecognizer} dan {@link TesseractThaiRecognizer}
     * karena ML Kit tidak menyediakan recognizer on-device untuk kedua
     * skrip itu sama sekali. Vietnamese memakai aksara Latin (dengan
     * diakritik) jadi seharusnya terdeteksi oleh recognizer Latin.
     */
    private static void recognizeInternal(Context context, Bitmap bitmap, ResultCallback callback, boolean alsoTranslate) {
        final Context appContext = context.getApplicationContext();
        final long myGeneration = requestGeneration.incrementAndGet();
        final InputImage image = InputImage.fromBitmap(bitmap, 0);

        // Daftar recognizer ML Kit multi-skrip (Latin selalu dijalankan;
        // lainnya untuk skrip non-Latin). Semua dijalankan paralel, hasil
        // terbaik dipilih. Arabic dan Thai (Tesseract) DITAMBAHKAN sebagai
        // dua slot terakhir array hasil (lihat totalRecognizers di bawah)
        // — bukan TextRecognizer ML Kit, jadi dijalankan lewat jalur terpisah.
        final TextRecognizer[] recognizers = new TextRecognizer[] {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
                TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build())
        };
        final String[] scriptNames = {"Latin", "Chinese", "Japanese", "Korean", "Devanagari", "Arabic", "Thai"};
        final int totalRecognizers = scriptNames.length; // 5 ML Kit + 2 Tesseract

        final List<OcrBlock>[] results = new List[totalRecognizers];
        final AtomicInteger remaining = new AtomicInteger(totalRecognizers);
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
                        results[idx] = toOcrBlocks(visionText.getTextBlocks());
                        anySuccess.incrementAndGet();
                        Log.d(TAG, "OCR " + scriptNames[idx] + " selesai, blok=" + results[idx].size());
                        finishMultiOcrIfDone(recognizers, scriptNames, results, remaining, anySuccess,
                                callback, alsoTranslate, myGeneration);
                    })
                    .addOnFailureListener(e -> {
                        if (!isCurrent(myGeneration)) {
                            recognizer.close();
                            return;
                        }
                        Log.w(TAG, "OCR " + scriptNames[idx] + " gagal: " + e.getMessage());
                        results[idx] = Collections.emptyList();
                        finishMultiOcrIfDone(recognizers, scriptNames, results, remaining, anySuccess,
                                callback, alsoTranslate, myGeneration);
                    });
        }

        // Recognizer ke-6: Arabic via Tesseract. Dijalankan di executor
        // terpisah karena TessBaseAPI blocking, bukan Task-based.
        final int arabicIdx = recognizers.length;
        TESSERACT_EXECUTOR.execute(() -> {
            List<OcrBlock> arabicBlocks;
            try {
                arabicBlocks = TesseractArabicRecognizer.recognize(appContext, bitmap);
            } catch (Exception e) {
                Log.w(TAG, "OCR Arabic (Tesseract) exception: " + e.getMessage());
                arabicBlocks = Collections.emptyList();
            }
            final List<OcrBlock> finalArabicBlocks = arabicBlocks;
            MAIN_HANDLER.post(() -> {
                if (!isCurrent(myGeneration)) return;
                results[arabicIdx] = finalArabicBlocks;
                anySuccess.incrementAndGet();
                Log.d(TAG, "OCR Arabic selesai, blok=" + finalArabicBlocks.size());
                finishMultiOcrIfDone(recognizers, scriptNames, results, remaining, anySuccess,
                        callback, alsoTranslate, myGeneration);
            });
        });

        // Recognizer ke-7: Thai via Tesseract. Sama seperti Arabic di atas
        // — dijalankan di executor Tesseract yang sama (single-thread,
        // sehingga Arabic dan Thai berjalan berurutan satu sama lain, tapi
        // tetap paralel terhadap kelima recognizer ML Kit di atas).
        final int thaiIdx = recognizers.length + 1;
        TESSERACT_EXECUTOR.execute(() -> {
            List<OcrBlock> thaiBlocks;
            try {
                thaiBlocks = TesseractThaiRecognizer.recognize(appContext, bitmap);
            } catch (Exception e) {
                Log.w(TAG, "OCR Thai (Tesseract) exception: " + e.getMessage());
                thaiBlocks = Collections.emptyList();
            }
            final List<OcrBlock> finalThaiBlocks = thaiBlocks;
            MAIN_HANDLER.post(() -> {
                if (!isCurrent(myGeneration)) return;
                results[thaiIdx] = finalThaiBlocks;
                anySuccess.incrementAndGet();
                Log.d(TAG, "OCR Thai selesai, blok=" + finalThaiBlocks.size());
                finishMultiOcrIfDone(recognizers, scriptNames, results, remaining, anySuccess,
                        callback, alsoTranslate, myGeneration);
            });
        });
    }

    /** Konversi hasil ML Kit ({@code Text.TextBlock}) ke {@link OcrBlock} netral-library. */
    private static List<OcrBlock> toOcrBlocks(List<Text.TextBlock> mlKitBlocks) {
        List<OcrBlock> result = new ArrayList<>();
        if (mlKitBlocks == null) return result;
        for (Text.TextBlock block : mlKitBlocks) {
            Rect box = block.getBoundingBox();
            String text = block.getText();
            if (box == null || text == null) continue;
            result.add(new OcrBlock(text, box));
        }
        return result;
    }

    private static void finishMultiOcrIfDone(
            TextRecognizer[] recognizers,
            String[] scriptNames,
            List<OcrBlock>[] results,
            AtomicInteger remaining,
            AtomicInteger anySuccess,
            ResultCallback callback,
            boolean alsoTranslate,
            long generation) {
        if (remaining.decrementAndGet() != 0) return;
        // Semua recognizer sudah selesai (sukses atau gagal). Hanya
        // recognizer ML Kit yang perlu ditutup di sini — slot Arabic dan
        // Thai (Tesseract) sudah mengurus siklus hidupnya sendiri
        // (recycle()) masing-masing di dalam
        // TesseractArabicRecognizer#recognize dan
        // TesseractThaiRecognizer#recognize.
        for (TextRecognizer r : recognizers) {
            try { r.close(); } catch (Exception ignored) {}
        }
        if (!isCurrent(generation)) return;

        if (anySuccess.get() == 0) {
            Log.e(TAG, "Semua OCR gagal");
            callback.onError(new Exception("OCR gagal pada semua skrip"));
            return;
        }

        // Pilih hasil dengan skor tertinggi: total karakter signifikan
        // (tinggi blok + panjang teks). Prefer hasil yang punya lebih banyak
        // konten nyata daripada hanya noise status-bar.
        //
        // PERBAIKAN BUG — OCR recognizer non-Latin (Chinese/Japanese/Korean/
        // Devanagari) kadang "salah baca" teks skrip LAIN (mis. teks Korean
        // asli) sebagai coretan skripnya sendiri dan menghasilkan string
        // yang secara KEBETULAN lebih panjang daripada hasil recognizer
        // yang sebenarnya benar (mis. recognizer Korean). Skor lama yang
        // murni "jumlah karakter" membuat hasil salah-baca ini menang,
        // sehingga bahasa yang terdeteksi belakangan jadi ngawur (lihat
        // kasus nyata: teks Korea terbaca sebagai skrip Devanagari,
        // menghasilkan deteksi bahasa "fil"/"ig"/"et" yang berubah-ubah).
        //
        // Perbaikan: untuk recognizer NON-LATIN, hasilnya HANYA dianggap
        // valid (skor dihitung) bila teksnya benar-benar didominasi
        // karakter dari rentang Unicode skrip tersebut (lihat
        // isScriptTextValid). Recognizer Latin selalu dianggap valid
        // (dipakai juga untuk bahasa berdiakritik seperti Vietnamese, jadi
        // tidak bisa divalidasi rentang Unicode secara ketat).
        List<OcrBlock> bestBlocks = Collections.emptyList();
        int bestScore = -1;
        String bestScript = "none";
        for (int i = 0; i < results.length; i++) {
            List<OcrBlock> blocks = results[i];
            if (blocks == null || blocks.isEmpty()) continue;
            String script = scriptNames[i];
            // Latin selalu dianggap valid tanpa validasi rentang Unicode
            // (dipakai juga untuk bahasa berdiakritik). Semua skrip
            // lainnya, TERMASUK Arabic dan Thai (hasil Tesseract),
            // divalidasi dominasi karakter — lihat isCharInScript untuk
            // rentang Unicode Arabic/Thai yang ditambahkan bersamaan
            // dengan fitur ini.
            if (!"Latin".equals(script) && !isScriptTextValid(blocks, script)) {
                Log.d(TAG, "OCR " + script + " dibuang: teks tidak didominasi karakter skrip " + script + " (kemungkinan salah-baca skrip lain)");
                continue;
            }
            int score = scoreOcrBlocks(blocks);
            if (score > bestScore) {
                bestScore = score;
                bestBlocks = blocks;
                bestScript = script;
            }
        }

        if (bestBlocks.isEmpty() || bestScore <= 0) {
            Log.d(TAG, "Tidak ada teks signifikan dari OCR multi-skrip");
            callback.onNoTextFound();
            return;
        }

        Log.d(TAG, "OCR terbaik: skrip=" + bestScript + ", skor=" + bestScore
                + ", jumlah blok=" + bestBlocks.size());

        if (!alsoTranslate) {
            callback.onSuccess(toUntranslatedBlocks(bestBlocks));
            return;
        }
        detectLanguageAndTranslateBlocks(bestBlocks, callback, generation);
    }

    /**
     * True bila gabungan teks dari blok-blok ini didominasi (>= 40%)
     * karakter dari rentang Unicode yang sesuai dengan nama skrip yang
     * diberikan. Dipakai untuk memvalidasi hasil recognizer non-Latin
     * (Chinese/Japanese/Korean/Devanagari) agar tidak "asal menang" saat
     * sebenarnya salah membaca skrip lain sebagai skripnya sendiri.
     *
     * Ambang 40% (bukan >50%) sengaja dilonggarkan karena satu blok teks
     * dunia nyata sering bercampur dengan angka/tanda baca/spasi/label
     * Latin (mis. merk, angka versi) di antara karakter skrip aslinya.
     */
    private static boolean isScriptTextValid(List<OcrBlock> blocks, String scriptName) {
        int scriptChars = 0;
        int totalNonSpace = 0;
        for (OcrBlock block : blocks) {
            String text = block.getText();
            if (text == null) continue;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) continue;
                totalNonSpace++;
                if (isCharInScript(c, scriptName)) scriptChars++;
            }
        }
        if (totalNonSpace == 0) return false;
        return scriptChars >= totalNonSpace * 0.4;
    }

    /**
     * Cek apakah satu karakter berada di rentang Unicode skrip yang disebut.
     *
     * "Arabic" DITAMBAHKAN bersamaan dengan {@link TesseractArabicRecognizer}
     * — mencakup blok Unicode Arabic dasar, Arabic Supplement (huruf
     * tambahan untuk bahasa non-Arab berskrip Arab seperti Urdu/Pashto/
     * Sindhi), dan Arabic Presentation Forms A/B (bentuk sambung/kontekstual
     * yang kadang muncul di hasil OCR mentah sebelum normalisasi).
     *
     * "Thai" DITAMBAHKAN bersamaan dengan {@link TesseractThaiRecognizer}
     * — blok Unicode Thai mencakup seluruh aksara Thai (konsonan, vokal,
     * tanda nada, dan angka Thai).
     */
    private static boolean isCharInScript(char c, String scriptName) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        if (block == null) return false;
        switch (scriptName) {
            case "Chinese":
                return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                        || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
            case "Japanese":
                return block == Character.UnicodeBlock.HIRAGANA
                        || block == Character.UnicodeBlock.KATAKANA
                        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                        || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
            case "Korean":
                return block == Character.UnicodeBlock.HANGUL_SYLLABLES
                        || block == Character.UnicodeBlock.HANGUL_JAMO
                        || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
            case "Devanagari":
                return block == Character.UnicodeBlock.DEVANAGARI;
            case "Arabic":
                return block == Character.UnicodeBlock.ARABIC
                        || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT
                        || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                        || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B;
            case "Thai":
                return block == Character.UnicodeBlock.THAI;
            default:
                return true;
        }
    }

    /** Skor sederhana: jumlah karakter dari blok yang lolos filter tinggi/panjang. */
    private static int scoreOcrBlocks(List<OcrBlock> blocks) {
        int score = 0;
        for (OcrBlock block : blocks) {
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
            List<OcrBlock> textBlocks, ResultCallback callback, long generation) {
        List<OcrBlock> significantForDetection = filterSignificantBlocks(textBlocks);
        List<OcrBlock> blocksForLangDetect =
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
        List<OcrBlock> sortedForDetect = new ArrayList<>(blocksForLangDetect);
        Collections.sort(sortedForDetect, (a, b) ->
                Integer.compare(b.getText().trim().length(), a.getText().trim().length()));

        StringBuilder combined = new StringBuilder();
        for (OcrBlock block : sortedForDetect) {
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
                    if (isTargetLanguage(languageCode)) {
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
            List<OcrBlock> sortedForDetect, List<OcrBlock> allBlocks,
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
            List<OcrBlock> sortedForDetect, List<OcrBlock> allBlocks,
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
            String languageCode, List<OcrBlock> allBlocks,
            ResultCallback callback, long generation) {
        if (isTargetLanguage(languageCode)) {
            callback.onSuccess(toUntranslatedBlocks(allBlocks));
            return;
        }
        translateBlocksIfModelAvailable(allBlocks, languageCode, callback, generation);
    }

    /**
     * Bandingkan kode bahasa hasil LanguageIdentifier (tag BCP-47 mentah,
     * mis. "fil") dengan {@link #TARGET_LANGUAGE} secara aman — keduanya
     * dinormalisasi lewat {@link #normalizeToTranslateLanguage} dulu
     * karena tag mentah tidak selalu sama persis dengan kode konstanta
     * TranslateLanguage (lihat catatan mismatch & alias di
     * {@link #normalizeToTranslateLanguage}).
     */
    private static boolean isTargetLanguage(String rawLanguageCode) {
        String normalized = normalizeToTranslateLanguage(rawLanguageCode);
        return TARGET_LANGUAGE.equals(normalized);
    }

    private static List<OcrBlock> filterSignificantBlocks(List<OcrBlock> textBlocks) {
        List<OcrBlock> result = new ArrayList<>();
        for (OcrBlock block : textBlocks) {
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

    private static List<TranslatedBlock> toUntranslatedBlocks(List<OcrBlock> textBlocks) {
        List<TranslatedBlock> result = new ArrayList<>();
        for (OcrBlock block : textBlocks) {
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
            List<OcrBlock> textBlocks, String sourceLanguageCode,
            ResultCallback callback, long generation) {

        // PERBAIKAN BUG — mismatch kode bahasa antar API ML Kit:
        // LanguageIdentifier (language-id) mengembalikan tag BCP-47 apa
        // adanya, mis. "fil" untuk Filipino/Tagalog. Tapi RemoteModelManager
        // & Translator (translate) memakai kode konstanta TranslateLanguage
        // sendiri, mis. TranslateLanguage.TAGALOG = "tl". Kedua kode ini
        // MERUJUK bahasa yang sama tapi STRING-nya beda, jadi model yang
        // sudah diunduh dengan kode "tl" tidak akan pernah ketemu saat
        // dicek pakai kode mentah "fil" hasil deteksi -> selalu dianggap
        // "belum diunduh" walau sudah ada.
        //
        // CATATAN LANJUTAN (setelah verifikasi di device asli): dokumentasi
        // Google menyarankan TranslateLanguage.fromLanguageTag() untuk
        // konversi "fil"->"tl", TAPI di device asli terbukti
        // fromLanguageTag("fil") mengembalikan NULL, bukan "tl". Karena itu
        // normalisasi TIDAK bergantung 100% pada fromLanguageTag() saja —
        // lihat {@link #normalizeToTranslateLanguage} yang cek alias
        // eksplisit (LANGUAGE_CODE_ALIASES) lebih dulu, baru fallback ke
        // fromLanguageTag() untuk kode lain yang match langsung.
        String normalized = normalizeToTranslateLanguage(sourceLanguageCode);
        if (normalized == null) {
            // Bahasa terdeteksi tapi tidak didukung sama sekali oleh
            // Translate API (beda dengan "belum diunduh") -> tampilkan asli.
            Log.w(TAG, "Bahasa terdeteksi (" + sourceLanguageCode + ") tidak didukung Translate API, tampilkan teks asli");
            callback.onModelNotDownloaded(sourceLanguageCode);
            callback.onSuccess(toUntranslatedBlocks(textBlocks));
            return;
        }

        isModelDownloaded(normalized, new ModelCallback() {
            @Override
            public void onSuccess() {
                // Model tersedia → lanjut translate
                if (!isCurrent(generation)) return;
                doTranslateBlocks(textBlocks, normalized, callback, generation);
            }

            @Override
            public void onFailure(Exception e) {
                // Model belum diunduh atau gagal cek → tampilkan teks asli
                if (!isCurrent(generation)) return;
                Log.w(TAG, "Model bahasa " + normalized + " (terdeteksi sebagai " + sourceLanguageCode + ") belum diunduh, tampilkan teks asli");
                callback.onModelNotDownloaded(normalized);
                callback.onSuccess(toUntranslatedBlocks(textBlocks));
            }
        });
    }

    private static void doTranslateBlocks(
            List<OcrBlock> textBlocks, String sourceLanguageCode,
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
            Translator translator, List<OcrBlock> textBlocks,
            ResultCallback callback, long generation) {
        List<OcrBlock> validBlocks = new ArrayList<>();
        for (OcrBlock block : textBlocks) {
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
            OcrBlock block = validBlocks.get(i);
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
