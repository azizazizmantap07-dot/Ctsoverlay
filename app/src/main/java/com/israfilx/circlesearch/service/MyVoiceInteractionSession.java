package com.israfilx.circlesearch.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

/**
 * Session ini "mendarat" setiap kali gesture assist sistem dipicu
 * (swipe panjang dari sudut bawah, atau long-press tombol power —
 * tergantung konfigurasi assist gesture di ROM/launcher).
 *
 * Kita TIDAK menampilkan UI voice-interaction bawaan apapun (tidak ada
 * mic prompt, tidak ada bottom sheet standar). Sebagai gantinya, session
 * langsung men-trigger OverlayCaptureService (yang melakukan root screencap
 * + menampilkan overlay seleksi), lalu menutup dirinya sendiri secepat
 * mungkin supaya tidak ada jeda/flash UI yang terlihat user.
 */
public class MyVoiceInteractionSession extends VoiceInteractionSession {

    private static final String TAG = "CircleSearch/Session";

    public MyVoiceInteractionSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(android.os.Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Log.d(TAG, "Assist gesture terdeteksi — meneruskan ke OverlayCaptureService");

        Context ctx = getContext();
        Intent serviceIntent = new Intent(ctx, OverlayCaptureService.class);
        serviceIntent.setAction(OverlayCaptureService.ACTION_START_CAPTURE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(serviceIntent);
        } else {
            ctx.startService(serviceIntent);
        }

        // Session voice-interaction tidak lagi diperlukan setelah trigger
        // diteruskan — overlay & capture sepenuhnya ditangani oleh
        // OverlayCaptureService, bukan oleh session ini.
        hide();
    }

    @Override
    public void onHide() {
        super.onHide();
        Log.d(TAG, "Session disembunyikan setelah trigger diteruskan");
    }
}
