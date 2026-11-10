#!/system/bin/sh
# Re-apply for 60 seconds after boot. HyperOS sometimes clears these shortly
# after the early property set.

MODDIR=${0%/*}
LOGFILE=/data/adb/keep-adb.log
FAILS=0

log_msg() {
  _ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)
  [ -z "$_ts" ] && _ts=-
  mkdir -p /data/adb 2>/dev/null
  if [ -f "$LOGFILE" ]; then
    _size=$(wc -c < "$LOGFILE" 2>/dev/null | tr -d ' ')
    if [ -n "$_size" ] && [ "$_size" -gt 262144 ]; then
      tail -c 131072 "$LOGFILE" > "${LOGFILE}.tmp" 2>/dev/null && mv -f "${LOGFILE}.tmp" "$LOGFILE"
      echo "----- log trimmed -----" >> "$LOGFILE"
    fi
  fi
  echo "$_ts service $1" >> "$LOGFILE" 2>/dev/null
}

global_now() {
  settings get global "$1" 2>/dev/null | tr -d '\r'
}

ensure_global() {
  _key=$1
  _want=$2
  _cur=$(global_now "$_key")
  [ "$_cur" = "$_want" ] && return 0
  settings put global "$_key" "$_want" >/dev/null 2>&1
  _cur=$(global_now "$_key")
  if [ "$_cur" = "$_want" ]; then
    log_msg "set global ${_key}=${_want}"
    return 0
  fi
  return 1
}

ensure_adbinput() {
  _cur=$(getprop persist.security.adbinput 2>/dev/null | tr -d '\r')
  [ "$_cur" = "1" ] && return 0
  resetprop persist.security.adbinput 1 2>/dev/null
  _cur=$(getprop persist.security.adbinput 2>/dev/null | tr -d '\r')
  if [ "$_cur" != "1" ]; then
    resetprop -n persist.security.adbinput 1 2>/dev/null
    _cur=$(getprop persist.security.adbinput 2>/dev/null | tr -d '\r')
  fi
  [ "$_cur" = "1" ] || return 1
  log_msg "set persist.security.adbinput=1"
  _stamp=$(date +%s 2>/dev/null)
  [ -z "$_stamp" ] && _stamp=1
  resetprop -n cache_key.package_info "$_stamp" 2>/dev/null
  _cache=$(getprop cache_key.package_info 2>/dev/null | tr -d '\r')
  if [ "$_cache" = "$_stamp" ]; then
    log_msg "invalidated cache_key.package_info"
  else
    log_msg "cache_key.package_info not updated"
  fi
  return 0
}

ensure_not_disabled() {
  _name=$1
  _cur=$(getprop "$_name" 2>/dev/null | tr -d '\r')
  [ "$_cur" = "1" ] || return 0
  resetprop "$_name" 0 2>/dev/null
  _cur=$(getprop "$_name" 2>/dev/null | tr -d '\r')
  if [ "$_cur" = "0" ]; then
    log_msg "set ${_name}=0"
    return 0
  fi
  return 1
}

apply_once() {
  _ok=0
  ensure_global development_settings_enabled 1 || _ok=1
  ensure_global adb_enabled 1 || _ok=1
  ensure_global adb_allowed_connection_time 0 || _ok=1
  ensure_adbinput || _ok=1
  ensure_not_disabled sys.usb.adb.disabled || _ok=1
  ensure_not_disabled vendor.sys.usb.adb.disabled || _ok=1
  if [ "$_ok" -eq 0 ]; then
    FAILS=0
    return 0
  fi
  FAILS=$((FAILS + 1))
  if [ "$FAILS" -eq 1 ] || [ $((FAILS % 30)) -eq 0 ]; then
    log_msg "pending dev=$(global_now development_settings_enabled) adb=$(global_now adb_enabled) timeout=$(global_now adb_allowed_connection_time) adbinput=$(getprop persist.security.adbinput 2>/dev/null | tr -d '\r')"
  fi
  return 1
}

i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
  i=$((i + 1))
  if [ "$i" -ge 180 ]; then
    break
  fi
  sleep 1
done

log_msg "start"
n=0
while [ "$n" -lt 12 ]; do
  apply_once
  n=$((n + 1))
  [ "$n" -ge 12 ] && break
  sleep 5
done
log_msg "stop"
