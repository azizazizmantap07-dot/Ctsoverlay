# Circle Search Overlay

Aplikasi overlay Android yang meniru cara kerja Circle to Search Google —
seleksi bebas di layar untuk translate, OCR teks, dan visual search. Trigger
memakai mekanisme **Default Assistant App**, dipanggil lewat gesture
assist bawaan ROM (swipe sudut bawah / long-press tombol power), atau lewat
**Floating Trigger Button**.

**Mode trigger (tanpa root):**
- **Asisten Digital** — capture layar silent lewat Assist API (screenshot
  sistem, tanpa dialog).
- **Floating Button** — tombol mengambang di tepi layar; capture lewat
  AccessibilityService.takeScreenshot.

**Tidak terikat Google.** OCR dan translate berjalan sepenuhnya on-device
lewat ML Kit (tidak ada data yang dikirim ke server manapun saat OCR/
translate berlangsung).

**Visual search ("Cari")** selalu di dalam aplikasi (WebView). Gambar
di-upload ke host sementara (Litterbox 1 jam / Catbox), lalu WebView
membuka URL hasil reverse search (Yandex / Bing / Google Lens / TinEye)
— pola sama seperti AKS-Labs/CircleToSearch. Hasil muncul otomatis,
tanpa file chooser atau Intent ke app eksternal.

Default assistant di-set manual lewat Settings → Default apps / Aplikasi
asisten digital.

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

APK debug ini ditandatangani dengan keystore debug TETAP yang di-commit
di `keystore/debug_keystore.dat` (lihat bagian **Keystore debug tetap**
di bawah) — signature-nya sama di semua build, jadi APK baru selalu bisa
dipasang sebagai *update* di atas APK lama tanpa perlu uninstall dulu.

### Keystore debug tetap (`keystore/debug_keystore.dat`)

File ini BUKAN keystore rahasia — sengaja di-commit ke repo, dengan
password & alias standar Android debug keystore (`android` / `android` /
`androiddebugkey`), sama seperti debug keystore bawaan Android Studio.

**Kenapa ini perlu:** tanpa keystore tetap, `assembleDebug` memakai
keystore auto-generate Gradle yang lokasinya beda-beda tiap environment
(termasuk tiap runner GitHub Actions) — signature APK debug jadi berubah
tiap build, sehingga install APK baru di atas versi lama selalu gagal
dengan "tidak konsisten dengan tanda tangan APK yang diinstal", memaksa
uninstall manual tiap kali (dan itu menghapus data app, termasuk model
bahasa ML Kit yang sudah diunduh).

**Jangan hapus atau regenerate file ini** kecuali sengaja ingin
memutus kompatibilitas dengan semua APK debug yang sudah pernah dipasang
sebelumnya (device lama harus uninstall dulu setelah keystore diganti).

**Berbeda dengan larangan di bawah** (`.jks` untuk release): larangan
itu untuk keystore RELEASE (rahasia, sesuai identitas developer asli),
bukan untuk keystore debug ini (publik, cuma untuk konsistensi testing).

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
