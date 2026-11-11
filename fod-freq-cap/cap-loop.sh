#!/system/bin/sh
trap '' HUP
LITTLE=/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq
BIG=/sys/devices/system/cpu/cpu6/cpufreq/scaling_max_freq
PRESS=/sys/class/touch/touch_dev/fod_press_status
DOWN=/sys/bus/platform/devices/soc:xiaomi_fingerprint/fingerdown
LOG=/data/adb/fod-freq-cap.log
last=0
while true; do
  read -r p < "$PRESS" || p=0
  read -r d < "$DOWN" || d=0
  [ -n "$p" ] || p=0
  [ -n "$d" ] || d=0
  if [ "$p" != "0" ] || [ "$d" != "0" ]; then
    echo 1996800 > "$LITTLE" 2>/dev/null
    echo 2438400 > "$BIG" 2>/dev/null
    now=$(date +%s)
    if [ "$now" != "$last" ]; then
      echo "$(date '+%Y-%m-%d %H:%M:%S') hold big=2438400 little=1996800 press=$p down=$d" >> "$LOG"
      last=$now
    fi
  fi
  sleep 0.05
done
