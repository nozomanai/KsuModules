#!/system/bin/sh

LOG_STAGE=post-fs-data
MODDIR=${0%/*}
if [ -f "$MODDIR/log.sh" ]; then
  . "$MODDIR/log.sh"
else
  echo "log.sh missing script=$0" >> /data/adb/xiaomi-mipush.log
  exit 0
fi

. "$MODDIR/mount.sh"
. "$MODDIR/drop-package.sh"

log_msg "start moddir=$MODDIR"
rm -f "$MODDIR/skip_mount"
rm -f /data/adb/post-mount.d/xiaomi-mipush.sh
if ! grep -q " /data/adb/xiaomi-mipush-mnt " /proc/mounts; then
  rm -rf /data/adb/xiaomi-mipush-mnt
fi
reset_xmsf_if_needed
exit 0
