package com.israfilx.circlesearch.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.israfilx.circlesearch.ui.BottomIconMenu;
import com.israfilx.circlesearch.ui.LoadingStatusView;
import com.israfilx.circlesearch.ui.RainbowGlowView;
import com.israfilx.circlesearch.ui.SelectionOverlayView;
import com.israfilx.circlesearch.ui.TranslationOverlayView;
import com.israfilx.circlesearch.util.BitmapCropUtil;
import com.israfilx.circlesearch.util.ImageSearchShareUtil;
import com.israfilx.circlesearch.util.OcrTranslateHelper;

import java.util.List;

/**
 * Foreground service tempat capture layar (Assist API / Accessibility)
 * dan overlay seleksi dijalankan.
 *
 * Alur:
 *  1. Trigger diterima -> ambil screenshot via Assist API atau Accessibility
 *  2. Load bitmap, tampilkan sebagai overlay FULL-SCREEN (edge-to-edge,
 *     menutupi juga area status bar & navigasi — lihat
 *     {@link #applyFullscreenImmersive}) dengan SelectionOverlayView di
 *     atasnya untuk gambar lasso bebas, plus animasi glow RGB satu kali
 *     jalan (RainbowGlowView) yang menyusuri bingkai layar dari
 *     tengah-bawah menuju kiri & kanan lalu bertemu di tengah-atas
 *     sebagai penanda overlay baru saja aktif, dan BottomIconMenu (kaca
 *     pembesar, translate, salin teks, tutup) menempel di bagian PALING
 *     BAWAH layar.
 *  3a. User tap salah satu ikon TANPA menyeleksi dulu -> aksi berlaku
 *      untuk fullScreenshot (seluruh layar), ATAU
 *  3b. User menggambar lasso -> crop bitmap sesuai path lasso, ikon di
 *      bawah tetap ada tapi aksinya sekarang berlaku untuk area crop itu
 *  4. Aksi "Translate" -> OCR + translate PER BLOK teks, lalu tampilkan
 *     TranslationOverlayView yang menggambar ulang terjemahan LANGSUNG
 *     MENIMPA posisi teks aslinya — tanpa popup kartu terpisah. Selama
 *     proses ini (termasuk kemungkinan unduh model bahasa pertama kali)
 *     LoadingStatusView menampilkan status berjalan supaya tidak terlihat
 *     macet.
 *  5. Aksi "Salin Teks" -> OCR saja (tanpa translate), hasilnya langsung
 *     disalin ke clipboard tanpa menampilkan overlay tambahan.
 *  6. Aksi "Cari" -> kirim bitmap (crop atau full) untuk pencarian visual
 *     via ImageSearchShareUtil. Default: buka WebView in-app
 *     (ImageSearchWebActivity). Bisa diganti ke mode eksternal (Yandex →
 *     Bing → Google Lens → chooser) lewat pengaturan di MainActivity.
 *
 * NAVIGASI "BACK BERTAHAP": overlay ini punya dua "layar" konseptual —
 * MENU UTAMA (seleksi + BottomIconMenu) dan HASIL TERJEMAHAN
 * (TranslationOverlayView menimpa menu utama). Tap back/luar-teks saat
 * di layar hasil terjemahan HANYA menutup layar itu dan mengembalikan
 * user ke menu utama (overlay TETAP AKTIF, siap dipakai lagi tanpa
 * trigger ulang) — bukan menutup semua overlay sekaligus. Penutupan
 * total hanya terjadi lewat tombol ✕ di menu utama, dipanggil dari
 * {@link #closeOverlayAndStop()}.
 */
public class OverlayCaptureService extends Service {

    private static final String TAG = "CircleSearch/Capture";
    public static final String ACTION_START_CAPTURE = "com.israfilx.circlesearch.action.START_CAPTURE";

