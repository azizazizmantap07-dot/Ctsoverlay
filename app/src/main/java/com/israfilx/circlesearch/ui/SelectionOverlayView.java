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
 *  1. User menggambar lasso bebas (dengan stroke RGB mengalir).
 *  2. Saat jari diangkat, path diubah otomatis menjadi bingkai
 *     persegi/persegi panjang (bounding box) yang rapi.
 *  3. Bingkai bisa diperbesar/diperkecil lewat 8 handle (4 sudut + 4
 *     tengah sisi) dan digeser utuh dengan drag di dalam area.
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
    private int activeHandle = HANDLE_NONE; // -1 none, 0-7 handles, 8 = move body
    private float touchOffsetX, touchOffsetY;

    private float colorPhase = 0f;
    private ValueAnimator rgbFlowAnimator;

    private static final int HANDLE_NONE = -1;
    private static final int HANDLE_MOVE = 8;
    // 0=TL 1=T 2=TR 3=R 4=BR 5=B 6=BL 7=L
    private static final int COLOR_RED    = 0xFFFF2D2D;
    private static final int COLOR_YELLOW = 0xFFFFD21E;
    private static final int COLOR_GREEN  = 0xFF32DC6E;

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
        glowPaint.setStrokeWidth(dp(9f));
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(2.5f));
        framePaint.setColor(Color.WHITE);

        frameFillPaint.setStyle(Paint.Style.FILL);
        frameFillPaint.setColor(Color.argb(28, 255, 255, 255));

        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);

        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(dp(1.5f));
        handleStrokePaint.setColor(Color.parseColor("#33FFFFFF"));

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
        rgbFlowAnimator = ValueAnimator.ofFloat(0f, 1f);
        rgbFlowAnimator.setDuration(1200);
        rgbFlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rgbFlowAnimator.setInterpolator(new LinearInterpolator());
        rgbFlowAnimator.addUpdateListener(anim -> {
            colorPhase = (float) anim.getAnimatedValue();
            if (hasMoved || mode == Mode.ADJUST) invalidate();
        });
        rgbFlowAnimator.start();
    }

    private static int colorAt(float t) {
        t = t - (float) Math.floor(t);
        if (t < 1f / 3f) return lerpColor(COLOR_RED, COLOR_YELLOW, t * 3f);
        if (t < 2f / 3f) return lerpColor(COLOR_YELLOW, COLOR_GREEN, (t - 1f / 3f) * 3f);
        return lerpColor(COLOR_GREEN, COLOR_RED, (t - 2f / 3f) * 3f);
    }

    private static int lerpColor(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | ((int) (ar + (br - ar) * f) << 16)
                | ((int) (ag + (bg - ag) * f) << 8)
                | (int) (ab + (bb - ab) * f);
    }

    private void drawRgbPath(Canvas canvas, Path path) {
        pathMeasure.setPath(path, false);
        float length = pathMeasure.getLength();
        if (length < 1f) return;
        float segmentLen = dp(SEGMENT_DP);
        float cycleLen = dp(180f);
        for (float d = 0f; d < length; d += segmentLen) {
            float end = Math.min(d + segmentLen + 1f, length);
            segmentPath.reset();
            if (!pathMeasure.getSegment(d, end, segmentPath, true)) continue;
            float t = ((d / cycleLen) + colorPhase) % 1f;
            if (t < 0f) t += 1f;
            int color = colorAt(t);
            glowPaint.setColor((color & 0x00FFFFFF) | 0x55000000);
            canvas.drawPath(segmentPath, glowPaint);
            segmentPaint.setColor(color);
            canvas.drawPath(segmentPath, segmentPaint);
        }
    }

    /** Stroke RGB di sekeliling rect (untuk mode ADJUST). */
    private void drawRgbRect(Canvas canvas, RectF r) {
        Path rectPath = new Path();
        float radius = dp(10);
        rectPath.addRoundRect(r, radius, radius, Path.Direction.CW);
        drawRgbPath(canvas, rectPath);
    }

    private void drawHandles(Canvas canvas, RectF r) {
        float hs = dp(7); // handle radius
        float[][] pts = handlePoints(r);
        for (float[] p : pts) {
            canvas.drawCircle(p[0], p[1], hs + dp(2), handleStrokePaint);
            canvas.drawCircle(p[0], p[1], hs, handlePaint);
        }
    }

    private float[][] handlePoints(RectF r) {
        float cx = r.centerX();
        float cy = r.centerY();
        return new float[][]{
                {r.left, r.top}, {cx, r.top}, {r.right, r.top},
                {r.right, cy},
                {r.right, r.bottom}, {cx, r.bottom}, {r.left, r.bottom},
                {r.left, cy}
        };
    }

    private int hitTestHandle(float x, float y) {
        float touchR = dp(22);
        float[][] pts = handlePoints(selectionRect);
        for (int i = 0; i < pts.length; i++) {
            float dx = x - pts[i][0];
            float dy = y - pts[i][1];
            if (dx * dx + dy * dy <= touchR * touchR) return i;
        }
        if (selectionRect.contains(x, y)) return HANDLE_MOVE;
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
            float radius = dp(10);
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
            case 0: // TL
                selectionRect.left = Math.min(x, selectionRect.right - min);
                selectionRect.top = Math.min(y, selectionRect.bottom - min);
                break;
            case 1: // T
                selectionRect.top = Math.min(y, selectionRect.bottom - min);
                break;
            case 2: // TR
                selectionRect.right = Math.max(x, selectionRect.left + min);
                selectionRect.top = Math.min(y, selectionRect.bottom - min);
                break;
            case 3: // R
                selectionRect.right = Math.max(x, selectionRect.left + min);
                break;
            case 4: // BR
                selectionRect.right = Math.max(x, selectionRect.left + min);
                selectionRect.bottom = Math.max(y, selectionRect.top + min);
                break;
            case 5: // B
                selectionRect.bottom = Math.max(y, selectionRect.top + min);
                break;
            case 6: // BL
                selectionRect.left = Math.min(x, selectionRect.right - min);
                selectionRect.bottom = Math.max(y, selectionRect.top + min);
                break;
            case 7: // L
                selectionRect.left = Math.min(x, selectionRect.right - min);
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
        if (rgbFlowAnimator != null) rgbFlowAnimator.cancel();
    }

    public void dismissAnimated(Runnable onEnd) {
        if (rgbFlowAnimator != null) rgbFlowAnimator.cancel();
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
