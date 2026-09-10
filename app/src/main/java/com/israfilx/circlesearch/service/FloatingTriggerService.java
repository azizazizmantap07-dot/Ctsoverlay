package com.israfilx.circlesearch.service;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
 * Floating trigger opsional: pil tipis semi-transparan di tepi kiri layar.
 *
 * Interaksi:
 *  - Drag vertikal → pindah posisi Y, menempel ke tepi kiri
 *  - Swipe ke kanan → animasi mengembang jadi tombol bulat
 *  - Tap tombol bulat → trigger overlay, sembunyikan floating
 *  - Swipe ke kiri pada tombol bulat → animasi kembali jadi pil
 *  - Idle 5 detik saat expanded → auto-collapse ke pil
 *  - Saat overlay ditutup → pil muncul lagi di posisi Y terakhir
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

    private WindowManager windowManager;
    private PillView pillView;
    private WindowManager.LayoutParams params;
    private boolean expanded = false;
    private boolean animating = false;
    private int savedY = 200;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoCollapseRunnable = () -> {
        if (expanded && !animating) {
            Log.d(TAG, "Auto-collapse setelah 5 detik idle");
            animateCollapse();
        }
    };

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
        pillView = new PillView(this);
        int pillW = dp(9);
        int pillH = dp(28);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        params = new WindowManager.LayoutParams(
                pillW, pillH, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = Math.max(0, savedY);

        try {
            windowManager.addView(pillView, params);
            Log.d(TAG, "Pil ditampilkan di y=" + params.y);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menampilkan pil", e);
            pillView = null;
        }
    }

    private void hidePill() {
        cancelAutoCollapse();
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

    private void scheduleAutoCollapse() {
        cancelAutoCollapse();
        mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS);
    }

    private void cancelAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable);
    }

    /** Animasi pil → tombol bulat. */
    private void animateExpand() {
        if (pillView == null || params == null || expanded || animating) return;
        animating = true;
        cancelAutoCollapse();

        final int startW = params.width;
        final int startH = params.height;
        final int endW = dp(40);
        final int endH = dp(40);
        final int startX = params.x;
        final int endX = dp(8);

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
                scheduleAutoCollapse();
            }
        });
        anim.start();
    }

    /** Animasi tombol bulat → pil. */
    private void animateCollapse() {
        if (pillView == null || params == null || !expanded || animating) return;
        animating = true;
        cancelAutoCollapse();

        final int startW = params.width;
        final int startH = params.height;
        final int endW = dp(9);
        final int endH = dp(28);
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
            // progress 1→0 saat collapse
            pillView.setProgress(1f - t);
            pillView.invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                expanded = false;
                animating = false;
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

    /** View kustom: morph antara pil tipis dan tombol bulat. */
    private class PillView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean isExpanded = false;
        /** 0 = pil, 1 = bulat penuh. */
        private float progress = 0f;
        private float downX, downY;
        private int startParamX, startParamY;
        private boolean moved;

        PillView(Context ctx) {
            super(ctx);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(Color.argb(150, 0, 0, 0));
            dotPaint.setColor(Color.WHITE);
            dotPaint.setStyle(Paint.Style.FILL);
        }

        void setExpanded(boolean e) {
            isExpanded = e;
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            // Interpolasi bentuk: sudut membulat → lingkaran penuh
            float corner = (h / 2f) * (1f - progress * 0.15f) + (Math.min(w, h) / 2f) * progress;
            RectF rect = new RectF(0, 0, w, h);
            canvas.drawRoundRect(rect, corner, corner, fillPaint);

            // Titik putih muncul saat mendekati bentuk bulat
            if (progress > 0.3f) {
                float alpha = (progress - 0.3f) / 0.7f;
                dotPaint.setAlpha((int) (255 * alpha));
                float r = Math.min(w, h) * 0.12f * progress;
                canvas.drawCircle(w / 2f, h / 2f, r, dotPaint);
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
                    // Sentuhan pada tombol bulat → reset timer auto-collapse
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
                        // Swipe kanan → expand
                        if (dx > dp(36) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            animateExpand();
                            return true;
                        }
                        // Drag vertikal, tetap di tepi kiri
                        params.x = 0;
                        params.y = Math.max(0, startParamY + (int) dy);
                        try {
                            windowManager.updateViewLayout(this, params);
                        } catch (Exception ignored) {
                        }
                    } else {
                        // Swipe kiri → collapse seketika (dengan animasi)
                        if (dx < -dp(36) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            animateCollapse();
                            return true;
                        }
                        // Geser bebas tombol bulat
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
                            // Setelah drag, tetap expanded; restart timer 5 dtk
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
                        // Klik (tanpa drag) → expand jadi tombol bulat
                        // (alternatif swipe kanan, agar tidak bentrok gesture back)
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
