# Circle Search Overlay

Aplikasi overlay Android yang meniru cara kerja Circle to Search Google —
seleksi bebas di layar untuk translate, OCR teks, dan visual search (via
Google Lens). Trigger memakai mekanisme **Default Assistant App** (root),
dipanggil lewat gesture assist bawaan ROM (swipe sudut bawah / long-press
tombol power).

Status: **trigger layer** sudah jalan (VoiceInteractionService/Session +
root installer module untuk auto-set default assistant saat boot). Tahap
berikutnya: root screencap + overlay seleksi freeform + OCR/translate/visual
search.

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
