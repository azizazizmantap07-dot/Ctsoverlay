package com.israfilx.circlesearch.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.ContextThemeWrapper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.color.MaterialColors;
import com.israfilx.circlesearch.service.FloatingTriggerService;
import com.israfilx.circlesearch.service.ScreenshotAccessibilityService;
import com.israfilx.circlesearch.util.OcrTranslateHelper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Menu utama: status izin + pengelolaan model bahasa.
 *
 * Tampilan Material 3 minimalis — kartu tonal, satu baris per izin,
 * teks singkat. Logika izin/unduhan tidak berubah.
 */
public class MainActivity extends Activity {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Baris izin: status + tombol aksi
    private Row rowOverlay;
    private Row rowA11y;
    private Row rowBattery;
    private Row rowAssistant;
    private MaterialSwitch floatingSwitch;
    private boolean suppressSwitch = false;

    // Bahasa
    private TextView languageSummary;
    private LinearLayout languageListContainer;
    private LinearProgressIndicator downloadProgress;
    private TextView downloadProgressText;
    private MaterialButton downloadAllButton;
    private boolean isDownloading = false;
    private Set<String> downloadedCodes = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat wic =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        wic.setAppearanceLightStatusBars(!night);
        wic.setAppearanceLightNavigationBars(!night);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = dp(16);
        root.setPadding(side, dp(8), side, dp(24));
        scroll.addView(root);

