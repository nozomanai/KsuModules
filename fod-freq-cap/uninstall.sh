#!/system/bin/sh
SO=/odm/lib64/hw/fingerprint.qcom_us.so
QC=/odm/lib64/libqc_hal.so
TOUCH=/odm/bin/hw/vendor.xiaomi.hw.touchfeature-service
nsenter -t 1 -m -- sh -c "
  for dst in \"$SO\" \"$QC\" \"$TOUCH\"; do
    while awk -v dst=\"\$dst\" '\$2 == dst { found = 1 } END { exit found ? 0 : 1 }' /proc/mounts; do
      umount \"\$dst\" || break
    done
  done
"
if pidof mfp-daemon >/dev/null 2>&1; then
  setprop ctl.restart mfp-daemon
fi
