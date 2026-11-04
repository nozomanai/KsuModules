#!/system/bin/sh
# Shared logger. File: /data/adb/fp-study-toggle.log  Logcat tag: FpStudyToggle
LOGFILE=/data/adb/fp-study-toggle.log
LOG_TAG=FpStudyToggle

log_msg() {
  _msg="$1"
  _ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)
  [ -z "$_ts" ] && _ts=-
  _stage=${LOG_STAGE:-unknown}
  _line="$_ts $_stage $_msg"
  mkdir -p /data/adb 2>/dev/null
  if [ -f "$LOGFILE" ]; then
    _size=$(wc -c < "$LOGFILE" 2>/dev/null | tr -d ' ')
    if [ -n "$_size" ] && [ "$_size" -gt 262144 ]; then
      tail -c 131072 "$LOGFILE" > "${LOGFILE}.tmp" 2>/dev/null && mv -f "${LOGFILE}.tmp" "$LOGFILE"
      echo "----- log trimmed -----" >> "$LOGFILE"
    fi
  fi
  echo "$_line" >> "$LOGFILE" 2>/dev/null
  if command -v log >/dev/null 2>&1; then
    log -t "$LOG_TAG" "$_line" 2>/dev/null
  fi
  if command -v ui_print >/dev/null 2>&1; then
    _one=$(printf '%s' "$_msg" | tr '\n' ' ')
    ui_print "$_one"
  fi
}
