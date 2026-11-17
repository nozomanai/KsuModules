#!/system/bin/sh

LOG_STAGE=customize
. "$MODPATH/log.sh"

rm -f "$MODPATH/skip_mount"
rm -f /data/adb/post-mount.d/xiaomi-mipush.sh
chmod 0755 "$MODPATH"/*.sh 2>/dev/null

log_msg "install start MODPATH=$MODPATH"
if command -v grep_prop >/dev/null 2>&1; then
  log_msg "version=$(grep_prop version "$MODPATH/module.prop" 2>/dev/null) versionCode=$(grep_prop versionCode "$MODPATH/module.prop" 2>/dev/null)"
fi
log_msg "install end"
exit 0
