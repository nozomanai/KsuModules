#!/system/bin/sh
# Encrypt profile.conf and install it where mi_thermald reads cloud overrides.
MODDIR=${0%/*}
LOG=/data/adb/thermal-local.log
GEN="$MODDIR/generated"
DEX="$MODDIR/thermal-crypt.dex"
PROFILE="$MODDIR/profile.conf"
MODEFILE="$MODDIR/mode"
DEST=/data/vendor/thermal/config
OWNED="thermal-normal.conf thermal-gl-normal.conf thermal-ind-normal.conf thermal-map.conf thermal-map-gl.conf thermal-map-ind.conf"
MAPS="/vendor/etc/thermal-map.conf /odm/etc/thermal-map-gl.conf /odm/etc/thermal-map-ind.conf"

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null || true
}

if [ ! -d "$DEST" ] && [ "$1" != "--ns" ]; then
  exec nsenter -t 1 -m -- sh "$0" --ns
fi

if [ ! -f "$PROFILE" ] && [ -f "$MODDIR/profile.default" ]; then
  cp -f "$MODDIR/profile.default" "$PROFILE"
  log "created profile.conf from profile.default"
fi
if [ ! -f "$MODEFILE" ] && [ -f "$MODDIR/mode.default" ]; then
  cp -f "$MODDIR/mode.default" "$MODEFILE"
  log "created mode from mode.default"
fi

MODE=$(cat "$MODEFILE" 2>/dev/null)
case "$MODE" in
  all|normal|off|scene) ;;
  *) MODE=all ;;
esac

crypt() {
  rm -f "$3"
  CLASSPATH="$DEX" /system/bin/app_process /system/bin ThermalCrypt "$1" "$2" "$3" >> "$LOG" 2>&1
  if [ ! -s "$3" ]; then
    log "crypt failed $1 $2"
    return 1
  fi
  return 0
}

unlock_owned() {
  name=$1
  if [ -f "$DEST/$name" ]; then
    chattr -i "$DEST/$name" 2>/dev/null || true
  fi
}

remove_owned() {
  name=$1
  unlock_owned "$name"
  rm -f "$DEST/$name"
}

install_file() {
  src=$1
  name=$2
  unlock_owned "$name"
  cp -f "$src" "$DEST/$name" || return 1
  chown root:system "$DEST/$name" 2>/dev/null || true
  chmod 644 "$DEST/$name"
  chcon u:object_r:thermal_data_file:s0 "$DEST/$name" 2>/dev/null || true
  chattr +i "$DEST/$name" 2>/dev/null || true
  log "installed $name"
  return 0
}

restart_daemon() {
  stop mi_thermald 2>/dev/null || true
  sleep 1
  start mi_thermald 2>/dev/null || true
  sleep 1
  if pidof mi_thermald >/dev/null 2>&1; then
    log "mi_thermald restarted pid=$(pidof mi_thermald)"
  else
    log "mi_thermald did not come back"
    return 1
  fi
  return 0
}

if [ ! -d "$DEST" ]; then
  log "missing $DEST"
  exit 1
fi
if [ ! -f "$DEX" ]; then
  log "missing thermal-crypt.dex"
  exit 1
fi
if [ ! -f "$PROFILE" ]; then
  log "missing profile.conf"
  exit 1
fi

mkdir -p "$GEN"
log "apply mode=$MODE"

apply_scene_maps() {
  assign="$MODDIR/scene-map.txt"
  if [ ! -s "$assign" ]; then
    log "scene-map.txt missing"
    return 1
  fi
  for name in thermal-normal.conf thermal-gl-normal.conf thermal-ind-normal.conf; do
    remove_owned "$name"
  done
  for src in $MAPS; do
    if [ ! -f "$src" ]; then
      log "map missing $src"
      continue
    fi
    base=${src##*/}
    if ! crypt d "$src" "$GEN/$base.dec"; then
      return 1
    fi
    awk -v ASSIGN="$assign" '
      BEGIN {
        while ((getline line < ASSIGN) > 0) {
          split(line, a, " ")
          if (a[1] != "" && a[2] != "") choice[a[1]] = a[2]
        }
      }
      /^\[[0-9]+:/ {
        id = $0
        sub(/^\[/, "", id)
        sub(/:.*/, "", id)
        if (id in choice) {
          print "[" id ":" choice[id] "]"
          written[id] = 1
          next
        }
        print
        next
      }
      { print }
      END {
        for (id in choice) if (!(id in written)) print "[" id ":" choice[id] "]"
      }
    ' "$GEN/$base.dec" > "$GEN/$base.txt" || return 1
    if ! crypt e "$GEN/$base.txt" "$GEN/$base"; then
      return 1
    fi
    if ! crypt d "$GEN/$base" "$GEN/$base.check"; then
      return 1
    fi
    if ! cmp -s "$GEN/$base.txt" "$GEN/$base.check"; then
      log "map roundtrip mismatch $base"
      return 1
    fi
    if ! install_file "$GEN/$base" "$base"; then
      log "failed to write $base"
      return 1
    fi
  done
  return 0
}

if [ "$MODE" = "off" ]; then
  for name in $OWNED; do
    remove_owned "$name"
  done
  sh "$MODDIR/conf.sh" purge >> "$LOG" 2>&1 || true
  restart_daemon
  log "overlays removed"
  exit 0
fi

if [ "$MODE" = "scene" ]; then
  if ! apply_scene_maps; then
    exit 1
  fi
  sh "$MODDIR/conf.sh" restore >> "$LOG" 2>&1 || true
  restart_daemon
  log "apply done"
  exit 0
fi

if ! crypt e "$PROFILE" "$GEN/thermal-normal.conf"; then
  exit 1
fi
if ! crypt d "$GEN/thermal-normal.conf" "$GEN/check.txt"; then
  exit 1
fi
if ! cmp -s "$PROFILE" "$GEN/check.txt"; then
  log "roundtrip mismatch, not installed"
  exit 1
fi

if ! install_file "$GEN/thermal-normal.conf" thermal-normal.conf; then
  log "failed to write thermal-normal.conf"
  exit 1
fi

if [ "$MODE" = "all" ]; then
  for name in thermal-gl-normal.conf thermal-ind-normal.conf; do
    remove_owned "$name"
  done
  for src in $MAPS; do
    if [ ! -f "$src" ]; then
      log "map missing $src"
      continue
    fi
    base=${src##*/}
    if ! crypt d "$src" "$GEN/$base.dec"; then
      exit 1
    fi
    sed 's/:.*\.conf]/:thermal-normal.conf]/' "$GEN/$base.dec" > "$GEN/$base.txt" || exit 1
    if ! crypt e "$GEN/$base.txt" "$GEN/$base"; then
      exit 1
    fi
    if ! install_file "$GEN/$base" "$base"; then
      log "failed to write $base"
      exit 1
    fi
  done
else
  if ! install_file "$GEN/thermal-normal.conf" thermal-gl-normal.conf; then
    exit 1
  fi
  if ! install_file "$GEN/thermal-normal.conf" thermal-ind-normal.conf; then
    exit 1
  fi
  for name in thermal-map.conf thermal-map-gl.conf thermal-map-ind.conf; do
    remove_owned "$name"
  done
fi

sh "$MODDIR/conf.sh" restore >> "$LOG" 2>&1 || true
restart_daemon
log "apply done"
exit 0
