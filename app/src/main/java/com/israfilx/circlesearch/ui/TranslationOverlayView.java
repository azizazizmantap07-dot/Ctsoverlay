package com.israfilx.circlesearch.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.israfilx.circlesearch.util.OcrTranslateHelper.TranslatedBlock;

import java.util.List;

/**
 * View yang menggambar hasil terjemahan LANGSUNG MENIMPA teks asli di
 * posisinya masing-masing — bukan kartu popup terpisah. Untuk tiap blok
 * teks hasil OCR:
 *  1. Area teks asli ditutup dengan satu warna SOLID (tanpa blur mosaic)
 *     hasil sampling rata-rata piksel di sekitar area itu sendiri lalu
 *     sedikit digelapkan/diterangkan untuk kontras yang konsisten —
 *     supaya area tertutup terlihat rapi dan tidak "berantakan"/abstrak
 *     seperti pendekatan blur mosaic sebelumnya.
 *  2. Teks terjemahan digambar ulang tepat di posisi & ukuran kira-kira
 *     sama dengan teks aslinya (ukuran font disesuaikan otomatis supaya
 *     muat di lebar boundingBox, dengan word-wrap bila perlu).
 *
 * View ini transparan di seluruh area lain (tidak menggambar background
 * screenshot sendiri) — dipasang SEBAGAI LAPISAN TAMBAHAN di atas
 * SelectionOverlayView yang sudah menampilkan screenshot beku, supaya
 * teks non-hasil-OCR (gambar, UI lain) tetap terlihat apa adanya.
 *
 * Penutupan overlay ini TIDAK BOLEH bergantung semata-mata pada tap di
 * area kosong (lihat onTouchEvent) — BottomIconMenu punya tombol ✕
 * eksplisit yang memanggil OverlayCaptureService.closeOverlayAndStop()
 * secara langsung, supaya selalu ada jalan keluar yang pasti berhasil
 * walau tap-di-luar-teks entah kenapa tidak sampai ke view ini.
 */
public class TranslationOverlayView extends View {

    /** Dipanggil saat user tap di luar semua blok teks, untuk menutup overlay. */
    public interface OnDismissListener {
        void onDismiss();
    }

    private final Bitmap sourceBitmap;
    private final List<TranslatedBlock> blocks;
    private OnDismissListener dismissListener;

    // Waktu view ini terpasang ke window (diisi di onAttachedToWindow).
    // Dipakai untuk mengabaikan MotionEvent yang datang dalam sesaat
    // setelah overlay ini tampil — mencegah "residu" ACTION_UP dari
    // tap ikon translate sebelumnya (yang memicu proses OCR async ini)
    // langsung tertangkap sebagai tap-untuk-menutup begitu overlay
    // baru saja ditambahkan ke WindowManager, yang membuat overlay
    // terlihat muncul sekejap lalu hilang sendiri.
    private long attachedAtMs = 0L;
    private static final long DISMISS_GRACE_PERIOD_MS = 350L;

    // Flag supaya dismiss (baik dari tap maupun dari tombol ✕) tidak
    // dipicu dua kali — mis. animasi keluar sedang berjalan lalu user
    // tap lagi, atau tombol ✕ ditekan berulang dengan cepat.
    private boolean dismissing = false;

    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final float PADDING_DP = 4f;
    private static final float MIN_TEXT_SIZE_SP = 9f;
    private static final float CORNER_RADIUS_DP = 4f;
    private static final long ENTER_ANIM_MS = 180L;
    private static final long EXIT_ANIM_MS = 140L;

    public TranslationOverlayView(Context context, Bitmap sourceBitmap, List<TranslatedBlock> blocks) {
        super(context);
        this.sourceBitmap = sourceBitmap;
        this.blocks = blocks;

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);

