package com.israfilx.circlesearch.util;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Upload bitmap ke host sementara (Litterbox 1 jam, fallback Catbox),
 * lalu bangun URL reverse-image-search yang langsung menampilkan hasil.
 *
 * Pola sama seperti AKS-Labs/CircleToSearch: mesin pencari menerima
 * parameter {@code url=} / {@code image_url=} yang menunjuk ke gambar
 * publik, sehingga WebView tidak perlu file-chooser manual.
 */
public final class ImageSearchUploader {

    private static final String TAG = "CircleSearch/Uploader";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int TIMEOUT_MS = 30_000;
    private static final int MAX_SIDE_PX = 1280;
    private static final int JPEG_QUALITY = 90;

    private ImageSearchUploader() {}

    /**
     * Upload ke Litterbox (kadaluarsa 1 jam). Jika gagal, coba Catbox.
     * @return URL publik gambar, atau null jika keduanya gagal
     */
    public static String uploadToImageHost(Bitmap bitmap) {
        String url = uploadToLitterbox(bitmap);
        if (url != null && url.startsWith("http")) {
            Log.d(TAG, "Uploaded to Litterbox (1h): " + url);
            return url.trim();
        }
        Log.w(TAG, "Litterbox failed, trying Catbox…");
        url = uploadToCatbox(bitmap);
        if (url != null && url.startsWith("http")) {
            Log.d(TAG, "Uploaded to Catbox: " + url);
            return url.trim();
        }
        Log.e(TAG, "Both Litterbox and Catbox uploads failed");
        return null;
    }

    // ------------------------------------------------------------------
    // URL builders (langsung ke halaman hasil reverse search)
    // ------------------------------------------------------------------

    public static String getYandexUrl(String imageUrl) {
        return "https://yandex.com/images/search?rpt=imageview&url=" + enc(imageUrl);
    }

    public static String getBingUrl(String imageUrl) {
        return "https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:" + enc(imageUrl);
    }

    public static String getGoogleLensUrl(String imageUrl) {
        return "https://lens.google.com/uploadbyurl?url=" + enc(imageUrl);
    }

    public static String getTinEyeUrl(String imageUrl) {
        return "https://tineye.com/search?url=" + enc(imageUrl);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------
    // Upload implementations
    // ------------------------------------------------------------------

    private static String uploadToLitterbox(Bitmap bitmap) {
        try {
            byte[] imageBytes = compress(bitmap);
            if (imageBytes == null) return null;

            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
            HttpURLConnection conn = openPost("https://litterbox.catbox.moe/resources/internals/api.php", boundary);

            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            writeField(dos, boundary, "reqtype", "fileupload");
            writeField(dos, boundary, "time", "1h");
            writeFile(dos, boundary, "fileToUpload", "image.jpg", "image/jpeg", imageBytes);
            dos.writeBytes("--" + boundary + "--\r\n");
            dos.flush();
            dos.close();

            return readBodyIfOk(conn);
        } catch (Exception e) {
            Log.e(TAG, "Litterbox upload error", e);
            return null;
        }
    }

    private static String uploadToCatbox(Bitmap bitmap) {
        try {
            byte[] imageBytes = compress(bitmap);
            if (imageBytes == null) return null;

            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
            HttpURLConnection conn = openPost("https://catbox.moe/user/api.php", boundary);

            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            writeField(dos, boundary, "reqtype", "fileupload");
            writeFile(dos, boundary, "fileToUpload", "image.jpg", "image/jpeg", imageBytes);
            dos.writeBytes("--" + boundary + "--\r\n");
            dos.flush();
            dos.close();

            return readBodyIfOk(conn);
        } catch (Exception e) {
            Log.e(TAG, "Catbox upload error", e);
            return null;
        }
    }

    private static HttpURLConnection openPost(String urlStr, String boundary) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setUseCaches(false);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        return conn;
    }

    private static void writeField(DataOutputStream dos, String boundary, String name, String value)
            throws IOException {
        dos.writeBytes("--" + boundary + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        dos.writeBytes(value + "\r\n");
    }

    private static void writeFile(DataOutputStream dos, String boundary, String name,
                                  String filename, String mime, byte[] data) throws IOException {
        dos.writeBytes("--" + boundary + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\n");
        dos.writeBytes("Content-Type: " + mime + "\r\n\r\n");
        dos.write(data);
        dos.writeBytes("\r\n");
    }

    private static String readBodyIfOk(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code == 200) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = conn.getInputStream().read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        }
        Log.e(TAG, "Upload HTTP " + code);
        return null;
    }

    /** Resize (max side MAX_SIDE_PX) + JPEG compress. */
    private static byte[] compress(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        Bitmap bmp = src;
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max > MAX_SIDE_PX) {
            float scale = (float) MAX_SIDE_PX / max;
            bmp = Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
            return null;
        }
        if (bmp != src) bmp.recycle();
        return out.toByteArray();
    }
}
