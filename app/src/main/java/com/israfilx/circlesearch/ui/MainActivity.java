package com.israfilx.circlesearch.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.israfilx.circlesearch.root.RootShell;
import com.israfilx.circlesearch.service.FloatingTriggerService;
import com.israfilx.circlesearch.service.ScreenshotAccessibilityService;
import com.israfilx.circlesearch.util.OcrTranslateHelper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MainActivity berfungsi sebagai menu pengaturan aplikasi.
 *
 * Fitur:
 *  - Status dan pengaturan semua perizinan yang diperlukan
 *    (tampil di atas aplikasi lain, asisten digital, root, dsb.)
 *  - Manajemen unduhan model bahasa (manual, satu per satu atau semua)
 *
 * App ini dirancang "tidak terlihat" saat dipakai sehari-hari
 * (trigger lewat assist gesture). Activity ini hanya untuk setup
 * dan pengelolaan model bahasa.
 */
public class MainActivity extends Activity {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView statusOverlay;
    private TextView statusAssistant;
    private TextView statusRoot;
    private TextView statusFloating;
    private TextView statusA11y;
    private TextView statusBattery;
    private TextView languageStatusText;
    private LinearLayout languageListContainer;
    private ProgressBar downloadProgress;
    private TextView downloadProgressText;
    private Button downloadAllButton;
    private boolean isDownloading = false;

    private Set<String> downloadedCodes = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        // ---- Judul ----
        TextView title = sectionTitle("Circle Search Overlay");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = bodyText(
                "Menu pengaturan perizinan dan model bahasa.\n" +
                "Aplikasi dipicu lewat gesture asisten (swipe sudut bawah / long-press power) " +
                "setelah diatur sebagai Asisten Digital.");
        root.addView(subtitle);

        // ================================================================
        // BAGIAN 1: PERIZINAN
        // ================================================================
        root.addView(sectionHeader("1. Perizinan yang Diperlukan"));

        root.addView(bodyText(
                "Overlay wajib. Asisten Digital ATAU Floating Trigger (+ Accessibility) " +
                "untuk memicu capture. Root opsional. Ketuk tombol untuk pengaturan."));

        // --- Overlay (tampil di atas aplikasi lain) ---
        root.addView(itemLabel("Tampil di atas aplikasi lain (Overlay)"));
        statusOverlay = statusText();
        root.addView(statusOverlay);
        Button btnOverlay = actionButton("Buka pengaturan Overlay");
        btnOverlay.setOnClickListener(v -> openOverlaySettings());
        root.addView(btnOverlay);

        // --- Asisten Digital ---
        root.addView(itemLabel("Asisten Digital (Default Assistant)"));
        statusAssistant = statusText();
        root.addView(statusAssistant);
        Button btnAssistant = actionButton("Buka pengaturan Asisten Digital");
        btnAssistant.setOnClickListener(v -> openAssistantSettings());
        root.addView(btnAssistant);

        Button btnSetAssistantRoot = actionButton("Set sebagai Asisten (via root)");
        btnSetAssistantRoot.setOnClickListener(v -> runSetupAssistantRoot());
        root.addView(btnSetAssistantRoot);

        // --- Root ---
        root.addView(itemLabel("Akses Root"));
        statusRoot = statusText();
        root.addView(statusRoot);
        Button btnCheckRoot = actionButton("Cek akses root");
        btnCheckRoot.setOnClickListener(v -> checkRoot());
        root.addView(btnCheckRoot);

        // --- Floating Trigger (opsional) ---
        root.addView(itemLabel("Floating Trigger (pil tepi layar)"));
        statusFloating = statusText();
        root.addView(statusFloating);
        Button btnToggleFloating = actionButton("Aktifkan / Nonaktifkan Floating");
        btnToggleFloating.setOnClickListener(v -> toggleFloatingTrigger());
        root.addView(btnToggleFloating);

