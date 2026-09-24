package com.israfilx.circlesearch.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.israfilx.circlesearch.R;

/**
 * Menu aksi di bagian bawah layar: Cari, Terjemah, Salin, Tutup.
 *
 * Gaya Material 3 minimalis — pill tonal, ikon vektor, label satu kata.
 * Ditampilkan lewat WindowManager dari Service (konteks tanpa tema
 * Activity), jadi warna dirujuk lewat R.color (bukan atribut tema).
 *
 * Perilaku tidak berubah: tombol ✕ selalu tersedia sebagai jalan keluar,
 * animasi masuk hanya diputar pada kemunculan pertama, dan
 * {@link #dismissAnimated(Runnable)} dipakai Service sebelum me-remove view.
 */
public class BottomIconMenu extends LinearLayout {

    public interface OnActionListener {
        void onSearchVisual();
        void onTranslate();
        /** Salin teks (hasil OCR, TANPA translate) dari area saat ini ke clipboard. */
        void onCopyText();
        void onClose();
    }

    private static final long FADE_OUT_DURATION_MS = 160L;

    private final int onSurface;
    private final int chipBg;

    public BottomIconMenu(Context context, OnActionListener listener) {
        this(context, listener, true);
    }

    /**
     * @param playEnterAnimation false saat instance dibuat ulang hanya untuk
     *                           naik ke z-order teratas (lihat
     *                           OverlayCaptureService#recreateBottomMenuOnTop),
     *                           supaya menu tidak "berkedip" turun-naik.
     */
    public BottomIconMenu(Context context, OnActionListener listener, boolean playEnterAnimation) {
        super(context);
        // Warna dari resource (values / values-night) — otomatis mengikuti mode sistem.
        onSurface = context.getColor(R.color.overlay_on_surface);
        chipBg = context.getColor(R.color.overlay_chip);

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(context.getColor(R.color.overlay_surface));
        bg.setCornerRadius(dp(32));
        setBackground(bg);

        setPadding((int) dp(10), (int) dp(8), (int) dp(10), (int) dp(8));
        setElevation(dp(6));

        addView(makeButton(R.drawable.ic_search, "Cari", v -> {
            if (listener != null) listener.onSearchVisual();
        }));
        addView(makeButton(R.drawable.ic_translate, "Terjemah", v -> {
            if (listener != null) listener.onTranslate();
        }));
        addView(makeButton(R.drawable.ic_copy, "Salin", v -> {
            if (listener != null) listener.onCopyText();
        }));
        addView(makeButton(R.drawable.ic_close, "Tutup", v -> {
            if (listener != null) listener.onClose();
        }));

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

    /** Satu tombol = lingkaran tonal berisi ikon + label kecil di bawahnya. */
    private LinearLayout makeButton(int iconRes, String label, OnClickListener click) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClickable(true);
        col.setFocusable(true);
        col.setLayoutParams(new LayoutParams((int) dp(64), LayoutParams.WRAP_CONTENT));
        col.setOnClickListener(click);

        ImageView icon = new ImageView(getContext());
        icon.setImageResource(iconRes);
        icon.setColorFilter(onSurface, PorterDuff.Mode.SRC_IN);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = (int) dp(10);
        icon.setPadding(pad, pad, pad, pad);
        int size = (int) dp(44);
        icon.setLayoutParams(new LayoutParams(size, size));

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(chipBg);
        icon.setBackground(new RippleDrawable(
                ColorStateList.valueOf(chipBg), circle, null));
        icon.setClickable(false);
        icon.setFocusable(false);
        col.addView(icon);

        TextView caption = new TextView(getContext());
        caption.setText(label);
        caption.setTextSize(11);
        caption.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        caption.setTextColor(onSurface);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, (int) dp(4), 0, 0);
        caption.setMaxLines(1);
        caption.setClickable(false);
        caption.setFocusable(false);
        col.addView(caption);

        return col;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /**
     * Fade-out singkat sebelum menu dilepas dari WindowManager.
     *
     * @param onEnd dipanggil setelah animasi selesai; pemanggil (Service)
     *              yang me-remove view ini dari WindowManager.
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
