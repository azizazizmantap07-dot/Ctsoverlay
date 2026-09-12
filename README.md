# Circle Search Overlay

Aplikasi overlay Android yang meniru cara kerja **Circle to Search** dari
Google: lingkari atau seleksi bebas apa pun yang tampil di layar — lalu
langsung **terjemahkan teksnya**, **salin hasil OCR**, atau **cari gambarnya**
di mesin pencari — tanpa perlu keluar dari aplikasi yang sedang dibuka.

Tidak butuh root, dan OCR + terjemahan berjalan **sepenuhnya on-device**
lewat Google ML Kit — teks di layar kamu tidak dikirim ke server mana pun
saat proses OCR/translate berlangsung.

## Cara kerja

### 1. Memicu overlay
Ada dua cara memanggil overlay ini, keduanya tanpa root:

- **Sebagai Asisten Digital (default)** — aplikasi didaftarkan sebagai
  `VoiceInteractionService`, sehingga muncul di daftar **Settings → Apps →
  Default apps → Digital assistant app**. Setelah diset sebagai default,
  overlay bisa dipanggil lewat gesture assist bawaan ROM (biasanya swipe
  dari sudut bawah layar, atau long-press tombol power, tergantung
  perangkat). Screenshot layar diambil secara *silent* lewat Assist API
  bawaan sistem — tanpa dialog konfirmasi.
- **Floating Trigger Button** — sebuah "pil" kecil yang mengambang di tepi
  layar (lewat `AccessibilityService`), bisa ditekan kapan saja untuk
  memicu capture layar tanpa harus setting default assistant sama sekali.

### 2. Menyeleksi area
Setelah layar ter-capture, muncul layer seleksi transparan di atasnya. Kamu
bisa melingkari (lasso) area tertentu secara bebas, atau langsung memilih
aksi untuk seluruh layar tanpa menyeleksi apa pun dulu.

### 3. Memilih aksi
Menu berisi empat ikon muncul di bagian bawah layar:

| Ikon | Aksi |
|---|---|
| 🔍 Cari | Visual/reverse image search atas area yang dipilih |
| 🌐 Translate | OCR teks pada area, lalu terjemahkan otomatis |
| 📋 Salin | OCR teks pada area, salin ke clipboard (tanpa terjemahan) |
| ✕ Tutup | Menutup overlay sepenuhnya |

**Translate & OCR** — Teks dideteksi dengan ML Kit Text Recognition, bahasa
sumbernya dikenali otomatis lewat ML Kit Language Identification dari
sekitar 20 bahasa yang didukung (Inggris, Mandarin, Jepang, Korea, Arab,
dll — lihat daftar lengkap di kode `OcrTranslateHelper`), lalu diterjemahkan
ke **Bahasa Indonesia** memakai ML Kit Translate. Hasil terjemahan
ditampilkan menimpa posisi teks aslinya di layar. Model bahasa diunduh
sekali per pasangan bahasa (butuh internet saat unduhan pertama); setelah
itu translate berjalan offline.

**Cari (visual search)** — gambar hasil crop area yang dipilih di-upload ke
hosting gambar sementara (Litterbox, kedaluwarsa 1 jam, dengan fallback ke
Catbox), lalu URL publiknya dipakai untuk membuka pencarian gambar di
**Google Lens** atau **Bing Visual Search** lewat WebView di dalam aplikasi
sendiri — tidak membuka browser eksternal maupun app lain.

## Struktur teknis singkat

- **Bahasa & platform:** Java, Android (`minSdk` 26 / Android 8.0, `targetSdk`
  34 / Android 14), dibangun dengan Gradle 8.7 + Android Gradle Plugin 8.5.0.
