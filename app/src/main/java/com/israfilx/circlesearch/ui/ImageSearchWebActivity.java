package com.israfilx.circlesearch.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Menampilkan hasil reverse image search di dalam WebView milik app sendiri
 * supaya user tidak merasa berpindah ke aplikasi lain.
 *
 * Alur:
 *  1. OverlayCaptureService menyimpan bitmap crop ke cache + kirim content://
 *     Uri lewat Intent.
 *  2. Activity ini memuat halaman reverse-image-search (default Yandex).
 *  3. Saat halaman meminta upload file (onShowFileChooser), Uri gambar
 *     langsung di-inject — user tidak perlu memilih file manual.
 *  4. Toolbar atas memungkinkan ganti mesin pencari (Yandex / Bing / Google)
 *     tanpa keluar dari activity.
 */
public class ImageSearchWebActivity extends Activity {

    private static final String TAG = "CircleSearch/WebSearch";

    public static final String EXTRA_IMAGE_URI = "image_uri";

    // URL entry point yang relatif ramah untuk reverse image search via WebView.
    private static final String URL_YANDEX = "https://yandex.com/images/";
    private static final String URL_BING   = "https://www.bing.com/visualsearch";
    private static final String URL_GOOGLE = "https://images.google.com/";

    private WebView webView;
    private ProgressBar progressBar;
    private Uri pendingImageUri;
    private ValueCallback<Uri[]> filePathCallback;
    private TextView btnYandex;
    private TextView btnBing;
    private TextView btnGoogle;
    private String currentEngine = "yandex";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String uriStr = getIntent() != null ? getIntent().getStringExtra(EXTRA_IMAGE_URI) : null;
        if (uriStr == null || uriStr.isEmpty()) {
            Toast.makeText(this, "Gambar tidak ditemukan", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        pendingImageUri = Uri.parse(uriStr);

        // Layout root: toolbar + progress + WebView
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

        webView = new WebView(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        webView.setLayoutParams(webLp);
        root.addView(webView);

        setContentView(root);

        setupWebView();
        loadEngine("yandex");
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(8), dp(10));
        bar.setBackgroundColor(Color.parseColor("#1D2029"));

        // Tombol tutup
        TextView btnClose = makeChip("✕  Tutup", true);
        btnClose.setOnClickListener(v -> finish());
        bar.addView(btnClose);

        // Spacer
        View spacer = new View(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
        spacer.setLayoutParams(sp);
        bar.addView(spacer);

        btnYandex = makeChip("Yandex", false);
        btnYandex.setOnClickListener(v -> loadEngine("yandex"));
        bar.addView(btnYandex);

        btnBing = makeChip("Bing", false);
        btnBing.setOnClickListener(v -> loadEngine("bing"));
        bar.addView(btnBing);

        btnGoogle = makeChip("Google", false);
        btnGoogle.setOnClickListener(v -> loadEngine("google"));
        bar.addView(btnGoogle);

        return bar;
    }

    private TextView makeChip(String label, boolean isClose) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setTextColor(Color.parseColor(isClose ? "#FF8A8A" : "#C8CBD8"));
        tv.setBackgroundColor(Color.parseColor(isClose ? "#3A2228" : "#2A2E3C"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private void highlightEngine(String engine) {
        currentEngine = engine;
        int activeBg = Color.parseColor("#3A5A40");
        int inactiveBg = Color.parseColor("#2A2E3C");
        int activeText = Color.parseColor("#A8E6A0");
        int inactiveText = Color.parseColor("#C8CBD8");

        btnYandex.setBackgroundColor("yandex".equals(engine) ? activeBg : inactiveBg);
        btnYandex.setTextColor("yandex".equals(engine) ? activeText : inactiveText);

        btnBing.setBackgroundColor("bing".equals(engine) ? activeBg : inactiveBg);
        btnBing.setTextColor("bing".equals(engine) ? activeText : inactiveText);

        btnGoogle.setBackgroundColor("google".equals(engine) ? activeBg : inactiveBg);
        btnGoogle.setTextColor("google".equals(engine) ? activeText : inactiveText);
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
        // User-Agent mirip Chrome mobile — beberapa situs reverse-search
        // lebih kooperatif dibanding UA WebView bawaan.
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Biarkan semua navigasi di dalam WebView (jangan lempar ke browser eksternal)
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

            /**
             * Saat halaman reverse-search meminta file upload, langsung
             * berikan Uri gambar yang sudah di-crop. Ini kunci supaya
             * terasa "satu ketuk" tanpa dialog file picker sistem.
             */
            @Override
            public boolean onShowFileChooser(WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {

                // Batalkan callback sebelumnya jika ada
                if (ImageSearchWebActivity.this.filePathCallback != null) {
                    ImageSearchWebActivity.this.filePathCallback.onReceiveValue(null);
                }
                ImageSearchWebActivity.this.filePathCallback = filePathCallback;

                if (pendingImageUri != null) {
                    Log.d(TAG, "Inject image Uri ke file chooser: " + pendingImageUri);
                    filePathCallback.onReceiveValue(new Uri[]{ pendingImageUri });
                    ImageSearchWebActivity.this.filePathCallback = null;
                    return true;
                }

                filePathCallback.onReceiveValue(null);
                ImageSearchWebActivity.this.filePathCallback = null;
                return true;
            }
        });
    }

    private void loadEngine(String engine) {
        highlightEngine(engine);
        String url;
        switch (engine) {
            case "bing":
                url = URL_BING;
                break;
            case "google":
                url = URL_GOOGLE;
                break;
            case "yandex":
            default:
                url = URL_YANDEX;
                break;
        }
        Log.d(TAG, "Load reverse image search: " + url);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        webView.loadUrl(url);

        // Petunjuk singkat sekali per engine (toast)
        String hint;
        switch (engine) {
            case "bing":
                hint = "Ketuk ikon kamera / \"Search using an image\" lalu gambar akan otomatis diunggah";
                break;
            case "google":
                hint = "Ketuk ikon kamera di bilah pencarian, lalu gambar akan otomatis diunggah";
                break;
            default:
                hint = "Ketuk ikon kamera di bilah pencarian Yandex, lalu gambar akan otomatis diunggah";
                break;
        }
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
