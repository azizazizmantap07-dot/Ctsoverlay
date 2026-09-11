# Circle Search Overlay

Aplikasi overlay Android yang meniru cara kerja Circle to Search Google —
seleksi bebas di layar untuk translate, OCR teks, dan visual search. Trigger
memakai mekanisme **Default Assistant App**, dipanggil lewat gesture
assist bawaan ROM (swipe sudut bawah / long-press tombol power).

**Mode dual (root + non-root):**
- **Root tersedia** → capture layar silent via `screencap` (tanpa dialog).
- **Non-root** → otomatis fallback ke **MediaProjection** (user mengizinkan
  Screen Capture sekali per trigger). Fitur OCR, translate, dan visual search
  tetap lengkap.

**Tidak terikat Google.** OCR dan translate berjalan sepenuhnya on-device
lewat ML Kit (tidak ada data yang dikirim ke server manapun saat OCR/
translate berlangsung).

**Visual search ("Cari")** punya dua mode (bisa diganti di halaman
pengaturan):

1. **Di dalam aplikasi (WebView)** — default. Gambar di-upload ke host
   sementara (Litterbox 1 jam / Catbox), lalu WebView membuka URL hasil
   reverse search langsung (Yandex / Bing / Google Lens / TinEye) —
   pola sama seperti AKS-Labs/CircleToSearch. Hasil muncul otomatis,
   tanpa file chooser.
2. **Aplikasi eksternal** — perilaku lama: share Intent ke Yandex → Bing →
   Google Lens → chooser sistem. Lihat `util/ImageSearchShareUtil.java`
   untuk urutan preferensi package.

Module Magisk/KernelSU di `module_root/` opsional (auto-set default assistant
saat boot). Tanpa root, user set manual lewat Settings → Default apps.

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
sendiri (misal untuk dibundel ke module root, atau upload ke tempat lain).

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

## Root installer module

Folder `module_root/` adalah module Magisk/KernelSU terpisah yang
mendaftarkan APK ini sebagai default Assistant App secara otomatis saat
boot (`settings put secure assistant` / `voice_interaction_service`).
Flash folder ini sebagai ZIP module setelah APK utama terpasang.

## Build lokal (opsional, jika punya Android SDK terinstall)

```
./gradlew assembleDebug
```

Output ada di `app/build/outputs/apk/debug/`.
