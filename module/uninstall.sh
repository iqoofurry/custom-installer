#!/system/bin/sh

SELF="com.iqoofurry.sheetinstaller"

pm uninstall --user 0 "$SELF" >/dev/null 2>&1

exit 0
