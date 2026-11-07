#!/system/bin/sh
DEST=/data/vendor/thermal/config
LOG=/data/adb/thermal-local.log
if [ ! -d "$DEST" ] && [ "$1" != "--ns" ]; then
  exec nsenter -t 1 -m -- sh "$0" --ns
fi
if [ ! -d "$DEST" ]; then
  exit 0
fi
echo "$(date '+%Y-%m-%d %H:%M:%S') uninstall" >> "$LOG" 2>/dev/null || true
for name in thermal-normal.conf thermal-gl-normal.conf thermal-ind-normal.conf thermal-map.conf thermal-map-gl.conf thermal-map-ind.conf; do
  if [ -f "$DEST/$name" ]; then
    chattr -i "$DEST/$name" 2>/dev/null || true
    rm -f "$DEST/$name"
  fi
done
stop mi_thermald 2>/dev/null || true
sleep 1
start mi_thermald 2>/dev/null || true
