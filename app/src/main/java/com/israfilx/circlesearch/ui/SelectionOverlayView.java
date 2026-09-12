package com.israfilx.circlesearch.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * Overlay seleksi ala Circle to Search:
 *  1. User menggambar lasso bebas (dengan stroke putih mengalir + denyut).
 *  2. Saat jari diangkat, path diubah otomatis menjadi bingkai
 *     persegi/persegi panjang (bounding box) yang rapi.
 *  3. Bingkai rounded dengan 4 sudut halus (handle sudut saja, ala CTS)
 *     untuk memperbesar/memperkecil, serta digeser utuh dengan drag di dalam.
 *  4. Aksi Cari / Translate / Salin memakai crop persegi akhir.
 */
public class SelectionOverlayView extends View {

    public interface OnSelectionListener {
        /** Dipanggil saat lasso selesai → masuk mode bingkai adjustable. */
        void onSelectionComplete(RectF bounds);

        /** Dipanggil tiap kali user mengubah ukuran/posisi bingkai. */
        void onSelectionBoundsChanged(RectF bounds);

        void onSelectionCancelled();

        void onSelectionStarted();
    }

    private enum Mode { IDLE, DRAWING, ADJUST }

    private final Bitmap frozenScreenshot;
    private OnSelectionListener listener;

    private final Path lassoPath = new Path();
    private final RectF selectionRect = new RectF();
    private final RectF pathBounds = new RectF();

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint lassoFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerBlurPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frameFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final PathMeasure pathMeasure = new PathMeasure();
    private final Path segmentPath = new Path();

    private Mode mode = Mode.IDLE;
    private float lastX, lastY;
    private boolean hasMoved = false;

    // Handle drag state (ADJUST mode)
    // 0=TL 1=TR 2=BR 3=BL, HANDLE_MOVE = geser body
    private int activeHandle = HANDLE_NONE;
    private float touchOffsetX, touchOffsetY;

    private float flowPhase = 0f;
    private float pulsePhase = 0f; // 0..1 denyut smooth
    private ValueAnimator flowAnimator;
    private ValueAnimator pulseAnimator;

    private static final int HANDLE_NONE = -1;
    private static final int HANDLE_MOVE = 8;
    private static final int HANDLE_TL = 0;
    private static final int HANDLE_TR = 1;
    private static final int HANDLE_BR = 2;
    private static final int HANDLE_BL = 3;

    // Palet resmi 4 warna Google (dipakai bergantian di seluruh brand:
    // logo, Circle to Search, loading spinner), diputar sebagai sweep
    // gradient berjalan di sekeliling bingkai — ala CTS asli.
    private static final int GOOGLE_BLUE = Color.parseColor("#4285F4");
    private static final int GOOGLE_RED = Color.parseColor("#EA4335");
    private static final int GOOGLE_YELLOW = Color.parseColor("#FBBC05");
    private static final int GOOGLE_GREEN = Color.parseColor("#34A853");
    private final int[] googleSweepColors = {
            GOOGLE_BLUE, GOOGLE_RED, GOOGLE_YELLOW, GOOGLE_GREEN, GOOGLE_BLUE
    };
    private final Matrix sweepMatrix = new Matrix();

    private static final float MIN_DRAG_DISTANCE_PX = 24f;
    private static final float MIN_RECT_SIZE_DP = 40f;
    private static final long FADE_OUT_DURATION_MS = 160L;
    private static final float SEGMENT_DP = 10f;

