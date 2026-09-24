package com.israfilx.circlesearch.service;

import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * Setiap kali sistem memicu assist gesture, ia memanggil onNewSession()
 * di sini untuk mendapatkan instance session baru. Kita tidak perlu logika
 * tambahan di kelas ini — cukup kembalikan session kustom kita.
 */
public class MyVoiceInteractionSessionService extends VoiceInteractionSessionService {

    @Override
    public VoiceInteractionSession onNewSession(android.os.Bundle args) {
        return new MyVoiceInteractionSession(this);
    }
}
