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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Upload bitmap ke host sementara (Litterbox / Catbox) secara paralel —
 * host mana yang lebih dulu sukses dipakai. Gambar di-resize + JPEG agar
 * payload kecil dan upload lebih cepat.
 */
public final class ImageSearchUploader {

    private static final String TAG = "CircleSearch/Uploader";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** Connect cepat gagal → host lain bisa menang. */
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 18_000;
    /** Batas total race (kedua host). */
    private static final int RACE_TIMEOUT_MS = 22_000;

    /** Sisi terpanjang — cukup untuk reverse search, file jauh lebih kecil. */
    private static final int MAX_SIDE_PX = 960;
    private static final int JPEG_QUALITY = 72;

    private ImageSearchUploader() {}

    /**
     * Compress sekali, lalu race Litterbox + Catbox. Return URL publik
     * host yang lebih dulu sukses, atau null jika keduanya gagal.
     */
    public static String uploadToImageHost(Bitmap bitmap) {
        byte[] imageBytes = compress(bitmap);
        if (imageBytes == null || imageBytes.length == 0) {
            Log.e(TAG, "Compress gagal / bitmap kosong");
            return null;
        }
        Log.d(TAG, "Payload JPEG " + imageBytes.length + " bytes (maxSide=" + MAX_SIDE_PX
                + ", q=" + JPEG_QUALITY + ")");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> litter = pool.submit(() -> uploadToLitterbox(imageBytes));
            Future<String> catbox = pool.submit(() -> uploadToCatbox(imageBytes));

            long deadline = System.nanoTime() + RACE_TIMEOUT_MS * 1_000_000L;
            Future<String>[] futures = new Future[]{litter, catbox};
            boolean[] done = new boolean[2];
            int remaining = 2;

            while (remaining > 0) {
                long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMs <= 0) break;

                for (int i = 0; i < futures.length; i++) {
                    if (done[i]) continue;
                    Future<String> f = futures[i];
                    if (!f.isDone()) continue;
                    done[i] = true;
                    remaining--;
                    try {
                        String url = f.get();
                        if (url != null && url.startsWith("http")) {
                            // Batalkan yang belum selesai
                            for (Future<String> other : futures) {
                                if (other != f) other.cancel(true);
                            }
                            Log.d(TAG, "Upload menang: " + url);
                            return url.trim();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Host race task gagal", e);
                    }
                }

                // Poll singkat
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Fallback: tunggu sedikit sisa yang belum done
            for (int i = 0; i < futures.length; i++) {
                if (done[i]) continue;
                try {
                    long leftMs = Math.max(200, (deadline - System.nanoTime()) / 1_000_000L);
                    String url = futures[i].get(leftMs, TimeUnit.MILLISECONDS);
                    if (url != null && url.startsWith("http")) {
                        Log.d(TAG, "Upload (fallback wait): " + url);
                        return url.trim();
                    }
                } catch (TimeoutException | ExecutionException | InterruptedException e) {
                    futures[i].cancel(true);
                }
            }

            Log.e(TAG, "Semua host upload gagal / timeout");
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // URL builders
    // ------------------------------------------------------------------

    public static String getBingUrl(String imageUrl) {
        return "https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:" + enc(imageUrl);
    }

    public static String getGoogleLensUrl(String imageUrl) {
        return "https://lens.google.com/uploadbyurl?url=" + enc(imageUrl);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------
    // Upload implementations (byte[] sudah di-compress)
    // ------------------------------------------------------------------

    private static String uploadToLitterbox(byte[] imageBytes) {
        try {
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
            HttpURLConnection conn = openPost(
                    "https://litterbox.catbox.moe/resources/internals/api.php", boundary);

            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            writeField(dos, boundary, "reqtype", "fileupload");
            writeField(dos, boundary, "time", "1h");
            writeFile(dos, boundary, "fileToUpload", "image.jpg", "image/jpeg", imageBytes);
            dos.writeBytes("--" + boundary + "--\r\n");
            dos.flush();
            dos.close();

            String body = readBodyIfOk(conn);
            if (body != null) Log.d(TAG, "Litterbox OK");
            return body;
        } catch (Exception e) {
            Log.e(TAG, "Litterbox upload error", e);
            return null;
        }
    }

    private static String uploadToCatbox(byte[] imageBytes) {
        try {
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
            HttpURLConnection conn = openPost("https://catbox.moe/user/api.php", boundary);

            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            writeField(dos, boundary, "reqtype", "fileupload");
            writeFile(dos, boundary, "fileToUpload", "image.jpg", "image/jpeg", imageBytes);
            dos.writeBytes("--" + boundary + "--\r\n");
            dos.flush();
            dos.close();

            String body = readBodyIfOk(conn);
            if (body != null) Log.d(TAG, "Catbox OK");
            return body;
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
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        // Hint ukuran agar server bisa memproses lebih awal (best-effort)
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
            byte[] buf = new byte[8192];
            int n;
            while ((n = conn.getInputStream().read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        }
        Log.e(TAG, "Upload HTTP " + code + " (" + conn.getURL() + ")");
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
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
            if (bmp != src) bmp.recycle();
            return null;
        }
        if (bmp != src) bmp.recycle();
        return out.toByteArray();
    }
}
