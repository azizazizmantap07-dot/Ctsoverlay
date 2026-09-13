package com.israfilx.circlesearch.util;

import android.graphics.Rect;

/**
 * Representasi satu blok teks hasil OCR yang NETRAL terhadap library OCR
 * yang menghasilkannya (ML Kit Text Recognition ATAU Tesseract4Android).
 *
 * DIPERKENALKAN saat menambah recognizer Tesseract khusus Arab (ML Kit
 * Text Recognition tidak punya model on-device untuk skrip Arab sama
 * sekali). Sebelumnya seluruh pipeline deteksi bahasa/filter/translate di
 * {@link OcrTranslateHelper} bekerja langsung di atas
 * {@code com.google.mlkit.vision.text.Text.TextBlock}, yang merupakan
 * kelas final dari ML Kit — tidak bisa dibuat instance-nya untuk
 * membungkus hasil dari Tesseract. OcrBlock adalah lapisan tipis di
 * atasnya (teks + boundingBox) supaya kedua sumber OCR bisa lewat
 * pipeline yang sama tanpa duplikasi logika.
 */
public final class OcrBlock {
    private final String text;
    private final Rect boundingBox;

    public OcrBlock(String text, Rect boundingBox) {
        this.text = text;
        this.boundingBox = boundingBox;
    }

    public String getText() {
        return text;
    }

    public Rect getBoundingBox() {
        return boundingBox;
    }
}
