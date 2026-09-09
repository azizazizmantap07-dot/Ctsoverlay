#!/system/bin/sh
# Circle Search Overlay - Assistant Setup
# Dijalankan sebagai late_start service.
#
# Konsisten dengan pola bug yang pernah ditemukan di proyek BG Restrict:
# action.sh/uninstall.sh sempat gagal total karena PATH tidak lengkap
# saat dijalankan Manager. service.sh (late_start) biasanya mewarisi PATH
# sistem penuh, tapi kita tetap export eksplisit untuk konsistensi &
# jaga-jaga bila dipanggil ulang lewat action button di masa depan.
export PATH="/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH"

MODDIR="${0%/*}"
LOG="$MODDIR/service.log"
TARGET_PKG="com.israfilx.circlesearch"
TARGET_SERVICE="$TARGET_PKG/.service.MyVoiceInteractionService"

log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $1" >> "$LOG"
}

log "=== service.sh mulai ==="

# Tunggu package manager siap & target package ter-index.
# Pola sama seperti retry di BG Restrict untuk komponen GMS yang belum
# ter-index di detik-detik awal boot.
i=0
max_retry=15
pkg_found=0
while [ "$i" -lt "$max_retry" ]; do
    if pm path "$TARGET_PKG" >/dev/null 2>&1; then
        pkg_found=1
        break
    fi
    i=$((i + 1))
    sleep 2
done

if [ "$pkg_found" -eq 0 ]; then
    log "GAGAL: paket $TARGET_PKG tidak ditemukan setelah $max_retry percobaan (app belum terpasang?)"
    exit 0
fi

log "Paket $TARGET_PKG ditemukan, melanjutkan setup assistant"

# Probe kapabilitas ROM dulu (pola sama seperti BG Restrict): jangan
# paksakan `settings put secure` bila ternyata tidak reversibel/berfungsi
# di ROM ini — cek dulu hasilnya sebelum dianggap sukses.
settings put secure voice_interaction_service "$TARGET_SERVICE" 2>>"$LOG"
settings put secure assistant "$TARGET_SERVICE" 2>>"$LOG"

current_assistant="$(settings get secure assistant 2>>"$LOG")"
current_vis="$(settings get secure voice_interaction_service 2>>"$LOG")"

log "settings get secure assistant -> $current_assistant"
log "settings get secure voice_interaction_service -> $current_vis"

if [ "$current_assistant" = "$TARGET_SERVICE" ] && [ "$current_vis" = "$TARGET_SERVICE" ]; then
    log "SUKSES: $TARGET_PKG terdaftar sebagai default assistant"
else
    log "PERINGATAN: nilai settings tidak sesuai target — kemungkinan ROM ini membatasi settings put secure (mirip temuan di device itel S666LN untuk appops/pm). Perlu debug lanjutan bila ini terjadi."
fi

log "=== service.sh selesai ==="