- **Komponen utama:**
  - `MyVoiceInteractionService` + `AssistTriggerActivity` — pintu masuk
    sebagai default Digital Assistant.
  - `OverlayCaptureService` — foreground service yang mengelola capture
    layar dan seluruh siklus overlay seleksi.
  - `FloatingTriggerService` — mengelola tombol pil mengambang sebagai
    trigger alternatif.
  - `ScreenshotAccessibilityService` — dipakai Floating Button untuk
    mengambil screenshot lewat Accessibility API.
  - `SelectionOverlayView` / `TranslationOverlayView` / `BottomIconMenu` —
    UI overlay untuk seleksi area, tampilan hasil translate, dan menu aksi.
  - `OcrTranslateHelper` — pembungkus ML Kit untuk OCR, deteksi bahasa, dan
    terjemahan.
  - `ImageSearchUploader` + `ImageSearchWebActivity` — upload gambar dan
    tampilan hasil pencarian visual dalam WebView.
- **Library pihak ketiga:** Google ML Kit (`text-recognition`,
  `language-id`, `translate`) — semua berjalan on-device.
- **Izin yang diminta:** overlay layar (`SYSTEM_ALERT_WINDOW`),
  accessibility (untuk Floating Button), voice interaction (untuk mode
  Asisten Digital), foreground service, serta internet (khusus untuk
  mengunduh model bahasa ML Kit dan meng-upload gambar saat fitur "Cari"
  dipakai).

## Build via GitHub Actions

Repo ini sudah dilengkapi Gradle Wrapper penuh, jadi tidak perlu setup SDK
manual — cukup push ke GitHub dan Actions akan build otomatis.

### APK Debug (otomatis, tiap push)
1. Push repo ini ke GitHub.
2. Buka tab **Actions** → workflow **Build APK** akan jalan otomatis pada
   push ke branch `main`/`master`, atau jalankan manual lewat
   **Run workflow**.
3. Setelah selesai, unduh APK dari bagian **Artifacts** pada run tersebut
   (nama artifact: `CircleSearchOverlay-debug-apk`).

APK debug ini ditandatangani otomatis dengan debug keystore bawaan Gradle —
cukup untuk instal & testing langsung di device, tidak perlu setup
tambahan.

### APK Release (signed, manual, opsional)
Hanya perlu jika ingin distribusi APK yang ditandatangani dengan keystore
sendiri (misal upload ke tempat lain).

1. Siapkan keystore (`.jks`), atau generate baru:
   ```
   keytool -genkeypair -v -keystore release.keystore.jks \
     -alias circlesearch -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Di GitHub: **Settings → Secrets and variables → Actions → New repository
   secret**, tambahkan:
   - `ANDROID_KEYSTORE_BASE64` — hasil `base64 -w0 release.keystore.jks`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
3. Buka tab **Actions** → workflow **Build Signed Release APK** →
   **Run workflow** (manual trigger saja, tidak jalan otomatis tiap push).
4. Unduh APK dari **Artifacts** (nama artifact:
   `CircleSearchOverlay-release-apk`).

**Jangan pernah commit file keystore (`.jks`) ke repo** — sudah masuk
`.gitignore`, tapi tetap perhatikan saat menambah file baru.

## Build lokal (opsional, jika punya Android SDK terinstall)

```
./gradlew assembleDebug
```

Output ada di `app/build/outputs/apk/debug/`.

## Privasi

- OCR dan terjemahan teks berjalan sepenuhnya di perangkat (ML Kit
  on-device) — tidak ada teks layar yang dikirim ke server mana pun untuk
  fitur ini.
- Fitur **Cari** adalah pengecualian yang disengaja: karena visual/reverse
  image search butuh URL gambar publik, potongan gambar hasil seleksi
  di-upload ke host sementara (Litterbox/Catbox, kedaluwarsa dalam
  hitungan jam) agar bisa dipakai oleh Google Lens / Bing. Fitur ini hanya
  aktif saat kamu menekan ikon "Cari" secara eksplisit.
- Aplikasi tidak terhubung ke akun Google mana pun dan tidak memerlukan
  login.
