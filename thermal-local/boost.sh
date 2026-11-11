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
  # 0xFFF on the prime cluster is the touch-up boost that still reaches 4 GHz.
  sed -i \
    -e 's/0x40804200, 4089/0x40804200, 2649/g' \
    -e 's/0x40800200, 4089/0x40800200, 2649/g' \
    -e 's/0x40804200, 0xFFF/0x40804200, 2649/g' \
    -e 's/0x40804300, 0xFFF/0x40804300, 2649/g' \
    -e 's/0x40800000, 0xFFF, 0x40800200, 0xFFF, 0x5FC00000/0x40800000, 1996, 0x40800200, 1689, 0x5FC00000/g' \
    "$PERF_GEN"
  TOUCH="$MODDIR/touch-boost"
  if [ ! -s "$TOUCH" ] && [ -s "$MODDIR/touch-boost.default" ]; then
    cp -f "$MODDIR/touch-boost.default" "$TOUCH"
  fi
  hz0=$(awk -F= '$1=="cpu0" { gsub(/[^0-9]/, "", $2); print $2; exit }' "$TOUCH" 2>/dev/null)
  hz6=$(awk -F= '$1=="cpu6" { gsub(/[^0-9]/, "", $2); print $2; exit }' "$TOUCH" 2>/dev/null)
  [ -n "$hz0" ] || hz0=2400000
  [ -n "$hz6" ] || hz6=2649600
  awk -v mhz0="$((hz0 / 1000))" -v mhz6="$((hz6 / 1000))" '
    /Id="0x00001085"/ && /Type="1"/ && /Target="sun"/ { hit = 1 }
    hit && /0x40804200/ {
      gsub(/0x40804200, 0x[0-9A-Fa-f]+/, "0x40804200, " mhz6)
      gsub(/0x40804200, [0-9]+/, "0x40804200, " mhz6)
      gsub(/0x40804000, 0x[0-9A-Fa-f]+/, "0x40804000, " mhz0)
      gsub(/0x40804000, [0-9]+/, "0x40804000, " mhz0)
      hit = 0
    }
    { print }
  ' "$PERF_GEN" > "$PERF_GEN.tmp" && mv -f "$PERF_GEN.tmp" "$PERF_GEN"
  log "touch boost cpu0=${hz0} cpu6=${hz6}"
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