        // Sisakan ruang status bar / nav bar (edge-to-edge)
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            androidx.core.graphics.Insets b =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(side, b.top + dp(8), side, b.bottom + dp(24));
            return insets;
        });

        // ---- Header ----
        TextView title = text("Circle Search", 28, com.google.android.material.R.attr.colorOnSurface);
        title.setPadding(dp(4), dp(16), 0, 0);
        root.addView(title);

        TextView sub = text("Cari dan terjemahkan dari layar", 14,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        sub.setPadding(dp(4), dp(2), 0, dp(20));
        root.addView(sub);

        // ---- Izin ----
        root.addView(sectionLabel("Izin"));
        MaterialCardView permCard = card();
        LinearLayout permBox = cardBox(permCard);

        rowOverlay = new Row("Tampil di atas aplikasi", "Buka");
        rowOverlay.button.setOnClickListener(v -> openOverlaySettings());
        permBox.addView(rowOverlay.view);

        permBox.addView(divider());
        rowA11y = new Row("Aksesibilitas", "Buka");
        rowA11y.button.setOnClickListener(v -> openAccessibilitySettings());
        permBox.addView(rowA11y.view);

        permBox.addView(divider());
        rowBattery = new Row("Tanpa batasan baterai", "Izinkan");
        rowBattery.button.setOnClickListener(v -> requestBatteryUnrestricted());
        permBox.addView(rowBattery.view);
        root.addView(permCard);

        // ---- Pemicu ----
        root.addView(sectionLabel("Pemicu"));
        MaterialCardView trigCard = card();
        LinearLayout trigBox = cardBox(trigCard);

        rowAssistant = new Row("Asisten digital", "Atur");
        rowAssistant.button.setOnClickListener(v -> openAssistantSettings());
        trigBox.addView(rowAssistant.view);

        trigBox.addView(divider());
        trigBox.addView(buildFloatingRow());
        root.addView(trigCard);

        // ---- Bahasa ----
        root.addView(sectionLabel("Bahasa"));
        MaterialCardView langCard = card();
        LinearLayout langBox = cardBox(langCard);

        LinearLayout langHead = new LinearLayout(this);
        langHead.setOrientation(LinearLayout.HORIZONTAL);
        langHead.setGravity(Gravity.CENTER_VERTICAL);
        langHead.setPadding(dp(16), dp(12), dp(8), dp(12));

        LinearLayout langTexts = new LinearLayout(this);
        langTexts.setOrientation(LinearLayout.VERTICAL);
        langTexts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        langTexts.addView(text("Model terjemahan", 16,
                com.google.android.material.R.attr.colorOnSurface));
        languageSummary = text("Memeriksa…", 13,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        langTexts.addView(languageSummary);
        langHead.addView(langTexts);

        downloadAllButton = tonalButton("Unduh semua");
        downloadAllButton.setOnClickListener(v -> downloadAllLanguages());
        langHead.addView(downloadAllButton);
        langBox.addView(langHead);

        downloadProgress = new LinearProgressIndicator(this);
        downloadProgress.setIndeterminate(true);
        downloadProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.setMargins(dp(16), 0, dp(16), 0);
        downloadProgress.setLayoutParams(pp);
        langBox.addView(downloadProgress);

        downloadProgressText = text("", 12, com.google.android.material.R.attr.colorOnSurfaceVariant);
        downloadProgressText.setPadding(dp(16), dp(6), dp(16), 0);
        downloadProgressText.setVisibility(View.GONE);
        langBox.addView(downloadProgressText);

        langBox.addView(divider());
        languageListContainer = new LinearLayout(this);
        languageListContainer.setOrientation(LinearLayout.VERTICAL);
        langBox.addView(languageListContainer);
        root.addView(langCard);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        refreshLanguageStatus();
        ensureFloatingRunning();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
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
    // Status izin
    // ----------------------------------------------------------------

    private void refreshPermissionStatus() {
        setRow(rowOverlay, Settings.canDrawOverlays(this), "Aktif", "Wajib", "Buka");

        boolean a11y = ScreenshotAccessibilityService.isAvailable();
        setRow(rowA11y, a11y, "Aktif", "Wajib untuk floating", "Buka");

        setRow(rowBattery, isBatteryUnrestricted(), "Aktif", "Masih dibatasi", "Izinkan");

        String assistant = null;
        try {
            assistant = Settings.Secure.getString(getContentResolver(), "assistant");
        } catch (Exception ignored) {}
        boolean isAssistant = assistant != null && assistant.contains(getPackageName());
        setRow(rowAssistant, isAssistant, "Aktif", "Belum diatur", "Atur");

        boolean floatingOn = getSharedPreferences(FloatingTriggerService.PREFS, MODE_PRIVATE)
                .getBoolean(FloatingTriggerService.KEY_ENABLED, false);
        suppressSwitch = true;
        floatingSwitch.setChecked(floatingOn);
        suppressSwitch = false;
    }

    private void setRow(Row row, boolean ok, String okText, String badText, String actionLabel) {
        row.status.setText(ok ? okText : badText);
        row.status.setTextColor(ok
                ? color(com.google.android.material.R.attr.colorTertiary)
                : color(com.google.android.material.R.attr.colorError));
        row.button.setText(actionLabel);
        row.button.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private boolean isBatteryUnrestricted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryUnrestricted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("Tidak diperlukan");
            return;
        }
        if (isBatteryUnrestricted()) {
            refreshPermissionStatus();
            return;
        }
        try {
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("Buka Pengaturan › Baterai");
            }
        }
    }

    private void setFloatingEnabled(boolean next) {
        SharedPreferences prefs = getSharedPreferences(FloatingTriggerService.PREFS, MODE_PRIVATE);
        Intent i = new Intent(this, FloatingTriggerService.class);
        if (next) {
            if (!Settings.canDrawOverlays(this)) {
                toast("Aktifkan izin overlay dulu");
                prefs.edit().putBoolean(FloatingTriggerService.KEY_ENABLED, false).apply();
                openOverlaySettings();
                refreshPermissionStatus();
                return;
            }
            prefs.edit().putBoolean(FloatingTriggerService.KEY_ENABLED, true).apply();
            i.setAction(FloatingTriggerService.ACTION_SHOW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } else {
            prefs.edit().putBoolean(FloatingTriggerService.KEY_ENABLED, false).apply();
            i.setAction(FloatingTriggerService.ACTION_HIDE);
            stopService(i);
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

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void openAssistantSettings() {
        Intent[] candidates = new Intent[]{
                new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                new Intent("android.settings.VOICE_INPUT_SETTINGS"),
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        };
        for (Intent intent : candidates) {
            try {
                startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }
        toast("Pengaturan › Aplikasi default › Asisten digital");
        openAppSettings();
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("Tidak bisa membuka pengaturan");
        }
    }

    // ----------------------------------------------------------------
    // Model bahasa
    // ----------------------------------------------------------------

    private void refreshLanguageStatus() {
        OcrTranslateHelper.getDownloadedLanguageCodes(new OcrTranslateHelper.ModelCallbackWithList() {
            @Override
            public void onSuccess(List<String> codes) {
                mainHandler.post(() -> {
                    downloadedCodes = new HashSet<>(codes);
                    boolean hasTarget = codes.contains(OcrTranslateHelper.TARGET_LANGUAGE);
                    languageSummary.setText(codes.size() + " terunduh"
                            + (hasTarget ? "" : " · Indonesia belum ada"));
                    rebuildLanguageList();
                });
            }

            @Override
            public void onFailure(Exception e) {
                mainHandler.post(() -> {
                    languageSummary.setText("Gagal memeriksa");
                    rebuildLanguageList();
                });
            }
        });
    }

    private void rebuildLanguageList() {
        languageListContainer.removeAllViews();
        boolean targetOk = downloadedCodes.contains(OcrTranslateHelper.TARGET_LANGUAGE);
        List<OcrTranslateHelper.LanguageInfo> list = OcrTranslateHelper.SUPPORTED_SOURCE_LANGUAGES;

        for (int idx = 0; idx < list.size(); idx++) {
            OcrTranslateHelper.LanguageInfo info = list.get(idx);
            boolean ready = downloadedCodes.contains(info.code) && targetOk;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(4), dp(8), dp(4));
            row.setMinimumHeight(dp(48));

            TextView dot = text(ready ? "●" : "○", 12, ready
                    ? com.google.android.material.R.attr.colorTertiary
                    : com.google.android.material.R.attr.colorOutline);
            dot.setPadding(0, 0, dp(12), 0);
            row.addView(dot);

            TextView name = text(info.displayName, 15,
                    com.google.android.material.R.attr.colorOnSurface);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);

            MaterialButton action = textButton(ready ? "Hapus" : "Unduh");
            if (ready) {
                action.setOnClickListener(v -> confirmDelete(info));
            } else {
                action.setEnabled(!isDownloading);
                action.setOnClickListener(v -> downloadOne(info));
            }
            row.addView(action);
            languageListContainer.addView(row);
        }
    }

    private void downloadOne(OcrTranslateHelper.LanguageInfo info) {
        if (isDownloading) return;
        setDownloading(true, info.displayName + "…");

        OcrTranslateHelper.downloadModel(info.code, new OcrTranslateHelper.ModelCallback() {
            @Override
            public void onSuccess() {
                mainHandler.post(() -> {
                    setDownloading(false, null);
                    toast(info.displayName + " siap");
                    refreshLanguageStatus();
                });
            }

            @Override
            public void onFailure(Exception e) {
                mainHandler.post(() -> {
                    setDownloading(false, null);
                    toast("Gagal: " + info.displayName);
                });
            }

            @Override
            public void onProgress(String message) {
                mainHandler.post(() -> downloadProgressText.setText(message));
            }
        });
    }

    private void downloadAllLanguages() {
        if (isDownloading) return;

        new MaterialAlertDialogBuilder(this)
                .setTitle("Unduh semua?")
                .setMessage("Ukuran bisa ratusan MB. Gunakan koneksi stabil.")
                .setPositiveButton("Unduh", (d, w) -> {
                    setDownloading(true, "Memulai…");
                    OcrTranslateHelper.downloadAllModels(new OcrTranslateHelper.ModelCallback() {
                        @Override
                        public void onSuccess() {
                            mainHandler.post(() -> {
                                setDownloading(false, null);
                                toast("Selesai");
                                refreshLanguageStatus();
                            });
                        }

                        @Override
                        public void onFailure(Exception e) {
                            mainHandler.post(() -> {
                                setDownloading(false, null);
                                toast("Sebagian gagal");
                                refreshLanguageStatus();
                            });
                        }

                        @Override
                        public void onProgress(String message) {
                            mainHandler.post(() -> downloadProgressText.setText(message));
                        }
                    });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void confirmDelete(OcrTranslateHelper.LanguageInfo info) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Hapus " + info.displayName + "?")
                .setMessage("Perlu diunduh ulang untuk dipakai lagi.")
                .setPositiveButton("Hapus", (d, w) ->
                        OcrTranslateHelper.deleteModel(info.code, new OcrTranslateHelper.ModelCallback() {
                            @Override
                            public void onSuccess() {
                                mainHandler.post(() -> {
                                    toast("Dihapus");
                                    refreshLanguageStatus();
                                });
                            }

                            @Override
                            public void onFailure(Exception e) {
                                mainHandler.post(() -> toast("Gagal menghapus"));
                            }
                        }))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void setDownloading(boolean downloading, String message) {
        isDownloading = downloading;
        downloadProgress.setVisibility(downloading ? View.VISIBLE : View.GONE);
        downloadProgressText.setVisibility(downloading ? View.VISIBLE : View.GONE);
        if (message != null) downloadProgressText.setText(message);
        downloadAllButton.setEnabled(!downloading);
        rebuildLanguageList();
    }

    // ----------------------------------------------------------------
    // Komponen UI
    // ----------------------------------------------------------------

    /** Satu baris izin: judul + status singkat di kiri, tombol kecil di kanan. */
    private class Row {
        final LinearLayout view;
        final TextView status;
        final MaterialButton button;

        Row(String label, String actionLabel) {
            view = new LinearLayout(MainActivity.this);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(16), dp(10), dp(8), dp(10));
            view.setMinimumHeight(dp(64));

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            texts.addView(text(label, 16, com.google.android.material.R.attr.colorOnSurface));
            status = text("", 13, com.google.android.material.R.attr.colorOnSurfaceVariant);
            texts.addView(status);
            view.addView(texts);

            button = tonalButton(actionLabel);
            view.addView(button);
        }
    }

    private View buildFloatingRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setMinimumHeight(dp(64));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(text("Tombol mengambang", 16,
                com.google.android.material.R.attr.colorOnSurface));
        texts.addView(text("Pil di tepi layar", 13,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        row.addView(texts);

        floatingSwitch = new MaterialSwitch(this);
        floatingSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!suppressSwitch) setFloatingEnabled(checked);
        });
        row.addView(floatingSwitch);
        return row;
    }

    private MaterialCardView card() {
        MaterialCardView c = new MaterialCardView(this);
        c.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainer));
        c.setRadius(dp(24));
        c.setCardElevation(0);
        c.setStrokeWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout cardBox(MaterialCardView card) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        card.addView(box);
        return box;
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(16), 0, dp(16), 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(withAlpha(
                color(com.google.android.material.R.attr.colorOutlineVariant), 0.6f));
        return v;
    }

    private TextView sectionLabel(String label) {
        TextView tv = text(label, 14, com.google.android.material.R.attr.colorPrimary);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(dp(8), dp(20), 0, dp(8));
        return tv;
    }

    private TextView text(String s, int sp, int colorAttr) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color(colorAttr));
        return tv;
    }

    private MaterialButton tonalButton(String label) {
        // Tidak ada atribut "tonal" di Material 1.12.0 — yang ada hanya style
        // Widget.Material3.Button.TonalButton, jadi dibungkus lewat theme wrapper.
        MaterialButton b = new MaterialButton(new ContextThemeWrapper(this,
                com.google.android.material.R.style.Widget_Material3_Button_TonalButton));
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        return b;
    }

    private MaterialButton textButton(String label) {
        MaterialButton b = new MaterialButton(new ContextThemeWrapper(this,
                com.google.android.material.R.style.Widget_Material3_Button_TextButton));
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        return b;
    }

    private int color(int attr) {
        return MaterialColors.getColor(this, attr, Color.MAGENTA);
    }

    private int withAlpha(int c, float a) {
        return Color.argb((int) (255 * a), Color.red(c), Color.green(c), Color.blue(c));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
