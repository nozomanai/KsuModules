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
flag() {
  [ -f "$1" ] || return 0
  tr -d ' \r\n' < "$1" 2>/dev/null || true
}
GPU=$(flag "$MODDIR/gpu-shade")
CPU=$(flag "$MODDIR/cpu-shade")
GPUR=$(flag "$MODDIR/gpu-recents")
CPUR=$(flag "$MODDIR/cpu-recents")
CPUU=$(flag "$MODDIR/cpu-unlock")
CPUA=$(flag "$MODDIR/cpu-anim")
[ "$GPU" = "off" ] || GPU=on
[ "$CPU" = "off" ] || CPU=on
[ "$GPUR" = "off" ] || GPUR=on
[ "$CPUR" = "off" ] || CPUR=on
[ "$CPUU" = "off" ] || CPUU=on
[ "$CPUA" = "off" ] || CPUA=on

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

overlay=1
if [ "$MODE" = "off" ] || [ ! -s "$SRC" ]; then
  overlay=0
  log "boost overlay off"
fi

if [ "$overlay" = 1 ]; then
  chown root:root "$SRC" 2>/dev/null || true
  chmod 644 "$SRC"
  chcon u:object_r:vendor_configs_file:s0 "$SRC" 2>/dev/null || true
  if ! mount -o bind "$SRC" "$DST"; then
    log "boost mount failed"
    echo "boost mount failed" >&2
    exit 1
  fi
  log "boost mounted"
fi

