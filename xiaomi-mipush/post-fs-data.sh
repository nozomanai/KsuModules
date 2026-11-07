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

log_msg "start moddir=$MODDIR"

mkdir -p /data/adb/post-mount.d
cp -a "$MODDIR/post-mount.sh" /data/adb/post-mount.d/xiaomi-mipush.sh
chmod 0755 /data/adb/post-mount.d/xiaomi-mipush.sh
chcon u:object_r:system_file:s0 /data/adb/post-mount.d/xiaomi-mipush.sh
log_msg "post-mount hook installed"

mount_framework
exit 0
