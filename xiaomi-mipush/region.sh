#!/system/bin/sh
# Write the markers the China framework reads on startup.
# Bytes are exactly "China" and "CN", with no trailing newline.

LOG_STAGE=${LOG_STAGE:-region}
MODDIR=${0%/*}
if [ -f "$MODDIR/log.sh" ]; then
  . "$MODDIR/log.sh"
elif [ -f /data/adb/modules/xiaomi-mipush/log.sh ]; then
  MODDIR=/data/adb/modules/xiaomi-mipush
  . "$MODDIR/log.sh"
else
  echo "log.sh missing script=$0" >> /data/adb/xiaomi-mipush.log
  exit 0
fi

DATA=/data/user/0/com.xiaomi.xmsf
FILES=$DATA/files
REGION=$FILES/mipush_region
COUNTRY=$FILES/mipush_country_code

_apply_region_locked() {
  if [ ! -d "$DATA" ]; then
    log_msg "region skip reason=data-dir-missing"
    return 0
  fi
  OWN=$(stat -c '%u:%g' "$DATA" 2>/dev/null)
  CTX=$(stat -c '%C' "$DATA" 2>/dev/null)
  if [ -z "$OWN" ]; then
    log_msg "region skip reason=stat-failed"
    return 0
  fi
  if [ ! -d "$FILES" ]; then
    mkdir -p "$FILES"
    chown "$OWN" "$FILES"
    chmod 0771 "$FILES"
    if [ -n "$CTX" ]; then
      chcon "$CTX" "$FILES"
    fi
    log_msg "region created files dir"
  fi
  PREV_R=$(cat "$REGION" 2>/dev/null)
  PREV_C=$(cat "$COUNTRY" 2>/dev/null)
  printf '%s' 'China' > "$REGION"
  printf '%s' 'CN' > "$COUNTRY"
  chown "$OWN" "$REGION" "$COUNTRY"
  chmod 0600 "$REGION" "$COUNTRY"
  if [ -n "$CTX" ]; then
    chcon "$CTX" "$REGION" "$COUNTRY"
  fi
  NOW_R=$(cat "$REGION" 2>/dev/null)
  NOW_C=$(cat "$COUNTRY" 2>/dev/null)
  log_msg "region previous=${PREV_R:-<empty>}/${PREV_C:-<empty>} now=${NOW_R:-<empty>}/${NOW_C:-<empty>} owner=$OWN"
  if [ "$NOW_R" != "China" ] || [ "$NOW_C" != "CN" ]; then
    log_msg "region write failed"
    return 0
  fi
  if [ "$PREV_R" = "China" ] && [ "$PREV_C" = "CN" ]; then
    log_msg "region unchanged"
  fi
}

network_validated() {
  LINE=$(dumpsys connectivity 2>/dev/null | grep 'Active default network:' | head -n 1)
  NET_ID=$(printf '%s' "$LINE" | tr -cd '0-9')
  if [ -z "$NET_ID" ]; then
    return 1
  fi
  dumpsys connectivity 2>/dev/null | grep "network{$NET_ID}" | grep -q 'VALIDATED'
}

restart_when_validated() {
  if command -v flock >/dev/null 2>&1; then
    exec 8>/data/adb/xiaomi-mipush.validated.lock
    flock 8
  fi
  BOOT=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
  MARK=/data/adb/xiaomi-mipush.validated
  if [ -n "$BOOT" ] && [ "$(cat "$MARK" 2>/dev/null)" = "$BOOT" ]; then
    log_msg "validated restart skip reason=already-done"
  else
    _n=0
    while [ "$_n" -lt 180 ]; do
      if network_validated; then
        am force-stop com.xiaomi.xmsf >/dev/null 2>&1
        log_msg "validated restart net=$NET_ID exit=$?"
        if [ -n "$BOOT" ]; then
          printf '%s' "$BOOT" > "$MARK"
        fi
        break
      fi
      sleep 10
      _n=$((_n + 1))
    done
    if [ "$_n" -ge 180 ]; then
      log_msg "validated restart timeout"
    fi
  fi
  if command -v flock >/dev/null 2>&1; then
    exec 8>&-
  fi
}

apply_region() {
  if command -v flock >/dev/null 2>&1; then
    exec 9>/data/adb/xiaomi-mipush.apply.lock
    flock 9
  fi
  _apply_region_locked
  _rc=$?
  if command -v flock >/dev/null 2>&1; then
    exec 9>&-
  fi
  return $_rc
}

wait_and_apply() {
  _i=0
  while [ "$_i" -lt 30 ]; do
    if [ -d "$DATA" ]; then
      break
    fi
    sleep 2
    _i=$((_i + 1))
  done
  if [ ! -d "$DATA" ]; then
    log_msg "region wait timeout"
  fi
  apply_region
}

case "$0" in
  *region.sh)
    wait_and_apply
    restart_when_validated
    ;;
esac
