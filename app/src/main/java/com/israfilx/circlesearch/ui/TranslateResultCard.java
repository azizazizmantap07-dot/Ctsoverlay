package com.israfilx.circlesearch.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Kartu hasil OCR + Translate, ditampilkan sebagai overlay mengambang
 * menempel di dekat area seleksi — mirip kartu hasil "Translate" pada
 * Circle to Search asli. Menampilkan teks asli (hasil OCR) dan hasil
 * terjemahan, dengan tombol salin untuk masing-masing dan tombol tutup.
 */
public class TranslateResultCard extends LinearLayout {

    public interface OnCloseListener {
        void onClose();
    }

    private static final int MAX_WIDTH_DP = 280;
    private static final int MAX_HEIGHT_DP = 320;

    public TranslateResultCard(Context context, String originalText, String translatedText,
                                boolean wasTranslated, OnCloseListener closeListener) {
        super(context);
        setOrientation(VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FAFFFFFF"));
        bg.setCornerRadius(dp(20));
        setBackground(bg);

        int pad = (int) dp(16);
        setPadding(pad, pad, pad, pad);

        LayoutParams rootLp = new LayoutParams((int) dp(MAX_WIDTH_DP), LayoutParams.WRAP_CONTENT);
        setLayoutParams(rootLp);

        // Header: judul + tombol tutup
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(wasTranslated ? "Terjemahan" : "Teks terdeteksi");
        title.setTextSize(13);
        title.setTextColor(Color.parseColor("#5F6368"));
        LayoutParams titleLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        TextView closeBtn = new TextView(context);
        closeBtn.setText("✕");
        closeBtn.setTextSize(16);
        closeBtn.setTextColor(Color.parseColor("#5F6368"));
        closeBtn.setPadding((int) dp(8), (int) dp(4), (int) dp(4), (int) dp(4));
        closeBtn.setOnClickListener(v -> {
            if (closeListener != null) closeListener.onClose();
        });
        header.addView(closeBtn);

        addView(header);

        // Area scroll untuk teks (bisa panjang). ScrollView bawaan Android
        // tidak punya method setMaxHeight() — batasi tingginya lewat
        // LayoutParams langsung (bukan WRAP_CONTENT, tapi angka tetap
        // sebagai batas atas; konten pendek tetap terlihat wajar karena
        // ScrollView tidak memaksa penuh bila isinya lebih pendek).
        ScrollView scrollView = new ScrollView(context);
        LayoutParams scrollLp = new LayoutParams(LayoutParams.MATCH_PARENT, (int) dp(MAX_HEIGHT_DP));
        scrollView.setLayoutParams(scrollLp);

        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(VERTICAL);

        // Hasil utama (terjemahan bila ada, atau teks OCR apa adanya)
        TextView mainText = new TextView(context);
        mainText.setText(translatedText);
        mainText.setTextSize(16);
        mainText.setTextColor(Color.parseColor("#202124"));
        mainText.setPadding(0, (int) dp(4), 0, (int) dp(8));
        textContainer.addView(mainText);

        // Teks asli, ditampilkan lebih kecil sebagai referensi — hanya bila
        // benar-benar diterjemahkan (beda dari teks utama).
        if (wasTranslated && !originalText.equals(translatedText)) {
            TextView divider = new TextView(context);
            divider.setText("Teks asli:");
            divider.setTextSize(12);
            divider.setTextColor(Color.parseColor("#9AA0A6"));
            divider.setPadding(0, (int) dp(4), 0, (int) dp(2));
            textContainer.addView(divider);

            TextView originalTextView = new TextView(context);
            originalTextView.setText(originalText);
            originalTextView.setTextSize(14);
            originalTextView.setTextColor(Color.parseColor("#5F6368"));
            textContainer.addView(originalTextView);
        }

        scrollView.addView(textContainer);
        addView(scrollView);

        // Tombol salin
        Button copyBtn = new Button(context);
        copyBtn.setText("Salin");
        copyBtn.setAllCaps(false);
        copyBtn.setTextSize(14);
        copyBtn.setTextColor(Color.parseColor("#1A73E8"));
        GradientDrawable copyBg = new GradientDrawable();
        copyBg.setColor(Color.TRANSPARENT);
        copyBtn.setBackground(copyBg);
        copyBtn.setPadding(0, (int) dp(8), 0, 0);
        copyBtn.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Terjemahan", translatedText));
            }
        });
        addView(copyBtn);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
