#!/system/bin/sh

LOG_STAGE=customize
. "$MODPATH/log.sh"

touch "$MODPATH/skip_mount"
chmod 0755 "$MODPATH"/*.sh 2>/dev/null
mkdir -p /data/adb/post-mount.d
cp -a "$MODPATH/post-mount.sh" /data/adb/post-mount.d/xiaomi-mipush.sh
chmod 0755 /data/adb/post-mount.d/xiaomi-mipush.sh
chcon u:object_r:system_file:s0 /data/adb/post-mount.d/xiaomi-mipush.sh 2>/dev/null

log_msg "install start MODPATH=$MODPATH"
if command -v grep_prop >/dev/null 2>&1; then
  log_msg "version=$(grep_prop version "$MODPATH/module.prop" 2>/dev/null) versionCode=$(grep_prop versionCode "$MODPATH/module.prop" 2>/dev/null)"
fi
log_msg "install end"
exit 0