    /**
     * Path file PNG screenshot yang diberikan sistem lewat
     * VoiceInteractionSession.onHandleScreenshot().
     */
    public static final String EXTRA_ASSIST_SCREENSHOT_PATH = "assist_screenshot_path";
    /** True bila trigger berasal dari floating pill (bukan Assist). */
    public static final String EXTRA_FROM_FLOATING = "from_floating";

    private static final String CHANNEL_ID = "circle_search_capture";
    private static final int NOTIF_ID = 1001;

    /**
     * Durasi fade-out selectionView & bottomMenu saat overlay ditutup —
     * harus sama dengan durasi animasi di dalam masing-masing view
     * (SelectionOverlayView.FADE_OUT_DURATION_MS,
     * BottomIconMenu.FADE_OUT_DURATION_MS) supaya window benar-benar
     * di-remove tepat saat animasi visual selesai, bukan lebih awal
     * (terlihat terpotong) atau lebih lambat (jeda kosong sebelum
     * benar-benar hilang).
     */
    private static final long FADE_OUT_DURATION_MS = 160L;

    private WindowManager windowManager;
    private SelectionOverlayView selectionView;
    private BottomIconMenu bottomMenu;
    private TranslationOverlayView translationOverlayView;
    private RainbowGlowView rainbowGlowView;
    private LoadingStatusView loadingStatusView;
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
            resetCaptureState();
            startForegroundWithType();
            handleCaptureTrigger(intent);
        }
        return START_NOT_STICKY;
    }

    private void startForegroundWithType() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    /**
     * Bersihkan window overlay + flag + bitmap lama dari sesi sebelumnya.
     */
    private void resetCaptureState() {
        closing = false;
        mainHandler.removeCallbacksAndMessages(null);

        try {
            if (loadingStatusView != null) {
                windowManager.removeView(loadingStatusView);
            }
        } catch (Exception ignored) {
        }
        loadingStatusView = null;

        try {
            if (rainbowGlowView != null) {
                rainbowGlowView.cleanup();
                windowManager.removeView(rainbowGlowView);
            }
        } catch (Exception ignored) {
        }
        rainbowGlowView = null;

        try {
            if (translationOverlayView != null) {
                windowManager.removeView(translationOverlayView);
            }
        } catch (Exception ignored) {
        }
        translationOverlayView = null;

        try {
            if (selectionView != null) {
                selectionView.destroy();
                windowManager.removeView(selectionView);
            }
        } catch (Exception ignored) {
        }
        selectionView = null;

        try {
            if (bottomMenu != null) {
                windowManager.removeView(bottomMenu);
            }
        } catch (Exception ignored) {
        }
        bottomMenu = null;

        if (fullScreenshot != null && !fullScreenshot.isRecycled()) {
            fullScreenshot.recycle();
        }
        fullScreenshot = null;
        if (currentCrop != null && !currentCrop.isRecycled()) {
            currentCrop.recycle();
        }
        currentCrop = null;
        currentBounds = null;
    }

    /**
     * Prioritas capture:
     *  1. Screenshot Assist API (dari VoiceInteractionSession)
     *  2. AccessibilityService.takeScreenshot (floating / non-assistant)
     */
    private void handleCaptureTrigger(Intent intent) {
        String assistPath = intent.getStringExtra(EXTRA_ASSIST_SCREENSHOT_PATH);
        if (assistPath != null) {
            Log.d(TAG, "Memakai screenshot sistem (Assist API): " + assistPath);
            new Thread(() -> {
                Bitmap bmp = BitmapFactory.decodeFile(assistPath);
                if (bmp != null) {
                    fullScreenshot = bmp;
                    mainHandler.post(this::showSelectionOverlay);
                } else {
                    Log.e(TAG, "Gagal decode screenshot assist, coba fallback");
                    mainHandler.post(() -> handleCaptureFallback());
                }
            }, "circlesearch-assist-load").start();
            return;
        }

        handleCaptureFallback();
    }

    private void handleCaptureFallback() {
        if (ScreenshotAccessibilityService.isAvailable()) {
            Log.d(TAG, "Memakai AccessibilityService.takeScreenshot");
            ScreenshotAccessibilityService.takeScreenshot(
                    new ScreenshotAccessibilityService.ScreenshotCallback() {
                        @Override
                        public void onSuccess(Bitmap bitmap) {
                            fullScreenshot = bitmap;
                            mainHandler.post(() -> showSelectionOverlay());
                        }

                        @Override
                        public void onFailure(String reason) {
                            Log.e(TAG, "A11y screenshot gagal: " + reason);
                            mainHandler.post(() -> {
                                Toast.makeText(OverlayCaptureService.this,
                                        "Gagal tangkap layar: " + reason,
                                        Toast.LENGTH_SHORT).show();
                                notifyFloatingOverlayClosed();
                                stopSelf();
                            });
                        }
                    });
            return;
        }

        Log.e(TAG, "Tidak ada metode capture (Assist/Accessibility)");
        mainHandler.post(() -> {
            Toast.makeText(this,
                    "Aktifkan Asisten Digital atau Accessibility untuk capture",
                    Toast.LENGTH_LONG).show();
            notifyFloatingOverlayClosed();
            stopSelf();
        });
    }

    /** Beri tahu FloatingTriggerService agar menampilkan pil lagi. */
    private void notifyFloatingOverlayClosed() {
        Intent i = new Intent(FloatingTriggerService.ACTION_OVERLAY_CLOSED);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void showSelectionOverlay() {
        selectionView = new SelectionOverlayView(this, fullScreenshot);
        selectionView.setOnSelectionListener(new SelectionOverlayView.OnSelectionListener() {
            @Override
            public void onSelectionComplete(RectF bounds) {
                handleSelectionComplete(bounds);
            }

            @Override
            public void onSelectionBoundsChanged(RectF bounds) {
                // User menggeser/melebarkan bingkai — update crop persegi
                updateSelectionCrop(bounds);
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // Gambar window sampai benar-benar ke tepi layar (di belakang
        // status bar & navigation bar), bukan cuma di area konten —
        // dilengkapi flag SYSTEM_UI di applyFullscreenImmersive supaya
        // status bar & nav bar juga disembunyikan, sehingga overlay
        // benar-benar full-screen, tidak menyisakan ruang untuk bilah
        // status atau tombol navigasi.
        applyLayoutInDisplayCutoutMode(params);

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Izin 'Tampil di atas aplikasi lain' belum diberikan untuk app ini — overlay tidak bisa ditampilkan");
            stopSelf();
            return;
        }

        try {
            windowManager.addView(selectionView, params);
            applyFullscreenImmersive(selectionView);
            Log.d(TAG, "Overlay seleksi ditampilkan");
            showBottomMenu(true);
            playRainbowGlowIntro();
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan overlay ke WindowManager — cek izin SYSTEM_ALERT_WINDOW", e);
            stopSelf();
        }
    }

    @SuppressWarnings("deprecation")
    private void applyLayoutInDisplayCutoutMode(WindowManager.LayoutParams params) {
        // Izinkan overlay menggambar juga di area lekukan kamera (notch)
        // supaya tidak ada strip hitam/kosong tersisa di sekitar cutout
        // saat mode fullscreen immersive aktif.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    /**
     * Sembunyikan status bar & navigation bar sepenuhnya selama overlay
     * tampil, dan gambar konten hingga ke tepi layar — supaya overlay
     * benar-benar FULL-SCREEN, tidak menyisakan ruang untuk bilah status
     * di atas atau tombol/gestur navigasi di bawah. Dipulihkan otomatis
     * oleh sistem begitu window overlay ini dilepas (tidak perlu
     * undo manual saat cleanup).
     */
    private void applyFullscreenImmersive(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = view.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                // View belum sepenuhnya attached ke window saat dipanggil
                // (jarang terjadi tapi mungkin) — fallback ke flag legacy
                // yang tidak butuh controller.
                applyLegacyImmersiveFlags(view);
            }
        } else {
            applyLegacyImmersiveFlags(view);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyLegacyImmersiveFlags(View view) {
        view.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Tampilkan animasi glow RGB satu kali jalan begitu overlay baru
     * saja aktif — murni dekoratif, tidak menangkap sentuhan, dan
     * membersihkan dirinya sendiri dari WindowManager setelah selesai.
     */
    private void playRainbowGlowIntro() {
        rainbowGlowView = new RainbowGlowView(this);
        rainbowGlowView.setOnFinishedListener(() -> {
            try {
                if (rainbowGlowView != null) {
                    windowManager.removeView(rainbowGlowView);
                }
            } catch (Exception ignored) {
            }
            rainbowGlowView = null;
        });

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                // NOT_FOCUSABLE + NOT_TOUCHABLE: lapisan dekoratif murni,
                // semua sentuhan tembus ke view di bawahnya.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        applyLayoutInDisplayCutoutMode(params);

        try {
            windowManager.addView(rainbowGlowView, params);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan animasi glow (non-fatal, overlay tetap jalan)", e);
            rainbowGlowView = null;
        }
    }

    /**
     * Menu ikon (kaca pembesar, translate, salin teks, tutup) menempel di
     * bagian PALING BAWAH layar — satu-satunya menu aksi, dipakai baik
     * untuk jalan pintas "1 layar" (bila user tap ikon sebelum menyeleksi
     * apapun, currentBounds masih null sehingga aksi berlaku ke
     * fullScreenshot) maupun untuk hasil seleksi lasso (currentBounds
     * terisi setelah onSelectionComplete, aksi berlaku ke currentCrop).
     *
     * @param playEnterAnimation false saat dipanggil dari
     *                           {@link #recreateBottomMenuOnTop()} (menu
     *                           sudah pernah muncul, hanya dibuat ulang
     *                           untuk naik ke z-order teratas) — animasi
     *                           masuk hanya diputar sekali di kemunculan
     *                           pertama.
     */
    private void showBottomMenu(boolean playEnterAnimation) {
        bottomMenu = new BottomIconMenu(this, new BottomIconMenu.OnActionListener() {
            @Override
            public void onSearchVisual() {
                Bitmap target = currentCrop != null ? currentCrop : fullScreenshot;
                Log.d(TAG, "Aksi: cari visual (" +
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

            @Override
            public void onCopyText() {
                Log.d(TAG, "Aksi: salin teks (OCR saja, tanpa translate) (" +
                        (currentCrop != null ? "area seleksi" : "1 layar penuh") + ")");
                runOcrCopyOnly();
            }

            @Override
            public void onClose() {
                // Jalan keluar EKSPLISIT yang selalu berfungsi, terlepas
                // dari overlay lain apa yang sedang tampil di atasnya.
                // Ini SATU-SATUNYA jalur yang menutup SEMUA overlay
                // sekaligus — lihat dokumentasi kelas untuk perbedaannya
                // dengan "back" dari layar hasil terjemahan.
                Log.d(TAG, "Aksi: tutup overlay (tombol ✕)");
                closeOverlayAndStop();
            }
        }, playEnterAnimation);

        int menuType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        WindowManager.LayoutParams menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                menuType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // Selalu di bawah tengah layar, dengan jarak aman dari tepi bawah
        // (mis. gesture nav bar) — tidak pernah menempel area seleksi.
        menuParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        menuParams.y = (int) dp(28);
        applyLayoutInDisplayCutoutMode(menuParams);

        try {
            windowManager.addView(bottomMenu, menuParams);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan menu ikon bawah", e);
        }
    }

    private void handleSelectionComplete(RectF bounds) {
        Log.d(TAG, "Seleksi selesai → bingkai adjustable, bounds=" + bounds);
        updateSelectionCrop(bounds);
    }

    /**
     * Update crop persegi dari bounds bingkai (setelah lasso dikonversi
     * ke rect, atau setelah user resize/geser handle).
     */
    private void updateSelectionCrop(RectF bounds) {
        if (bounds == null || fullScreenshot == null) return;
        currentBounds = new RectF(bounds);
        if (currentCrop != null && !currentCrop.isRecycled()) {
            currentCrop.recycle();
        }
        // Crop persegi rapi (bukan mask path freeform) — mirip CTS asli
        currentCrop = BitmapCropUtil.cropToRect(fullScreenshot, currentBounds);
        if (currentCrop == null) {
            Log.e(TAG, "Crop gagal (bounds tidak valid): " + bounds);
        }
    }

    // ---- OCR-only (Salin Teks) ----

    private void runOcrCopyOnly() {
        Bitmap ocrBitmap = currentBounds != null
                ? BitmapCropUtil.cropToRect(fullScreenshot, currentBounds)
                : fullScreenshot;

        if (ocrBitmap == null) {
            Log.e(TAG, "Crop untuk OCR (salin teks) gagal");
            showLoadingStatus(false, null);
            return;
        }

        showLoadingStatus(true, "Membaca teks…");
        OcrTranslateHelper.recognizeTextOnly(OverlayCaptureService.this, ocrBitmap, new OcrTranslateHelper.ResultCallback() {
            @Override
            public void onSuccess(List<OcrTranslateHelper.TranslatedBlock> blocks) {
                mainHandler.post(() -> {
                    showLoadingStatus(false, null);
                    copyAllOriginalTextToClipboard(blocks);
                });
            }

            @Override
            public void onNoTextFound() {
                mainHandler.post(() -> {
                    showLoadingStatus(false, null);
                    Toast.makeText(OverlayCaptureService.this, "Tidak ada teks terdeteksi", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "OCR (salin teks) gagal", e);
                mainHandler.post(() -> {
                    showLoadingStatus(false, null);
                    Toast.makeText(OverlayCaptureService.this, "Gagal membaca teks", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void copyAllOriginalTextToClipboard(List<OcrTranslateHelper.TranslatedBlock> blocks) {
        if (blocks.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (OcrTranslateHelper.TranslatedBlock b : blocks) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(b.originalText);
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Circle Search", sb.toString()));
        }
        Toast.makeText(this, "Teks disalin (" + blocks.size() + " blok)", Toast.LENGTH_SHORT).show();
    }

    // ---- Translate ----

    private void runOcrAndTranslate() {
        // Crop persegi (bukan mengikuti bentuk lasso) memberi konteks
        // visual lebih utuh untuk baris teks di tepi area seleksi,
        // sehingga akurasi baca ML Kit lebih baik. Untuk overlay hasil,
        // kita tetap perlu offset area crop terhadap fullScreenshot supaya
        // boundingBox tiap blok bisa dipetakan balik ke koordinat layar penuh.
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
            // (Akurasi mode ini ditingkatkan di OcrTranslateHelper dengan
            // menyaring blok "noise" berukuran sangat kecil sebelum
            // dipakai untuk deteksi bahasa — lihat catatan di sana.)
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
     * boundingBox tiap blok kembali ke koordinat fullScreenshot.
     *
     * Selama proses berjalan (termasuk kemungkinan unduh model bahasa
     * pertama kali, yang bisa memakan beberapa detik), LoadingStatusView
     * ditampilkan supaya user tahu overlay sedang bekerja — mengatasi
     * kesan "macet" yang sebelumnya terjadi saat unduhan berlangsung
     * tanpa umpan balik visual apapun.
     */
    private void runOcrAndTranslateOnBitmap(Bitmap bitmap, int offsetX, int offsetY) {
        showLoadingStatus(true, "Membaca teks…");

        OcrTranslateHelper.recognizeAndTranslate(OverlayCaptureService.this, bitmap, new OcrTranslateHelper.ResultCallback() {
            @Override
            public void onModelNotDownloaded(String sourceLanguageCode) {
                mainHandler.post(() -> Toast.makeText(OverlayCaptureService.this,
                        "Model bahasa belum diunduh. Buka menu aplikasi untuk mengunduh model.",
                        Toast.LENGTH_LONG).show());
            }

            @Override
            public void onSuccess(List<OcrTranslateHelper.TranslatedBlock> blocks) {
                List<OcrTranslateHelper.TranslatedBlock> shifted = shiftBlocks(blocks, offsetX, offsetY);
                mainHandler.post(() -> {
                    showLoadingStatus(false, null);
                    showTranslationOverlay(shifted);
                });
            }

            @Override
            public void onNoTextFound() {
                Log.d(TAG, "Tidak ada teks terdeteksi");
                mainHandler.post(() -> {
                    showLoadingStatus(false, null);
                    Toast.makeText(OverlayCaptureService.this, "Tidak ada teks terdeteksi", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "OCR/Translate gagal", e);
                mainHandler.post(() -> {
                    showLoadingStatus(false, null);
                    Toast.makeText(OverlayCaptureService.this, "Gagal menerjemahkan", Toast.LENGTH_SHORT).show();
                });
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
     * yang sudah ada.
     *
     * "Back"/tap-di-luar-teks pada layar ini HANYA mengembalikan ke menu
     * utama overlay (lihat {@link #returnToMainMenu()}), TIDAK menutup
     * semua overlay — sesuai kebutuhan navigasi bertahap.
     */
    private void showTranslationOverlay(List<OcrTranslateHelper.TranslatedBlock> blocks) {
        translationOverlayView = new TranslationOverlayView(this, fullScreenshot, blocks);
        translationOverlayView.setOnDismissListener(this::returnToMainMenu);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        applyLayoutInDisplayCutoutMode(params);

        try {
            windowManager.addView(translationOverlayView, params);
            applyFullscreenImmersive(translationOverlayView);
            Log.d(TAG, "Overlay terjemahan ditampilkan, jumlah blok=" + blocks.size());
        } catch (Exception e) {
            Log.e(TAG, "Gagal menambahkan overlay terjemahan", e);
            // Gagal menampilkan hasil BUKAN alasan untuk menutup semua
            // overlay — kembalikan user ke menu utama supaya tetap bisa
            // mencoba aksi lain tanpa perlu trigger ulang.
            returnToMainMenu();
            return;
        }

        // translationOverlayView ditambahkan setelah bottomMenu, jadi
        // secara z-order window baru ini berada DI ATAS bottomMenu dan
        // akan menangkap semua sentuhan (termasuk di area ikon). Untuk
        // mengembalikan bottomMenu ke depan TANPA pola remove+add yang
        // rapuh, kita buat ulang instance bottomMenu dan pasang baru
        // setelah translationOverlayView.
        //
        // Tombol ✕ di BottomIconMenu adalah jalan keluar yang SELALU
        // berfungsi apapun kondisi z-order-nya, jadi walau ada race
        // condition kecil di sini, overlay tidak akan pernah benar-benar
        // tidak bisa ditutup lagi.
        recreateBottomMenuOnTop();
    }

    /**
     * Kembali ke MENU UTAMA overlay: tutup hasil terjemahan yang sedang
     * tampil SAJA (tanpa menutup selectionView/bottomMenu/window overlay
     * lain), sehingga user bisa langsung lanjut ke aksi berikutnya
     * (salin teks, cari 1 layar, cari lasso, translate lasso, dst) tanpa
     * perlu memicu ulang overlay dari awal. Ini adalah implementasi inti
     * dari "back bertahap" yang diminta — lawan dari
     * {@link #closeOverlayAndStop()} yang menutup SEMUANYA.
     */
    private void returnToMainMenu() {
        removeTranslationOverlayIfShown();
        // bottomMenu & selectionView sengaja TIDAK disentuh di sini —
        // keduanya tetap seperti apa adanya, persis kondisi sebelum
        // translate dipicu, siap dipakai lagi langsung.
    }

    /**
     * Hapus bottomMenu lama (bila ada) dan buat instance baru, ditambahkan
     * SETELAH semua overlay lain sehingga selalu berada di z-order paling
     * depan dan tombol-tombolnya (termasuk ✕) selalu bisa disentuh.
     */
    private void recreateBottomMenuOnTop() {
        if (bottomMenu != null) {
            try {
                windowManager.removeView(bottomMenu);
            } catch (Exception ignored) {
            }
            bottomMenu = null;
        }
        // Menu ini sudah pernah tampil sebelumnya (bukan kemunculan
        // pertama) — animasi masuk dilewati supaya tidak "berkedip
        // turun-naik" tiap kali translate dijalankan ulang.
        showBottomMenu(false);
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

    // ---- Loading indicator (OCR / translate / unduh model) ----

    /**
     * Tampilkan/sembunyikan pill status kecil di atas BottomIconMenu.
     * Dipanggil dari mana saja (thread manapun sudah dipastikan main
     * thread oleh pemanggil) setiap kali proses OCR/translate/unduh
     * model mulai atau selesai.
     */
    private void showLoadingStatus(boolean show, @Nullable String label) {
        if (!show) {
            if (loadingStatusView != null) {
                LoadingStatusView toRemove = loadingStatusView;
                loadingStatusView = null;
                toRemove.hideAnimated(() -> {
                    try {
                        windowManager.removeView(toRemove);
                    } catch (Exception ignored) {
                    }
                });
            }
            return;
        }

        if (loadingStatusView == null) {
            loadingStatusView = new LoadingStatusView(this);

            int menuType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    menuType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            // Menempel tepat di atas BottomIconMenu (yang tingginya kira-kira
            // 64dp + padding), dengan jarak aman tambahan.
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.y = (int) dp(96);
            applyLayoutInDisplayCutoutMode(params);

            try {
                windowManager.addView(loadingStatusView, params);
            } catch (Exception e) {
                Log.e(TAG, "Gagal menambahkan indikator loading (non-fatal)", e);
                loadingStatusView = null;
                return;
            }
        }

        loadingStatusView.showAnimated(label != null ? label : "Memproses…");
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // Mencegah closeOverlayAndStop() dijalankan dobel (mis. tombol ✕
    // ditekan bersamaan dengan animasi dismiss dari tap-di-luar-teks
    // yang sedang berjalan) — bukan untuk mencegah cleanup, tapi supaya
    // stopSelf() dan log tidak terpanggil berulang. Cleanup window itu
    // sendiri tetap aman dipanggil berkali-kali karena tiap langkah
    // sudah null-check + try-catch independen.
    private boolean closing = false;

    /**
     * Tutup SEMUA window overlay yang mungkin sedang tampil, lalu hentikan
     * service. Dipanggil dari jalur yang MEMANG dimaksudkan untuk
     * menutup semuanya (tombol ✕ di menu utama, tap-di-luar-lasso saat
     * belum menyeleksi apapun, seleksi dibatalkan, OCR "1 layar"/lasso
     * gagal atau kosong, dst.) — harus selalu berhasil membersihkan
     * window walau salah satu langkah gagal, supaya overlay TIDAK PERNAH
     * tersisa menempel di layar. Setiap removeView dibungkus try-catch
     * TERPISAH: kegagalan menghapus satu window tidak boleh menghalangi
     * window lain untuk tetap dicoba dihapus.
     *
     * BUKAN dipanggil dari "back"/tap-di-luar-teks pada layar hasil
     * terjemahan — untuk itu pakai {@link #returnToMainMenu()}.
     */
    private void closeOverlayAndStop() {
        mainHandler.post(() -> {
            if (closing) return;
            closing = true;

            // loadingStatusView & rainbowGlowView dihapus SEGERA (tanpa
            // menunggu fade tambahan) — keduanya murni indikator sesaat,
            // dan rainbowGlowView sudah membawa fade-out sendiri lewat
            // cleanup(). Yang butuh fade-out simetris dengan kemunculannya
            // adalah selectionView & bottomMenu (lihat di bawah).
            try {
                if (loadingStatusView != null) {
                    windowManager.removeView(loadingStatusView);
                    loadingStatusView = null;
                }
            } catch (Exception e) {
                loadingStatusView = null;
            }
            try {
                if (rainbowGlowView != null) {
                    rainbowGlowView.cleanup();
                    windowManager.removeView(rainbowGlowView);
                    rainbowGlowView = null;
                }
            } catch (Exception e) {
                rainbowGlowView = null;
            }
            try {
                if (translationOverlayView != null) {
                    windowManager.removeView(translationOverlayView);
                    translationOverlayView = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Gagal remove translationOverlayView", e);
                translationOverlayView = null;
            }

            // selectionView & bottomMenu di-fade-out PARALEL (durasi sama,
            // FADE_OUT_DURATION_MS) supaya penutupan terasa smooth
            // simetris dengan animasi kemunculannya — sebelumnya keduanya
            // langsung snap hilang di sini. Window sesungguhnya baru
            // di-remove & stopSelf() dipanggil setelah durasi fade
            // terlampaui, ditunggu SEKALI lewat postDelayed (bukan
            // saling menunggu callback masing-masing animator) supaya
            // service tidak tertunda berbeda-beda tergantung urutan
            // callback selesai.
            //
            // PENTING: field selectionView/bottomMenu SENGAJA belum
            // di-null-kan di sini (beda dari loadingStatusView/
            // rainbowGlowView/translationOverlayView di atas) — kalau
            // service dibunuh paksa oleh sistem tepat selama jeda fade
            // ini, jaring pengaman di onDestroy() masih perlu melihat
            // kedua field ini terisi supaya window yang belum sempat
            // di-remove tetap dibersihkan, bukan tertinggal menempel di
            // layar. Variabel lokal di bawah hanya untuk membawa
            // referensi ke callback postDelayed; field aslinya baru
            // di-null-kan setelah window benar-benar dihapus.
            final SelectionOverlayView selectionToRemove = selectionView;
            final BottomIconMenu menuToRemove = bottomMenu;

            if (selectionToRemove != null) {
                selectionToRemove.dismissAnimated(null);
            }
            if (menuToRemove != null) {
                menuToRemove.dismissAnimated(null);
            }

            long fadeDelay = (selectionToRemove != null || menuToRemove != null)
                    ? FADE_OUT_DURATION_MS
                    : 0L;

            mainHandler.postDelayed(() -> {
                try {
                    if (selectionToRemove != null) {
                        windowManager.removeView(selectionToRemove);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Gagal remove selectionView", e);
                }
                if (selectionView == selectionToRemove) {
                    selectionView = null;
                }
                try {
                    if (menuToRemove != null) {
                        windowManager.removeView(menuToRemove);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Gagal remove bottomMenu", e);
                }
                if (bottomMenu == menuToRemove) {
                    bottomMenu = null;
                }

                if (fullScreenshot != null && !fullScreenshot.isRecycled()) {
                    fullScreenshot.recycle();
                }
                fullScreenshot = null;
                if (currentCrop != null && !currentCrop.isRecycled()) {
                    currentCrop.recycle();
                }
                currentCrop = null;
                currentBounds = null;

                notifyFloatingOverlayClosed();
                stopSelf();
            }, fadeDelay);
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

        // Jaring pengaman terakhir: kalau service ini berhenti lewat
        // jalur manapun (dibunuh sistem, exception tak tertangani, atau
        // stopSelf() terpanggil sebelum closeOverlayAndStop() sempat
        // membersihkan window-nya sendiri), pastikan TIDAK ADA window
        // overlay yang tersisa menempel di layar.
        try {
            if (loadingStatusView != null) {
                windowManager.removeView(loadingStatusView);
                loadingStatusView = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (rainbowGlowView != null) {
                rainbowGlowView.cleanup();
                windowManager.removeView(rainbowGlowView);
                rainbowGlowView = null;
            }
        } catch (Exception ignored) {
        }
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
