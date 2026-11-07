#!/system/bin/sh
MODDIR=${0%/*}
if [ "$1" != "--ns" ]; then
  exec nsenter -t 1 -m -- sh "$0" --ns
fi

LOG=/data/adb/thermal-local.log
SRC="$MODDIR/boost.xml"
DEF="$MODDIR/boost.default.xml"
DST=/vendor/etc/perf/thermalbreakboostconfig.xml
MODE=$(tr -d ' \r\n' < "$MODDIR/mode" 2>/dev/null)

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null || true
}

if [ ! -s "$SRC" ] && [ -s "$DEF" ]; then
  cp -f "$DEF" "$SRC"
fi

mounted() {
  awk -v dst="$DST" '$2 == dst { found = 1 } END { exit found ? 0 : 1 }' /proc/mounts
}

while mounted; do
  umount "$DST" || break
done

if [ "$MODE" = "off" ] || [ ! -s "$SRC" ]; then
  log "boost overlay off"
  echo "boost off"
  exit 0
fi

chown root:root "$SRC" 2>/dev/null || true
chmod 644 "$SRC"
chcon u:object_r:vendor_configs_file:s0 "$SRC" 2>/dev/null || true
if ! mount -o bind "$SRC" "$DST"; then
  log "boost mount failed"
  echo "boost mount failed" >&2
  exit 1
fi
log "boost mounted"
if [ "$(getprop init.svc.perf2-hal-1-0 2>/dev/null)" = "running" ]; then
  setprop ctl.restart perf2-hal-1-0
  log "perf2-hal restarted"
fi
echo "boost mounted"
exit 0
