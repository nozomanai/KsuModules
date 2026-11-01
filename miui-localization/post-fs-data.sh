#!/system/bin/sh

LOG_STAGE=post-fs-data
MODDIR=${0%/*}
if [ -f "$MODDIR/log.sh" ]; then
  . "$MODDIR/log.sh"
else
  echo "log.sh missing script=$0" >> /data/adb/miui-localization.log
  exit 0
fi

APK_SRC="$MODDIR/product/priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk"
PERM_SRC="$MODDIR/product/etc/permissions/privapp-permissions-miui-localization.xml"
MNT=/data/adb/miui-localization-mnt

log_msg "start moddir=$MODDIR"

if [ ! -f "$APK_SRC" ]; then
  log_msg "result=skip reason=apk-not-in-module path=$APK_SRC"
  exit 0
fi

mkdir -p "$MNT"
if grep -q " $MNT " /proc/mounts; then
  log_msg "tmpfs already mounted path=$MNT"
else
  mount -t tmpfs tmpfs "$MNT"
  log_msg "tmpfs mount path=$MNT exit=$?"
fi

mkdir -p "$MNT/priv-app/MIUIQuickSearchBox"
cp -a "$APK_SRC" "$MNT/priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk"
chmod 0755 "$MNT/priv-app" "$MNT/priv-app/MIUIQuickSearchBox"
chmod 0644 "$MNT/priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk"
chcon u:object_r:system_file:s0 "$MNT/priv-app/MIUIQuickSearchBox" "$MNT/priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk"
BYTES=$(wc -c < "$MNT/priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk" | tr -d ' ')
log_msg "staged apk bytes=$BYTES"

mount -t overlay overlay -o "lowerdir=${MNT}/priv-app:/mnt/vendor/mi_ext/product/priv-app:/product/priv-app,redirect_dir=on" /product/priv-app
log_msg "overlay priv-app exit=$?"

if [ -f "$PERM_SRC" ] && [ -d /mnt/vendor/mi_ext/product/etc/permissions ] && [ -d /product/etc/permissions ]; then
  mkdir -p "$MNT/permissions"
  cp -a "$PERM_SRC" "$MNT/permissions/privapp-permissions-miui-localization.xml"
  chmod 0755 "$MNT/permissions"
  chmod 0644 "$MNT/permissions/privapp-permissions-miui-localization.xml"
  chcon u:object_r:system_file:s0 "$MNT/permissions" "$MNT/permissions/privapp-permissions-miui-localization.xml"
  mount -t overlay overlay -o "lowerdir=${MNT}/permissions:/mnt/vendor/mi_ext/product/etc/permissions:/product/etc/permissions,redirect_dir=on" /product/etc/permissions
  log_msg "overlay permissions exit=$?"
else
  log_msg "permissions overlay skipped src=$PERM_SRC"
fi

if [ -f /product/priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk ]; then
  log_msg "result=mounted path=/product/priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk"
else
  log_msg "result=not-visible path=/product/priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk"
fi
