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
    // Stroke putih: intensitas penuh, mengalir + berdenyut

    private static final float MIN_DRAG_DISTANCE_PX = 24f;
    private static final float MIN_RECT_SIZE_DP = 40f;
    private static final long FADE_OUT_DURATION_MS = 160L;
    private static final float SEGMENT_DP = 10f;

    public SelectionOverlayView(Context context, Bitmap frozenScreenshot) {
        super(context);
        this.frozenScreenshot = frozenScreenshot;

        dimPaint.setColor(Color.argb(48, 0, 0, 0));

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

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(2.8f));
        framePaint.setColor(Color.WHITE);
        framePaint.setStrokeJoin(Paint.Join.ROUND);
        framePaint.setStrokeCap(Paint.Cap.ROUND);

        frameFillPaint.setStyle(Paint.Style.FILL);
        frameFillPaint.setColor(Color.argb(20, 255, 255, 255));

        // Sudut handle: stroke putih tebal, ujung membulat (ala CTS)
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeWidth(dp(4.2f));
        handlePaint.setStrokeCap(Paint.Cap.ROUND);
        handlePaint.setStrokeJoin(Paint.Join.ROUND);
        handlePaint.setColor(Color.WHITE);

        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(dp(7f));
        handleStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        handleStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        handleStrokePaint.setColor(Color.argb(70, 255, 255, 255));

        setWillNotDraw(false);
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

    /** Path lasso asli (boleh kosong setelah masuk mode ADJUST). */
    public Path getLassoPath() {
        return lassoPath;
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
     * Alpha putih sepanjang path: gelombang terang mengalir (peak ~255),
     * area lain tetap putih lembut. Dikombinasikan dengan denyut global.
     */
    private int whiteAt(float t, float pulse) {
        t = t - (float) Math.floor(t);
        // Dua puncak terang per siklus agar aliran terasa terus-menerus
        float wave = (float) Math.sin(t * Math.PI * 2.0);
        wave = wave * wave; // 0..1, puncak lebih tajam
        float base = 0.45f + 0.55f * wave; // 0.45..1.0
        float pulseAmp = 0.72f + 0.28f * pulse; // denyut 72%..100%
        int alpha = Math.max(1, Math.min(255, (int) (255f * base * pulseAmp)));
        return (alpha << 24) | 0x00FFFFFF;
    }

    private void drawRgbPath(Canvas canvas, Path path) {
        pathMeasure.setPath(path, false);
        float length = pathMeasure.getLength();
        if (length < 1f) return;
        float segmentLen = dp(SEGMENT_DP);
        float cycleLen = dp(160f);
        float pulse = pulsePhase;
        // Stroke width ikut denyut sedikit
        float pulseW = 1f + 0.18f * pulse;
        segmentPaint.setStrokeWidth(dp(3.2f) * pulseW);
        glowPaint.setStrokeWidth(dp(10f) * pulseW);

        for (float d = 0f; d < length; d += segmentLen) {
            float end = Math.min(d + segmentLen + 1f, length);
            segmentPath.reset();
            if (!pathMeasure.getSegment(d, end, segmentPath, true)) continue;
            float t = ((d / cycleLen) + flowPhase) % 1f;
            if (t < 0f) t += 1f;
            int color = whiteAt(t, pulse);
            int a = (color >>> 24) & 0xFF;
            // Glow lebih transparan dari core stroke
            int glowA = Math.max(1, (int) (a * 0.28f));
            glowPaint.setColor((glowA << 24) | 0x00FFFFFF);
            canvas.drawPath(segmentPath, glowPaint);
            segmentPaint.setColor(color);
            canvas.drawPath(segmentPath, segmentPaint);
        }
    }

    /** Stroke putih mengalir di sekeliling rect (mode ADJUST). */
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
        float len = dp(18);   // panjang lengan sudut
        float rad = dp(12);   // radius lengkung sudut (selaras bingkai)
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
