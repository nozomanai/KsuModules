#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/apply.sh" >/dev/null 2>&1 || true
