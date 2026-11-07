#!/system/bin/sh
# Arm a next-boot revert. Editing packages.xml while system_server is running
# does not stick; post-fs-data.d runs before it starts, including after this
# module directory has been removed.

MODDIR=${0%/*}
rm -f /data/adb/post-mount.d/xiaomi-mipush.sh
mkdir -p /data/adb/post-fs-data.d
cp -a "$MODDIR/log.sh" /data/adb/xiaomi-mipush-log.sh
cp -a "$MODDIR/drop-package.sh" /data/adb/xiaomi-mipush-drop.sh
cp -a "$MODDIR/reset-packages.awk" /data/adb/xiaomi-mipush-revert.awk
chmod 0644 /data/adb/xiaomi-mipush-log.sh /data/adb/xiaomi-mipush-drop.sh /data/adb/xiaomi-mipush-revert.awk

cat > /data/adb/post-fs-data.d/xiaomi-mipush-revert.sh << 'EOF'
#!/system/bin/sh
LOGFILE=/data/adb/xiaomi-mipush.log
LOG_STAGE=revert
MODDIR=/data/adb
AWK_FILE=/data/adb/xiaomi-mipush-revert.awk
. /data/adb/xiaomi-mipush-log.sh
. /data/adb/xiaomi-mipush-drop.sh
log_msg "revert start"
if drop_xmsf_record; then
  wipe_xmsf_data
  rm -f /data/adb/xiaomi-mipush.reset
  log_msg "revert done"
else
  log_msg "revert failed"
fi
rm -f /data/adb/xiaomi-mipush-log.sh /data/adb/xiaomi-mipush-drop.sh /data/adb/xiaomi-mipush-revert.awk
rm -f /data/adb/post-fs-data.d/xiaomi-mipush-revert.sh
EOF
chmod 0755 /data/adb/post-fs-data.d/xiaomi-mipush-revert.sh
echo "xiaomi-mipush uninstall armed" >> /data/adb/xiaomi-mipush.log
exit 0
