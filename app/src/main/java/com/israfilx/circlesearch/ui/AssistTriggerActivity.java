package com.israfilx.circlesearch.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.israfilx.circlesearch.service.OverlayCaptureService;

/**
 * Activity ini WAJIB ada agar app lolos syarat RoleManager untuk
 * android.app.role.ASSISTANT. Menurut dokumentasi resmi Android
 * (source.android.com/docs/core/config/android-roles), sebuah app
 * baru dianggap kandidat valid untuk role ASSISTANT bila memenuhi
 * salah satu dari:
 *   (a) punya Activity yang menangani ACTION_ASSIST, ATAU
 *   (b) punya VoiceInteractionService DENGAN flag eksplisit yang
 *       menandakan service tersebut mampu menangani assist action
 *
 * Tanpa activity ini, VoiceInteractionService saja TIDAK CUKUP —
 * app tidak akan muncul sama sekali di daftar pilihan "Aplikasi
 * asisten digital" pada Settings, meski service-nya terdaftar
 * dengan benar di package manager.
 *
 * Activity ini transparan (tidak render UI apapun) dan hanya
 * berfungsi sebagai jalur trigger alternatif: begitu dipanggil,
 * langsung teruskan ke OverlayCaptureService lalu finish() secepat
 * mungkin, sama seperti alur MyVoiceInteractionSession.
 */
public class AssistTriggerActivity extends Activity {

    private static final String TAG = "CircleSearch/AssistAct";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "ACTION_ASSIST diterima — meneruskan ke OverlayCaptureService");

        Intent serviceIntent = new Intent(this, OverlayCaptureService.class);
        serviceIntent.setAction(OverlayCaptureService.ACTION_START_CAPTURE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        finish();
        overridePendingTransition(0, 0);
    }
}
