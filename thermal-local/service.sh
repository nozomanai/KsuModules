#!/system/bin/sh
MODDIR=${0%/*}
i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
  i=$((i + 1))
  if [ "$i" -ge 180 ]; then
    break
  fi
  sleep 1
done
sh "$MODDIR/apply.sh"
if [ -f "$MODDIR/remote.on" ]; then
  sh "$MODDIR/remote.sh" start
fi
