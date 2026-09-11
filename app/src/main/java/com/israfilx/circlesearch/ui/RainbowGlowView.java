package com.israfilx.circlesearch.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Garis glow tipis warna-warni (RGB berputar) yang berjalan di sepanjang
 * BINGKAI (tepi) layar begitu overlay dipicu — animasi kedatangan sekali
 * jalan, bukan efek permanen.
 *
 * Lintasan: dimulai dari titik tengah-BAWAH bingkai, lalu bergerak
 * berbelah dua serentak (satu menyusuri tepi kiri ke atas, satu lagi
 * menyusuri tepi kanan ke atas) mengikuti sisi layar, dan kedua ujung
 * bertemu di titik tengah-ATAS. Sesaat setelah bertemu, seluruh garis
 * memudar (fade-out) dan view ini membersihkan dirinya sendiri.
 *
 * View ini TIDAK menangkap sentuhan sama sekali (selalu meneruskan ke
 * view di bawahnya) — murni lapisan dekoratif di atas semua overlay lain.
 */
public class RainbowGlowView extends View {

    public interface OnFinishedListener {
        void onFinished();
    }

    private static final long TRAVEL_DURATION_MS = 650L;
    private static final long HOLD_DURATION_MS = 120L;
    private static final long FADE_DURATION_MS = 260L;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Path leftPath;
    private Path rightPath;
    private PathMeasure leftMeasure;
    private PathMeasure rightMeasure;
    private float leftLength;
    private float rightLength;

    // Progress 0..1 perjalanan sisi kiri & kanan menyusuri bingkai.
    private float travelProgress = 0f;
    // Pergeseran hue (derajat 0..360) untuk efek RGB berputar sepanjang garis.
    private float hueShift = 0f;
    private float alpha = 1f;

    private OnFinishedListener finishedListener;
    private ValueAnimator travelAnimator;
    private ValueAnimator hueAnimator;

    public RainbowGlowView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeWidth(dp(12));

        corePaint.setStyle(Paint.Style.STROKE);
        corePaint.setStrokeCap(Paint.Cap.ROUND);
        corePaint.setStrokeJoin(Paint.Join.ROUND);
        corePaint.setStrokeWidth(dp(2.5f));
    }

    public void setOnFinishedListener(OnFinishedListener l) {
        this.finishedListener = l;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildPaths(w, h);
        startAnimation();
    }

    /**
     * Bangun dua jalur bingkai: kiri (tengah-bawah -> pojok kiri-bawah ->
     * pojok kiri-atas -> tengah-atas) dan kanan (tengah-bawah -> pojok
     * kanan-bawah -> pojok kanan-atas -> tengah-atas), keduanya menyusuri
     * tepi persis pada garis pinggir view (bingkai layar).
     */
    private void buildPaths(int w, int h) {
        float midX = w / 2f;
        float inset = dp(2f); // sedikit masuk dari tepi mutlak supaya glow tak terpotong clip

        leftPath = new Path();
        leftPath.moveTo(midX, h - inset);
        leftPath.lineTo(inset, h - inset);
        leftPath.lineTo(inset, inset);
        leftPath.lineTo(midX, inset);

        rightPath = new Path();
        rightPath.moveTo(midX, h - inset);
        rightPath.lineTo(w - inset, h - inset);
        rightPath.lineTo(w - inset, inset);
        rightPath.lineTo(midX, inset);

        leftMeasure = new PathMeasure(leftPath, false);
        rightMeasure = new PathMeasure(rightPath, false);
        leftLength = leftMeasure.getLength();
        rightLength = rightMeasure.getLength();
    }

    private void startAnimation() {
        travelAnimator = ValueAnimator.ofFloat(0f, 1f);
        travelAnimator.setDuration(TRAVEL_DURATION_MS);
        travelAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
        travelAnimator.addUpdateListener(a -> {
            travelProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        travelAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                postDelayed(RainbowGlowView.this::startFadeOut, HOLD_DURATION_MS);
            }
        });

        // Hue berputar terus selama animasi supaya kesan "RGB berjalan"
        // terlihat sepanjang garis, bukan warna statis.
        hueAnimator = ValueAnimator.ofFloat(0f, 360f);
        hueAnimator.setDuration(1100L);
        hueAnimator.setRepeatCount(ValueAnimator.INFINITE);
        hueAnimator.addUpdateListener(a -> hueShift = (float) a.getAnimatedValue());

        travelAnimator.start();
        hueAnimator.start();
    }

    private void startFadeOut() {
        // Hentikan putaran hue lebih awal (bukan menunggu fade selesai
        // lewat cleanup()) — sebelumnya hueAnimator infinite terus memicu
        // perhitungan warna tiap frame selama ~260ms fade berjalan padahal
        // hasilnya sudah tidak terlihat (alpha menuju 0), kerja CPU yang
        // tidak perlu tepat di momen transisi paling padat.
        if (hueAnimator != null) {
            hueAnimator.cancel();
        }

        ValueAnimator fade = ValueAnimator.ofFloat(1f, 0f);
        fade.setDuration(FADE_DURATION_MS);
        fade.addUpdateListener(a -> {
            alpha = (float) a.getAnimatedValue();
            invalidate();
        });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                cleanup();
                if (finishedListener != null) finishedListener.onFinished();
            }
        });
        fade.start();
    }

    /** Hentikan seluruh animasi lebih awal (mis. overlay ditutup manual sebelum animasi selesai). */
    public void cleanup() {
        if (travelAnimator != null) travelAnimator.cancel();
        if (hueAnimator != null) hueAnimator.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (leftMeasure == null || rightMeasure == null || alpha <= 0f) return;

        Path leftSegment = new Path();
        Path rightSegment = new Path();
        leftMeasure.getSegment(0f, leftLength * travelProgress, leftSegment, true);
        rightMeasure.getSegment(0f, rightLength * travelProgress, rightSegment, true);

        int[] rainbow = rainbowColors(hueShift, alpha);

        // Lapisan luar: glow blur lembut (lebar, transparan).
        glowPaint.setShader(makeShader(getWidth(), rainbow));
        glowPaint.setAlpha((int) (95 * alpha));
        canvas.drawPath(leftSegment, glowPaint);
        canvas.drawPath(rightSegment, glowPaint);

        // Lapisan inti: garis tipis lebih pekat di tengah glow.
        corePaint.setShader(makeShader(getWidth(), rainbow));
        corePaint.setAlpha((int) (155 * alpha));
        canvas.drawPath(leftSegment, corePaint);
        canvas.drawPath(rightSegment, corePaint);
    }

    private Shader makeShader(int width, int[] colors) {
        return new LinearGradient(0, 0, width, 0, colors, null, Shader.TileMode.CLAMP);
    }

    /** Deret warna RGB penuh (pelangi) bergeser sesuai hueShift, dengan alpha global diterapkan. */
    private int[] rainbowColors(float shiftDeg, float globalAlpha) {
        int steps = 6;
        int[] colors = new int[steps];
        float[] hsv = new float[3];
        // Saturasi & value dilunakkan ~30% agar RGB tidak menyilaukan
        hsv[1] = 0.72f;
        hsv[2] = 0.78f;
        for (int i = 0; i < steps; i++) {
            float hue = (shiftDeg + (360f / steps) * i) % 360f;
            hsv[0] = hue;
            int c = Color.HSVToColor(hsv);
            colors[i] = Color.argb((int) (255 * globalAlpha), Color.red(c), Color.green(c), Color.blue(c));
        }
        return colors;
    }
}
