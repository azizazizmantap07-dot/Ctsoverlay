package com.israfilx.circlesearch.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * View overlay yang menampilkan screenshot layar (beku) sebagai
 * background, lalu menangkap gesture jari user untuk menggambar
 * coretan bebas (freeform lasso) di atasnya — sama seperti cara kerja
 * Circle to Search Google.
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
         * Dipakai pemanggil untuk menyembunyikan menu awal (mis.
         * InitialQuickActionMenu) begitu user beralih ke mode seleksi
         * manual.
         */
        void onSelectionStarted();
    }

    private final Bitmap frozenScreenshot;
    private OnSelectionListener listener;

    private final Path lassoPath = new Path();
    private final RectF pathBounds = new RectF();

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint lassoStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lassoFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float lastX, lastY;
    private boolean hasMoved = false;

    // Dash offset untuk animasi garis putus-putus berjalan (efek "marching ants"),
    // sesuai kesan visual Circle to Search asli.
    private float dashOffset = 0f;
    private ValueAnimator marchingAntsAnimator;

    private static final float MIN_DRAG_DISTANCE_PX = 24f;

    public SelectionOverlayView(Context context, Bitmap frozenScreenshot) {
        super(context);
        this.frozenScreenshot = frozenScreenshot;

        dimPaint.setColor(Color.argb(140, 0, 0, 0));

        lassoStrokePaint.setStyle(Paint.Style.STROKE);
        lassoStrokePaint.setStrokeWidth(dp(3));
        lassoStrokePaint.setColor(Color.WHITE);
        lassoStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        lassoStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        lassoStrokePaint.setPathEffect(new DashPathEffect(new float[]{dp(14), dp(8)}, 0));

        lassoFillPaint.setStyle(Paint.Style.FILL);
        lassoFillPaint.setColor(Color.argb(60, 255, 255, 255));

        setWillNotDraw(false);
        startMarchingAnts();
    }

    public void setOnSelectionListener(OnSelectionListener l) {
        this.listener = l;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void startMarchingAnts() {
        marchingAntsAnimator = ValueAnimator.ofFloat(0f, dp(22));
        marchingAntsAnimator.setDuration(600);
        marchingAntsAnimator.setRepeatCount(ValueAnimator.INFINITE);
        marchingAntsAnimator.setInterpolator(new LinearInterpolator());
        marchingAntsAnimator.addUpdateListener(anim -> {
            dashOffset = (float) anim.getAnimatedValue();
            if (hasMoved) invalidate();
        });
        marchingAntsAnimator.start();
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

            lassoStrokePaint.setPathEffect(new DashPathEffect(new float[]{dp(14), dp(8)}, dashOffset));
            canvas.drawPath(lassoPath, lassoStrokePaint);
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
        PathMeasure measure = new PathMeasure(lassoPath, false);
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

    public void destroy() {
        if (marchingAntsAnimator != null) {
            marchingAntsAnimator.cancel();
        }
    }
}