        root.addView(itemLabel("Accessibility (screenshot untuk floating)"));
        statusA11y = statusText();
        root.addView(statusA11y);
        Button btnA11y = actionButton("Buka pengaturan Accessibility");
        btnA11y.setOnClickListener(v -> openAccessibilitySettings());
        root.addView(btnA11y);

        // --- Battery unrestricted ---
        root.addView(itemLabel("Tanpa batasan baterai (abaikan optimasi)"));
        statusBattery = statusText();
        root.addView(statusBattery);
        Button btnBattery = actionButton("Izinkan tanpa batasan baterai");
        btnBattery.setOnClickListener(v -> requestBatteryUnrestricted());
        root.addView(btnBattery);

        // --- Info tambahan ---
        root.addView(bodyText(
                "Catatan:\n" +
                "• Overlay wajib untuk menampilkan layer seleksi.\n" +
                "• Asisten Digital: trigger gesture + screenshot sistem (tanpa dialog).\n" +
                "• Floating Trigger: pil tipis di tepi kiri — swipe kanan jadi tombol, " +
                "tap untuk memicu. Butuh Accessibility (Android 11+) bila tanpa root/asisten.\n" +
                "• Root (opsional): screencap silent sebagai fallback."));

        // ================================================================
        // BAGIAN 2: MODEL BAHASA
        // ================================================================
        root.addView(sectionHeader("2. Model Bahasa (Unduhan Manual)"));

        root.addView(bodyText(
                "Model terjemahan TIDAK diunduh otomatis saat dipakai. " +
                "Anda harus mengunduh model terlebih dahulu di sini. " +
                "Setelah diunduh, terjemahan berjalan sepenuhnya offline.\n\n" +
                "Bahasa target tetap: Indonesia (id)."));

        languageStatusText = statusText();
        root.addView(languageStatusText);

        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setIndeterminate(true);
        downloadProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        pp.topMargin = dp(4);
        pp.bottomMargin = dp(4);
        downloadProgress.setLayoutParams(pp);
        root.addView(downloadProgress);

        downloadProgressText = bodyText("");
        downloadProgressText.setVisibility(View.GONE);
        root.addView(downloadProgressText);

        downloadAllButton = actionButton("Unduh SEMUA bahasa");
        downloadAllButton.setOnClickListener(v -> downloadAllLanguages());
        root.addView(downloadAllButton);

        Button refreshLangButton = actionButton("Segarkan status model");
        refreshLangButton.setOnClickListener(v -> refreshLanguageStatus());
        root.addView(refreshLangButton);

