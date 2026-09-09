package com.israfilx.circlesearch.root;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Persistent su session, sama seperti pola yang dipakai di proyek
 * GameOverlay (menggantikan pola "su per-call" yang lambat).
 *
 * Satu proses `su` dibuka sekali dan dipakai berulang untuk semua
 * perintah root (screencap, dsb) selama aplikasi hidup, dengan marker
 * echo untuk mendeteksi akhir output tiap perintah.
 */
public final class RootShell {

    private static final String TAG = "CircleSearch/Root";
    private static final String END_MARKER = "__CMD_END__";

    private static Process suProcess;
    private static DataOutputStream stdin;
    private static BufferedReader stdout;
    private static boolean available = false;

    private RootShell() {}

    public static synchronized boolean open() {
        if (available) return true;
        try {
            suProcess = Runtime.getRuntime().exec("su");
            stdin = new DataOutputStream(suProcess.getOutputStream());
            stdout = new BufferedReader(new InputStreamReader(suProcess.getInputStream()));
            // Verifikasi root benar-benar granted
            stdin.writeBytes("id\n");
            stdin.flush();
            stdin.writeBytes("echo " + END_MARKER + "\n");
            stdin.flush();

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.contains(END_MARKER)) break;
                sb.append(line).append('\n');
            }
            available = sb.toString().contains("uid=0");
            Log.d(TAG, "Root session dibuka, granted=" + available);
        } catch (IOException e) {
            Log.e(TAG, "Gagal membuka su session", e);
            available = false;
        }
        return available;
    }

    /**
     * Jalankan satu perintah pada su session yang sudah terbuka, kembalikan
     * seluruh stdout sebagai String. Membuka session otomatis bila belum ada.
     */
    public static synchronized String exec(String command) {
        if (!available && !open()) {
            Log.e(TAG, "Root tidak tersedia, perintah dibatalkan: " + command);
            return null;
        }
        try {
            stdin.writeBytes(command + "\n");
            stdin.flush();
            stdin.writeBytes("echo " + END_MARKER + "\n");
            stdin.flush();

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.contains(END_MARKER)) break;
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "Perintah root gagal: " + command, e);
            available = false; // Paksa reopen di panggilan berikutnya
            return null;
        }
    }

    public static synchronized void close() {
        try {
            if (stdin != null) {
                stdin.writeBytes("exit\n");
                stdin.flush();
                stdin.close();
            }
            if (stdout != null) stdout.close();
            if (suProcess != null) suProcess.destroy();
        } catch (IOException ignored) {
        } finally {
            available = false;
            suProcess = null;
        }
    }
}
