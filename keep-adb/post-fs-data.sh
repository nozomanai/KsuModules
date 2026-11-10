#!/system/bin/sh
# Before zygote. miui-services checks persist.security.adbinput when deciding
# whether shell (uid 2000) may inject input. Set it before system_server starts.

MODDIR=${0%/*}
resetprop persist.security.adbinput 1

_ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)
[ -z "$_ts" ] && _ts=-
mkdir -p /data/adb 2>/dev/null
echo "$_ts post-fs-data adbinput=$(getprop persist.security.adbinput)" >> /data/adb/keep-adb.log 2>/dev/null
