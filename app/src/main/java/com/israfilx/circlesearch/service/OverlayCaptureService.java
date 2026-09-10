package com.israfilx.circlesearch.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
import com.israfilx.circlesearch.ui.BottomIconMenu;
import com.israfilx.circlesearch.ui.SelectionOverlayView;
import com.israfilx.circlesearch.ui.TranslationOverlayView;
import com.israfilx.circlesearch.util.BitmapCropUtil;
import com.israfilx.circlesearch.util.ImageSearchShareUtil;
import com.israfilx.circlesearch.util.OcrTranslateHelper;

import java.util.List;

/**
 * Foreground service tempat capture layar (root screencap) dan overlay
 * seleksi dijalankan.
 *
 * Alur:
 *  1. Trigger diterima -> screencap via root ke file cache internal
 *  2. Load bitmap, tampilkan sebagai overlay full-screen (WindowManager)
 *     dengan SelectionOverlayView di atasnya untuk gambar lasso bebas,
 *     plus BottomIconMenu (ikon kaca pembesar + translate) menempel di
 *     bagian PALING BAWAH layar — dipakai baik sebagai jalan pintas
 *     "1 layar" (tap langsung tanpa menyeleksi) maupun sebagai menu aksi
 *     untuk hasil seleksi lasso (tetap di bawah, tidak menempel area
 *     crop, supaya tidak menghalangi pemandangan).
 *  3a. User tap salah satu ikon TANPA menyeleksi dulu -> aksi berlaku
 *      untuk fullScreenshot (seluruh layar), ATAU
 *  3b. User menggambar lasso -> crop bitmap sesuai path lasso, ikon di
 *      bawah tetap ada tapi aksinya sekarang berlaku untuk area crop itu
 *  4. Aksi "Translate" -> OCR + translate PER BLOK teks, lalu tampilkan
 *     TranslationOverlayView yang menggambar ulang terjemahan LANGSUNG
 *     MENIMPA posisi teks aslinya (teks asli diburamkan) — tanpa popup
 *     kartu terpisah.
 *  5. Aksi "Cari" -> kirim bitmap (crop atau full) untuk pencarian visual
 *     via ImageSearchShareUtil — mesin non-Google (Yandex/Bing) bila
 *     terpasang, jika tidak lewat chooser umum Android (lihat util
 *     tersebut untuk detail urutan preferensi).
 */
public class OverlayCaptureService extends Service {

    private static final String TAG = "CircleSearch/Capture";
    public static final String ACTION_START_CAPTURE = "com.israfilx.circlesearch.action.START_CAPTURE";

    private static final String CHANNEL_ID = "circle_search_capture";
    private static final int NOTIF_ID = 1001;