if [ "$overlay" = 1 ] || [ "$GPU" = "off" ] || [ "$CPU" = "off" ] || [ "$GPUR" = "off" ] || [ "$CPUR" = "off" ] || [ "$CPUU" = "off" ] || [ "$CPUA" = "off" ]; then
  mkdir -p "$MODDIR/generated"
  perf_ok=0
  if cp -f "$PERF_DST" "$PERF_GEN"; then
    if [ "$overlay" = 1 ]; then
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
    fi
    if [ "$GPU" = "off" ]; then
      # Status-bar pull on sun pins GPU min and max power level to 3 (900 MHz).
      awk '
        /Id="0x000011A0"/ { id = 1; type = 0; sun = 0 }
        id && /Type="1"/ { type = 1 }
        id && /Target="sun"/ { sun = 1 }
        id && type && sun {
          gsub(/0x42804000,[[:space:]]*0x3,[[:space:]]*0[Xx]42808000,[[:space:]]*0x3,[[:space:]]*/, "")
        }
        /\/>/ { id = 0; type = 0; sun = 0 }
        { print }
      ' "$PERF_GEN" > "$PERF_GEN.tmp" && mv -f "$PERF_GEN.tmp" "$PERF_GEN"
      if grep -F '42808000, 0x3' "$PERF_GEN" >/dev/null 2>&1; then
        log "gpu shade strip failed"
        echo "gpu shade strip failed" >&2
        exit 1
      fi
      log "gpu shade lock off"
    else
      log "gpu shade lock on"
    fi
    if [ "$CPU" = "off" ]; then
      # Status-bar pull on sun raises the CPU clusters for 500 ms.
      awk '
        /Id="0x000011A0"/ { id = 1; type = 0; sun = 0 }
        id && /Type="1"/ { type = 1 }
        id && /Target="sun"/ { sun = 1 }
        id && type && sun {
          gsub(/0x40C00000,[[:space:]]*0x1,[[:space:]]*/, "")
          gsub(/0x40804000,[[:space:]]*2745,[[:space:]]*/, "")
          gsub(/0x40804200,[[:space:]]*2649,[[:space:]]*/, "")
          gsub(/0x40800000,[[:space:]]*1996,[[:space:]]*/, "")
          gsub(/0x40800200,[[:space:]]*1689,[[:space:]]*/, "")
        }
        /\/>/ { id = 0; type = 0; sun = 0 }
        { print }
      ' "$PERF_GEN" > "$PERF_GEN.tmp" && mv -f "$PERF_GEN.tmp" "$PERF_GEN"
      if awk '
        /Id="0x000011A0"/ { id = 1; type = 0; sun = 0 }
        id && /Type="1"/ { type = 1 }
        id && /Target="sun"/ { sun = 1 }
        id && type && sun && /2745|0x40800000|0x40C00000/ { found = 1 }
        /\/>/ { id = 0; type = 0; sun = 0 }
        END { exit found ? 0 : 1 }
      ' "$PERF_GEN"; then
        log "cpu shade strip failed"
        echo "cpu shade strip failed" >&2
        exit 1
      fi
      log "cpu shade boost off"
    else
      log "cpu shade boost on"
    fi
    if [ "$GPUR" = "off" ]; then
      # Recents on sun: gesture animation floors GPU at level 5, landscape pins level 2.
      awk '
        /Id="0x000011A0"/ { id = 1; type = 0; sun = 0 }
        id && /Type="10"/ { type = 10 }
        id && /Type="11"/ { type = 11 }
        id && /Target="sun"/ { sun = 1 }
        id && type == 11 && sun {
          gsub(/0x42804000,[[:space:]]*0x5,[[:space:]]*0[Xx]42808000,[[:space:]]*0x2,[[:space:]]*/, "")
        }
        id && type == 10 && sun {
          gsub(/0x42804000,[[:space:]]*0x2,[[:space:]]*0[Xx]42808000,[[:space:]]*0x2,[[:space:]]*/, "")
        }
        /\/>/ { id = 0; type = 0; sun = 0 }
        { print }
      ' "$PERF_GEN" > "$PERF_GEN.tmp" && mv -f "$PERF_GEN.tmp" "$PERF_GEN"
      if awk '
        /Id="0x000011A0"/ { id = 1; type = 0; sun = 0 }
        id && /Type="10"/ { type = 10 }
        id && /Type="11"/ { type = 11 }
        id && /Target="sun"/ { sun = 1 }
        id && type == 11 && sun && /0x42804000,[[:space:]]*0x5/ { found = 1 }
        id && type == 10 && sun && /0x42804000,[[:space:]]*0x2/ { found = 1 }
        /\/>/ { id = 0; type = 0; sun = 0 }
        END { exit found ? 0 : 1 }
      ' "$PERF_GEN"; then
        log "gpu recents strip failed"
        echo "gpu recents strip failed" >&2
        exit 1
      fi
      log "gpu recents lock off"
    else
      log "gpu recents lock on"
    fi
    if [ "$CPUR" = "off" ]; then
      # Recents on sun raises the CPU clusters for 500 ms. Landscape uses higher mins.
      awk '
        /Id="0x000011A0"/ { id = 1; type = 0; sun = 0 }
        id && /Type="10"/ { type = 10 }
        id && /Type="11"/ { type = 11 }
        id && /Target="sun"/ { sun = 1 }
        id && (type == 10 || type == 11) && sun {
          gsub(/0x40C00000,[[:space:]]*0x1,[[:space:]]*/, "")
          gsub(/0x40804000,[[:space:]]*2745,[[:space:]]*/, "")
          gsub(/0x40804200,[[:space:]]*2649,[[:space:]]*/, "")
        }
        id && type == 11 && sun {
          gsub(/0x40800000,[[:space:]]*1996,[[:space:]]*/, "")
          gsub(/0x40800200,[[:space:]]*1689,[[:space:]]*/, "")
        }
        id && type == 10 && sun {
          gsub(/0x40800000,[[:space:]]*2400,[[:space:]]*/, "")
          gsub(/0x40800200,[[:space:]]*2246,[[:space:]]*/, "")
        }
        /\/>/ { id = 0; type = 0; sun = 0 }
        { print }
      ' "$PERF_GEN" > "$PERF_GEN.tmp" && mv -f "$PERF_GEN.tmp" "$PERF_GEN"
      if awk '
        /Id="0x000011A0"/ { id = 1; type = 0; sun = 0 }
        id && /Type="10"/ { type = 10 }
        id && /Type="11"/ { type = 11 }
        id && /Target="sun"/ { sun = 1 }
        id && type == 11 && sun && /1996|1689|2745|0x40C00000/ { found = 1 }
        id && type == 10 && sun && /2400|2246|2745|0x40C00000/ { found = 1 }
        /\/>/ { id = 0; type = 0; sun = 0 }
        END { exit found ? 0 : 1 }
      ' "$PERF_GEN"; then
        log "cpu recents strip failed"
        echo "cpu recents strip failed" >&2
        exit 1
      fi
      log "cpu recents boost off"
    else
      log "cpu recents boost on"
    fi
    if [ "$CPUU" = "off" ]; then
      # Unlock on sun raises the CPU clusters for 500 ms. GPU power levels stay.
      awk '
        /Id="0x000011A0"/ { id = 1; type = 0; sun = 0 }
        id && /Type="2"/ { type = 2 }
        id && /Target="sun"/ { sun = 1 }
        id && type == 2 && sun {
          gsub(/0x40C00000,[[:space:]]*0x1,[[:space:]]*/, "")
          gsub(/0x40804000,[[:space:]]*2745,[[:space:]]*/, "")
          gsub(/0x40804200,[[:space:]]*2649,[[:space:]]*/, "")
          gsub(/0x40800000,[[:space:]]*1996,[[:space:]]*/, "")
          gsub(/0x40800200,[[:space:]]*1689,[[:space:]]*/, "")
        }
        /\/>/ { id = 0; type = 0; sun = 0 }
        { print }
      ' "$PERF_GEN" > "$PERF_GEN.tmp" && mv -f "$PERF_GEN.tmp" "$PERF_GEN"
      if awk '
        /Id="0x000011A0"/ { id = 1; type = 0; sun = 0 }
        id && /Type="2"/ { type = 2 }
        id && /Target="sun"/ { sun = 1 }
        id && type == 2 && sun && /1996|1689|2745|0x40C00000/ { found = 1 }
        /\/>/ { id = 0; type = 0; sun = 0 }
        END { exit found ? 0 : 1 }
      ' "$PERF_GEN"; then
        log "cpu unlock strip failed"
        echo "cpu unlock strip failed" >&2
        exit 1
      fi
      log "cpu unlock boost off"
    else
      log "cpu unlock boost on"
    fi
    if [ "$CPUA" = "off" ]; then
      # Window animation on sun, including the unlock animation, raises CPU for 400 ms.
      awk '
        /Id="0x00001083"/ { id = 1; sun = 0 }
        id && /Target="sun"/ { sun = 1 }
        id && sun {
          gsub(/0x40804000,[[:space:]]*2745,[[:space:]]*/, "")
          gsub(/0x40804200,[[:space:]]*2649,[[:space:]]*/, "")
          gsub(/0x40800000,[[:space:]]*1996,[[:space:]]*/, "")
          gsub(/0x40800200,[[:space:]]*1689,[[:space:]]*/, "")
        }
        /\/>/ { id = 0; sun = 0 }
        { print }
      ' "$PERF_GEN" > "$PERF_GEN.tmp" && mv -f "$PERF_GEN.tmp" "$PERF_GEN"
      if awk '
        /Id="0x00001083"/ { id = 1; sun = 0 }
        id && /Target="sun"/ { sun = 1 }
        id && sun && /1996|1689|2745|2649/ { found = 1 }
        /\/>/ { id = 0; sun = 0 }
        END { exit found ? 0 : 1 }
      ' "$PERF_GEN"; then
        log "cpu anim strip failed"
        echo "cpu anim strip failed" >&2
        exit 1
      fi
      log "cpu anim boost off"
    else
      log "cpu anim boost on"
    fi
    chown root:root "$PERF_GEN" 2>/dev/null || true
    chmod 644 "$PERF_GEN"
    chcon u:object_r:vendor_configs_file:s0 "$PERF_GEN" 2>/dev/null || true
    if mount -o bind "$PERF_GEN" "$PERF_DST"; then
      log "perfboost mounted"
      perf_ok=1
    else
      log "perfboost mount failed"
    fi
  else
    log "perfboost copy failed"
  fi
  if { [ "$GPU" = "off" ] || [ "$CPU" = "off" ] || [ "$GPUR" = "off" ] || [ "$CPUR" = "off" ] || [ "$CPUU" = "off" ] || [ "$CPUA" = "off" ]; } && [ "$perf_ok" != 1 ]; then
    echo "shade mount failed" >&2
    exit 1
  fi
  if [ "$(getprop init.svc.perf2-hal-1-0 2>/dev/null)" = "running" ]; then
    setprop ctl.restart perf2-hal-1-0
    log "perf2-hal restarted"
  fi
fi

if [ "$CPU" = "off" ]; then
  echo "cpu shade off"
else
  echo "cpu shade on"
fi
if [ "$GPU" = "off" ]; then
  echo "gpu shade off"
else
  echo "gpu shade on"
fi
if [ "$CPUR" = "off" ]; then
  echo "cpu recents off"
else
  echo "cpu recents on"
fi
if [ "$GPUR" = "off" ]; then
  echo "gpu recents off"
else
  echo "gpu recents on"
fi
if [ "$CPUU" = "off" ]; then
  echo "cpu unlock off"
else
  echo "cpu unlock on"
fi
if [ "$CPUA" = "off" ]; then
  echo "cpu anim off"
else
  echo "cpu anim on"
fi
if [ "$overlay" = 1 ]; then
  echo "boost mounted"
else
  echo "boost off"
fi
exit 0
