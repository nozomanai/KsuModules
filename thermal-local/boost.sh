#!/system/bin/sh
MODDIR=${0%/*}
if [ "$1" != "--ns" ]; then
  exec nsenter -t 1 -m -- sh "$0" --ns
fi

LOG=/data/adb/thermal-local.log
SRC="$MODDIR/boost.xml"
DEF="$MODDIR/boost.default.xml"
DST=/vendor/etc/perf/thermalbreakboostconfig.xml
PERF_DST=/vendor/etc/perf/perfboostsconfig.xml
PERF_GEN="$MODDIR/generated/perfboostsconfig.xml"
MODE=$(tr -d ' \r\n' < "$MODDIR/mode" 2>/dev/null)

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null || true
}

if [ ! -s "$SRC" ] && [ -s "$DEF" ]; then
  cp -f "$DEF" "$SRC"
fi

mounted() {
  awk -v dst="$1" '$2 == dst { found = 1 } END { exit found ? 0 : 1 }' /proc/mounts
}

while mounted "$DST"; do
  umount "$DST" || break
done
while mounted "$PERF_DST"; do
  umount "$PERF_DST" || break
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
mkdir -p "$MODDIR/generated"
if cp -f "$PERF_DST" "$PERF_GEN"; then
  sed -i \
    -e 's/0x40804200, 4089/0x40804200, 2649/g' \
    -e 's/0x40800200, 4089/0x40800200, 2649/g' \
    -e 's/0x40800000, 0xFFF, 0x40800200, 0xFFF, 0x5FC00000/0x40800000, 1996, 0x40800200, 1689, 0x5FC00000/g' \
    "$PERF_GEN"
  chown root:root "$PERF_GEN" 2>/dev/null || true
  chmod 644 "$PERF_GEN"
  chcon u:object_r:vendor_configs_file:s0 "$PERF_GEN" 2>/dev/null || true
  if mount -o bind "$PERF_GEN" "$PERF_DST"; then
    log "perfboost mounted"
  else
    log "perfboost mount failed"
  fi
else
  log "perfboost copy failed"
fi
if [ "$(getprop init.svc.perf2-hal-1-0 2>/dev/null)" = "running" ]; then
  setprop ctl.restart perf2-hal-1-0
  log "perf2-hal restarted"
fi
echo "boost mounted"
exit 0
