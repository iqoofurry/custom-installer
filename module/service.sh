#!/system/bin/sh

# Intentionally does nothing at boot.
# The APK is provided by the module overlay in /system/app/SheetInstaller/.
# No pm install, no pm disable, no framework interaction -> cannot block boot.

MODDIR=${0%/*}
echo "$(date '+%Y-%m-%d %H:%M:%S') service start (no-op)" >>"$MODDIR/service.log" 2>/dev/null

exit 0