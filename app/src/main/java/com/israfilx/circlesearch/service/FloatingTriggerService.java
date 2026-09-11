package com.israfilx.circlesearch.service;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Floating trigger: pil visual 9×56 dp di tepi kiri, area sentuh lebih besar
 * agar klik/swipe responsif tanpa mengubah ukuran visual.
 *
 * Saat di-expand jadi tombol bulat, tombol berputar dengan tema
 * "chrome robotik": cincin metalik + segmen RGB glow animasi + percikan
 * petir kecil yang menyembur dari tepi lingkaran selagi berputar.
 */
public class FloatingTriggerService extends Service {

    private static final String TAG = "CircleSearch/Float";
    public static final String ACTION_SHOW = "com.israfilx.circlesearch.action.FLOAT_SHOW";
    public static final String ACTION_HIDE = "com.israfilx.circlesearch.action.FLOAT_HIDE";
    public static final String ACTION_OVERLAY_CLOSED =
            "com.israfilx.circlesearch.action.OVERLAY_CLOSED";
    public static final String PREFS = "floating_trigger_prefs";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_POS_Y = "pos_y";

    private static final String CHANNEL_ID = "floating_trigger";
    private static final int NOTIF_ID = 1002;
    private static final long AUTO_COLLAPSE_MS = 5000L;
    private static final long ANIM_MS = 220L;
    private static final long COLOR_CYCLE_MS = 10_000L;

    /** Ukuran visual pil. */
    private static final int VISUAL_PILL_W_DP = 9;
    private static final int VISUAL_PILL_H_DP = 56;
    /** Area sentuh lebih lebar dari visual (hanya hit-box). */
    private static final int TOUCH_PILL_W_DP = 40;
    private static final int TOUCH_PILL_H_DP = 56;
    private static final int VISUAL_BTN_DP = 40;
    private static final int TOUCH_BTN_DP = 52;

    // Merah / kuning / hijau semi-transparan
    private static final int[] CYCLE_COLORS = {
            Color.argb(160, 220, 40, 40),
            Color.argb(160, 230, 190, 20),
            Color.argb(160, 40, 170, 70)
    };

    private WindowManager windowManager;
    private PillView pillView;
    private WindowManager.LayoutParams params;
    private boolean expanded = false;
    private boolean animating = false;
    private int savedY = 200;
    private int colorIndex = 0;
    /** Sudut putar tombol bulat gaya Chrome (derajat). */
    private float spinAngle = 0f;
    private ValueAnimator spinAnim;
    /** Fase glow RGB tombol chrome (0..1, dipetakan ke hue merah→kuning→hijau). */
    private float chromeGlowPhase = 0f;
    private ValueAnimator chromeGlowAnim;
    /** Generator & wadah percikan petir aktif saat tombol berputar. */
    private final Random sparkRandom = new Random();
    private final List<LightningSpark> sparks = new ArrayList<>();
    private ValueAnimator sparkSpawnAnim;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoCollapseRunnable = () -> {
        if (expanded && !animating) {
            Log.d(TAG, "Auto-collapse setelah 5 detik idle");
            animateCollapse();
        }
    };
    private ValueAnimator colorAnim;

    private final Runnable colorCycleRunnable = new Runnable() {
        @Override
        public void run() {
            animateToNextColor();
            mainHandler.postDelayed(this, COLOR_CYCLE_MS);
        }
    };

    /** Transisi warna smooth (merah ↔ kuning ↔ hijau). */
    private void animateToNextColor() {
        if (pillView == null) return;
        int from = CYCLE_COLORS[colorIndex];
        int nextIndex = (colorIndex + 1) % CYCLE_COLORS.length;
        int to = CYCLE_COLORS[nextIndex];
        colorIndex = nextIndex;

        if (colorAnim != null) {
            colorAnim.cancel();
        }
        colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        colorAnim.setDuration(700);
        colorAnim.addUpdateListener(a -> {
            if (pillView != null) {
                pillView.setFillColor((int) a.getAnimatedValue());
                pillView.invalidate();
            }
        });
        colorAnim.start();
    }

