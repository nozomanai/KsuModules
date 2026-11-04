#!/system/bin/sh
# /system_ext is its own erofs mount. KernelSU does not replace
# MiuiSystemUI.apk there, so bind it. Clear the dalvik cache so
# systemui loads the patched classes3.dex.
# Disable: touch /data/adb/modules/fod-timeout-wake/disable
MODDIR=${0%/*}
DEST=/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk
LOG=/data/adb/fod-timeout-wake.log
EXPECTED=OS3.0.307.0.WOCCNXM

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null || true
}

INCREMENTAL=$(getprop ro.build.version.incremental)
if [ "$INCREMENTAL" != "$EXPECTED" ]; then
  log "skip incremental=$INCREMENTAL expected=$EXPECTED"
  exit 0
fi

SRC="$MODDIR/system/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk"
if [ ! -f "$SRC" ] || [ ! -f "$DEST" ]; then
  log "missing src=$SRC dest=$DEST"
  exit 0
fi

if grep -q " $DEST " /proc/mounts; then
  log "bind already present"
else
  if mount -o bind "$SRC" "$DEST"; then
    log "bind ok src=$SRC"
  else
    log "bind failed src=$SRC"
    exit 0
  fi
fi

rm -f \
  "/data/dalvik-cache/arm64/system_ext@priv-app@MiuiSystemUI@MiuiSystemUI.apk@classes.dex" \
  "/data/dalvik-cache/arm64/system_ext@priv-app@MiuiSystemUI@MiuiSystemUI.apk@classes.vdex"
rm -rf \
  /data/misc/profiles/cur/0/com.android.systemui \
  /data/misc/profiles/cur/999/com.android.systemui \
  /data/misc/profiles/ref/com.android.systemui
log "dalvik-cache cleared"