    public SelectionOverlayView(Context context, Bitmap frozenScreenshot) {
        super(context);
        this.frozenScreenshot = frozenScreenshot;

        // Dim gelap ala CTS: area di luar bingkai jelas meredup, bukan sekadar
        // sedikit tergelapkan, supaya perhatian jatuh ke objek yang diseleksi.
        dimPaint.setColor(Color.argb(150, 0, 0, 0));

        lassoFillPaint.setStyle(Paint.Style.FILL);
        lassoFillPaint.setColor(Color.argb(50, 255, 255, 255));

        segmentPaint.setStyle(Paint.Style.STROKE);
        segmentPaint.setStrokeWidth(dp(3.2f));
        segmentPaint.setStrokeJoin(Paint.Join.ROUND);
        segmentPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(11f));
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        // Lapisan blur terluar: sebar cahaya warna-warni ke kanvas di luar
        // garis border, bukan cuma stroke transparan lebar. setLayerType
        // SOFTWARE wajib di view yang memakai BlurMaskFilter agar tampil.
        outerBlurPaint.setStyle(Paint.Style.STROKE);
        outerBlurPaint.setStrokeJoin(Paint.Join.ROUND);
        outerBlurPaint.setStrokeCap(Paint.Cap.ROUND);
        outerBlurPaint.setMaskFilter(new BlurMaskFilter(dp(16f), BlurMaskFilter.Blur.NORMAL));

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(2.8f));
        framePaint.setColor(Color.WHITE);
        framePaint.setStrokeJoin(Paint.Join.ROUND);
        framePaint.setStrokeCap(Paint.Cap.ROUND);

        frameFillPaint.setStyle(Paint.Style.FILL);
        frameFillPaint.setColor(Color.argb(20, 255, 255, 255));

        // Sudut handle: bracket putih solid, tebal, ujung membulat (ala CTS asli)
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeWidth(dp(5.5f));
        handlePaint.setStrokeCap(Paint.Cap.ROUND);
        handlePaint.setStrokeJoin(Paint.Join.ROUND);
        handlePaint.setColor(Color.WHITE);

        // Bayangan tipis di belakang bracket agar tetap terbaca di atas
        // latar terang, tanpa membuat efek glow warna-warni.
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(dp(8.5f));
        handleStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        handleStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        handleStrokePaint.setColor(Color.argb(90, 0, 0, 0));

        setWillNotDraw(false);
        // BlurMaskFilter (dipakai outerBlurPaint) tidak dirender oleh hardware
        // acceleration di Android — wajib software layer agar glow benar-benar
        // tampil, bukan hilang diam-diam.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        startRgbFlow();

        setAlpha(0f);
        animate().alpha(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    public void setOnSelectionListener(OnSelectionListener l) {
        this.listener = l;
    }

    /** Bounds bingkai saat ini (mode ADJUST) atau path bounds. */
    public RectF getSelectionBounds() {
        if (mode == Mode.ADJUST && !selectionRect.isEmpty()) {
            return new RectF(selectionRect);
        }
        return new RectF(pathBounds);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void startRgbFlow() {
        // Aliran highlight putih sepanjang path
        flowAnimator = ValueAnimator.ofFloat(0f, 1f);
        flowAnimator.setDuration(1400);
        flowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        flowAnimator.setInterpolator(new LinearInterpolator());
        flowAnimator.addUpdateListener(anim -> {
            flowPhase = (float) anim.getAnimatedValue();
            if (hasMoved || mode == Mode.ADJUST) invalidate();
        });
        flowAnimator.start();

        // Denyut smooth: alpha + sedikit tebal stroke
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1600);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new DecelerateInterpolator());
        pulseAnimator.addUpdateListener(anim -> {
            pulsePhase = (float) anim.getAnimatedValue();
            if (hasMoved || mode == Mode.ADJUST) invalidate();
        });
        pulseAnimator.start();
    }

    /**
     * Sweep gradient 4 warna Google (biru-merah-kuning-hijau) berpusat di
     * tengah rect/path, berputar terus mengikuti flowPhase — inilah border
     * "pelangi Google" khas CTS/Circle to Search, bukan cincin putih polos.
     * Denyut global sedikit menaikkan opacity keseluruhan.
     */
    private SweepGradient buildGoogleSweep(float cx, float cy, float pulse) {
        SweepGradient sweep = new SweepGradient(cx, cy, googleSweepColors, null);
        sweepMatrix.reset();
        sweepMatrix.postRotate(flowPhase * 360f, cx, cy);
        sweep.setLocalMatrix(sweepMatrix);
        return sweep;
    }

    private void drawRgbPath(Canvas canvas, Path path) {
        pathMeasure.setPath(path, false);
        float length = pathMeasure.getLength();
        if (length < 1f) return;

        RectF bounds = new RectF();
        path.computeBounds(bounds, true);
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float pulse = pulsePhase;

        // Stroke width ikut denyut sedikit
        float pulseW = 1f + 0.18f * pulse;
        float coreWidth = dp(3.6f) * pulseW;
        float glowWidth = dp(10f) * pulseW;
        int baseAlpha = Math.max(1, Math.min(255, (int) (255f * (0.82f + 0.18f * pulse))));

        SweepGradient sweep = buildGoogleSweep(cx, cy, pulse);
        float blurWidth = dp(22f) * pulseW;

        // 1) Blur terluar: cahaya menyebar lembut ke kedua sisi garis,
        //    inilah kesan "menyala" ala CTS yang tidak didapat dari stroke biasa.
        outerBlurPaint.setShader(sweep);
        outerBlurPaint.setStrokeWidth(blurWidth);
        outerBlurPaint.setAlpha((int) (baseAlpha * 0.6f));
        canvas.drawPath(path, outerBlurPaint);

        // 2) Glow tengah: lebih pekat, transisi antara blur dan garis inti.
        glowPaint.setShader(sweep);
        glowPaint.setStrokeWidth(glowWidth);
        glowPaint.setAlpha((int) (baseAlpha * 0.55f));
        canvas.drawPath(path, glowPaint);

        // 3) Garis inti: tegas dan tipis, warna paling jenuh.
        segmentPaint.setShader(sweep);
        segmentPaint.setStrokeWidth(coreWidth);
        segmentPaint.setAlpha(baseAlpha);
        canvas.drawPath(path, segmentPaint);

        // Bersihkan shader agar paint ini tidak "bocor" dipakai di draw lain
        outerBlurPaint.setShader(null);
        glowPaint.setShader(null);
        segmentPaint.setShader(null);
    }

    /** Border gradasi 4-warna Google berputar di sekeliling rect (mode ADJUST). */
    private void drawRgbRect(Canvas canvas, RectF r) {
        Path rectPath = new Path();
        float radius = dp(14);
        rectPath.addRoundRect(r, radius, radius, Path.Direction.CW);
        drawRgbPath(canvas, rectPath);
    }

    /**
     * Gambar 4 sudut halus ala Circle to Search: bracket melengkung
     * di tiap corner (bukan 8 titik lingkaran).
     */
    private void drawHandles(Canvas canvas, RectF r) {
        float len = dp(22);   // panjang lengan sudut (lebih tegas, ala CTS)
        float rad = dp(14);   // radius lengkung sudut (selaras bingkai)
        Path corner = new Path();

        // TL
        corner.reset();
        corner.moveTo(r.left, r.top + len);
        corner.lineTo(r.left, r.top + rad);
        corner.quadTo(r.left, r.top, r.left + rad, r.top);
        corner.lineTo(r.left + len, r.top);
        canvas.drawPath(corner, handleStrokePaint);
        canvas.drawPath(corner, handlePaint);

        // TR
        corner.reset();
        corner.moveTo(r.right - len, r.top);
        corner.lineTo(r.right - rad, r.top);
        corner.quadTo(r.right, r.top, r.right, r.top + rad);
        corner.lineTo(r.right, r.top + len);
        canvas.drawPath(corner, handleStrokePaint);
        canvas.drawPath(corner, handlePaint);

        // BR
        corner.reset();
        corner.moveTo(r.right, r.bottom - len);
        corner.lineTo(r.right, r.bottom - rad);
        corner.quadTo(r.right, r.bottom, r.right - rad, r.bottom);
        corner.lineTo(r.right - len, r.bottom);
        canvas.drawPath(corner, handleStrokePaint);
        canvas.drawPath(corner, handlePaint);

        // BL
        corner.reset();
        corner.moveTo(r.left + len, r.bottom);
        corner.lineTo(r.left + rad, r.bottom);
        corner.quadTo(r.left, r.bottom, r.left, r.bottom - rad);
        corner.lineTo(r.left, r.bottom - len);
        canvas.drawPath(corner, handleStrokePaint);
        canvas.drawPath(corner, handlePaint);
    }

    private float[][] cornerPoints(RectF r) {
        return new float[][]{
                {r.left, r.top},
                {r.right, r.top},
                {r.right, r.bottom},
                {r.left, r.bottom}
        };
    }

    private int hitTestHandle(float x, float y) {
        float touchR = dp(28); // target sentuh sudut lebih lega
        float[][] pts = cornerPoints(selectionRect);
        for (int i = 0; i < pts.length; i++) {
            float dx = x - pts[i][0];
            float dy = y - pts[i][1];
            if (dx * dx + dy * dy <= touchR * touchR) return i;
        }
        // Sedikit inset agar drag di dekat tepi sudut tetap prioritas sudut
        RectF body = new RectF(selectionRect);
        body.inset(dp(12), dp(12));
        if (body.contains(x, y) || selectionRect.contains(x, y)) return HANDLE_MOVE;
        return HANDLE_NONE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Rect dst = new Rect(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(frozenScreenshot, null, dst, bitmapPaint);
        canvas.drawRect(dst, dimPaint);

        if (mode == Mode.DRAWING && hasMoved) {
            // Punch-out + stroke lasso
            int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            canvas.drawPath(lassoPath, lassoFillPaint);
            Paint clip = new Paint(Paint.ANTI_ALIAS_FLAG);
            clip.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(frozenScreenshot, null, dst, clip);
            canvas.restoreToCount(save);
            drawRgbPath(canvas, lassoPath);
        } else if (mode == Mode.ADJUST && !selectionRect.isEmpty()) {
            // Punch-out rectangular
            int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            Path rr = new Path();
            float radius = dp(14);
            rr.addRoundRect(selectionRect, radius, radius, Path.Direction.CW);
            canvas.drawPath(rr, lassoFillPaint);
            Paint clip = new Paint(Paint.ANTI_ALIAS_FLAG);
            clip.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(frozenScreenshot, null, dst, clip);
            canvas.restoreToCount(save);

            canvas.drawRoundRect(selectionRect, radius, radius, frameFillPaint);
            drawRgbRect(canvas, selectionRect);
            drawHandles(canvas, selectionRect);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        if (mode == Mode.ADJUST) {
            return handleAdjustTouch(event, x, y);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lassoPath.reset();
                pathBounds.setEmpty();
                selectionRect.setEmpty();
                hasMoved = false;
                mode = Mode.DRAWING;
                lassoPath.moveTo(x, y);
                lastX = x;
                lastY = y;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(x - lastX);
                float dy = Math.abs(y - lastY);
                if (dx >= 3 || dy >= 3) {
                    lassoPath.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2);
                    lastX = x;
                    lastY = y;
                    boolean first = !hasMoved;
                    hasMoved = true;
                    if (first && listener != null) listener.onSelectionStarted();
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                lassoPath.lineTo(x, y);
                lassoPath.close();
                computePathBounds();

                float drag = Math.max(pathBounds.width(), pathBounds.height());
                if (!hasMoved || drag < MIN_DRAG_DISTANCE_PX) {
                    lassoPath.reset();
                    hasMoved = false;
                    mode = Mode.IDLE;
                    invalidate();
                    if (listener != null) listener.onSelectionCancelled();
                    return true;
                }

                // Konversi lasso → bingkai persegi panjang adjustable
                selectionRect.set(pathBounds);
                // Sedikit padding biar objek tidak mepet tepi
                float pad = dp(4);
                selectionRect.inset(-pad, -pad);
                clampRect(selectionRect);
                mode = Mode.ADJUST;
                invalidate();
                if (listener != null) {
                    listener.onSelectionComplete(new RectF(selectionRect));
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private boolean handleAdjustTouch(MotionEvent event, float x, float y) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeHandle = hitTestHandle(x, y);
                if (activeHandle == HANDLE_NONE) {
                    // Tap di luar: boleh mulai lasso baru
                    mode = Mode.DRAWING;
                    lassoPath.reset();
                    hasMoved = false;
                    selectionRect.setEmpty();
                    lassoPath.moveTo(x, y);
                    lastX = x;
                    lastY = y;
                    invalidate();
                    return true;
                }
                if (activeHandle == HANDLE_MOVE) {
                    touchOffsetX = x - selectionRect.left;
                    touchOffsetY = y - selectionRect.top;
                } else {
                    touchOffsetX = x;
                    touchOffsetY = y;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activeHandle == HANDLE_NONE) return true;
                if (activeHandle == HANDLE_MOVE) {
                    float w = selectionRect.width();
                    float h = selectionRect.height();
                    float nl = x - touchOffsetX;
                    float nt = y - touchOffsetY;
                    selectionRect.set(nl, nt, nl + w, nt + h);
                    clampRect(selectionRect);
                } else {
                    resizeByHandle(activeHandle, x, y);
                    clampRect(selectionRect);
                }
                invalidate();
                if (listener != null) {
                    listener.onSelectionBoundsChanged(new RectF(selectionRect));
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeHandle = HANDLE_NONE;
                if (listener != null && !selectionRect.isEmpty()) {
                    listener.onSelectionBoundsChanged(new RectF(selectionRect));
                }
                return true;
        }
        return true;
    }

    private void resizeByHandle(int handle, float x, float y) {
        float min = dp(MIN_RECT_SIZE_DP);
        switch (handle) {
            case HANDLE_TL: // kiri-atas
                selectionRect.left = Math.min(x, selectionRect.right - min);
                selectionRect.top = Math.min(y, selectionRect.bottom - min);
                break;
            case HANDLE_TR: // kanan-atas
                selectionRect.right = Math.max(x, selectionRect.left + min);
                selectionRect.top = Math.min(y, selectionRect.bottom - min);
                break;
            case HANDLE_BR: // kanan-bawah
                selectionRect.right = Math.max(x, selectionRect.left + min);
                selectionRect.bottom = Math.max(y, selectionRect.top + min);
                break;
            case HANDLE_BL: // kiri-bawah
                selectionRect.left = Math.min(x, selectionRect.right - min);
                selectionRect.bottom = Math.max(y, selectionRect.top + min);
                break;
        }
    }

    private void clampRect(RectF r) {
        float min = dp(MIN_RECT_SIZE_DP);
        if (r.left < 0) r.left = 0;
        if (r.top < 0) r.top = 0;
        if (r.right > getWidth()) r.right = getWidth();
        if (r.bottom > getHeight()) r.bottom = getHeight();
        if (r.width() < min) r.right = r.left + min;
        if (r.height() < min) r.bottom = r.top + min;
        if (r.right > getWidth()) {
            r.right = getWidth();
            r.left = r.right - min;
        }
        if (r.bottom > getHeight()) {
            r.bottom = getHeight();
            r.top = r.bottom - min;
        }
        if (r.left < 0) r.left = 0;
        if (r.top < 0) r.top = 0;
    }

    private void computePathBounds() {
        RectF bounds = new RectF();
        lassoPath.computeBounds(bounds, true);
        bounds.left = Math.max(0, bounds.left);
        bounds.top = Math.max(0, bounds.top);
        bounds.right = Math.min(getWidth(), bounds.right);
        bounds.bottom = Math.min(getHeight(), bounds.bottom);
        pathBounds.set(bounds);
    }

    public void destroy() {
        if (flowAnimator != null) flowAnimator.cancel();
        if (pulseAnimator != null) pulseAnimator.cancel();
    }

    public void dismissAnimated(Runnable onEnd) {
        if (flowAnimator != null) flowAnimator.cancel();
        if (pulseAnimator != null) pulseAnimator.cancel();
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
