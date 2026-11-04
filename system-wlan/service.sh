#!/system/bin/sh
# Patched Security Center is not a valid platform signature. Mount it only
# after boot_completed so package scan still sees the original apk.
MODDIR=${0%/*}
DEST=/product/priv-app/SecurityCenter/SecurityCenter.apk
SRC=$MODDIR/SecurityCenter.apk
LOG=/data/adb/system-wlan.log

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null || true
}

i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  i=$((i + 1))
  if [ "$i" -gt 90 ]; then
    log "boot_completed timeout"
    break
  fi
  sleep 1
done

if [ ! -f "$SRC" ] || [ ! -f "$DEST" ]; then
  log "missing src=$SRC dest=$DEST"
  exit 0
fi

if grep -q " $DEST " /proc/mounts; then
  log "bind already present"
else
  if mount -o bind "$SRC" "$DEST"; then
    log "bind ok"
  else
    log "bind failed"
    exit 0
  fi
fi

am force-stop com.miui.securitycenter
log "force-stop com.miui.securitycenter"
