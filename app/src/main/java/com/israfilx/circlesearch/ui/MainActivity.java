package com.israfilx.circlesearch.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
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

    // ---- Palet tema dark soft ----
    private static final String BG_DARK = "#14161C";
    private static final String CARD_DARK = "#1D2029";
    private static final String TEXT_PRIMARY = "#ECEDF2";
    private static final String TEXT_SECONDARY = "#9A9DAE";
    private static final String TEXT_BODY = "#8890A6";
    private static final String STATUS_OK = "#4FD37A";
    private static final String STATUS_BAD = "#FF6B6B";
    private static final String STATUS_NEUTRAL = "#6B7080";
    private static final String BTN_BG = "#262A38";
    private static final String BTN_TEXT = "#E4E6F0";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor(BG_DARK));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        // ---- Judul cyberpunk: RGB + glow animasi ----
        TextView title = new TextView(this);
        title.setText("CIRCLE TO SEARCH");
        title.setTextSize(30);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.12f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, dp(12), 0, dp(4));
        title.setShadowLayer(18f, 0f, 0f, Color.parseColor("#DC2828"));
        root.addView(title);
        startTitleRgbAnimation(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("by: Aziz_dev");
        subtitle.setTextSize(12);
        subtitle.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        subtitle.setLetterSpacing(0.15f);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setTextColor(Color.parseColor(TEXT_SECONDARY));
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle);

        // ============================================================
        // PERIZINAN INTI
        // ============================================================
        root.addView(sectionHeader("Perizinan inti"));

        root.addView(itemLabel("Tampil di atas aplikasi lain"));
        statusOverlay = statusText();
        root.addView(statusOverlay);
        Button btnOverlay = actionButton("Buka pengaturan Overlay");
        btnOverlay.setOnClickListener(v -> openOverlaySettings());
        root.addView(btnOverlay);

        root.addView(itemLabel("Izin Accessibility"));
        statusA11y = statusText();
        root.addView(statusA11y);
        Button btnA11y = actionButton("Buka pengaturan Accessibility");
        btnA11y.setOnClickListener(v -> openAccessibilitySettings());
        root.addView(btnA11y);

        root.addView(itemLabel("Tanpa batasan baterai"));
        statusBattery = statusText();
        root.addView(statusBattery);
        Button btnBattery = actionButton("Izinkan tanpa batasan baterai");
        btnBattery.setOnClickListener(v -> requestBatteryUnrestricted());
        root.addView(btnBattery);

        // ============================================================
        // NON ROOT — SUPPORT ASSISTEN
        // ============================================================
        root.addView(sectionHeader("Non root · support asisten"));

        root.addView(itemLabel("Asisten Digital (default)"));
        statusAssistant = statusText();
        root.addView(statusAssistant);
        Button btnAssistant = actionButton("Buka pengaturan Asisten Digital");
        btnAssistant.setOnClickListener(v -> openAssistantSettings());
        root.addView(btnAssistant);

        // ============================================================
        // NON ROOT — TIDAK SUPPORT ASSISTEN
        // ============================================================
        root.addView(sectionHeader("Non root · tidak support asisten"));

        root.addView(itemLabel("Floating Trigger Button"));
        statusFloating = statusText();
        root.addView(statusFloating);
        Button btnToggleFloating = actionButton("Aktifkan / Nonaktifkan Floating");
        btnToggleFloating.setOnClickListener(v -> toggleFloatingTrigger());
        root.addView(btnToggleFloating);

        // ============================================================
        // ROOT MODE
        // ============================================================
        root.addView(sectionHeader("Root mode"));

        root.addView(itemLabel("Set asisten default (via root)"));
        statusRoot = statusText();
        root.addView(statusRoot);
        Button btnCheckRoot = actionButton("Cek akses root");
        btnCheckRoot.setOnClickListener(v -> checkRoot());
        root.addView(btnCheckRoot);
        Button btnSetAssistantRoot = actionButton("Set sebagai Asisten (via root)");
        btnSetAssistantRoot.setOnClickListener(v -> runSetupAssistantRoot());
        root.addView(btnSetAssistantRoot);

        // ============================================================
        // MENU BAHASA
        // ============================================================
        root.addView(sectionHeader("Menu bahasa"));

        root.addView(bodyText(
                "Model terjemahan diunduh manual. Target: Indonesia (id). Offline setelah unduh."));

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

        root.addView(itemLabel("Daftar bahasa (ketuk unduh / hapus)"));
        languageListContainer = new LinearLayout(this);
        languageListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(languageListContainer);

        setContentView(scroll);
    }

    /**
     * Judul cyberpunk RGB + glow dinamis: merah → kuning → hijau → merah (loop).
     * Warna teks dan warna shadow-glow dianimasikan bersamaan, plus radius glow
     * "bernapas" (membesar-mengecil) agar terasa seperti neon sign yang hidup.
     */
    private void startTitleRgbAnimation(TextView title) {
        int red = Color.rgb(255, 45, 45);
        int yellow = Color.rgb(255, 210, 30);
        int green = Color.rgb(50, 220, 110);

        ValueAnimator colorAnim = ValueAnimator.ofObject(
                new ArgbEvaluator(), red, yellow, green, red);
        colorAnim.setDuration(5000);
        colorAnim.setRepeatCount(ValueAnimator.INFINITE);
        colorAnim.setRepeatMode(ValueAnimator.RESTART);
        colorAnim.addUpdateListener(a -> {
            int color = (int) a.getAnimatedValue();
            title.setTextColor(color);
            float radius = title.getTag() != null ? (float) title.getTag() : 18f;
            title.setShadowLayer(radius, 0f, 0f, color);
        });
        colorAnim.start();

        // Radius glow "bernapas" — dianimasikan terpisah agar independen dari warna
        ValueAnimator glowPulse = ValueAnimator.ofFloat(14f, 26f);
        glowPulse.setDuration(1400);
        glowPulse.setRepeatCount(ValueAnimator.INFINITE);
        glowPulse.setRepeatMode(ValueAnimator.REVERSE);
        glowPulse.addUpdateListener(a -> {
            float radius = (float) a.getAnimatedValue();
            title.setTag(radius);
            int currentColor = title.getCurrentTextColor();
            title.setShadowLayer(radius, 0f, 0f, currentColor);
        });
        glowPulse.start();
    }

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
        statusOverlay.setTextColor(overlayGranted ? Color.parseColor(STATUS_OK) : Color.parseColor(STATUS_BAD));

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
        statusAssistant.setTextColor(isAssistant ? Color.parseColor(STATUS_OK) : Color.parseColor(STATUS_BAD));

        // Root (cek cepat tanpa blocking lama)
        statusRoot.setText("Menekan tombol \"Cek akses root\" untuk memeriksa…");
        statusRoot.setTextColor(Color.parseColor(STATUS_NEUTRAL));

        // Floating
        boolean floatingOn = getSharedPreferences(FloatingTriggerService.PREFS, MODE_PRIVATE)
                .getBoolean(FloatingTriggerService.KEY_ENABLED, false);
        if (statusFloating != null) {
            statusFloating.setText(floatingOn
                    ? "✓ Floating Trigger AKTIF (pil di tepi kiri)"
                    : "○ Floating Trigger nonaktif");
            statusFloating.setTextColor(floatingOn
                    ? Color.parseColor(STATUS_OK) : Color.parseColor(STATUS_NEUTRAL));
        }

        // Accessibility
        boolean a11y = ScreenshotAccessibilityService.isAvailable();
        if (statusA11y != null) {
            statusA11y.setText(a11y
                    ? "✓ Accessibility aktif (screenshot siap)"
                    : "✗ Accessibility belum aktif — wajib untuk floating tanpa root");
            statusA11y.setTextColor(a11y
                    ? Color.parseColor(STATUS_OK) : Color.parseColor(STATUS_BAD));
        }

        // Battery
        if (statusBattery != null) {
            boolean unrestricted = isBatteryUnrestricted();
            statusBattery.setText(unrestricted
                    ? "✓ Tanpa batasan baterai (tidak dioptimasi sistem)"
                    : "✗ Masih dioptimasi — service bisa di-kill sistem");
            statusBattery.setTextColor(unrestricted
                    ? Color.parseColor(STATUS_OK) : Color.parseColor(STATUS_BAD));
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
                statusRoot.setTextColor(ok ? Color.parseColor(STATUS_OK) : Color.parseColor(STATUS_BAD));
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
                    languageStatusText.setTextColor(Color.parseColor("#5AA9FF"));
                    rebuildLanguageList();
                });
            }

            @Override
            public void onFailure(Exception e) {
                mainHandler.post(() -> {
                    languageStatusText.setText("Gagal memeriksa model: " + e.getMessage());
                    languageStatusText.setTextColor(Color.parseColor(STATUS_BAD));
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
            name.setTextColor(ready ? Color.parseColor(STATUS_OK) : Color.parseColor(TEXT_SECONDARY));
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
        tv.setTextColor(Color.parseColor(TEXT_PRIMARY));
        return tv;
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(22), 0, dp(6));
        tv.setTextColor(Color.parseColor(TEXT_PRIMARY));
        return tv;
    }

    private TextView itemLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText("•  " + text);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(10), 0, dp(2));
        tv.setTextColor(Color.parseColor(TEXT_PRIMARY));
        return tv;
    }

    private TextView bodyText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setPadding(0, dp(2), 0, dp(6));
        tv.setTextColor(Color.parseColor(TEXT_BODY));
        return tv;
    }

    private TextView statusText() {
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setPadding(dp(8), 0, 0, dp(2));
        tv.setTextColor(Color.parseColor(STATUS_NEUTRAL));
        return tv;
    }

    private Button actionButton(String label) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTextColor(Color.parseColor(BTN_TEXT));
        btn.setBackgroundColor(Color.parseColor(BTN_BG));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(6);
        btn.setLayoutParams(lp);
        return btn;
    }
}

