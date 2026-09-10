package com.israfilx.circlesearch.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import com.israfilx.circlesearch.util.OcrTranslateHelper.TranslatedBlock;

import java.util.List;

/**
 * View yang menggambar hasil terjemahan LANGSUNG MENIMPA teks asli di
 * posisinya masing-masing — bukan kartu popup terpisah. Untuk tiap blok
 * teks hasil OCR:
 *  1. Area teks asli ditutup dengan satu warna SOLID hasil sampling
 *     rata-rata piksel di sekitar area itu sendiri, dengan sudut kotak
 *     dibuat lebih membulat (lihat CORNER_RADIUS_DP) supaya terasa lebih
 *     halus/modern, tidak tajam seperti kotak persegi biasa.
 *  2. Teks terjemahan digambar ulang tepat di posisi & ukuran kira-kira
 *     sama dengan teks aslinya (ukuran font disesuaikan otomatis).
 *
 * Interaksi tambahan:
 *  - TAP SEKALI pada sebuah blok teks: salin teks blok itu (hasil
 *    terjemahan) ke clipboard, beri feedback highlight singkat + toast.
 *  - TAP-TAHAN (long-press) pada sebuah blok: salin teks ASLI (sebelum
 *    diterjemahkan) blok itu ke clipboard — berguna saat user butuh teks
 *    sumbernya, bukan hasil terjemahannya.
 *  - Tombol "Salin Semua" kecil di pojok atas: salin seluruh teks hasil
 *    terjemahan pada layar ini sekaligus (satu blok per baris).
 *
 * View ini transparan di seluruh area lain (tidak menggambar background
 * screenshot sendiri) — dipasang SEBAGAI LAPISAN TAMBAHAN di atas
 * SelectionOverlayView yang sudah menampilkan screenshot beku.
 *
 * PENTING — perilaku "back" vs "tutup total": view ini TIDAK PERNAH
 * memutuskan sendiri untuk menutup seluruh overlay. Baik tap di luar
 * semua blok teks maupun tombol back sistem hanya memanggil
 * {@link OnDismissListener#onDismiss()}, yang oleh pemanggil
 * (OverlayCaptureService) diartikan sebagai "kembali ke menu utama
 * overlay" (seleksi + BottomIconMenu tetap tampil), BUKAN menutup semua
 * window. Penutupan total hanya terjadi lewat tombol ✕ eksplisit di
 * BottomIconMenu setelah kembali ke menu utama itu.
 */
public class TranslationOverlayView extends View {

    /** Dipanggil saat user ingin kembali ke menu utama overlay (bukan menutup semuanya). */
    public interface OnDismissListener {
        void onDismiss();
    }

    private final Bitmap sourceBitmap;
    private final List<TranslatedBlock> blocks;
    private OnDismissListener dismissListener;

    private long attachedAtMs = 0L;
    private static final long DISMISS_GRACE_PERIOD_MS = 350L;

    private boolean dismissing = false;

    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint copyBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint copyBtnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final float PADDING_DP = 4f;
    private static final float MIN_TEXT_SIZE_SP = 9f;
    // Sudut kotak penutup diperhalus (sebelumnya 4dp, terasa terlalu
    // tajam/kaku) — radius lebih besar membuat tepi kotak terasa lembut
    // tanpa kehilangan keterbacaan batas area.
    private static final float CORNER_RADIUS_DP = 10f;
    private static final long ENTER_ANIM_MS = 180L;
    private static final long EXIT_ANIM_MS = 140L;
    private static final long HIGHLIGHT_FLASH_MS = 220L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final GestureDetector gestureDetector;

    private TranslatedBlock flashingBlock;
    private float flashAlpha = 0f;

    private RectF copyAllButtonRect;
    private static final String COPY_ALL_LABEL = "⧉ Salin Semua";

    public TranslationOverlayView(Context context, Bitmap sourceBitmap, List<TranslatedBlock> blocks) {
        super(context);
        this.sourceBitmap = sourceBitmap;
        this.blocks = blocks;

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);

        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(dp(3));
        highlightPaint.setColor(Color.parseColor("#80D8FF"));

        copyBtnBgPaint.setStyle(Paint.Style.FILL);
        copyBtnBgPaint.setColor(Color.parseColor("#DD202124"));

        copyBtnTextPaint.setColor(Color.WHITE);
        copyBtnTextPaint.setTextSize(spToPx(13f));
        copyBtnTextPaint.setTextAlign(Paint.Align.CENTER);

