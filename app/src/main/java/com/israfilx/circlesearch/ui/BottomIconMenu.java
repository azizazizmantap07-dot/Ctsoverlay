package com.israfilx.circlesearch.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Menu aksi berupa empat ikon (kaca pembesar = cari visual, ikon
 * translate = OCR & terjemahkan, ikon salin = OCR teks saja tanpa
 * translate, ikon ✕ = tutup overlay SELURUHNYA), selalu ditempatkan
 * menempel di bagian PALING BAWAH layar — bukan di tengah atau menempel
 * area seleksi — supaya tidak pernah menghalangi pemandangan/konten yang
 * sedang dilihat user, baik dipakai untuk:
 *  - Mode "1 layar": tap salah satu ikon langsung memproses seluruh
 *    screenshot, tanpa perlu menyeleksi dulu.
 *  - Mode lasso: muncul (di posisi bawah yang sama) setelah user selesai
 *    melingkari area tertentu, aksinya berlaku untuk area yang dilingkari.
 *
 * Setiap ikon dilengkapi label teks di bawahnya (cari / translate / salin /
 * tutup) agar fungsi masing-masing jelas tanpa mengandalkan tebakan glyph.
 *
 * Ikon ✕ SENGAJA ditambahkan sebagai jalan keluar eksplisit yang selalu
 * bisa ditekan, terlepas dari overlay lain apa yang sedang tampil di
 * atasnya (mis. TranslationOverlayView). Sebelumnya satu-satunya cara
 * menutup overlay terjemahan adalah tap di area "kosong" — kalau tap itu
 * jatuh di view lain yang ikut menyerap sentuhan (mis. area BottomIconMenu
 * sendiri di luar kedua ikon lama), tidak ada listener yang menutup
 * overlay dan window overlay bisa menempel permanen sampai proses
 * dihentikan paksa (force-stop).
 *
 * Menggantikan InitialQuickActionMenu (dialog tengah layar) dan
 * SelectionActionMenu (menempel di bawah area crop) — kini keduanya
 * memakai menu ikon ringkas yang sama ini.
 */
public class BottomIconMenu extends LinearLayout {

    public interface OnActionListener {
        void onSearchVisual();
        void onTranslate();
        /** Salin teks (hasil OCR, TANPA translate) dari area saat ini ke clipboard. */
        void onCopyText();
        void onClose();
    }

    public BottomIconMenu(Context context, OnActionListener listener) {
        this(context, listener, true);
    }

    /**
     * @param playEnterAnimation false saat instance ini dibuat ulang hanya
     *                           untuk naik ke z-order teratas (lihat
     *                           OverlayCaptureService#recreateBottomMenuOnTop),
     *                           bukan kemunculan pertama menu. Sebelumnya
     *                           animasi fade+slide-up ini replay setiap kali
     *                           translate dijalankan ulang, membuat menu
     *                           terlihat "berkedip turun-naik" alih-alih
     *                           diam di tempat.
     */
    public BottomIconMenu(Context context, OnActionListener listener, boolean playEnterAnimation) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#DD202124"));
        // Radius dibuat sama dengan setengah tinggi pill secara efektif
        // (dihitung di onSizeChanged-independent constant besar) supaya
        // ujung menu selalu tampak benar-benar membulat penuh (pill
        // shape), bukan sekadar "kurang tajam" di sudut.
        bg.setCornerRadius(dp(40));
        setBackground(bg);

        int padH = (int) dp(14);
        int padV = (int) dp(8);
        setPadding(padH, padV, padH, padV);
        setElevation(dp(8));

        LinearLayout searchBtn = makeLabeledButton("🔍", "cari");
        searchBtn.setOnClickListener(v -> {
            if (listener != null) listener.onSearchVisual();
        });
        addView(searchBtn);

        addView(spacer());

        LinearLayout translateBtn = makeLabeledButton("🌐", "translate");
        translateBtn.setOnClickListener(v -> {
            if (listener != null) listener.onTranslate();
        });
        addView(translateBtn);

        addView(spacer());

        // Salin teks (OCR murni, tanpa translate) — berguna saat user
        // hanya ingin menyalin teks yang ada pada gambar apa adanya.
        LinearLayout copyBtn = makeLabeledButton("📋", "salin");
        copyBtn.setOnClickListener(v -> {
            if (listener != null) listener.onCopyText();
        });
        addView(copyBtn);

        addView(spacer());

        // Tombol tutup eksplisit — selalu ada, selalu berfungsi, tidak
        // bergantung pada tap-di-luar-area yang bisa nyasar ke view lain.
        LinearLayout closeBtn = makeLabeledButton("✕", "tutup");
        closeBtn.setOnClickListener(v -> {
            if (listener != null) listener.onClose();
        });
        addView(closeBtn);

        // Animasi masuk halus (fade + slide-up sedikit) supaya kemunculan
        // menu tidak terasa "muncul tiba-tiba" (snap) — hanya diputar pada
        // kemunculan pertama menu, bukan setiap kali instance dibuat ulang
        // untuk naik ke z-order teratas.
        if (playEnterAnimation) {
            setAlpha(0f);
            setTranslationY(dp(24));
            animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /**
     * Satu tombol = ikon di atas + label teks kecil di bawah, dalam
     * LinearLayout vertikal yang bisa diklik utuh.
     */
    private LinearLayout makeLabeledButton(String glyph, String label) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClickable(true);
        col.setFocusable(true);

        // Area sentuh minimal ~48dp lebar supaya nyaman ditekan
        int minW = (int) dp(52);
        LayoutParams colLp = new LayoutParams(minW, LayoutParams.WRAP_CONTENT);
        col.setLayoutParams(colLp);

        // Ikon bulat
        TextView icon = new TextView(getContext());
        icon.setText(glyph);
        icon.setTextSize(20);
        icon.setGravity(Gravity.CENTER);
        int iconSize = (int) dp(40);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        icon.setLayoutParams(iconLp);

        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(Color.parseColor("#33FFFFFF"));
        icon.setBackground(circleBg);
        // Jangan biarkan TextView ikon menyerap klik sendiri — biarkan parent
        icon.setClickable(false);
        icon.setFocusable(false);

        col.addView(icon);

        // Label di bawah ikon
        TextView caption = new TextView(getContext());
        caption.setText(label);
        caption.setTextSize(10);
        caption.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        caption.setTextColor(Color.parseColor("#E8EAF0"));
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, (int) dp(3), 0, 0);
        caption.setMaxLines(1);
        caption.setClickable(false);
        caption.setFocusable(false);
        col.addView(caption);

        return col;
    }

    private android.view.View spacer() {
        android.view.View v = new android.view.View(getContext());
        LayoutParams lp = new LayoutParams((int) dp(10), 1);
        v.setLayoutParams(lp);
        return v;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final long FADE_OUT_DURATION_MS = 160L;

    /**
     * Fade-out singkat (kebalikan animasi masuk di constructor) sebelum
     * menu ini dilepas dari WindowManager — sebelumnya menu langsung
     * hilang seketika (snap) saat overlay ditutup, tidak simetris dengan
     * animasi kemunculannya.
     *
     * @param onEnd dipanggil setelah animasi selesai; pemanggil (Service)
     *              yang bertanggung jawab me-remove view ini dari
     *              WindowManager di sana.
     */
    public void dismissAnimated(Runnable onEnd) {
        animate()
                .alpha(0f)
                .translationY(dp(24))
                .setDuration(FADE_OUT_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (onEnd != null) onEnd.run();
                    }
                })
                .start();
    }
}
