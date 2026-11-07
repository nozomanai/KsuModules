#!/system/bin/sh
MODDIR=${0%/*}
GEN="$MODDIR/generated"
DEST=/data/vendor/thermal/config
MODE=$(cat "$MODDIR/mode" 2>/dev/null)
if [ ! -d "$DEST" ] || [ "$MODE" = "off" ]; then
  exit 0
fi
if [ "$MODE" = "normal" ]; then
  names="thermal-normal.conf thermal-gl-normal.conf thermal-ind-normal.conf"
elif [ "$MODE" = "scene" ]; then
  names="thermal-map.conf thermal-map-gl.conf thermal-map-ind.conf"
else
  names="thermal-normal.conf thermal-map.conf thermal-map-gl.conf thermal-map-ind.conf"
fi
for name in $names; do
  src="$GEN/$name"
  if [ -f "$src" ]; then
    chattr -i "$DEST/$name" 2>/dev/null || true
    cp -f "$src" "$DEST/$name"
    chown root:system "$DEST/$name" 2>/dev/null || true
    chmod 644 "$DEST/$name"
    chcon u:object_r:thermal_data_file:s0 "$DEST/$name" 2>/dev/null || true
    chattr +i "$DEST/$name" 2>/dev/null || true
  fi
done
sh "$MODDIR/conf.sh" restore >/dev/null 2>&1 || true