        setWillNotDraw(false);
        setClickable(true);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return handleTap(e.getX(), e.getY(), false);
            }

            @Override
            public void onLongPress(MotionEvent e) {
                handleTap(e.getX(), e.getY(), true);
            }
        });
    }

    public void setOnDismissListener(OnDismissListener l) {
        this.dismissListener = l;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedAtMs = android.os.SystemClock.uptimeMillis();

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
     * dismissListener setelah animasi selesai. Pemanggil (Service)
     * menafsirkan callback ini sebagai "kembali ke menu utama", bukan
     * menutup semua window — lihat dokumentasi kelas di atas.
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float btnW = dp(120);
        float btnH = dp(34);
        float margin = dp(14);
        copyAllButtonRect = new RectF(w - margin - btnW, margin, w - margin, margin + btnH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (TranslatedBlock block : blocks) {
            drawBlockOverlay(canvas, block);
        }

        if (flashingBlock != null && flashAlpha > 0f) {
            drawFlashHighlight(canvas, flashingBlock);
        }

        drawCopyAllButton(canvas);
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

        int avgColor = sampleAverageColor(box);
        int coverColor = solidCoverColor(avgColor);
        drawCoverBase(canvas, coverRect, coverColor);

        textPaint.setColor(readableTextColor(coverColor));
        drawWrappedText(canvas, block.translatedText, coverRect);
    }

    private void drawFlashHighlight(Canvas canvas, TranslatedBlock block) {
        Rect box = block.boundingBox;
        if (box == null) return;
        float pad = dp(PADDING_DP + 2f);
        RectF rect = new RectF(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad);
        highlightPaint.setAlpha((int) (255 * flashAlpha));
        canvas.drawRoundRect(rect, dp(CORNER_RADIUS_DP + 2f), dp(CORNER_RADIUS_DP + 2f), highlightPaint);
    }

    private void drawCopyAllButton(Canvas canvas) {
        if (copyAllButtonRect == null || blocks.isEmpty()) return;
        canvas.drawRoundRect(copyAllButtonRect, dp(17), dp(17), copyBtnBgPaint);
        float textY = copyAllButtonRect.centerY() - (copyBtnTextPaint.ascent() + copyBtnTextPaint.descent()) / 2f;
        canvas.drawText(COPY_ALL_LABEL, copyAllButtonRect.centerX(), textY, copyBtnTextPaint);
    }

    private int sampleAverageColor(Rect box) {
        int left = Math.max(0, box.left);
        int top = Math.max(0, box.top);
        int right = Math.min(sourceBitmap.getWidth(), box.right);
        int bottom = Math.min(sourceBitmap.getHeight(), box.bottom);
        if (right <= left || bottom <= top) return Color.DKGRAY;

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

    private int readableTextColor(int backgroundColor) {
        double luminance = (0.299 * Color.red(backgroundColor)
                + 0.587 * Color.green(backgroundColor)
                + 0.114 * Color.blue(backgroundColor)) / 255.0;
        return luminance > 0.55 ? Color.BLACK : Color.WHITE;
    }

    private void drawWrappedText(Canvas canvas, String text, RectF rect) {
        if (text == null || text.trim().isEmpty()) return;

        float maxWidth = rect.width() - dp(4);
        float maxHeight = rect.height() - dp(2);
        if (maxWidth <= 0 || maxHeight <= 0) return;

        float textSize = Math.max(spToPx(MIN_TEXT_SIZE_SP), rect.height() * 0.62f);
        List<String> lines;

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

    // ---- Interaksi: tap salin per-blok, long-press salin teks asli, tombol Salin Semua ----

    private boolean handleTap(float x, float y, boolean isLongPress) {
        if (copyAllButtonRect != null && copyAllButtonRect.contains(x, y)) {
            copyAllBlocksToClipboard();
            return true;
        }

        TranslatedBlock hit = findBlockAt(x, y);
        if (hit == null) return false;

        String textToCopy = isLongPress ? hit.originalText : hit.translatedText;
        String label = isLongPress ? "Teks asli disalin" : "Terjemahan disalin";
        copyToClipboard(textToCopy, label);
        flashBlock(hit);
        return true;
    }

    private TranslatedBlock findBlockAt(float x, float y) {
        float pad = dp(PADDING_DP);
        for (TranslatedBlock block : blocks) {
            Rect box = block.boundingBox;
            if (box == null) continue;
            RectF r = new RectF(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad);
            if (r.contains(x, y)) return block;
        }
        return null;
    }

    private void flashBlock(TranslatedBlock block) {
        flashingBlock = block;
        flashAlpha = 1f;
        invalidate();
        mainHandler.postDelayed(() -> {
            flashAlpha = 0f;
            flashingBlock = null;
            invalidate();
        }, HIGHLIGHT_FLASH_MS);
    }

    private void copyAllBlocksToClipboard() {
        if (blocks.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (TranslatedBlock b : blocks) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(b.translatedText);
        }
        copyToClipboard(sb.toString(), "Semua terjemahan disalin (" + blocks.size() + " blok)");
    }

    private void copyToClipboard(String text, String toastLabel) {
        if (text == null || text.trim().isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Circle Search", text));
        Toast.makeText(getContext(), toastLabel, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);

        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            long sinceAttach = android.os.SystemClock.uptimeMillis() - attachedAtMs;
            if (sinceAttach < DISMISS_GRACE_PERIOD_MS) {
                // Kemungkinan besar residu jari dari tap ikon translate
                // sebelumnya — abaikan supaya overlay yang baru saja
                // tampil tidak langsung "kembali" begitu OCR selesai.
                return true;
            }
            // Tap yang TIDAK kena blok teks maupun tombol Salin Semua
            // dianggap "tap di area kosong" -> kembali ke menu utama
            // (BUKAN menutup semua overlay).
            float x = event.getX();
            float y = event.getY();
            boolean onCopyAllButton = copyAllButtonRect != null && copyAllButtonRect.contains(x, y);
            boolean onBlock = findBlockAt(x, y) != null;
            if (!onCopyAllButton && !onBlock) {
                dismissAnimated();
            }
        }
        return true;
    }
}
