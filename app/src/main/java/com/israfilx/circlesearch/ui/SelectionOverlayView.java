package com.israfilx.circlesearch.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * View overlay yang menampilkan screenshot layar (beku) sebagai
 * background, lalu menangkap gesture jari user untuk menggambar
 * coretan bebas (freeform lasso) di atasnya — sama seperti cara kerja
 * Circle to Search Google.
 *
 * Stroke lasso memakai animasi RGB tiga warna (merah → kuning → hijau)
 * yang mengalir sepanjang path, plus soft glow di luarnya, supaya tali
 * seleksi terasa penuh warna dan hidup.
 *
 * Area gelap di luar coretan sedikit di-dim supaya area yang diseleksi
 * terlihat menonjol (efek spotlight), mengikuti bounding box path yang
 * sedang/sudah digambar.
 *
 * Setelah user mengangkat jari, {@link OnSelectionListener#onSelectionComplete}
 * dipanggil dengan bounding box (dalam koordinat bitmap asli) dari
 * area yang diseleksi, siap untuk di-crop oleh pemanggil.
 */
public class SelectionOverlayView extends View {

    public interface OnSelectionListener {
        /**
         * @param bounds bounding box hasil seleksi, dalam koordinat
         *               bitmap screenshot asli (bukan koordinat layar
         *               view, meski umumnya sama karena overlay full-screen)
         */
        void onSelectionComplete(RectF bounds);

        /** Dipanggil bila user tap sekali tanpa menggambar (dianggap batal). */
        void onSelectionCancelled();

        /**
         * Dipanggil sekali begitu user mulai menggambar (gerakan jari
         * pertama yang terdeteksi sebagai drag, bukan sekadar tap).
         * Menu ikon bawah (BottomIconMenu) tetap ditampilkan apa adanya
         * saat ini terjadi — posisinya sudah di bagian bawah layar
         * sehingga tidak menghalangi area yang sedang diseleksi.
         */
        void onSelectionStarted();
    }

    private final Bitmap frozenScreenshot;
    private OnSelectionListener listener;

    private final Path lassoPath = new Path();
    private final RectF pathBounds = new RectF();

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint lassoFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Stroke inti (tipis, warna RGB segmen) + glow luar (lebih tebal, alpha rendah)
    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // PathMeasure + buffer segmen dipakai ulang tiap frame onDraw
    private final PathMeasure pathMeasure = new PathMeasure();
    private final Path segmentPath = new Path();
    private final float[] pos = new float[2];
    private final float[] tan = new float[2];

    private float lastX, lastY;
    private boolean hasMoved = false;

    /**
     * Fase 0..1 untuk pergeseran warna sepanjang path.
     * Dinaikkan terus oleh animator supaya gradasi RGB "berjalan".
     */
    private float colorPhase = 0f;
    private ValueAnimator rgbFlowAnimator;

    // Tiga warna inti: merah → kuning → hijau (lalu kembali ke merah)
    private static final int COLOR_RED    = 0xFFFF2D2D;
    private static final int COLOR_YELLOW = 0xFFFFD21E;
    private static final int COLOR_GREEN  = 0xFF32DC6E;

    private static final float MIN_DRAG_DISTANCE_PX = 24f;
    private static final long FADE_OUT_DURATION_MS = 160L;

    /** Panjang tiap segmen warna di path (dp). Semakin kecil = gradasi lebih halus. */
    private static final float SEGMENT_DP = 10f;

    public SelectionOverlayView(Context context, Bitmap frozenScreenshot) {
        super(context);
        this.frozenScreenshot = frozenScreenshot;

        // Dim dibuat setransparan mungkin (alpha rendah) — cukup untuk
        // memberi kesan "layar dibekukan/mode seleksi aktif" tanpa
        // menggelapkan konten terlalu banyak, supaya user tetap bisa
        // melihat detail asli layar dengan jelas di balik overlay.
        dimPaint.setColor(Color.argb(48, 0, 0, 0));

        lassoFillPaint.setStyle(Paint.Style.FILL);
        lassoFillPaint.setColor(Color.argb(60, 255, 255, 255));

        segmentPaint.setStyle(Paint.Style.STROKE);
        segmentPaint.setStrokeWidth(dp(3.2f));
        segmentPaint.setStrokeJoin(Paint.Join.ROUND);
        segmentPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(9f));
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        setWillNotDraw(false);
        startRgbFlow();

        // Animasi fade-in halus saat overlay seleksi pertama kali muncul
        // (begitu screencap selesai) — supaya transisi dari layar app
        // biasa ke tampilan "beku + dim" terasa smooth, bukan snap tiba-tiba.
        setAlpha(0f);
        animate()
                .alpha(1f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public void setOnSelectionListener(OnSelectionListener l) {
        this.listener = l;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void startRgbFlow() {
        // 0 → 1 dalam ~1.2 detik, loop infinite — warna seolah mengalir
        // sepanjang tali lasso.
        rgbFlowAnimator = ValueAnimator.ofFloat(0f, 1f);
        rgbFlowAnimator.setDuration(1200);
        rgbFlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rgbFlowAnimator.setInterpolator(new LinearInterpolator());
        rgbFlowAnimator.addUpdateListener(anim -> {
            colorPhase = (float) anim.getAnimatedValue();
            if (hasMoved) invalidate();
        });
        rgbFlowAnimator.start();
    }

    /**
     * Interpolasi warna sepanjang siklus merah → kuning → hijau → merah.
     * @param t nilai 0..1 (sudah dimodulo)
     */
    private static int colorAt(float t) {
        t = t - (float) Math.floor(t); // pastikan 0..1
        if (t < 1f / 3f) {
            return lerpColor(COLOR_RED, COLOR_YELLOW, t * 3f);
        } else if (t < 2f / 3f) {
            return lerpColor(COLOR_YELLOW, COLOR_GREEN, (t - 1f / 3f) * 3f);
        } else {
            return lerpColor(COLOR_GREEN, COLOR_RED, (t - 2f / 3f) * 3f);
        }
    }

    private static int lerpColor(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * f);
        int g = (int) (ag + (bg - ag) * f);
        int bl = (int) (ab + (bb - ab) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Gambar path lasso sebagai rangkaian segmen pendek, masing-masing
     * dengan warna RGB yang bergeser menurut posisi di path + colorPhase.
     * Glow digambar dulu (di bawah), lalu stroke inti di atasnya.
     */
    private void drawRgbLasso(Canvas canvas) {
        pathMeasure.setPath(lassoPath, false);
        float length = pathMeasure.getLength();
        if (length < 1f) return;

        float segmentLen = dp(SEGMENT_DP);
        // Satu siklus warna penuh kira-kira sepanjang ~180dp path
        float cycleLen = dp(180f);

        for (float d = 0f; d < length; d += segmentLen) {
            float end = Math.min(d + segmentLen + 1f, length); // +1 overlap anti-celah
            segmentPath.reset();
            if (!pathMeasure.getSegment(d, end, segmentPath, true)) continue;

            // Posisi relatif di path + fase animasi → warna
            float t = ((d / cycleLen) + colorPhase) % 1f;
            if (t < 0f) t += 1f;
            int color = colorAt(t);

            // Glow luar (alpha rendah)
            glowPaint.setColor((color & 0x00FFFFFF) | 0x55000000);
            canvas.drawPath(segmentPath, glowPaint);

            // Stroke inti solid
            segmentPaint.setColor(color);
            canvas.drawPath(segmentPath, segmentPaint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Gambar screenshot beku sebagai background, di-scale agar pas
        //    dengan ukuran view (overlay full-screen, jadi umumnya 1:1).
        Rect dst = new Rect(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(frozenScreenshot, null, dst, bitmapPaint);

        // 2. Dim seluruh layar sedikit supaya area seleksi terasa menonjol.
        canvas.drawRect(dst, dimPaint);

        // 3. Gambar coretan lasso + area terselect tidak di-dim (di-punch out).
        if (hasMoved) {
            // Punch-out: gambar ulang bitmap asli (tanpa dim) hanya di area
            // yang sudah dilingkari, memakai path sebagai clip.
            int saveCount = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            canvas.drawPath(lassoPath, lassoFillPaint);
            Paint clipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            clipPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(frozenScreenshot, null, dst, clipPaint);
            canvas.restoreToCount(saveCount);

            // Stroke RGB mengalir sepanjang path
            drawRgbLasso(canvas);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lassoPath.reset();
                pathBounds.setEmpty();
                hasMoved = false;
                lassoPath.moveTo(x, y);
                lastX = x;
                lastY = y;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(x - lastX);
                float dy = Math.abs(y - lastY);
                if (dx >= 3 || dy >= 3) {
                    // Quad-to untuk kurva yang lebih halus mengikuti jari
                    lassoPath.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2);
                    lastX = x;
                    lastY = y;
                    boolean wasFirstMove = !hasMoved;
                    hasMoved = true;
                    if (wasFirstMove && listener != null) {
                        listener.onSelectionStarted();
                    }
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                lassoPath.lineTo(x, y);
                lassoPath.close();

                computePathBounds();

                float dragDistance = Math.max(pathBounds.width(), pathBounds.height());
                if (!hasMoved || dragDistance < MIN_DRAG_DISTANCE_PX) {
                    // Dianggap tap saja, bukan seleksi — batalkan.
                    lassoPath.reset();
                    hasMoved = false;
                    invalidate();
                    if (listener != null) listener.onSelectionCancelled();
                    return true;
                }

                invalidate();
                if (listener != null) {
                    listener.onSelectionComplete(new RectF(pathBounds));
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void computePathBounds() {
        RectF bounds = new RectF();
        lassoPath.computeBounds(bounds, true);

        // Clamp ke ukuran view supaya tidak keluar batas bitmap saat crop.
        bounds.left = Math.max(0, bounds.left);
        bounds.top = Math.max(0, bounds.top);
        bounds.right = Math.min(getWidth(), bounds.right);
        bounds.bottom = Math.min(getHeight(), bounds.bottom);

        pathBounds.set(bounds);
    }

    /** Path lasso saat ini, dipakai untuk membuat masked-crop (bukan cuma bounding box). */
    public Path getLassoPath() {
        return lassoPath;
    }

    /** Hentikan animator internal (RGB flow). Dipanggil sebelum view benar-benar dilepas. */
    public void destroy() {
        if (rgbFlowAnimator != null) {
            rgbFlowAnimator.cancel();
        }
    }

    /**
     * Fade-out singkat sebelum view ini dilepas dari WindowManager, supaya
     * penutupan overlay (tombol ✕, tap-di-luar tanpa seleksi, dst) terasa
     * smooth simetris dengan fade-in kemunculannya — sebelumnya overlay ini
     * langsung hilang seketika (snap) saat ditutup, berbeda dari
     * TranslationOverlayView/LoadingStatusView yang sudah pakai fade-out.
     *
     * rgbFlowAnimator dibatalkan lebih dulu supaya tidak ada
     * invalidate() sia-sia selama fade berjalan.
     *
     * @param onEnd dipanggil setelah animasi selesai — pemanggil (Service)
     *              yang bertanggung jawab benar-benar me-remove view dari
     *              WindowManager di sini, sama seperti pola dismissAnimated
     *              pada TranslationOverlayView.
     */
    public void dismissAnimated(Runnable onEnd) {
        if (rgbFlowAnimator != null) {
            rgbFlowAnimator.cancel();
        }
        animate()
                .alpha(0f)
                .setDuration(FADE_OUT_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (onEnd != null) onEnd.run();
                    }
                })
                .start();
    }
}
