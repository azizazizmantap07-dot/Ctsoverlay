package com.israfilx.circlesearch.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Menu aksi tunggal berupa dua ikon (kaca pembesar = cari visual, ikon
 * translate = OCR & terjemahkan), selalu ditempatkan menempel di bagian
 * PALING BAWAH layar — bukan di tengah atau menempel area seleksi —
 * supaya tidak pernah menghalangi pemandangan/konten yang sedang dilihat
 * user, baik dipakai untuk:
 *  - Mode "1 layar": tap salah satu ikon langsung memproses seluruh
 *    screenshot, tanpa perlu menyeleksi dulu.
 *  - Mode lasso: muncul (di posisi bawah yang sama) setelah user selesai
 *    melingkari area tertentu, aksinya berlaku untuk area yang dilingkari.
 *
 * Menggantikan InitialQuickActionMenu (dialog tengah layar) dan
 * SelectionActionMenu (menempel di bawah area crop) — kini keduanya
 * memakai menu ikon ringkas yang sama ini.
 */
public class BottomIconMenu extends LinearLayout {

    public interface OnActionListener {
        void onSearchVisual();
        void onTranslate();
    }

    public BottomIconMenu(Context context, OnActionListener listener) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#DD202124"));
        bg.setCornerRadius(dp(32));
        setBackground(bg);

        int padH = (int) dp(18);
        int padV = (int) dp(10);
        setPadding(padH, padV, padH, padV);
        setElevation(dp(8));

        TextView searchIcon = makeIconButton("🔍");
        searchIcon.setOnClickListener(v -> {
            if (listener != null) listener.onSearchVisual();
        });
        addView(searchIcon);

        addView(spacer());

        TextView translateIcon = makeIconButton("🌐");
        translateIcon.setOnClickListener(v -> {
            if (listener != null) listener.onTranslate();
        });
        addView(translateIcon);
    }

    private TextView makeIconButton(String glyph) {
        TextView tv = new TextView(getContext());
        tv.setText(glyph);
        tv.setTextSize(22);
        tv.setGravity(Gravity.CENTER);
        int size = (int) dp(44);
        LayoutParams lp = new LayoutParams(size, size);
        tv.setLayoutParams(lp);

        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(Color.parseColor("#33FFFFFF"));
        tv.setBackground(circleBg);

        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private android.view.View spacer() {
        android.view.View v = new android.view.View(getContext());
        LayoutParams lp = new LayoutParams((int) dp(20), 1);
        v.setLayoutParams(lp);
        return v;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
