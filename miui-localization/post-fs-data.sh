#!/system/bin/sh

LOG_STAGE=post-fs-data
MODDIR=${0%/*}
if [ -f "$MODDIR/log.sh" ]; then
  . "$MODDIR/log.sh"
else
  echo "log.sh missing script=$0" >> /data/adb/miui-localization.log
  exit 0
fi

MNT=/data/adb/miui-localization-mnt
SRC="$MODDIR/product"

log_msg "start moddir=$MODDIR"

stage_tree() {
  CATEGORY="$1"
  FROM="$SRC/$CATEGORY"
  if [ ! -d "$FROM" ]; then
    log_msg "stage skip reason=missing category=$CATEGORY"
    return 1
  fi
  FOUND=0
  for apk in "$FROM"/*/*.apk; do
    if [ ! -f "$apk" ]; then
      continue
    fi
    FOUND=1
    APP=$(basename "$(dirname "$apk")")
    BASE=$(basename "$apk")
    mkdir -p "$MNT/$CATEGORY/$APP"
    cp -a "$apk" "$MNT/$CATEGORY/$APP/$BASE"
    chmod 0755 "$MNT/$CATEGORY" "$MNT/$CATEGORY/$APP"
    chmod 0644 "$MNT/$CATEGORY/$APP/$BASE"
    chcon u:object_r:system_file:s0 "$MNT/$CATEGORY" "$MNT/$CATEGORY/$APP" "$MNT/$CATEGORY/$APP/$BASE"
    BYTES=$(wc -c < "$MNT/$CATEGORY/$APP/$BASE" | tr -d ' ')
    log_msg "staged category=$CATEGORY app=$APP bytes=$BYTES"
  done
  if [ "$FOUND" -eq 0 ]; then
    log_msg "stage skip reason=no-apk category=$CATEGORY"
    return 1
  fi
  return 0
}

mount_overlay() {
  CATEGORY="$1"
  TARGET="$2"
  MIEXT="$3"
  if [ ! -d "$MNT/$CATEGORY" ]; then
    log_msg "overlay skip reason=not-staged category=$CATEGORY"
    return 0
  fi
  if [ ! -d "$MIEXT" ] || [ ! -d "$TARGET" ]; then
    log_msg "overlay skip reason=target-missing category=$CATEGORY target=$TARGET miext=$MIEXT"
    return 0
  fi
  mount -t overlay overlay -o "lowerdir=${MNT}/${CATEGORY}:${MIEXT}:${TARGET},redirect_dir=on" "$TARGET"
  log_msg "overlay category=$CATEGORY exit=$? target=$TARGET"
}

mkdir -p "$MNT"
if grep -q " $MNT " /proc/mounts; then
  log_msg "tmpfs already mounted path=$MNT"
else
  mount -t tmpfs tmpfs "$MNT"
  log_msg "tmpfs mount path=$MNT exit=$?"
fi

ANY=0
for CATEGORY in priv-app app data-app overlay; do
  if stage_tree "$CATEGORY"; then
    ANY=1
  fi
done

if [ "$ANY" -eq 0 ]; then
  log_msg "result=skip reason=no-apk"
  exit 0
fi

mount_overlay priv-app /product/priv-app /mnt/vendor/mi_ext/product/priv-app
mount_overlay app /product/app /mnt/vendor/mi_ext/product/app
mount_overlay data-app /product/data-app /mnt/vendor/mi_ext/product/data-app
mount_overlay overlay /product/overlay /mnt/vendor/mi_ext/product/overlay

PERM_SRC="$SRC/etc/permissions"
if [ -d "$PERM_SRC" ] && [ -d /mnt/vendor/mi_ext/product/etc/permissions ] && [ -d /product/etc/permissions ]; then
  mkdir -p "$MNT/permissions"
  chmod 0755 "$MNT/permissions"
  chcon u:object_r:system_file:s0 "$MNT/permissions"
  PERM_N=0
  for xml in "$PERM_SRC"/*.xml; do
    if [ ! -f "$xml" ]; then
      continue
    fi
    BASE=$(basename "$xml")
    cp -a "$xml" "$MNT/permissions/$BASE"
    chmod 0644 "$MNT/permissions/$BASE"
    chcon u:object_r:system_file:s0 "$MNT/permissions/$BASE"
    log_msg "staged perm=$BASE"
    PERM_N=$((PERM_N + 1))
  done
  if [ "$PERM_N" -gt 0 ]; then
    mount -t overlay overlay -o "lowerdir=${MNT}/permissions:/mnt/vendor/mi_ext/product/etc/permissions:/product/etc/permissions,redirect_dir=on" /product/etc/permissions
    log_msg "overlay permissions exit=$? count=$PERM_N"
  else
    log_msg "permissions overlay skipped reason=no-xml"
  fi
else
  log_msg "permissions overlay skipped src=$PERM_SRC"
fi

for REL in \
  priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk \
  priv-app/VoiceAssistAndroidT/VoiceAssistAndroidT.apk \
  app/VoiceTrigger/VoiceTrigger.apk \
  app/MIUIAiasstService/MIUIAiasstService.apk \
  app/XiaoaiRecommendation/XiaoaiRecommendation.apk \
  data-app/MIUIXiaoAiSpeechEngine/MIUIXiaoAiSpeechEngine.apk \
  data-app/VoiceAssistProxy/VoiceAssistProxy.apk \
  overlay/VoiceAssistAndroidOverlay/VoiceAssistAndroidOverlay.apk
do
  if [ -f "/product/$REL" ]; then
    log_msg "result=mounted path=/product/$REL"
  else
    log_msg "result=not-visible path=/product/$REL"
  fi
done
