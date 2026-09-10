package com.israfilx.circlesearch.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.israfilx.circlesearch.root.RootShell;
import com.israfilx.circlesearch.ui.InitialQuickActionMenu;
import com.israfilx.circlesearch.ui.SelectionActionMenu;
import com.israfilx.circlesearch.ui.SelectionOverlayView;
import com.israfilx.circlesearch.ui.TranslateResultCard;
import com.israfilx.circlesearch.util.BitmapCropUtil;
import com.israfilx.circlesearch.util.LensShareUtil;
import com.israfilx.circlesearch.util.OcrTranslateHelper;

/**
 * Foreground service tempat capture layar (root screencap) dan overlay
 * seleksi dijalankan.
 *
 * Alur:
 *  1. Trigger diterima -> screencap via root ke file cache internal
 *  2. Load bitmap, tampilkan sebagai overlay full-screen (WindowManager)
 *     dengan SelectionOverlayView di atasnya untuk gambar lasso bebas,
 *     plus InitialQuickActionMenu di tengah layar (jalan pintas Cari/
 *     Translate seluruh layar tanpa perlu menyeleksi)
 *  3a. User pilih "Cari/Translate 1 layar" -> proses fullScreenshot
 *      langsung, ATAU
 *  3b. User mulai menggambar lasso -> InitialQuickActionMenu disembunyikan,
 *      lanjut ke alur seleksi manual seperti biasa
 *  4. User selesai menyeleksi -> crop bitmap sesuai path lasso
 *  5. Tampilkan menu aksi (Cari / Translate) menempel di area crop
 *  6. Aksi dipilih -> proses lalu tutup overlay & service
 */
public class OverlayCaptureService extends Service {

    private static final String TAG = "CircleSearch/Capture";
    public static final String ACTION_START_CAPTURE = "com.israfilx.circlesearch.action.START_CAPTURE";

    private static final String CHANNEL_ID = "circle_search_capture";
    private static final int NOTIF_ID = 1001;