        setWillNotDraw(false);
        setClickable(true);
    }

    public void setOnDismissListener(OnDismissListener l) {
        this.dismissListener = l;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedAtMs = android.os.SystemClock.uptimeMillis();

        // Animasi masuk: fade-in + scale-up ringan dari 96% supaya
        // kemunculan hasil terjemahan terasa halus, bukan muncul
        // tiba-tiba (snap) begitu OCR selesai.
        setAlpha(0f);
        setScaleX(0.96f);
        setScaleY(0.96f);
        animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ENTER_ANIM_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /**
     * Tutup overlay ini dengan animasi fade-out singkat, baru panggil
     * dismissListener setelah animasi selesai (bukan langsung), supaya
     * transisi terlihat mulus alih-alih view hilang mendadak.
     *
     * Aman dipanggil berkali-kali — panggilan kedua dst. diabaikan
     * selama animasi keluar masih berjalan.
     */
    public void dismissAnimated() {
        if (dismissing) return;
        dismissing = true;
        animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(EXIT_ANIM_MS)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (dismissListener != null) dismissListener.onDismiss();
                    }
                })
                .start();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float spToPx(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (TranslatedBlock block : blocks) {
            drawBlockOverlay(canvas, block);
        }
    }

    private void drawBlockOverlay(Canvas canvas, TranslatedBlock block) {
        Rect box = block.boundingBox;
        if (box == null || box.width() <= 0 || box.height() <= 0) return;

        float pad = dp(PADDING_DP);
        RectF coverRect = new RectF(
                box.left - pad,
                box.top - pad,
                box.right + pad,
                box.bottom + pad);

        // 1. Tutup area teks asli dengan satu warna solid saja — warna
        //    dasar diambil dari rata-rata piksel sekitar blok itu sendiri
        //    (supaya tetap menyatu dengan tema/latar sekitarnya, bukan
        //    kotak abu-abu generik), lalu digelapkan/diterangkan sedikit
        //    supaya kontras terhadap teks putih/hitam di atasnya selalu
        //    konsisten. Tidak ada lapisan blur/mosaic lagi — itu yang
        //    sebelumnya membuat area tertutup terlihat abstrak dan
        //    berantakan alih-alih rapi.
        int avgColor = sampleAverageColor(box);
        int coverColor = solidCoverColor(avgColor);
        drawCoverBase(canvas, coverRect, coverColor);

        // 2. Gambar teks terjemahan di atas area yang sudah ditutup,
        //    warna kontras otomatis (putih/hitam) berdasar kecerahan
        //    warna dasar penutup supaya tetap terbaca.
        textPaint.setColor(readableTextColor(coverColor));
        drawWrappedText(canvas, block.translatedText, coverRect);
    }

    /** Warna rata-rata area sekitar blok, dipakai sebagai basis warna penutup. */
    private int sampleAverageColor(Rect box) {
        int left = Math.max(0, box.left);
        int top = Math.max(0, box.top);
        int right = Math.min(sourceBitmap.getWidth(), box.right);
        int bottom = Math.min(sourceBitmap.getHeight(), box.bottom);
        if (right <= left || bottom <= top) return Color.DKGRAY;

        // Ambil sample kecil (downscale) supaya cepat, bukan tiap piksel.
        int sampleW = Math.max(1, Math.min(12, right - left));
        int sampleH = Math.max(1, Math.min(12, bottom - top));
        try {
            Bitmap sample = Bitmap.createScaledBitmap(
                    Bitmap.createBitmap(sourceBitmap, left, top, right - left, bottom - top),
                    sampleW, sampleH, true);
            long r = 0, g = 0, b = 0;
            int count = sampleW * sampleH;
            for (int y = 0; y < sampleH; y++) {
                for (int x = 0; x < sampleW; x++) {
                    int px = sample.getPixel(x, y);
                    r += Color.red(px);
                    g += Color.green(px);
                    b += Color.blue(px);
                }
            }
            sample.recycle();
            return Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
        } catch (Exception e) {
            return Color.DKGRAY;
        }
    }

    /**
     * Ubah warna rata-rata sampel jadi warna penutup solid yang cukup
     * "padat"/pekat sebagai latar teks — digelapkan bila terang, atau
     * sedikit diterangkan bila sangat gelap, supaya teks kontras yang
     * digambar di atasnya (hitam/putih) selalu mudah dibaca terlepas
     * dari warna asli area yang ditutup.
     */
    private int solidCoverColor(int avgColor) {
        double luminance = (0.299 * Color.red(avgColor)
                + 0.587 * Color.green(avgColor)
                + 0.114 * Color.blue(avgColor)) / 255.0;

        float factor = luminance > 0.5f ? 0.55f : 1.35f;
        int r = clamp255(Math.round(Color.red(avgColor) * factor));
        int g = clamp255(Math.round(Color.green(avgColor) * factor));
        int b = clamp255(Math.round(Color.blue(avgColor) * factor));
        return Color.rgb(r, g, b);
    }

    private int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void drawCoverBase(Canvas canvas, RectF coverRect, int coverColor) {
        coverPaint.setColor(coverColor);
        coverPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(coverRect, dp(CORNER_RADIUS_DP), dp(CORNER_RADIUS_DP), coverPaint);
    }

    /** Pilih warna teks (putih/hitam) yang paling kontras terhadap warna dasar penutup. */
    private int readableTextColor(int backgroundColor) {
        double luminance = (0.299 * Color.red(backgroundColor)
                + 0.587 * Color.green(backgroundColor)
                + 0.114 * Color.blue(backgroundColor)) / 255.0;
        return luminance > 0.55 ? Color.BLACK : Color.WHITE;
    }

    /**
     * Gambar teks terjemahan dengan word-wrap otomatis di dalam rect,
     * ukuran font disusutkan bertahap sampai muat (baik lebar maupun
     * tinggi), supaya kalimat lebih panjang dari aslinya (umum terjadi
     * saat translate ke Indonesia) tetap terbaca dalam area yang sama.
     */
    private void drawWrappedText(Canvas canvas, String text, RectF rect) {
        if (text == null || text.trim().isEmpty()) return;

        float maxWidth = rect.width() - dp(4);
        float maxHeight = rect.height() - dp(2);
        if (maxWidth <= 0 || maxHeight <= 0) return;

        float textSize = Math.max(spToPx(MIN_TEXT_SIZE_SP), rect.height() * 0.62f);
        List<String> lines;

        // Susutkan ukuran font bertahap sampai seluruh baris muat di
        // tinggi rect, atau sampai mencapai batas minimum keterbacaan.
        while (true) {
            textPaint.setTextSize(textSize);
            lines = wrapText(text, maxWidth);
            float totalHeight = lines.size() * textPaint.getFontSpacing();
            if (totalHeight <= maxHeight || textSize <= spToPx(MIN_TEXT_SIZE_SP)) {
                break;
            }
            textSize -= 1f;
        }

        float lineHeight = textPaint.getFontSpacing();
        float totalTextHeight = lines.size() * lineHeight;
        float startY = rect.top + Math.max(0, (rect.height() - totalTextHeight) / 2f)
                - textPaint.ascent();

        float x = rect.left + dp(2);
        float y = startY;
        for (String line : lines) {
            canvas.drawText(line, x, y, textPaint);
            y += lineHeight;
        }
    }

    private List<String> wrapText(String text, float maxWidth) {
        List<String> lines = new java.util.ArrayList<>();
        String[] words = text.trim().split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (textPaint.measureText(candidate) <= maxWidth || current.length() == 0) {
                current = new StringBuilder(candidate);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            long sinceAttach = android.os.SystemClock.uptimeMillis() - attachedAtMs;
            if (sinceAttach < DISMISS_GRACE_PERIOD_MS) {
                // Kemungkinan besar ini residu dari jari yang masih
                // menyentuh layar sesaat setelah tap ikon translate
                // (yang baru selesai diproses secara async) — abaikan,
                // jangan langsung menutup overlay yang baru saja tampil.
                return true;
            }
            dismissAnimated();
        }
        return true;
    }
}
