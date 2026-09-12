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

        // Selalu coba Tesseract sebagai kandidat tambahan untuk Arab/Thai.
        // ML Kit Latin sering menghasilkan sampah saat membaca aksara Thai/Arab,
        // dan skornya kadang masih tinggi sehingga threshold lama tidak memicu fallback.
        if (context != null && bitmap != null) {
            final List<Text.TextBlock> mlKitBlocks = bestBlocks;
            final int mlKitScore = bestScore;
            final String mlKitScript = bestScript;
            tryTesseractFallback(context, bitmap, alsoTranslate, callback, generation,
                    mlKitBlocks, mlKitScore, mlKitScript);
            return;
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
        detectLanguageAndTranslateBlocks(bestBlocks, callback, generation);
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

                boolean preferTess = false;
                if (tessBlocks != null && !tessBlocks.isEmpty()) {
                    if (tessHasScript && !mlKitHasScript) preferTess = true;
                    else if (tessHasScript && tessScore >= mlKitScore * 0.5) preferTess = true;
                    else if (mlKitScore < 100 && tessScore > mlKitScore) preferTess = true;
                    else if (mlKitScore < 40 && tessScore > 0) preferTess = true;
                }

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
                detectLanguageAndTranslateBlocks(mlKitBlocks, callback, generation);
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
                detectLanguageAndTranslateBlocks(mlKitBlocks, callback, generation);
            }
            @Override
            public void onProgress(String message) {
                Log.d(TAG, "Tessdata: " + message);
            }
        });
    }

    private static boolean containsArabicOrThai(List<TranslatedBlock> blocks) {
        if (blocks == null) return false;
        for (TranslatedBlock b : blocks) {
            if (b == null || b.originalText == null) continue;
            if (textHasArabicOrThai(b.originalText)) return true;
        }
        return false;
    }

    private static boolean containsArabicOrThaiFromMlKit(List<Text.TextBlock> blocks) {
        if (blocks == null) return false;
        for (Text.TextBlock b : blocks) {
            if (b == null || b.getText() == null) continue;
            if (textHasArabicOrThai(b.getText())) return true;
        }
        return false;
    }

    private static boolean textHasArabicOrThai(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                    || (c >= 0x08A0 && c <= 0x08FF) || (c >= 0xFB50 && c <= 0xFDFF)
                    || (c >= 0xFE70 && c <= 0xFEFF)) return true;
            if (c >= 0x0E00 && c <= 0x0E7F) return true;
        }
        return false;
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
        return score;
    }

    private static File getTessdataDir(Context context) {
        File dir = new File(context.getFilesDir(), "tessdata");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * ara.traineddata "best" ~12MB (jauh lebih akurat dari "fast" ~1MB).
     * tha.traineddata "fast" sudah cukup baik dan lebih ringan.
     */
    private static final long MIN_ARA_TRAINEDDATA_BYTES = 3_000_000L; // bedakan fast vs best
    private static final long MIN_THA_TRAINEDDATA_BYTES = 50_000L;

    private static boolean isTessdataReady(Context context) {
        File dir = getTessdataDir(context);
        File ara = new File(dir, "ara.traineddata");
        File tha = new File(dir, "tha.traineddata");
        return ara.exists() && ara.length() >= MIN_ARA_TRAINEDDATA_BYTES
                && tha.exists() && tha.length() >= MIN_THA_TRAINEDDATA_BYTES;
    }

    public static void ensureTessdata(Context context, ModelCallback callback) {
        if (isTessdataReady(context)) {
            callback.onSuccess();
            return;
        }
        new Thread(() -> {
            try {
                File dir = getTessdataDir(context);
                File ara = new File(dir, "ara.traineddata");
                File tha = new File(dir, "tha.traineddata");

                // Upgrade: hapus ara "fast" lama yang terlalu kecil/akurasi buruk
                if (ara.exists() && ara.length() < MIN_ARA_TRAINEDDATA_BYTES) {
                    Log.d(TAG, "Mengganti ara.traineddata fast (" + ara.length()
                            + " byte) dengan versi best…");
                    //noinspection ResultOfMethodCallIgnored
                    ara.delete();
                }

                // Arabic: tessdata_best (akurasi jauh lebih baik)
                String bestBase = "https://github.com/tesseract-ocr/tessdata_best/raw/main/";
                String fastBase = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/";
                downloadFile(bestBase + "ara.traineddata", ara, callback,
                        "Arabic OCR (best, ~12MB)", MIN_ARA_TRAINEDDATA_BYTES);
                downloadFile(fastBase + "tha.traineddata", tha, callback,
                        "Thai OCR", MIN_THA_TRAINEDDATA_BYTES);

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
            Log.d(TAG, "Tesseract lang=" + lang + " skor=" + sc
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
        int arab = 0, thai = 0, other = 0;
        for (TranslatedBlock b : blocks) {
            if (b == null || b.originalText == null) continue;
            String t = b.originalText;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (Character.isWhitespace(c)) continue;
                if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                        || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) {
                    arab++;
                } else if (c >= 0x0E00 && c <= 0x0E7F) {
                    thai++;
                } else if (Character.isLetterOrDigit(c)) {
                    other++;
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
        return score;
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
        Bitmap b = src;
        if (scale > 1.05f) {
            int w = Math.round(src.getWidth() * scale);
            int h = Math.round(src.getHeight() * scale);
            b = Bitmap.createScaledBitmap(src, w, h, true);
        }
        if (b.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap converted = b.copy(Bitmap.Config.ARGB_8888, true);
            if (b != src && !b.isRecycled()) {
                try { b.recycle(); } catch (Exception ignored) {}
            }
            b = converted;
        }
        return b;
    }

    private static final float MIN_TESS_LINE_CONFIDENCE = 40f;

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
            // PSM_AUTO bagus untuk potongan seleksi; untuk Arab RTL LSTM tetap jalan
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
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
                        // Buang baris confidence rendah (sumber utama terjemahan kacau)
                        if (conf > 0f && conf < MIN_TESS_LINE_CONFIDENCE) {
                            Log.d(TAG, "Skip baris conf=" + conf + " text=[" +
                                    (text.length() > 40 ? text.substring(0, 40) + "…" : text) + "]");
                            continue;
                        }
                        if (isMostlyGarbageLine(text)) continue;
                        Rect rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        if (rect != null && rect.width() > 2 && rect.height() > 2) {
                            blocks.add(new TranslatedBlock(text, text, new Rect(rect)));
                        } else {
                            blocks.add(new TranslatedBlock(text, text,
                                    new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())));
                        }
                    } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE));
                } finally {
                    it.delete();
                }
            }

            if (blocks.isEmpty() && !fullText.trim().isEmpty()) {
                String cleaned = fullText.trim();
                if (!isMostlyGarbageLine(cleaned)) {
                    blocks.add(new TranslatedBlock(cleaned, cleaned,
                            new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())));
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
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0E00 && c <= 0x0E7F)) {
                arabicThai++;
                letters++;
            } else if (Character.isLetter(c)) {
                letters++;
            } else if (c == 0xFFFD || (c > 0x024F && c < 0x0600)) {
                weird++;
            }
        }
        if (total == 0) return true;
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
        String forced = detectScriptLanguage(combined);
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
                    if (langCode == null || "und".equals(langCode)) {
                        identifier.identifyPossibleLanguages(combined)
                                .addOnSuccessListener(cands -> {
                                    if (!isCurrent(generation)) return;
                                    String best = pickBestCandidate(cands);
                                    if (best == null) {
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

    /** Deteksi th/ar dari rentang Unicode. null jika tidak jelas. */
    private static String detectScriptLanguage(String text) {
        int thai = 0, arab = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0E00 && c <= 0x0E7F) thai++;
            else if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                    || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) arab++;
        }
        if (thai >= 3 && thai >= arab) return TranslateLanguage.THAI;
        if (arab >= 3 && arab >= thai) return TranslateLanguage.ARABIC;
        if (thai >= 1 && arab == 0) return TranslateLanguage.THAI;
        if (arab >= 1 && thai == 0) return TranslateLanguage.ARABIC;
        return null;
    }

    private static void doTranslateOnTranslatedBlocks(
            List<TranslatedBlock> blocks, String sourceLang,
            ResultCallback callback, long generation) {
        isModelDownloaded(sourceLang, new ModelCallback() {
            @Override
            public void onSuccess() {
                if (!isCurrent(generation)) return;
                TranslatorOptions opts = new TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLang)
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
                                results[idx] = new TranslatedBlock(b.originalText, translated, b.boundingBox);
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
                Log.w(TAG, "Model " + sourceLang + " belum diunduh untuk hasil Tesseract");
                callback.onModelNotDownloaded(sourceLang);
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
