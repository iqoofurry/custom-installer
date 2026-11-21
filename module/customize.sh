#!/system/bin/sh
ui_print "- Custom Installer 1.3"
ui_print "- Installing bottom-sheet package installer"
ui_print "- System app only (no pm install, no pm disable)"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/system/app/SheetInstaller/SheetInstaller.apk" 0 0 0644

ui_print "- Done. Reboot to apply."