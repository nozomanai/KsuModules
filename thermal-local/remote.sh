#!/system/bin/sh
export PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:$PATH
MODDIR=${0%/*}
PORT=8765
BUSYBOX=/data/adb/ksu/bin/busybox
PIDFILE="$MODDIR/httpd.pid"
FLAG="$MODDIR/remote.on"
WWW="$MODDIR/webroot"
LOG="$MODDIR/httpd.log"

if [ ! -d /data/vendor/thermal/config ] && [ "$2" != "--ns" ]; then
  exec nsenter -t 1 -m -- sh "$0" "$1" --ns
fi

urls() {
  ip -4 addr show scope global 2>/dev/null | awk '
    /inet / {
      split($2, a, "/")
      if (a[1] != "") print "http://" a[1] ":'"$PORT"'/"
    }
  '
}

running() {
  if [ ! -f "$PIDFILE" ]; then
    return 1
  fi
  kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null
}

start_server() {
  if running; then
    return 0
  fi
  if [ ! -x "$BUSYBOX" ]; then
    echo "busybox httpd missing" >&2
    return 1
  fi
  mkdir -p "$WWW/cgi-bin" "$MODDIR/generated"
  chmod 755 "$WWW" "$WWW/cgi-bin" "$WWW/cgi-bin/api" 2>/dev/null || true
  "$BUSYBOX" httpd -f -p "0.0.0.0:$PORT" -h "$WWW" >> "$LOG" 2>&1 &
  echo $! > "$PIDFILE"
  sleep 1
  if ! running; then
    echo "httpd failed" >&2
    rm -f "$PIDFILE"
    return 1
  fi
  touch "$FLAG"
  return 0
}

stop_server() {
  if [ -f "$PIDFILE" ]; then
    kill "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null || true
    rm -f "$PIDFILE"
  fi
  rm -f "$FLAG"
}

case "$1" in
  start)
    start_server || exit 1
    echo running
    urls
    ;;
  stop)
    stop_server
    echo stopped
    ;;
  status)
    if running; then
      echo running
      urls
    else
      echo stopped
    fi
    ;;
  *)
    echo "usage: remote.sh start|stop|status" >&2
    exit 1
    ;;
esac
