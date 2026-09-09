package com.israfilx.circlesearch.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.israfilx.circlesearch.root.RootShell;

/**
 * Foreground service tempat capture layar (root screencap) dan overlay
 * seleksi akan dijalankan. Untuk saat ini baru berupa skeleton yang
 * membuktikan trigger layer bekerja end-to-end: begitu dipanggil dari
 * MyVoiceInteractionSession, service ini start sebagai foreground service
 * dan memverifikasi akses root.
 *
 * TODO (tahap berikutnya):
 *  - Jalankan `screencap -p` via RootShell, simpan ke cache internal
 *  - Tampilkan overlay WindowManager (TYPE_ACCESSIBILITY_OVERLAY) berisi
 *    screenshot yang di-freeze + canvas seleksi bebas
 *  - Setelah user selesai menyeleksi, crop bitmap sesuai bounding box
 *  - Tampilkan menu aksi: OCR+Translate (ML Kit) atau Visual Search
 *    (kirim crop via Intent.ACTION_SEND ke Google Lens)
 */
public class OverlayCaptureService extends Service {

    private static final String TAG = "CircleSearch/Capture";
    public static final String ACTION_START_CAPTURE = "com.israfilx.circlesearch.action.START_CAPTURE";

    private static final String CHANNEL_ID = "circle_search_capture";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START_CAPTURE.equals(intent.getAction())) {
            startForeground(NOTIF_ID, buildNotification());
            handleCaptureTrigger();
        }
        // START_NOT_STICKY: ini bukan daemon jangka panjang, tiap trigger
        // adalah siklus pendek capture -> overlay -> selesai/stop sendiri.
        return START_NOT_STICKY;
    }

    private void handleCaptureTrigger() {
        Log.d(TAG, "Trigger diterima, memverifikasi akses root...");
        boolean rootOk = RootShell.open();
        if (!rootOk) {
            Log.e(TAG, "Root tidak tersedia — capture dibatalkan");
            stopSelf();
            return;
        }
        Log.d(TAG, "Root OK. (Placeholder) Tahap berikutnya: screencap + overlay seleksi di sini.");

        // Placeholder sementara: hentikan service setelah verifikasi.
        // Akan diganti dengan alur capture+overlay sesungguhnya.
        stopSelf();
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
