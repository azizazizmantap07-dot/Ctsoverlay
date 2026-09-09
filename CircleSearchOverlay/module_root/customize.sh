#!/system/bin/sh
# Dijalankan Manager (Magisk/KernelSU) saat instalasi module.
ui_print "- Circle Search Overlay: Assistant Setup"
ui_print "  Module ini hanya mendaftarkan app sebagai default Assistant."
ui_print "  Pastikan APK Circle Search Overlay sudah terpasang lebih dulu."
set_perm "$MODPATH/service.sh" 0 0 0755
