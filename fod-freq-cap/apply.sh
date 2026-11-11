#!/system/bin/sh
MODDIR=${0%/*}
if [ "$1" != "--ns" ]; then
  exec nsenter -t 1 -m -- sh "$0" --ns "$2"
fi

LOG=/data/adb/fod-freq-cap.log
SO=/odm/lib64/hw/fingerprint.qcom_us.so
PATCHED="$MODDIR/fingerprint.qcom_us.so"
OFF=$((0x1c124))

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null || true
}

mounted() {
  awk -v dst="$SO" '$2 == dst { found = 1 } END { exit found ? 0 : 1 }' /proc/mounts
}

word() {
  dd if="$1" bs=1 skip="$2" count=4 2>/dev/null | od -An -tu4 | tr -d ' \n'
}

while mounted; do
  umount "$SO" || umount -l "$SO" || break
done

if [ ! -f "$SO" ]; then
  log "missing $SO"
  echo "missing library" >&2
  exit 1
fi

cp -f "$SO" "$PATCHED" || exit 1
sig=$(dd if="$PATCHED" bs=1 skip="$OFF" count=12 2>/dev/null | od -An -tx1 | tr -d ' \n')
if [ "$sig" != "0000c0400100000000408040" ]; then
  log "signature mismatch $sig"
  echo "signature mismatch" >&2
  exit 1
fi

# little cluster (cpu0): 1996800 Hz -> 1996 MHz
# prime cluster (cpu6): 2438400 Hz -> 2438 MHz
printf '\314\007\000\000' | dd of="$PATCHED" bs=1 seek=$((OFF + 12)) conv=notrunc >/dev/null 2>&1
printf '\206\011\000\000' | dd of="$PATCHED" bs=1 seek=$((OFF + 20)) conv=notrunc >/dev/null 2>&1
printf '\314\007\000\000' | dd of="$PATCHED" bs=1 seek=$((OFF + 28)) conv=notrunc >/dev/null 2>&1
printf '\206\011\000\000' | dd of="$PATCHED" bs=1 seek=$((OFF + 36)) conv=notrunc >/dev/null 2>&1

if [ "$(word "$PATCHED" $((OFF + 12)))" != "1996" ] || [ "$(word "$PATCHED" $((OFF + 20)))" != "2438" ]; then
  log "patch verify failed"
  echo "patch verify failed" >&2
  exit 1
fi

chown root:root "$PATCHED" 2>/dev/null || true
chmod 644 "$PATCHED"
chcon u:object_r:vendor_file:s0 "$PATCHED" 2>/dev/null || true
if ! mount -o bind "$PATCHED" "$SO"; then
  log "mount failed"
  echo "mount failed" >&2
  exit 1
fi
log "mounted"

# touchfeature-service, on finger down, acquires its own perf lock for 200ms.
# Every cluster min and max in that list is 0xFFF, which is what still
# pulls the big cores to 4 GHz.
TOUCH=/odm/bin/hw/vendor.xiaomi.hw.touchfeature-service
TOUCH_OUT="$MODDIR/touchfeature-service"
TOUCH_OFF=$((0x383b0))
while awk -v dst="$TOUCH" '$2 == dst { found = 1 } END { exit found ? 0 : 1 }' /proc/mounts; do
  umount "$TOUCH" || umount -l "$TOUCH" || break
done
cp -f "$TOUCH" "$TOUCH_OUT" || exit 1
tsig=$(dd if="$TOUCH_OUT" bs=1 skip="$TOUCH_OFF" count=12 2>/dev/null | od -An -tx1 | tr -d ' \n')
if [ "$tsig" != "0000c0400100000000408040" ]; then
  log "touchfeature signature mismatch $tsig"
  echo "touchfeature signature mismatch" >&2
  exit 1
fi
# +12/+20 cluster0 max/cluster1 max -> 1996; +28/+36 prime and cluster3 max -> 2438
# +44/+52 mins for cluster0/1; +60/+68 mins for prime/cluster3
printf '\314\007\000\000' | dd of="$TOUCH_OUT" bs=1 seek=$((TOUCH_OFF + 12)) conv=notrunc >/dev/null 2>&1
printf '\314\007\000\000' | dd of="$TOUCH_OUT" bs=1 seek=$((TOUCH_OFF + 20)) conv=notrunc >/dev/null 2>&1
printf '\206\011\000\000' | dd of="$TOUCH_OUT" bs=1 seek=$((TOUCH_OFF + 28)) conv=notrunc >/dev/null 2>&1
printf '\206\011\000\000' | dd of="$TOUCH_OUT" bs=1 seek=$((TOUCH_OFF + 36)) conv=notrunc >/dev/null 2>&1
printf '\314\007\000\000' | dd of="$TOUCH_OUT" bs=1 seek=$((TOUCH_OFF + 44)) conv=notrunc >/dev/null 2>&1
printf '\314\007\000\000' | dd of="$TOUCH_OUT" bs=1 seek=$((TOUCH_OFF + 52)) conv=notrunc >/dev/null 2>&1
printf '\206\011\000\000' | dd of="$TOUCH_OUT" bs=1 seek=$((TOUCH_OFF + 60)) conv=notrunc >/dev/null 2>&1
printf '\206\011\000\000' | dd of="$TOUCH_OUT" bs=1 seek=$((TOUCH_OFF + 68)) conv=notrunc >/dev/null 2>&1
if [ "$(word "$TOUCH_OUT" $((TOUCH_OFF + 28)))" != "2438" ]; then
  log "touchfeature patch verify failed"
  echo "touchfeature patch verify failed" >&2
  exit 1
fi
chown root:shell "$TOUCH_OUT" 2>/dev/null || true
chmod 755 "$TOUCH_OUT"
chcon u:object_r:hal_touchfeature_xiaomi_default_exec:s0 "$TOUCH_OUT" 2>/dev/null || true
if ! mount -o bind "$TOUCH_OUT" "$TOUCH"; then
  log "touchfeature mount failed"
  echo "touchfeature mount failed" >&2
  exit 1
fi
log "touchfeature mounted"
if pidof vendor.xiaomi.hw.touchfeature-service >/dev/null 2>&1; then
  setprop ctl.restart touchfeature-service
  sleep 1
  tpid=$(pidof vendor.xiaomi.hw.touchfeature-service)
  log "restarted touchfeature pid=$tpid $(grep touchfeature-service /proc/$tpid/maps | head -1)"
fi

if [ -w /sys/module/metis/parameters/mi_fboost_enable ]; then
  echo 0 > /sys/module/metis/parameters/mi_fboost_enable
  log "mi_fboost_enable=$(cat /sys/module/metis/parameters/mi_fboost_enable)"
fi

# Finger-down goes through QfpPerfLock::setPerfLock in libqc_hal.so.
# That list still had 0xFFF. perfd multiplies the value by 1000 and picks
# the next available frequency, so 4095 becomes 4204800 on the prime cluster.
QC=/odm/lib64/libqc_hal.so
QC_OUT="$MODDIR/libqc_hal.so"
QC_OFF=$((0x3f250))
while awk -v dst="$QC" '$2 == dst { found = 1 } END { exit found ? 0 : 1 }' /proc/mounts; do
  umount "$QC" || umount -l "$QC" || break
done
if [ ! -f "$QC" ]; then
  log "missing $QC"
  echo "missing libqc_hal" >&2
  exit 1
fi
cp -f "$QC" "$QC_OUT" || exit 1
qsig=$(dd if="$QC_OUT" bs=1 skip="$QC_OFF" count=12 2>/dev/null | od -An -tx1 | tr -d ' \n')
if [ "$qsig" != "0000c0400100000000408040" ] || [ "$(word "$QC_OUT" $((QC_OFF + 16)))" != "1082147328" ]; then
  log "libqc_hal signature mismatch $qsig"
  echo "libqc_hal signature mismatch" >&2
  exit 1
fi
printf '\314\007\000\000' | dd of="$QC_OUT" bs=1 seek=$((QC_OFF + 12)) conv=notrunc >/dev/null 2>&1
printf '\206\011\000\000' | dd of="$QC_OUT" bs=1 seek=$((QC_OFF + 20)) conv=notrunc >/dev/null 2>&1
printf '\314\007\000\000' | dd of="$QC_OUT" bs=1 seek=$((QC_OFF + 28)) conv=notrunc >/dev/null 2>&1
printf '\206\011\000\000' | dd of="$QC_OUT" bs=1 seek=$((QC_OFF + 36)) conv=notrunc >/dev/null 2>&1
if [ "$(word "$QC_OUT" $((QC_OFF + 20)))" != "2438" ] || [ "$(word "$QC_OUT" $((QC_OFF + 12)))" != "1996" ]; then
  log "libqc_hal patch verify failed"
  echo "libqc_hal patch verify failed" >&2
  exit 1
fi
chown root:root "$QC_OUT" 2>/dev/null || true
chmod 644 "$QC_OUT"
chcon u:object_r:vendor_file:s0 "$QC_OUT" 2>/dev/null || true
if ! mount -o bind "$QC_OUT" "$QC"; then
  log "libqc_hal mount failed"
  echo "libqc_hal mount failed" >&2
  exit 1
fi
log "libqc_hal mounted"
# late_start brings mfp-daemon up before service.sh. The process keeps the
# library it mapped, so a later bind does nothing until the daemon restarts.
if pidof mfp-daemon >/dev/null 2>&1; then
  setprop ctl.restart mfp-daemon
  sleep 1
  pid=$(pidof mfp-daemon)
  log "restarted pid=$pid $(grep 'libqc_hal.so' /proc/$pid/maps | head -1)"
fi
if [ -f "$MODDIR/cap-loop.pid" ]; then
  kill "$(cat "$MODDIR/cap-loop.pid")" 2>/dev/null || true
  rm -f "$MODDIR/cap-loop.pid"
fi
echo "fod freq cap applied"
exit 0
