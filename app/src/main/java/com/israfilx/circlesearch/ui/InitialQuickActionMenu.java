package com.israfilx.circlesearch.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Menu awal, ditampilkan di tengah layar begitu overlay seleksi muncul
 * (sebelum user menggambar apapun). Menawarkan jalan pintas: cari atau
 * terjemahkan SELURUH layar langsung, tanpa perlu melingkari area
 * spesifik dulu — untuk kasus saat user butuh info dari satu layar
 * penuh (mis. membaca & menerjemahkan satu halaman chat/artikel).
 *
 * Menu ini otomatis disembunyikan begitu user mulai menggambar lasso
 * (lihat SelectionOverlayView) — seleksi spesifik tetap jadi cara utama,
 * ini cuma jalan pintas tambahan.
 */
public class InitialQuickActionMenu extends LinearLayout {

    public interface OnQuickActionListener {
        void onSearchFullScreen();
        void onTranslateFullScreen();
    }

    public InitialQuickActionMenu(Context context, OnQuickActionListener listener) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2FFFFFF"));
        bg.setCornerRadius(dp(24));
        setBackground(bg);

        int padH = (int) dp(20);
        int padV = (int) dp(16);
        setPadding(padH, padV, padH, padV);

        Button searchBtn = makeButton("🔍  Cari 1 layar");
        searchBtn.setOnClickListener(v -> {
            if (listener != null) listener.onSearchFullScreen();
        });
        addView(searchBtn);

        addView(spacer());

        Button translateBtn = makeButton("Translate 1 layar");
        translateBtn.setOnClickListener(v -> {
            if (listener != null) listener.onTranslateFullScreen();
        });
        addView(translateBtn);

        addView(spacer());

        android.widget.TextView hint = new android.widget.TextView(context);
        hint.setText("atau lingkari area tertentu di layar");
        hint.setTextSize(12);
        hint.setTextColor(Color.parseColor("#9AA0A6"));
        hint.setGravity(Gravity.CENTER);
        addView(hint);
    }

    private Button makeButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setPadding((int) dp(24), (int) dp(12), (int) dp(24), (int) dp(12));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#1A73E8"));
        btnBg.setCornerRadius(dp(24));
        b.setBackground(btnBg);

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private android.view.View spacer() {
        android.view.View v = new android.view.View(getContext());
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, (int) dp(8));
        v.setLayoutParams(lp);
        return v;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
