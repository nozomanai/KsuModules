#!/system/bin/sh

LOG_STAGE=boot-completed
MODDIR=${0%/*}
if [ -f "$MODDIR/log.sh" ]; then
  . "$MODDIR/log.sh"
elif [ -f /data/adb/modules/miui-localization/log.sh ]; then
  . /data/adb/modules/miui-localization/log.sh
else
  echo "log.sh missing script=$0" >> /data/adb/miui-localization.log
  exit 0
fi

PKG=com.android.quicksearchbox
log_msg "start script=$0 moddir=$MODDIR"

PATHS=$(pm path --user 0 "$PKG" 2>/dev/null)
log_msg "pm path --user 0 $PKG => ${PATHS:-<empty>}"
if [ -n "$PATHS" ]; then
  log_msg "result=noop reason=already-installed package=$PKG"
  exit 0
fi

pm install-existing --user 0 "$PKG" >/dev/null 2>&1
RC=$?
log_msg "pm install-existing --user 0 $PKG exit=$RC"
AFTER=$(pm path --user 0 "$PKG" 2>/dev/null)
log_msg "after install-existing path=${AFTER:-<empty>}"
exit 0
