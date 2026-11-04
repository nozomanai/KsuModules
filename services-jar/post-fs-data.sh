#!/system/bin/sh
# /system and /system_ext and /odm are erofs. KernelSU does not replace
# these files. system_server maps the jars directly, and mfp-daemon
# (class late_start) maps libqc_hal.so, so the binds have to be in place
# before those processes start.
MODDIR=${0%/*}
LOG=/data/adb/services-jar.log

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null || true
}

bind_one() {
  src="$1"
  dest="$2"
  if [ ! -f "$src" ] || [ ! -f "$dest" ]; then
    log "missing src=$src dest=$dest"
    return
  fi
  if grep -q " $dest " /proc/mounts; then
    log "bind already present $dest"
    return
  fi
  if mount -o bind "$src" "$dest"; then
    log "bind ok $dest"
  else
    log "bind failed $dest"
  fi
}

MIUI="$MODDIR/system_ext/framework/miui-services.jar"
if [ ! -f "$MIUI" ]; then
  MIUI="$MODDIR/system/system_ext/framework/miui-services.jar"
fi
bind_one "$MIUI" /system_ext/framework/miui-services.jar
bind_one "$MODDIR/system/framework/services.jar" /system/framework/services.jar
bind_one "$MODDIR/odm/lib64/libqc_hal.so" /odm/lib64/libqc_hal.so

# Module files are labeled system_file. vendor_mfp-daemon cannot read that,
# dlopen fails, and the HAL then persists fp.vendor=none.
if [ -f "$MODDIR/odm/lib64/libqc_hal.so" ]; then
  chcon u:object_r:vendor_file:s0 "$MODDIR/odm/lib64/libqc_hal.so"
  log "chcon vendor_file libqc_hal.so"
fi

# late_start has not run yet on a normal boot. If this script runs again
# after the daemon is up, reload it so the patched library is mapped.
if pidof mfp-daemon >/dev/null 2>&1; then
  stop mfp-daemon
  start mfp-daemon
  log "restarted mfp-daemon"
fi
