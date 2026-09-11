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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
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

/**
 * Floating trigger: pil visual 9×56 dp di tepi kiri, area sentuh lebih besar
 * agar klik/swipe responsif tanpa mengubah ukuran visual.
 *
 * Warna siklus setiap 10 detik: merah → kuning → hijau → merah …
 * (berlaku untuk bentuk pil maupun tombol bulat).
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
                // Tombol bulat gaya Chrome: 3 segmen RGB berputar
                float chromeAlpha = Math.min(1f, (progress - 0.35f) / 0.65f);
                int save = canvas.save();
                canvas.rotate(spinAngle, cx, cy);

                RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
                // Merah (atas) ~120°, kuning (kanan-bawah), hijau (kiri-bawah)
                chromePaint.setStyle(Paint.Style.FILL);
                chromePaint.setColor(Color.rgb(219, 68, 55)); // red
                chromePaint.setAlpha((int) (255 * chromeAlpha));
                canvas.drawArc(oval, -90 - 10, 120, true, chromePaint);

                chromePaint.setColor(Color.rgb(244, 180, 0)); // yellow
                canvas.drawArc(oval, 30 - 10, 120, true, chromePaint);

                chromePaint.setColor(Color.rgb(15, 157, 88)); // green
                canvas.drawArc(oval, 150 - 10, 120, true, chromePaint);

                canvas.restoreToCount(save);

                // Cincin putih
                float ringOuter = radius * 0.52f;
                float ringInner = radius * 0.36f;
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ringOuter - ringInner);
                ringPaint.setColor(Color.WHITE);
                ringPaint.setAlpha((int) (255 * chromeAlpha));
                canvas.drawCircle(cx, cy, (ringOuter + ringInner) / 2f, ringPaint);

                // Pusat gelap
                centerPaint.setStyle(Paint.Style.FILL);
                centerPaint.setColor(Color.rgb(50, 50, 50));
                centerPaint.setAlpha((int) (255 * chromeAlpha));
                canvas.drawCircle(cx, cy, ringInner * 0.85f, centerPaint);
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
