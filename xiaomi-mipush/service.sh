#!/system/bin/sh

LOG_STAGE=service
MODDIR=${0%/*}
if [ -f "$MODDIR/log.sh" ]; then
  . "$MODDIR/log.sh"
fi
log_msg "start"
"$MODDIR/region.sh"
exit 0
