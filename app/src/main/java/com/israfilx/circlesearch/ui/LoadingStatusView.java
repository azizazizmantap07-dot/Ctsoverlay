package com.israfilx.circlesearch.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Pill kecil berisi spinner + label status ("Membaca teks…", "Mengunduh
 * bahasa…", dst), ditampilkan menempel tepat DI ATAS BottomIconMenu
 * selama proses OCR/translate berjalan di background.
 *
 * Tujuan utamanya adalah mengatasi kesan "overlay macet/stuck" yang
 * terjadi terutama pada unduhan model bahasa pertama kali (bisa makan
 * waktu beberapa detik tanpa umpan balik visual apapun sebelumnya) —
 * dengan indikator ini, user tahu proses masih berjalan dan bisa
 * menunggu atau membatalkan lewat tombol ✕ yang tetap bisa disentuh.
 */
public class LoadingStatusView extends LinearLayout {

    private final TextView label;
    private final ProgressBar spinner;

    public LoadingStatusView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#CC202124"));
        bg.setCornerRadius(dp(20));
        setBackground(bg);

        int padH = (int) dp(14);
        int padV = (int) dp(8);
        setPadding(padH, padV, padH, padV);

        spinner = new ProgressBar(context);
        spinner.setIndeterminate(true);
        int spinnerSize = (int) dp(16);
        LayoutParams spinnerLp = new LayoutParams(spinnerSize, spinnerSize);
        spinnerLp.setMarginEnd((int) dp(8));
        spinner.setLayoutParams(spinnerLp);
        addView(spinner);

        label = new TextView(context);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        addView(label);

        setAlpha(0f);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void setLabel(String text) {
        label.setText(text);
    }

    public void showAnimated(String text) {
        setLabel(text);
        animate().alpha(1f).setDuration(160).start();
    }

    public void hideAnimated(Runnable onEnd) {
        animate().alpha(0f).setDuration(140)
                .withEndAction(() -> {
                    if (onEnd != null) onEnd.run();
                })
                .start();
    }
}
