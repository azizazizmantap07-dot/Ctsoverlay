package com.israfilx.circlesearch.service;

import android.service.voice.VoiceInteractionService;
import android.util.Log;

/**
 * Entry point wajib untuk sistem VoiceInteraction.
 * Service ini sendiri tidak melakukan apa-apa selain "mendaftarkan diri" —
 * pekerjaan sebenarnya (capture, overlay) terjadi di
 * {@link MyVoiceInteractionSession} yang dibuat oleh
 * {@link MyVoiceInteractionSessionService} setiap kali gesture assist
 * (swipe pojok bawah / long-press power) dipicu oleh sistem.
 */
public class MyVoiceInteractionService extends VoiceInteractionService {

    private static final String TAG = "CircleSearch/VIS";

    @Override
    public void onReady() {
        super.onReady();
        Log.d(TAG, "VoiceInteractionService ready — app siap menerima trigger assist gesture");
    }
}
