package com.israfilx.circlesearch.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.israfilx.circlesearch.root.RootShell;

/**
 * MainActivity ini BUKAN antarmuka utama pemakaian sehari-hari — app ini
 * dirancang "tidak terlihat" saat dipakai (trigger lewat assist gesture).
 * Activity ini hanya untuk:
 *  - Menunjukkan status apakah app sudah terdaftar sebagai default assistant
 *  - Tombol untuk menjalankan setup manual (fallback bila installer
 *    root module tidak/dijalankan)
 */
public class MainActivity extends Activity {

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        root.addView(statusText);

        Button setupButton = new Button(this);
        setupButton.setText("Set sebagai Default Assistant (root)");
        setupButton.setOnClickListener(v -> runSetup());
        root.addView(setupButton);

        Button checkRootButton = new Button(this);
        checkRootButton.setText("Cek akses root");
        checkRootButton.setOnClickListener(v -> checkRoot());
        root.addView(checkRootButton);

        setContentView(root);
        refreshStatus();
    }

    private void refreshStatus() {
        String pkg = getPackageName();
        statusText.setText(
                "Package: " + pkg + "\n\n" +
                "Trigger gesture assist (swipe sudut bawah / long-press power) " +
                "akan memanggil app ini setelah app di-set sebagai default assistant.\n\n" +
                "Gunakan tombol di bawah untuk setup manual, atau flash module root " +
                "installer agar setup otomatis saat boot."
        );
    }

    private void checkRoot() {
        boolean ok = RootShell.open();
        statusText.setText("Akses root: " + (ok ? "TERSEDIA" : "TIDAK TERSEDIA"));
    }

    private void runSetup() {
        String pkg = getPackageName();
        RootShell.exec("settings put secure voice_interaction_service " + pkg + "/.service.MyVoiceInteractionService");
        RootShell.exec("settings put secure assistant " + pkg + "/.service.MyVoiceInteractionService");
        String check = RootShell.exec("settings get secure assistant");
        statusText.setText("Setup dijalankan.\nNilai 'assistant' sekarang: " +
                (check == null ? "(gagal membaca)" : check.trim()));
    }
}
