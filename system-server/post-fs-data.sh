#!/system/bin/sh
# /system_ext is its own erofs mount. KernelSU does not replace
# miui-services.jar there. system_server maps this jar directly, so the
# bind has to stay in place; there is no /system/bin/odrefresh on this device.
MODDIR=${0%/*}
DEST=/system_ext/framework/miui-services.jar
LOG=/data/adb/system-server.log

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null || true
}

SRC="$MODDIR/system_ext/framework/miui-services.jar"
if [ ! -f "$SRC" ]; then
  SRC="$MODDIR/system/system_ext/framework/miui-services.jar"
fi
if [ ! -f "$SRC" ] || [ ! -f "$DEST" ]; then
  log "missing src=$SRC dest=$DEST"
  exit 0
fi

if grep -q " $DEST " /proc/mounts; then
  log "bind already present"
  exit 0
fi

if mount -o bind "$SRC" "$DEST"; then
  log "bind ok src=$SRC"
else
  log "bind failed src=$SRC"
fi