        root.addView(itemLabel("Daftar bahasa (ketuk untuk unduh / hapus)"));
        languageListContainer = new LinearLayout(this);
        languageListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(languageListContainer);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        refreshLanguageStatus();
        ensureFloatingRunning();
    }

    /** Jika user sebelumnya mengaktifkan floating, pastikan service jalan. */
    private void ensureFloatingRunning() {
        boolean on = getSharedPreferences(FloatingTriggerService.PREFS, MODE_PRIVATE)
                .getBoolean(FloatingTriggerService.KEY_ENABLED, false);
        if (on && Settings.canDrawOverlays(this)) {
            Intent i = new Intent(this, FloatingTriggerService.class);
            i.setAction(FloatingTriggerService.ACTION_SHOW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        }
    }

    // ----------------------------------------------------------------
    // Status perizinan
    // ----------------------------------------------------------------

    private void refreshPermissionStatus() {
        // Overlay
        boolean overlayGranted = Settings.canDrawOverlays(this);
        statusOverlay.setText(overlayGranted
                ? "✓ Diizinkan"
                : "✗ Belum diizinkan — wajib diaktifkan");
        statusOverlay.setTextColor(overlayGranted ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));

        // Assistant
        String assistant = null;
        try {
            assistant = Settings.Secure.getString(getContentResolver(), "assistant");
        } catch (Exception ignored) {}
        String pkg = getPackageName();
        boolean isAssistant = assistant != null && assistant.contains(pkg);
        statusAssistant.setText(isAssistant
                ? "✓ Sudah diatur sebagai asisten digital\n  (" + assistant + ")"
                : "✗ Belum diatur — ketuk tombol di bawah untuk mengatur");
        statusAssistant.setTextColor(isAssistant ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));

        // Root (cek cepat tanpa blocking lama)
        statusRoot.setText("Menekan tombol \"Cek akses root\" untuk memeriksa…");
        statusRoot.setTextColor(Color.GRAY);

        // Floating
        boolean floatingOn = getSharedPreferences(FloatingTriggerService.PREFS, MODE_PRIVATE)
                .getBoolean(FloatingTriggerService.KEY_ENABLED, false);
        if (statusFloating != null) {
            statusFloating.setText(floatingOn
                    ? "✓ Floating Trigger AKTIF (pil di tepi kiri)"
                    : "○ Floating Trigger nonaktif");
            statusFloating.setTextColor(floatingOn
                    ? Color.parseColor("#2E7D32") : Color.GRAY);
        }

        // Accessibility
        boolean a11y = ScreenshotAccessibilityService.isAvailable();
        if (statusA11y != null) {
            statusA11y.setText(a11y
                    ? "✓ Accessibility aktif (screenshot siap)"
                    : "✗ Accessibility belum aktif — wajib untuk floating tanpa root");
            statusA11y.setTextColor(a11y
                    ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));
        }

        // Battery
        if (statusBattery != null) {
            boolean unrestricted = isBatteryUnrestricted();
            statusBattery.setText(unrestricted
                    ? "✓ Tanpa batasan baterai (tidak dioptimasi sistem)"
                    : "✗ Masih dioptimasi — service bisa di-kill sistem");
            statusBattery.setTextColor(unrestricted
                    ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));
        }
    }

    private boolean isBatteryUnrestricted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryUnrestricted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Tidak diperlukan di versi Android ini", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isBatteryUnrestricted()) {
            Toast.makeText(this, "Sudah tanpa batasan baterai", Toast.LENGTH_SHORT).show();
            refreshPermissionStatus();
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Buka Settings → Baterai → Optimasi baterai manual",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void toggleFloatingTrigger() {
        SharedPreferences prefs = getSharedPreferences(FloatingTriggerService.PREFS, MODE_PRIVATE);
        boolean currently = prefs.getBoolean(FloatingTriggerService.KEY_ENABLED, false);
        boolean next = !currently;
        prefs.edit().putBoolean(FloatingTriggerService.KEY_ENABLED, next).apply();

        Intent i = new Intent(this, FloatingTriggerService.class);
        if (next) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Aktifkan izin Overlay dulu", Toast.LENGTH_SHORT).show();
                prefs.edit().putBoolean(FloatingTriggerService.KEY_ENABLED, false).apply();
                openOverlaySettings();
                refreshPermissionStatus();
                return;
            }
            i.setAction(FloatingTriggerService.ACTION_SHOW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Toast.makeText(this, "Floating Trigger diaktifkan", Toast.LENGTH_SHORT).show();
        } else {
            i.setAction(FloatingTriggerService.ACTION_HIDE);
            stopService(i);
            Toast.makeText(this, "Floating Trigger dimatikan", Toast.LENGTH_SHORT).show();
        }
        refreshPermissionStatus();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void checkRoot() {
        statusRoot.setText("Memeriksa…");
        new Thread(() -> {
            boolean ok = RootShell.open();
            mainHandler.post(() -> {
                statusRoot.setText(ok ? "✓ Akses root TERSEDIA" : "✗ Akses root TIDAK TERSEDIA");
                statusRoot.setTextColor(ok ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));
            });
        }).start();
    }

    private void runSetupAssistantRoot() {
        String pkg = getPackageName();
        new Thread(() -> {
            RootShell.exec("settings put secure voice_interaction_service " + pkg + "/.service.MyVoiceInteractionService");
            RootShell.exec("settings put secure assistant " + pkg + "/.service.MyVoiceInteractionService");
            String check = RootShell.exec("settings get secure assistant");
            mainHandler.post(() -> {
                Toast.makeText(this, "Setup asisten selesai.", Toast.LENGTH_SHORT).show();
                statusAssistant.setText("Nilai 'assistant' sekarang:\n" +
                        (check == null ? "(gagal membaca)" : check.trim()));
                refreshPermissionStatus();
            });
        }).start();
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            // Fallback ke pengaturan aplikasi
            openAppSettings();
        }
    }

    private void openAssistantSettings() {
        // Coba beberapa intent yang umum dipakai OEM
        Intent[] candidates = new Intent[]{
                new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                new Intent("android.settings.VOICE_INPUT_SETTINGS"),
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        };
        boolean launched = false;
        for (Intent intent : candidates) {
            if (intent == null) continue;
            try {
                startActivity(intent);
                launched = true;
                break;
            } catch (Exception ignored) {}
        }
        if (!launched) {
            // Fallback: buka pengaturan aplikasi lalu user harus cari "Aplikasi asisten digital"
            Toast.makeText(this,
                    "Buka Settings → Aplikasi → Aplikasi asisten digital (atau Default apps) " +
                    "lalu pilih Circle Search Overlay.",
                    Toast.LENGTH_LONG).show();
            openAppSettings();
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka pengaturan", Toast.LENGTH_SHORT).show();
        }
    }

    // ----------------------------------------------------------------
    // Manajemen model bahasa
    // ----------------------------------------------------------------

    private void refreshLanguageStatus() {
        languageStatusText.setText("Memeriksa model yang sudah diunduh…");
        OcrTranslateHelper.getDownloadedLanguageCodes(new OcrTranslateHelper.ModelCallbackWithList() {
            @Override
            public void onSuccess(List<String> codes) {
                mainHandler.post(() -> {
                    downloadedCodes = new HashSet<>(codes);
                    int count = codes.size();
                    languageStatusText.setText("Model terunduh: " + count + " bahasa"
                            + (codes.contains(OcrTranslateHelper.TARGET_LANGUAGE)
                            ? " (termasuk Indonesia)" : " — model target Indonesia BELUM ada"));
                    languageStatusText.setTextColor(Color.parseColor("#1565C0"));
                    rebuildLanguageList();
                });
            }

            @Override
            public void onFailure(Exception e) {
                mainHandler.post(() -> {
                    languageStatusText.setText("Gagal memeriksa model: " + e.getMessage());
                    languageStatusText.setTextColor(Color.parseColor("#C62828"));
                    rebuildLanguageList();
                });
            }
        });
    }

    private void rebuildLanguageList() {
        languageListContainer.removeAllViews();
        for (OcrTranslateHelper.LanguageInfo info : OcrTranslateHelper.SUPPORTED_SOURCE_LANGUAGES) {
            boolean downloaded = downloadedCodes.contains(info.code);
            // Target model juga dihitung sebagai "siap" bila target ada
            boolean targetOk = downloadedCodes.contains(OcrTranslateHelper.TARGET_LANGUAGE);
            boolean ready = downloaded && targetOk;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView name = new TextView(this);
            name.setText((ready ? "✓ " : "○ ") + info.displayName);
            name.setTextSize(14);
            name.setTextColor(ready ? Color.parseColor("#2E7D32") : Color.parseColor("#424242"));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(np);
            row.addView(name);

            Button action = new Button(this);
            if (ready) {
                action.setText("Hapus");
                action.setOnClickListener(v -> confirmDelete(info));
            } else {
                action.setText("Unduh");
                action.setEnabled(!isDownloading);
                action.setOnClickListener(v -> downloadOne(info));
            }
            action.setTextSize(12);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            action.setLayoutParams(bp);
            row.addView(action);

            languageListContainer.addView(row);
        }
    }

    private void downloadOne(OcrTranslateHelper.LanguageInfo info) {
        if (isDownloading) return;
        setDownloading(true, "Mengunduh " + info.displayName + "…");

        OcrTranslateHelper.downloadModel(info.code, new OcrTranslateHelper.ModelCallback() {
            @Override
            public void onSuccess() {
                mainHandler.post(() -> {
                    setDownloading(false, null);
                    Toast.makeText(MainActivity.this, info.displayName + " berhasil diunduh", Toast.LENGTH_SHORT).show();
                    refreshLanguageStatus();
                });
            }

            @Override
            public void onFailure(Exception e) {
                mainHandler.post(() -> {
                    setDownloading(false, null);
                    Toast.makeText(MainActivity.this,
                            "Gagal unduh " + info.displayName + ": " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(String message) {
                mainHandler.post(() -> {
                    if (downloadProgressText != null) {
                        downloadProgressText.setText(message);
                    }
                });
            }
        });
    }

    private void downloadAllLanguages() {
        if (isDownloading) return;

        new AlertDialog.Builder(this)
                .setTitle("Unduh semua bahasa")
                .setMessage("Ini akan mengunduh model untuk semua bahasa yang didukung. " +
                        "Ukuran total bisa mencapai puluhan hingga ratusan MB. " +
                        "Pastikan koneksi internet stabil. Lanjutkan?")
                .setPositiveButton("Unduh semua", (d, w) -> {
                    setDownloading(true, "Memulai unduhan semua bahasa…");
                    OcrTranslateHelper.downloadAllModels(new OcrTranslateHelper.ModelCallback() {
                        @Override
                        public void onSuccess() {
                            mainHandler.post(() -> {
                                setDownloading(false, null);
                                Toast.makeText(MainActivity.this,
                                        "Semua model berhasil diunduh (atau sudah tersedia)",
                                        Toast.LENGTH_LONG).show();
                                refreshLanguageStatus();
                            });
                        }

                        @Override
                        public void onFailure(Exception e) {
                            mainHandler.post(() -> {
                                setDownloading(false, null);
                                Toast.makeText(MainActivity.this,
                                        "Unduhan selesai dengan beberapa kegagalan: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                                refreshLanguageStatus();
                            });
                        }

                        @Override
                        public void onProgress(String message) {
                            mainHandler.post(() -> {
                                if (downloadProgressText != null) {
                                    downloadProgressText.setText(message);
                                }
                            });
                        }
                    });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void confirmDelete(OcrTranslateHelper.LanguageInfo info) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus model")
                .setMessage("Hapus model " + info.displayName + "? Ruang penyimpanan akan dibebaskan, " +
                        "tetapi terjemahan dari bahasa ini tidak akan tersedia sampai diunduh ulang.")
                .setPositiveButton("Hapus", (d, w) -> {
                    OcrTranslateHelper.deleteModel(info.code, new OcrTranslateHelper.ModelCallback() {
                        @Override
                        public void onSuccess() {
                            mainHandler.post(() -> {
                                Toast.makeText(MainActivity.this, "Model dihapus", Toast.LENGTH_SHORT).show();
                                refreshLanguageStatus();
                            });
                        }

                        @Override
                        public void onFailure(Exception e) {
                            mainHandler.post(() ->
                                    Toast.makeText(MainActivity.this,
                                            "Gagal hapus: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void setDownloading(boolean downloading, String message) {
        isDownloading = downloading;
        downloadProgress.setVisibility(downloading ? View.VISIBLE : View.GONE);
        downloadProgressText.setVisibility(downloading ? View.VISIBLE : View.GONE);
        if (message != null) {
            downloadProgressText.setText(message);
        }
        downloadAllButton.setEnabled(!downloading);
        rebuildLanguageList();
    }

    // ----------------------------------------------------------------
    // Helper UI
    // ----------------------------------------------------------------

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(20), 0, dp(8));
        tv.setTextColor(Color.parseColor("#1565C0"));
        return tv;
    }

    private TextView itemLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(12), 0, dp(2));
        return tv;
    }

    private TextView bodyText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding(0, dp(2), 0, dp(6));
        tv.setTextColor(Color.parseColor("#424242"));
        return tv;
    }

    private TextView statusText() {
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private Button actionButton(String label) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(4);
        btn.setLayoutParams(lp);
        return btn;
    }
}
