#!/system/bin/sh
# Hybrid Mount mounts product/app/XiaomiServiceFramework. The mount type
# comes from Hybrid Mount's own configuration.

APP_SRC="$MODDIR/product/app/XiaomiServiceFramework"

reset_xmsf_if_needed() {
  MARK=/data/adb/xiaomi-mipush.reset
  TOKEN=$(wc -c < "$APP_SRC/XiaomiServiceFramework.apk" | tr -d ' ')
  if [ -f "$MARK" ] && [ "$(cat "$MARK" 2>/dev/null)" = "$TOKEN" ]; then
    log_msg "package reset skip reason=already-done token=$TOKEN"
    return 0
  fi
  KEEP=/data/adb/xiaomi-mipush-xmsf-keep
  if [ ! -d "$KEEP" ] && [ -d /data/user/0/com.xiaomi.xmsf ]; then
    mkdir -p "$KEEP"
    cp -a /data/user/0/com.xiaomi.xmsf/. "$KEEP/"
    log_msg "xmsf backup path=$KEEP files=$(find "$KEEP" -type f 2>/dev/null | wc -l | tr -d ' ')"
  fi
  if drop_xmsf_record; then
    wipe_xmsf_data
    printf '%s' "$TOKEN" > "$MARK"
    log_msg "package reset done token=$TOKEN"
    return 0
  fi
  log_msg "package reset not applied"
  return 1
}
