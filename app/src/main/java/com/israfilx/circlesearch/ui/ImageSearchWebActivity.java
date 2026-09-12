package com.israfilx.circlesearch.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.israfilx.circlesearch.util.ImageSearchUploader;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reverse image search in-app lewat WebView.
 *
 * Alur (mengikuti AKS-Labs/CircleToSearch):
 *  1. Terima content:// Uri gambar crop.
 *  2. Upload ke Litterbox (1 jam) / Catbox → dapat URL publik.
 *  3. Bangun URL mesin pencari (prioritas: Google Lens, lalu Bing).
 *  4. Load URL di WebView → hasil langsung tampil.
 *
 * Saat ditutup, activity di-remove dari task stack (finishAndRemoveTask)
 * supaya user kembali ke app yang tadi di-overlay, bukan ke MainActivity CTS.
 */
public class ImageSearchWebActivity extends Activity {

    private static final String TAG = "CircleSearch/WebSearch";

    public static final String EXTRA_IMAGE_URI = "image_uri";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView btnLens;
    private TextView btnBing;

    private String publicImageUrl;
    private Bitmap sourceBitmap;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String uriStr = getIntent() != null ? getIntent().getStringExtra(EXTRA_IMAGE_URI) : null;
        if (uriStr == null || uriStr.isEmpty()) {
            Toast.makeText(this, "Gambar tidak ditemukan", Toast.LENGTH_SHORT).show();
            closeAndReturn();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#14161C"));

        root.addView(buildToolbar());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        root.addView(progressBar);

        statusText = new TextView(this);
        statusText.setText("Mengunggah gambar…");
        statusText.setTextColor(Color.parseColor("#C8CBD8"));
        statusText.setTextSize(13);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(12), dp(16), dp(12), dp(8));
        root.addView(statusText);

        webView = new WebView(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        webView.setLayoutParams(webLp);
        webView.setVisibility(View.GONE);
        root.addView(webView);

        setContentView(root);
        setupWebView();

        final Uri contentUri = Uri.parse(uriStr);
        executor.execute(() -> {
            Bitmap bmp = decodeBitmap(contentUri);
            if (bmp == null) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    statusText.setText("Gagal membaca gambar");
                    Toast.makeText(this, "Gagal membaca gambar", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            sourceBitmap = bmp;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                statusText.setText("Mengunggah gambar (host tercepat)…");
            });

            String uploaded = ImageSearchUploader.uploadToImageHost(bmp);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (uploaded == null) {
                    statusText.setText("Gagal mengunggah gambar.\nPeriksa koneksi internet lalu coba lagi.");
                    Toast.makeText(this, "Upload gagal", Toast.LENGTH_LONG).show();
                    return;
                }
                publicImageUrl = uploaded;
                statusText.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                // Prioritas utama: Google Lens
                loadEngine("google");
            });
        });
    }

    private Bitmap decodeBitmap(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            Log.e(TAG, "decodeBitmap failed", e);
            return null;
        }
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(6), dp(8));
        bar.setBackgroundColor(Color.parseColor("#1D2029"));

        TextView btnClose = makeChip("✕  Tutup", true);
        btnClose.setOnClickListener(v -> closeAndReturn());
        bar.addView(btnClose);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(spacer);

        btnLens = makeChip("Google Lens", false);
        btnLens.setOnClickListener(v -> loadEngine("google"));
        bar.addView(btnLens);

        btnBing = makeChip("Bing", false);
        btnBing.setOnClickListener(v -> loadEngine("bing"));
        bar.addView(btnBing);

        return bar;
    }

    private TextView makeChip(String label, boolean isClose) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));
        tv.setTextColor(Color.parseColor(isClose ? "#FF8A8A" : "#C8CBD8"));
        tv.setBackgroundColor(Color.parseColor(isClose ? "#3A2228" : "#2A2E3C"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private void highlightEngine(String engine) {
        int activeBg = Color.parseColor("#3A5A40");
        int inactiveBg = Color.parseColor("#2A2E3C");
        int activeText = Color.parseColor("#A8E6A0");
        int inactiveText = Color.parseColor("#C8CBD8");

        btnLens.setBackgroundColor("google".equals(engine) ? activeBg : inactiveBg);
        btnLens.setTextColor("google".equals(engine) ? activeText : inactiveText);
        btnBing.setBackgroundColor("bing".equals(engine) ? activeBg : inactiveBg);
        btnBing.setTextColor("bing".equals(engine) ? activeText : inactiveText);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progressBar.setProgress(newProgress);
            }
        });
    }

    private void loadEngine(String engine) {
        if (publicImageUrl == null) {
            Toast.makeText(this, "Gambar belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        highlightEngine(engine);

        String url;
        if ("bing".equals(engine)) {
            url = ImageSearchUploader.getBingUrl(publicImageUrl);
        } else {
            // Default & prioritas utama: Google Lens
            url = ImageSearchUploader.getGoogleLensUrl(publicImageUrl);
        }

        Log.d(TAG, "Load search URL (" + engine + "): " + url);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        webView.loadUrl(url);
    }

    /**
     * Tutup activity dan buang dari task stack supaya user kembali ke
     * app yang sedang terbuka saat overlay dipicu — bukan ke MainActivity
     * Circle Search Overlay.
     */
    private void closeAndReturn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            closeAndReturn();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
            sourceBitmap.recycle();
            sourceBitmap = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