    private WindowManager windowManager;
    private SelectionOverlayView selectionView;
    private InitialQuickActionMenu initialMenu;
    private SelectionActionMenu actionMenu;
    private TranslateResultCard translateResultCard;
    private Bitmap fullScreenshot;
    private Bitmap currentCrop;
    private RectF currentBounds;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannelIfNeeded();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START_CAPTURE.equals(intent.getAction())) {
            startForeground(NOTIF_ID, buildNotification());
            handleCaptureTrigger();
        }
        return START_NOT_STICKY;
    }

    private void handleCaptureTrigger() {
        Log.d(TAG, "Trigger diterima, memulai screencap root...");

        if (!RootShell.open()) {
            Log.e(TAG, "Root tidak tersedia — capture dibatalkan");
            stopSelf();
            return;
        }

        // Screencap dilakukan di background thread supaya tidak memblokir
        // main thread (I/O root shell + baca file bisa memakan puluhan ms).
        new Thread(this::doScreencapAndShowOverlay, "circlesearch-screencap").start();
    }

    private void doScreencapAndShowOverlay() {
        String capPath = getCacheDir().getAbsolutePath() + "/circlesearch_capture.png";
        boolean ok = RootShell.screencapToFile(capPath);

        if (!ok) {
            Log.e(TAG, "screencap gagal, membatalkan overlay");
            mainHandler.post(this::stopSelf);
            return;
        }

        Bitmap bmp = BitmapFactory.decodeFile(capPath);
        if (bmp == null) {
            Log.e(TAG, "Gagal decode hasil screencap");
            mainHandler.post(this::stopSelf);
            return;
        }

        fullScreenshot = bmp;
        mainHandler.post(this::showSelectionOverlay);
    }

    private void showSelectionOverlay() {
        selectionView = new SelectionOverlayView(this, fullScreenshot);
        selectionView.setOnSelectionListener(new SelectionOverlayView.OnSelectionListener() {
            @Override
            public void onSelectionComplete(RectF bounds) {
                handleSelectionComplete(bounds);
            }

            @Override
            public void onSelectionCancelled() {
                Log.d(TAG, "Seleksi dibatalkan (tap tanpa drag) — tutup overlay");
                closeOverlayAndStop();
            }

            @Override
            public void onSelectionStarted() {
                // User mulai menggambar lasso manual — sembunyikan menu
                // awal (Cari/Translate 1 layar) supaya tidak menghalangi
                // area yang sedang diseleksi.
                hideInitialQuickMenu();
            }
        });

        // TYPE_ACCESSIBILITY_OVERLAY sengaja TIDAK dipakai di sini — window
        // type itu hanya bisa ditambahkan oleh proses yang terdaftar sebagai
        // AccessibilityService aktif (kita tidak punya), dan akan gagal
        // dengan BadTokenException bila dipaksakan. TYPE_APPLICATION_OVERLAY
        // cukup dengan izin SYSTEM_ALERT_WINDOW biasa yang sudah kita minta.
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Izin 'Tampil di atas aplikasi lain' belum diberikan untuk app ini — overlay tidak bisa ditampilkan");
            stopSelf();
            return;
        }

        try {
            windowManager.addView(selectionView, params);
            Log.d(TAG, "Overlay seleksi ditampilkan");
            showInitialQuickMenu();
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan overlay ke WindowManager — cek izin SYSTEM_ALERT_WINDOW", e);
            stopSelf();
        }
    }

    /**
     * Menu awal di tengah layar (Cari 1 layar / Translate 1 layar) —
     * jalan pintas untuk memproses seluruh screenshot langsung, tanpa
     * perlu melingkari area tertentu dulu. Ditampilkan begitu overlay
     * seleksi muncul, dan disembunyikan otomatis begitu user mulai
     * menggambar lasso manual (lihat onSelectionStarted).
     */
    private void showInitialQuickMenu() {
        initialMenu = new InitialQuickActionMenu(this, new InitialQuickActionMenu.OnQuickActionListener() {
            @Override
            public void onSearchFullScreen() {
                Log.d(TAG, "Aksi: cari 1 layar penuh via Google Lens");
                boolean sent = LensShareUtil.shareToLens(OverlayCaptureService.this, fullScreenshot);
                if (!sent) {
                    Log.e(TAG, "Tidak ada aplikasi yang menerima gambar untuk visual search");
                }
                closeOverlayAndStop();
            }

            @Override
            public void onTranslateFullScreen() {
                Log.d(TAG, "Aksi: translate 1 layar penuh");
                hideInitialQuickMenu();
                runOcrAndTranslateOnBitmap(fullScreenshot);
            }
        });

        int menuType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                menuType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(initialMenu, params);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan menu awal", e);
        }
    }

    private void hideInitialQuickMenu() {
        mainHandler.post(() -> {
            try {
                if (initialMenu != null) {
                    windowManager.removeView(initialMenu);
                    initialMenu = null;
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void handleSelectionComplete(RectF bounds) {
        Log.d(TAG, "Seleksi selesai, bounds=" + bounds);
        currentBounds = bounds;

        currentCrop = BitmapCropUtil.cropToPath(fullScreenshot, selectionView.getLassoPath(), bounds);
        if (currentCrop == null) {
            Log.e(TAG, "Crop gagal (bounds tidak valid)");
            closeOverlayAndStop();
            return;
        }

        showActionMenu(bounds);
    }

    private void showActionMenu(RectF bounds) {
        actionMenu = new SelectionActionMenu(this, new SelectionActionMenu.OnActionListener() {
            @Override
            public void onSearchVisual() {
                Log.d(TAG, "Aksi: cari visual via Google Lens");
                boolean sent = LensShareUtil.shareToLens(OverlayCaptureService.this, currentCrop);
                if (!sent) {
                    Log.e(TAG, "Tidak ada aplikasi yang menerima gambar untuk visual search");
                }
                closeOverlayAndStop();
            }

            @Override
            public void onOcrTranslate() {
                Log.d(TAG, "Aksi: OCR + Translate");
                runOcrAndTranslate();
            }
        });

        int menuType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        WindowManager.LayoutParams menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                menuType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.START;

        int menuX = (int) Math.max(0, bounds.left);
        int menuY = (int) Math.min(bounds.bottom + dp(12), getResources().getDisplayMetrics().heightPixels - dp(80));
        menuParams.x = menuX;
        menuParams.y = menuY;

        try {
            windowManager.addView(actionMenu, menuParams);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan menu aksi", e);
        }
    }

    private void runOcrAndTranslate() {
        // Untuk OCR, pakai crop persegi (cropToRect) bukan crop mengikuti
        // bentuk lasso (cropToPath) — teks yang terpotong bentuk lasso bisa
        // mengurangi akurasi baca ML Kit, sedangkan area persegi penuh
        // memberi konteks visual lebih utuh untuk baris teks yang terpotong
        // tipis oleh coretan.
        Bitmap ocrCrop = BitmapCropUtil.cropToRect(fullScreenshot, currentBounds);
        if (ocrCrop == null) {
            Log.e(TAG, "Crop untuk OCR gagal");
            closeOverlayAndStop();
            return;
        }

        // Sembunyikan menu aksi sementara proses OCR+Translate berjalan,
        // supaya tidak bisa dipencet dua kali dan area layar lebih bersih.
        mainHandler.post(() -> {
            try {
                if (actionMenu != null) windowManager.removeView(actionMenu);
            } catch (Exception ignored) {
            }
        });

        runOcrAndTranslateOnBitmap(ocrCrop);
    }

    /**
     * Jalankan OCR + Translate pada bitmap apapun — dipakai baik untuk
     * crop hasil seleksi manual (runOcrAndTranslate di atas) maupun
     * untuk seluruh screenshot layar (jalan pintas "Translate 1 layar"
     * dari InitialQuickActionMenu).
     */
    private void runOcrAndTranslateOnBitmap(Bitmap bitmap) {
        OcrTranslateHelper.recognizeAndTranslate(bitmap, new OcrTranslateHelper.ResultCallback() {
            @Override
            public void onSuccess(String originalText, String translatedText) {
                boolean wasTranslated = !originalText.equals(translatedText);
                mainHandler.post(() -> showTranslateResult(originalText, translatedText, wasTranslated));
            }

            @Override
            public void onNoTextFound() {
                Log.d(TAG, "Tidak ada teks terdeteksi");
                mainHandler.post(OverlayCaptureService.this::closeOverlayAndStop);
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "OCR/Translate gagal", e);
                mainHandler.post(OverlayCaptureService.this::closeOverlayAndStop);
            }
        });
    }

    private void showTranslateResult(String originalText, String translatedText, boolean wasTranslated) {
        translateResultCard = new TranslateResultCard(this, originalText, translatedText, wasTranslated,
                this::closeOverlayAndStop);

        int cardType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        WindowManager.LayoutParams cardParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                cardType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        if (currentBounds != null) {
            // Ada seleksi manual — tempelkan kartu di dekat area itu.
            cardParams.gravity = Gravity.TOP | Gravity.START;
            int cardX = (int) Math.max(dp(8), Math.min(currentBounds.left,
                    getResources().getDisplayMetrics().widthPixels - dp(280) - dp(8)));
            int cardY = (int) Math.min(currentBounds.bottom + dp(12),
                    getResources().getDisplayMetrics().heightPixels - dp(340));
            cardParams.x = cardX;
            cardParams.y = Math.max((int) dp(40), cardY);
        } else {
            // Mode "Translate 1 layar" (tanpa seleksi manual) — tampilkan
            // kartu di tengah layar, konsisten dengan posisi InitialQuickActionMenu.
            cardParams.gravity = Gravity.CENTER;
        }

        try {
            windowManager.addView(translateResultCard, cardParams);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan kartu hasil translate", e);
            closeOverlayAndStop();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void closeOverlayAndStop() {
        mainHandler.post(() -> {
            try {
                if (selectionView != null) {
                    selectionView.destroy();
                    windowManager.removeView(selectionView);
                    selectionView = null;
                }
            } catch (Exception ignored) {
            }
            try {
                if (initialMenu != null) {
                    windowManager.removeView(initialMenu);
                    initialMenu = null;
                }
            } catch (Exception ignored) {
            }
            try {
                if (actionMenu != null) {
                    windowManager.removeView(actionMenu);
                    actionMenu = null;
                }
            } catch (Exception ignored) {
            }
            try {
                if (translateResultCard != null) {
                    windowManager.removeView(translateResultCard);
                    translateResultCard = null;
                }
            } catch (Exception ignored) {
            }
            stopSelf();
        });
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Circle Search Capture", NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Circle Search")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(false)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (fullScreenshot != null && !fullScreenshot.isRecycled()) {
            fullScreenshot.recycle();
        }
        if (currentCrop != null && !currentCrop.isRecycled()) {
            currentCrop.recycle();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