    private final BroadcastReceiver overlayClosedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_OVERLAY_CLOSED.equals(intent.getAction())) {
                Log.d(TAG, "Overlay ditutup — tampilkan pil lagi");
                showPill();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        savedY = sp.getInt(KEY_POS_Y, dp(200));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Floating Trigger", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        IntentFilter filter = new IntentFilter(ACTION_OVERLAY_CLOSED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayClosedReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(overlayClosedReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_HIDE.equals(action)) {
            cancelAutoCollapse();
            stopColorCycle();
            hidePill();
        } else {
            showPill();
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Floating Trigger aktif")
                .setContentText("Pil pemicu di tepi kiri layar")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(true)
                .build();
    }

    private void showPill() {
        if (pillView != null) return;
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Izin overlay belum diberikan");
            Toast.makeText(this, "Aktifkan izin Overlay dulu", Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }

        expanded = false;
        animating = false;
        colorIndex = 0;
        pillView = new PillView(this);
        pillView.setFillColor(CYCLE_COLORS[0]);

        // Window = area sentuh (lebih besar); visual digambar lebih kecil di dalam
        int touchW = dp(TOUCH_PILL_W_DP);
        int touchH = dp(TOUCH_PILL_H_DP);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        params = new WindowManager.LayoutParams(
                touchW, touchH, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = Math.max(0, savedY);

        try {
            windowManager.addView(pillView, params);
            startColorCycle();
            Log.d(TAG, "Pil ditampilkan di y=" + params.y);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menampilkan pil", e);
            pillView = null;
        }
    }

    private void hidePill() {
        cancelAutoCollapse();
        stopColorCycle();
        stopChromeSpin();
        if (pillView != null) {
            try {
                windowManager.removeView(pillView);
            } catch (Exception ignored) {
            }
            pillView = null;
        }
        expanded = false;
        animating = false;
    }

    private void startColorCycle() {
        stopColorCycle();
        mainHandler.postDelayed(colorCycleRunnable, COLOR_CYCLE_MS);
    }

    private void stopColorCycle() {
        mainHandler.removeCallbacks(colorCycleRunnable);
        if (colorAnim != null) {
            colorAnim.cancel();
            colorAnim = null;
        }
    }

    /** Putaran segmen RGB gaya Chrome, ~2 putaran/detik, smooth. */
    private void startChromeSpin() {
        stopChromeSpin();
        spinAnim = ValueAnimator.ofFloat(0f, 360f);
        spinAnim.setDuration(500); // 2 putaran/detik
        spinAnim.setRepeatCount(ValueAnimator.INFINITE);
        spinAnim.setRepeatMode(ValueAnimator.RESTART);
        spinAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        spinAnim.addUpdateListener(a -> {
            spinAngle = (float) a.getAnimatedValue();
            if (pillView != null) pillView.invalidate();
        });
        spinAnim.start();

        // Glow RGB berdenyut mengelilingi roda warna selagi berputar
        chromeGlowAnim = ValueAnimator.ofFloat(0f, 1f);
        chromeGlowAnim.setDuration(900);
        chromeGlowAnim.setRepeatCount(ValueAnimator.INFINITE);
        chromeGlowAnim.setRepeatMode(ValueAnimator.RESTART);
        chromeGlowAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        chromeGlowAnim.addUpdateListener(a -> chromeGlowPhase = (float) a.getAnimatedValue());
        chromeGlowAnim.start();

        // Spawner percikan petir: menambah spark baru secara berkala selama berputar
        sparks.clear();
        sparkSpawnAnim = ValueAnimator.ofFloat(0f, 1f);
        sparkSpawnAnim.setDuration(70);
        sparkSpawnAnim.setRepeatCount(ValueAnimator.INFINITE);
        sparkSpawnAnim.addUpdateListener(a -> {
            spawnLightningSpark();
            updateSparks();
            if (pillView != null) pillView.invalidate();
        });
        sparkSpawnAnim.start();
    }

    private void stopChromeSpin() {
        if (spinAnim != null) {
            spinAnim.cancel();
            spinAnim = null;
        }
        spinAngle = 0f;
        if (chromeGlowAnim != null) {
            chromeGlowAnim.cancel();
            chromeGlowAnim = null;
        }
        chromeGlowPhase = 0f;
        if (sparkSpawnAnim != null) {
            sparkSpawnAnim.cancel();
            sparkSpawnAnim = null;
        }
        sparks.clear();
    }

    /** Satu percikan petir: garis zig-zag pendek yang menyembur dari tepi lingkaran lalu memudar. */
    private static class LightningSpark {
        float angleDeg;
        float life = 1f;
        float seedJitter;
    }

    private void spawnLightningSpark() {
        // Maks 5 spark aktif sekaligus, biar tetap ringan & tidak penuh sesak
        if (sparks.size() >= 5) return;
        if (sparkRandom.nextFloat() > 0.55f) return; // spawn tidak setiap tick, terasa acak natural
        LightningSpark s = new LightningSpark();
        s.angleDeg = sparkRandom.nextFloat() * 360f;
        s.life = 1f;
        s.seedJitter = sparkRandom.nextFloat();
        sparks.add(s);
    }

    private void updateSparks() {
        Iterator<LightningSpark> it = sparks.iterator();
        while (it.hasNext()) {
            LightningSpark s = it.next();
            s.life -= 0.16f;
            if (s.life <= 0f) it.remove();
        }
    }

    private void scheduleAutoCollapse() {
        cancelAutoCollapse();
        mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS);
    }

    private void cancelAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable);
    }

    private void animateExpand() {
        if (pillView == null || params == null || expanded || animating) return;
        animating = true;
        cancelAutoCollapse();

        final int startW = params.width;
        final int startH = params.height;
        final int endW = dp(TOUCH_BTN_DP);
        final int endH = dp(TOUCH_BTN_DP);
        final int startX = params.x;
        final int endX = dp(4);

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ANIM_MS);
        anim.setInterpolator(new OvershootInterpolator(1.15f));
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            params.width = (int) (startW + (endW - startW) * t);
            params.height = (int) (startH + (endH - startH) * t);
            params.x = (int) (startX + (endX - startX) * t);
            try {
                windowManager.updateViewLayout(pillView, params);
            } catch (Exception ignored) {
            }
            pillView.setProgress(t);
            pillView.invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                expanded = true;
                animating = false;
                pillView.setExpanded(true);
                pillView.setProgress(1f);
                pillView.invalidate();
                startChromeSpin();
                scheduleAutoCollapse();
            }
        });
        anim.start();
    }

    private void animateCollapse() {
        if (pillView == null || params == null || !expanded || animating) return;
        animating = true;
        cancelAutoCollapse();

        final int startW = params.width;
        final int startH = params.height;
        final int endW = dp(TOUCH_PILL_W_DP);
        final int endH = dp(TOUCH_PILL_H_DP);
        final int startX = params.x;
        final int endX = 0;

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ANIM_MS);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            params.width = (int) (startW + (endW - startW) * t);
            params.height = (int) (startH + (endH - startH) * t);
            params.x = (int) (startX + (endX - startX) * t);
            try {
                windowManager.updateViewLayout(pillView, params);
            } catch (Exception ignored) {
            }
            pillView.setProgress(1f - t);
            pillView.invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                expanded = false;
                animating = false;
                stopChromeSpin();
                params.x = 0;
                params.width = endW;
                params.height = endH;
                try {
                    windowManager.updateViewLayout(pillView, params);
                } catch (Exception ignored) {
                }
                pillView.setExpanded(false);
                pillView.setProgress(0f);
                pillView.invalidate();
                savePosition();
            }
        });
        anim.start();
    }

    private void triggerCapture() {
        Log.d(TAG, "Trigger capture dari floating button");
        cancelAutoCollapse();
        hidePill();

        Intent serviceIntent = new Intent(this, OverlayCaptureService.class);
        serviceIntent.setAction(OverlayCaptureService.ACTION_START_CAPTURE);
        serviceIntent.putExtra(OverlayCaptureService.EXTRA_FROM_FLOATING, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void savePosition() {
        if (params != null) {
            savedY = params.y;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_POS_Y, savedY)
                    .apply();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        cancelAutoCollapse();
        stopColorCycle();
        try {
            unregisterReceiver(overlayClosedReceiver);
        } catch (Exception ignored) {
        }
        hidePill();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * View: window = hit-box besar; visual pil 9×56 digambar di tepi kiri.
     * Saat expanded, visual tombol bulat di tengah hit-box.
     */
    private class PillView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean isExpanded = false;
        private float progress = 0f;
        private float downX, downY;
        private int startParamX, startParamY;
        private boolean moved;

        PillView(Context ctx) {
            super(ctx);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(CYCLE_COLORS[0]);
            dotPaint.setColor(Color.WHITE);
            dotPaint.setStyle(Paint.Style.FILL);
            // BlurMaskFilter (glow & petir) butuh software layer agar tampil di semua device
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setFillColor(int color) {
            fillPaint.setColor(color);
        }

        void setExpanded(boolean e) {
            isExpanded = e;
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
        }

        private final Paint chromePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bezelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint notchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path sparkPath = new Path();

        /** Warna RGB pada fase 0..1 (0=merah, 1/3=kuning, 2/3=hijau, 1=merah lagi). */
        private int glowColorAt(float phase) {
            float p = phase - (float) Math.floor(phase);
            int red = Color.rgb(255, 45, 45);
            int yellow = Color.rgb(255, 210, 30);
            int green = Color.rgb(50, 220, 110);
            if (p < 0.5f) {
                return (int) new ArgbEvaluator().evaluate(p / 0.5f, red, yellow);
            } else {
                return (int) new ArgbEvaluator().evaluate((p - 0.5f) / 0.5f, yellow, green);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float visPillW = dp(VISUAL_PILL_W_DP);
            float visPillH = dp(VISUAL_PILL_H_DP);
            float visBtn = dp(VISUAL_BTN_DP);

            // Interpolasi ukuran: pil → lingkaran
            float curW = visPillW + (visBtn - visPillW) * progress;
            float curH = visPillH + (visBtn - visPillH) * progress;
            float left = (w - curW) * progress * 0.5f;
            float top = (h - curH) * 0.5f;
            float cx = left + curW / 2f;
            float cy = top + curH / 2f;
            float radius = Math.min(curW, curH) / 2f;

            if (progress < 0.35f) {
                // Bentuk pil: warna siklus solid
                float corner = Math.min(curW, curH) / 2f;
                RectF rect = new RectF(left, top, left + curW, top + curH);
                canvas.drawRoundRect(rect, corner, corner, fillPaint);
            } else {
                // Tombol bulat gaya chrome robotik: glow RGB + bezel metalik + segmen berputar + petir
                float chromeAlpha = Math.min(1f, (progress - 0.35f) / 0.65f);
                int glowColor = glowColorAt(chromeGlowPhase);

                // 1) Glow radial di belakang tombol, warnanya berdenyut RGB
                float glowRadius = radius * 1.9f;
                glowPaint.setStyle(Paint.Style.FILL);
                glowPaint.setShader(new RadialGradient(
                        cx, cy, glowRadius,
                        new int[]{
                                Color.argb((int) (150 * chromeAlpha), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                                Color.argb(0, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
                        },
                        new float[]{0f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, glowRadius, glowPaint);
                glowPaint.setShader(null);

                // 2) Bezel luar metalik (gradient chrome abu terang→gelap, kesan robotik)
                bezelPaint.setStyle(Paint.Style.FILL);
                bezelPaint.setShader(new RadialGradient(
                        cx - radius * 0.3f, cy - radius * 0.3f, radius * 1.6f,
                        new int[]{
                                Color.argb((int) (255 * chromeAlpha), 235, 238, 242),
                                Color.argb((int) (255 * chromeAlpha), 150, 155, 165),
                                Color.argb((int) (255 * chromeAlpha), 70, 74, 84)
                        },
                        new float[]{0f, 0.6f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, radius, bezelPaint);
                bezelPaint.setShader(null);

                // 3) Segmen RGB berputar (dalam bezel, dengan gradient agar tidak flat)
                float segRadius = radius * 0.82f;
                int save = canvas.save();
                canvas.rotate(spinAngle, cx, cy);
                RectF oval = new RectF(cx - segRadius, cy - segRadius, cx + segRadius, cy + segRadius);

                drawGlowSegment(canvas, oval, cx, cy, segRadius, -90 - 10, 120,
                        Color.rgb(255, 70, 70), Color.rgb(150, 20, 20), chromeAlpha);
                drawGlowSegment(canvas, oval, cx, cy, segRadius, 30 - 10, 120,
                        Color.rgb(255, 214, 60), Color.rgb(170, 130, 10), chromeAlpha);
                drawGlowSegment(canvas, oval, cx, cy, segRadius, 150 - 10, 120,
                        Color.rgb(70, 230, 120), Color.rgb(15, 120, 60), chromeAlpha);

                // Garis pemisah gelap antar segmen, ala panel robot
                notchPaint.setStyle(Paint.Style.STROKE);
                notchPaint.setStrokeWidth(Math.max(2f, radius * 0.045f));
                notchPaint.setColor(Color.argb((int) (200 * chromeAlpha), 30, 32, 38));
                for (int i = 0; i < 3; i++) {
                    double ang = Math.toRadians(-90 - 10 + i * 120);
                    canvas.drawLine(cx, cy,
                            cx + (float) Math.cos(ang) * segRadius,
                            cy + (float) Math.sin(ang) * segRadius,
                            notchPaint);
                }
                canvas.restoreToCount(save);

                // 4) Baut/notch teknis kecil mengelilingi bezel (statis, tidak ikut berputar)
                notchPaint.setStyle(Paint.Style.FILL);
                notchPaint.setColor(Color.argb((int) (220 * chromeAlpha), 210, 213, 220));
                float boltR = Math.max(1.5f, radius * 0.05f);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.toRadians(i * 45);
                    float bx = cx + (float) Math.cos(ang) * radius * 0.92f;
                    float by = cy + (float) Math.sin(ang) * radius * 0.92f;
                    canvas.drawCircle(bx, by, boltR, notchPaint);
                }

                // 5) Cincin metalik tipis pemisah antara segmen & pusat
                float ringOuter = radius * 0.52f;
                float ringInner = radius * 0.36f;
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ringOuter - ringInner);
                ringPaint.setShader(new LinearGradient(
                        cx, cy - ringOuter, cx, cy + ringOuter,
                        Color.argb((int) (255 * chromeAlpha), 250, 250, 252),
                        Color.argb((int) (255 * chromeAlpha), 170, 173, 180),
                        Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, (ringOuter + ringInner) / 2f, ringPaint);
                ringPaint.setShader(null);

                // 6) Pusat metalik gelap dengan highlight, warna inti ikut glow RGB tipis
                centerPaint.setStyle(Paint.Style.FILL);
                centerPaint.setShader(new RadialGradient(
                        cx - ringInner * 0.3f, cy - ringInner * 0.3f, ringInner,
                        new int[]{
                                Color.argb((int) (255 * chromeAlpha), 80, 82, 90),
                                Color.argb((int) (255 * chromeAlpha), 32, 33, 38)
                        },
                        new float[]{0f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, ringInner * 0.85f, centerPaint);
                centerPaint.setShader(null);
                // Titik inti berdenyut RGB, seperti indikator daya
                centerPaint.setColor(Color.argb((int) (255 * chromeAlpha), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)));
                centerPaint.setMaskFilter(new BlurMaskFilter(ringInner * 0.4f, BlurMaskFilter.Blur.NORMAL));
                canvas.drawCircle(cx, cy, ringInner * 0.28f, centerPaint);
                centerPaint.setMaskFilter(null);

                // 7) Percikan petir yang menyembur dari tepi lingkaran selagi berputar
                drawLightningSparks(canvas, cx, cy, radius, chromeAlpha);
            }
        }

        /** Menggambar satu segmen chrome dengan gradient radial + sedikit glow tepi. */
        private void drawGlowSegment(Canvas canvas, RectF oval, float cx, float cy, float segRadius,
                                      float startAngle, float sweepAngle,
                                      int colorBright, int colorDark, float alphaMul) {
            chromePaint.setStyle(Paint.Style.FILL);
            chromePaint.setShader(new RadialGradient(
                    cx, cy, segRadius,
                    new int[]{
                            Color.argb((int) (255 * alphaMul), Color.red(colorBright), Color.green(colorBright), Color.blue(colorBright)),
                            Color.argb((int) (255 * alphaMul), Color.red(colorDark), Color.green(colorDark), Color.blue(colorDark))
                    },
                    new float[]{0.3f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawArc(oval, startAngle, sweepAngle, true, chromePaint);
            chromePaint.setShader(null);
        }

        /** Menggambar semua percikan petir aktif sebagai garis zig-zag tipis dari tepi lingkaran. */
        private void drawLightningSparks(Canvas canvas, float cx, float cy, float radius, float alphaMul) {
            if (sparks.isEmpty()) return;
            for (LightningSpark s : sparks) {
                float lifeAlpha = Math.max(0f, Math.min(1f, s.life)) * alphaMul;
                if (lifeAlpha <= 0.02f) continue;

                double baseAngle = Math.toRadians(s.angleDeg + spinAngle);
                float startR = radius * 0.98f;
                float endR = radius * (1.35f + s.seedJitter * 0.45f);

                float sx = cx + (float) Math.cos(baseAngle) * startR;
                float sy = cy + (float) Math.sin(baseAngle) * startR;
                float ex = cx + (float) Math.cos(baseAngle) * endR;
                float ey = cy + (float) Math.sin(baseAngle) * endR;

                // Titik tengah zig-zag digeser tegak lurus arah radial agar berbentuk petir
                float midR = (startR + endR) / 2f;
                float mx = cx + (float) Math.cos(baseAngle) * midR;
                float my = cy + (float) Math.sin(baseAngle) * midR;
                float perpX = -(float) Math.sin(baseAngle);
                float perpY = (float) Math.cos(baseAngle);
                float jitter = radius * 0.16f * (s.seedJitter - 0.5f) * 2f;
                mx += perpX * jitter;
                my += perpY * jitter;

                sparkPath.reset();
                sparkPath.moveTo(sx, sy);
                sparkPath.lineTo(mx, my);
                sparkPath.lineTo(ex, ey);

                sparkPaint.setStyle(Paint.Style.STROKE);
                sparkPaint.setStrokeWidth(Math.max(2f, radius * 0.05f));
                sparkPaint.setStrokeCap(Paint.Cap.ROUND);
                sparkPaint.setColor(Color.argb((int) (255 * lifeAlpha), 235, 245, 255));
                sparkPaint.setMaskFilter(new BlurMaskFilter(radius * 0.06f, BlurMaskFilter.Blur.SOLID));
                canvas.drawPath(sparkPath, sparkPaint);
                sparkPaint.setMaskFilter(null);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (animating) return true;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startParamX = params.x;
                    startParamY = params.y;
                    moved = false;
                    if (isExpanded) {
                        cancelAutoCollapse();
                    }
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved = true;
                    }

                    if (!isExpanded) {
                        if (dx > dp(36) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            animateExpand();
                            return true;
                        }
                        params.x = 0;
                        params.y = Math.max(0, startParamY + (int) dy);
                        try {
                            windowManager.updateViewLayout(this, params);
                        } catch (Exception ignored) {
                        }
                    } else {
                        if (dx < -dp(36) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            animateCollapse();
                            return true;
                        }
                        params.x = Math.max(0, startParamX + (int) dx);
                        params.y = Math.max(0, startParamY + (int) dy);
                        try {
                            windowManager.updateViewLayout(this, params);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isExpanded) {
                        if (!moved || (Math.abs(event.getRawX() - downX) < 12
                                && Math.abs(event.getRawY() - downY) < 12)) {
                            triggerCapture();
                        } else {
                            savePosition();
                            scheduleAutoCollapse();
                        }
                    } else {
                        params.x = 0;
                        try {
                            windowManager.updateViewLayout(this, params);
                        } catch (Exception ignored) {
                        }
                        savePosition();
                        if (!moved || (Math.abs(event.getRawX() - downX) < 12
                                && Math.abs(event.getRawY() - downY) < 12)) {
                            animateExpand();
                        }
                    }
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
