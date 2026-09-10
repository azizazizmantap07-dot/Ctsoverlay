package com.israfilx.circlesearch.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import com.israfilx.circlesearch.util.OcrTranslateHelper.TranslatedBlock;

import java.util.List;

/**
 * View yang menggambar hasil terjemahan LANGSUNG MENIMPA teks asli di
 * posisinya masing-masing — bukan kartu popup terpisah. Untuk tiap blok
 * teks hasil OCR:
 *  1. Area teks asli diburamkan/ditutup dengan warna solid yang diambil
 *     dari sekitar area itu sendiri (sampling dari bitmap), supaya
 *     menyatu dengan background di sekitarnya alih-alih kotak putih polos
 *     yang mencolok.
 *  2. Teks terjemahan digambar ulang tepat di posisi & ukuran kira-kira
 *     sama dengan teks aslinya (ukuran font disesuaikan otomatis supaya
 *     muat di lebar boundingBox, dengan word-wrap bila perlu).
 *
 * Hasilnya terlihat seperti teks di layar "diganti langsung" ke bahasa
 * target — mirip cara kerja mode terjemahan realtime Google Lens/Circle
 * to Search pada gambar.
 *
 * View ini transparan di seluruh area lain (tidak menggambar background
 * screenshot sendiri) — dipasang SEBAGAI LAPISAN TAMBAHAN di atas
 * SelectionOverlayView yang sudah menampilkan screenshot beku, supaya
 * teks non-hasil-OCR (gambar, UI lain) tetap terlihat apa adanya.
 */
public class TranslationOverlayView extends View {

    /** Dipanggil saat user tap di luar semua blok teks, untuk menutup overlay. */
    public interface OnDismissListener {
        void onDismiss();
    }

    private final Bitmap sourceBitmap;
    private final List<TranslatedBlock> blocks;
    private OnDismissListener dismissListener;

    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blurCoverBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private static final float PADDING_DP = 4f;
    private static final float MIN_TEXT_SIZE_SP = 9f;
    private static final float CORNER_RADIUS_DP = 4f;

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

        // 1. Tutup area teks asli. Dua lapis: warna dasar hasil sampling
        //    rata-rata piksel di sekitar blok (drawCoverBase, dipakai
        //    sebagai fallback bila blur gagal), lalu di atasnya lapisan
        //    blur dari potongan bitmap area itu sendiri (drawBlurredCover)
        //    supaya tekstur/warna background asli tetap terasa menyatu —
        //    jauh lebih menyatu dibanding kotak warna polos saja.
        int avgColor = sampleAverageColor(box);
        drawCoverBase(canvas, coverRect, avgColor);
        drawBlurredCover(canvas, box, coverRect);

        // 2. Gambar teks terjemahan di atas area yang sudah ditutup,
        //    warna kontras otomatis (putih/hitam) berdasar kecerahan
        //    warna dasar penutup supaya tetap terbaca.
        textPaint.setColor(readableTextColor(avgColor));
        drawWrappedText(canvas, block.translatedText, coverRect);
    }

    /** Warna rata-rata area sekitar blok, dipakai sebagai dasar penutup & fallback tanpa blur. */
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

    private void drawCoverBase(Canvas canvas, RectF coverRect, int avgColor) {
        coverPaint.setColor(avgColor);
        coverPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(coverRect, dp(CORNER_RADIUS_DP), dp(CORNER_RADIUS_DP), coverPaint);
    }

    /**
     * Lapis blur di atas cover dasar: gambar ulang potongan bitmap asli
     * area itu, diperkecil drastis lalu diperbesar kembali (mosaic/box
     * blur) sebelum digambar ke area penutup. Ini menyamarkan bentuk
     * huruf teks asli sampai tidak terbaca, sementara warna & tekstur
     * kasar di sekitarnya (background gradient, pola, dsb.) tetap
     * terasa menyatu — bukan kotak warna rata polos.
     *
     * Pakai downscale/upscale (bukan RenderEffect API 31+) supaya
     * perilakunya identik di semua versi Android dan tidak butuh
     * hardware layer terpisah untuk digambar langsung ke Canvas ini.
     */
    private void drawBlurredCover(Canvas canvas, Rect box, RectF coverRect) {
        int left = Math.max(0, box.left);
        int top = Math.max(0, box.top);
        int right = Math.min(sourceBitmap.getWidth(), box.right);
        int bottom = Math.min(sourceBitmap.getHeight(), box.bottom);
        if (right <= left || bottom <= top) return;

        try {
            Bitmap patch = Bitmap.createBitmap(sourceBitmap, left, top, right - left, bottom - top);
            int smallW = Math.max(1, patch.getWidth() / 10);
            int smallH = Math.max(1, patch.getHeight() / 10);
            Bitmap small = Bitmap.createScaledBitmap(patch, smallW, smallH, true);
            canvas.drawRoundRect(coverRect, dp(CORNER_RADIUS_DP), dp(CORNER_RADIUS_DP), coverPaint);
            int saveCount = canvas.save();
            canvas.clipRect(coverRect);
            canvas.drawBitmap(small, null, coverRect, blurCoverBitmapPaint);
            canvas.restoreToCount(saveCount);
            small.recycle();
            patch.recycle();
        } catch (Exception ignored) {
            // Kalau blur gagal karena alasan apapun, cover warna solid dari
            // drawCoverBase() di atas sudah cukup menutup teks asli.
        }
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
            if (dismissListener != null) dismissListener.onDismiss();
        }
        return true;
    }
}
