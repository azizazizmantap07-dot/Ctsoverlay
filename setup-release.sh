#!/data/data/com.termux/files/usr/bin/bash
# Jalankan SEKALI di Termux, dari root project.
# Membuat keystore release + menampilkan 4 nilai yang harus jadi GitHub Secrets.
set -e

REPO="azizazizmantap07-dot/Ctsoverlay"
KS="$HOME/release.keystore.jks"   # simpan DI LUAR folder project
ALIAS="circlesearch"

command -v keytool >/dev/null || { echo "Install dulu: pkg install openjdk-17"; exit 1; }

if [ -f "$KS" ]; then
  echo "Keystore sudah ada di $KS — tidak dibuat ulang (jangan timpa!)."
else
  read -rsp "Buat password keystore (min 6 karakter): " PASS; echo
  keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Israfil, OU=Dev, O=IsrafilX, L=ID, ST=ID, C=ID"
  echo "$PASS" > "$HOME/.ks_pass"; chmod 600 "$HOME/.ks_pass"
fi

PASS=$(cat "$HOME/.ks_pass" 2>/dev/null || true)

echo
echo "=== BACKUP KEYSTORE INI (kalau hilang, tidak bisa update app selamanya) ==="
echo "$KS"
echo
if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  echo "gh CLI terdeteksi & sudah login -> set secrets otomatis..."
  base64 -w0 "$KS" | gh secret set ANDROID_KEYSTORE_BASE64 -R "$REPO"
  printf %s "$PASS"   | gh secret set ANDROID_KEYSTORE_PASSWORD -R "$REPO"
  printf %s "$ALIAS"  | gh secret set ANDROID_KEY_ALIAS -R "$REPO"
  printf %s "$PASS"   | gh secret set ANDROID_KEY_PASSWORD -R "$REPO"
  echo "Selesai. 4 secrets sudah terpasang."
else
  echo "gh belum login. Isi manual di:"
  echo "https://github.com/$REPO/settings/secrets/actions"
  echo
  echo "ANDROID_KEYSTORE_PASSWORD = $PASS"
  echo "ANDROID_KEY_ALIAS         = $ALIAS"
  echo "ANDROID_KEY_PASSWORD      = $PASS"
  echo "ANDROID_KEYSTORE_BASE64   = (isi file: $HOME/ks.b64)"
  base64 -w0 "$KS" > "$HOME/ks.b64"
  echo "-> salin isi file itu:  cat ~/ks.b64"
fi
