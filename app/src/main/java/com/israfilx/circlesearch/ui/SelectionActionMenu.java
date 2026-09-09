package com.israfilx.circlesearch.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Menu aksi kecil mengambang, ditampilkan menempel di dekat area hasil
 * seleksi begitu user selesai menggambar lasso. Berisi dua tombol:
 * "Cari" (visual search via Google Lens) dan "OCR & Translate".
 */
public class SelectionActionMenu extends LinearLayout {

    public interface OnActionListener {
        void onSearchVisual();
        void onOcrTranslate();
    }

    public SelectionActionMenu(Context context, OnActionListener listener) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2FFFFFF"));
        bg.setCornerRadius(dp(28));
        setBackground(bg);

        int padH = (int) dp(8);
        int padV = (int) dp(6);
        setPadding(padH, padV, padH, padV);

        Button searchBtn = makeButton("🔍 Cari");
        searchBtn.setOnClickListener(v -> {
            if (listener != null) listener.onSearchVisual();
        });

        Button ocrBtn = makeButton("Aa Translate");
        ocrBtn.setOnClickListener(v -> {
            if (listener != null) listener.onOcrTranslate();
        });

        addView(searchBtn);
        addView(spacer());
        addView(ocrBtn);
    }

    private Button makeButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#1A73E8"));
        b.setPadding((int) dp(16), (int) dp(10), (int) dp(16), (int) dp(10));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.TRANSPARENT);
        b.setBackground(btnBg);
        return b;
    }

    private android.view.View spacer() {
        android.view.View v = new android.view.View(getContext());
        LayoutParams lp = new LayoutParams((int) dp(4), 1);
        v.setLayoutParams(lp);
        return v;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