    private WindowManager windowManager;
    private SelectionOverlayView selectionView;
    private BottomIconMenu bottomMenu;
    private TranslationOverlayView translationOverlayView;
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
                // User mulai menggambar lasso manual — menu bawah tetap
                // ditampilkan (posisinya sudah di bawah, tidak menghalangi
                // area yang sedang diseleksi), jadi tidak perlu disembunyikan.
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
            showBottomMenu();
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan overlay ke WindowManager — cek izin SYSTEM_ALERT_WINDOW", e);
            stopSelf();
        }
    }

    /**
     * Menu ikon (kaca pembesar + translate) menempel di bagian PALING
     * BAWAH layar — satu-satunya menu aksi, dipakai baik untuk jalan
     * pintas "1 layar" (bila user tap ikon sebelum menyeleksi apapun,
     * currentBounds masih null sehingga aksi berlaku ke fullScreenshot)
     * maupun untuk hasil seleksi lasso (currentBounds terisi setelah
     * onSelectionComplete, aksi berlaku ke currentCrop).
     */
    private void showBottomMenu() {
        bottomMenu = new BottomIconMenu(this, new BottomIconMenu.OnActionListener() {
            @Override
            public void onSearchVisual() {
                Bitmap target = currentCrop != null ? currentCrop : fullScreenshot;
                Log.d(TAG, "Aksi: cari visual (non-Google) (" +
                        (currentCrop != null ? "area seleksi" : "1 layar penuh") + ")");
                boolean sent = ImageSearchShareUtil.shareForVisualSearch(OverlayCaptureService.this, target);
                if (!sent) {
                    Log.e(TAG, "Tidak ada aplikasi yang menerima gambar untuk visual search");
                }
                closeOverlayAndStop();
            }

            @Override
            public void onTranslate() {
                Log.d(TAG, "Aksi: translate (" +
                        (currentCrop != null ? "area seleksi" : "1 layar penuh") + ")");
                removeTranslationOverlayIfShown();
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
        // Selalu di bawah tengah layar, dengan jarak aman dari tepi bawah
        // (mis. gesture nav bar) — tidak pernah menempel area seleksi,
        // sesuai tujuan supaya tidak menghalangi pemandangan konten.
        menuParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        menuParams.y = (int) dp(28);

        try {
            windowManager.addView(bottomMenu, menuParams);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan menu ikon bawah", e);
        }
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
        // Menu ikon di bawah sudah tampil sejak awal (showBottomMenu) dan
        // tetap di tempatnya; sekarang aksinya otomatis mengarah ke
        // currentCrop karena listener di atas mengecek currentCrop != null.
    }

    private void runOcrAndTranslate() {
        // Untuk OCR, pakai crop persegi (cropToRect) bukan crop mengikuti
        // bentuk lasso (cropToPath) — teks yang terpotong bentuk lasso bisa
        // mengurangi akurasi baca ML Kit, sedangkan area persegi penuh
        // memberi konteks visual lebih utuh untuk baris teks yang terpotong
        // tipis oleh coretan. Untuk overlay hasil, kita tetap perlu offset
        // area crop terhadap fullScreenshot supaya boundingBox tiap blok
        // bisa dipetakan balik ke koordinat layar penuh.
        final Rect cropOffset;
        final Bitmap ocrBitmap;

        if (currentBounds != null) {
            ocrBitmap = BitmapCropUtil.cropToRect(fullScreenshot, currentBounds);
            cropOffset = new Rect(
                    (int) Math.max(0, currentBounds.left),
                    (int) Math.max(0, currentBounds.top),
                    0, 0);
        } else {
            // Mode "1 layar" — OCR pada seluruh screenshot, tanpa offset.
            ocrBitmap = fullScreenshot;
            cropOffset = new Rect(0, 0, 0, 0);
        }

        if (ocrBitmap == null) {
            Log.e(TAG, "Crop untuk OCR gagal");
            closeOverlayAndStop();
            return;
        }

        runOcrAndTranslateOnBitmap(ocrBitmap, cropOffset.left, cropOffset.top);
    }

    /**
     * Jalankan OCR + Translate pada bitmap apapun — dipakai baik untuk
     * crop hasil seleksi manual maupun untuk seluruh screenshot layar
     * (jalan pintas "Translate 1 layar"). offsetX/offsetY menggeser
     * boundingBox tiap blok kembali ke koordinat fullScreenshot, supaya
     * TranslationOverlayView (yang selalu menggambar di atas seluruh
     * layar) bisa menimpa teks tepat di posisi aslinya walau OCR
     * dijalankan pada bitmap crop yang sudah terpotong dari titik (0,0).
     */
    private void runOcrAndTranslateOnBitmap(Bitmap bitmap, int offsetX, int offsetY) {
        OcrTranslateHelper.recognizeAndTranslate(bitmap, new OcrTranslateHelper.ResultCallback() {
            @Override
            public void onSuccess(List<OcrTranslateHelper.TranslatedBlock> blocks) {
                List<OcrTranslateHelper.TranslatedBlock> shifted = shiftBlocks(blocks, offsetX, offsetY);
                mainHandler.post(() -> showTranslationOverlay(shifted));
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

    private List<OcrTranslateHelper.TranslatedBlock> shiftBlocks(
            List<OcrTranslateHelper.TranslatedBlock> blocks, int offsetX, int offsetY) {
        if (offsetX == 0 && offsetY == 0) return blocks;

        java.util.List<OcrTranslateHelper.TranslatedBlock> shifted = new java.util.ArrayList<>();
        for (OcrTranslateHelper.TranslatedBlock b : blocks) {
            Rect box = new Rect(b.boundingBox);
            box.offset(offsetX, offsetY);
            shifted.add(new OcrTranslateHelper.TranslatedBlock(b.originalText, b.translatedText, box));
        }
        return shifted;
    }

    /**
     * Tampilkan hasil terjemahan LANGSUNG MENIMPA teks asli di posisinya
     * (via TranslationOverlayView) — bukan kartu popup. View ini dipasang
     * sebagai layer tambahan full-screen di atas SelectionOverlayView
     * yang sudah ada, dan otomatis tertutup begitu user tap di luar area
     * teks (lihat OnDismissListener).
     */
    private void showTranslationOverlay(List<OcrTranslateHelper.TranslatedBlock> blocks) {
        translationOverlayView = new TranslationOverlayView(this, fullScreenshot, blocks);
        translationOverlayView.setOnDismissListener(this::closeOverlayAndStop);

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

        try {
            windowManager.addView(translationOverlayView, params);
            Log.d(TAG, "Overlay terjemahan ditampilkan, jumlah blok=" + blocks.size());
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan overlay terjemahan", e);
            closeOverlayAndStop();
            return;
        }

        // translationOverlayView ditambahkan setelah bottomMenu, jadi
        // secara z-order window baru ini berada DI ATAS bottomMenu dan
        // akan menangkap semua sentuhan (termasuk di area ikon). Angkat
        // ulang bottomMenu ke depan (remove lalu add kembali) supaya
        // ikon tetap bisa dipencet untuk menutup overlay atau ganti aksi.
        bringBottomMenuToFront();
    }

    /** Bersihkan overlay hasil terjemahan sebelumnya (bila ada) sebelum memproses ulang. */
    private void removeTranslationOverlayIfShown() {
        if (translationOverlayView == null) return;
        try {
            windowManager.removeView(translationOverlayView);
        } catch (Exception ignored) {
        }
        translationOverlayView = null;
    }

    private void bringBottomMenuToFront() {
        if (bottomMenu == null) return;
        try {
            windowManager.removeView(bottomMenu);
            WindowManager.LayoutParams menuParams = (WindowManager.LayoutParams) bottomMenu.getLayoutParams();
            windowManager.addView(bottomMenu, menuParams);
        } catch (Exception e) {
            Log.e(TAG, "Gagal mengangkat menu bawah ke depan", e);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void closeOverlayAndStop() {
        mainHandler.post(() -> {
            try {
                if (translationOverlayView != null) {
                    windowManager.removeView(translationOverlayView);
                    translationOverlayView = null;
                }
            } catch (Exception ignored) {
            }
            try {
                if (selectionView != null) {
                    selectionView.destroy();
                    windowManager.removeView(selectionView);
                    selectionView = null;
                }
            } catch (Exception ignored) {
            }
            try {
                if (bottomMenu != null) {
                    windowManager.removeView(bottomMenu);
                    bottomMenu = null;
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
