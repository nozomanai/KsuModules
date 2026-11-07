#!/system/bin/sh
# Runs from /data/adb/post-mount.d after every module post-fs-data.sh.
# That is the last point before system_server scans /product/app.

MODDIR=/data/adb/modules/xiaomi-mipush
if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ ! -f "$MODDIR/mount.sh" ]; then
  exit 0
fi

LOG_STAGE=post-mount
. "$MODDIR/log.sh"
. "$MODDIR/mount.sh"
. "$MODDIR/drop-package.sh"

log_msg "start"
if mount_framework; then
  reset_xmsf_if_needed
fi
exit 0
