#!/system/bin/sh
# Stage the China framework and bind it over the system app directory.
# miui-localization mounts a new overlay on /product/app from its own
# post-fs-data.sh. A bind done before that overlay is hidden, so the final
# bind is repeated from post-mount.d after every module script.

APP_SRC="$MODDIR/system/product/app/XiaomiServiceFramework"
TARGET=/product/app/XiaomiServiceFramework
MNT=/data/adb/xiaomi-mipush-mnt

stage_framework() {
  if [ ! -d "$APP_SRC" ]; then
    log_msg "stage skip reason=apk-dir-missing"
    return 1
  fi
  mkdir -p "$MNT"
  if grep -q " $MNT " /proc/mounts; then
    log_msg "tmpfs already mounted path=$MNT"
  else
    mount -t tmpfs tmpfs "$MNT"
    log_msg "tmpfs mount path=$MNT exit=$?"
  fi
  if [ ! -f "$MNT/XiaomiServiceFramework/XiaomiServiceFramework.apk" ]; then
    rm -rf "$MNT/XiaomiServiceFramework"
    mkdir -p "$MNT/XiaomiServiceFramework"
    cp -a "$APP_SRC"/. "$MNT/XiaomiServiceFramework/"
    chmod 0755 "$MNT" "$MNT/XiaomiServiceFramework"
    find "$MNT/XiaomiServiceFramework" -type d -exec chmod 0755 {} \;
    find "$MNT/XiaomiServiceFramework" -type f -exec chmod 0644 {} \;
    find "$MNT/XiaomiServiceFramework" -exec chcon u:object_r:system_file:s0 {} \;
    log_msg "staged into $MNT/XiaomiServiceFramework"
  fi
  return 0
}

framework_visible() {
  if [ ! -d "$APP_SRC" ] || [ ! -d "$TARGET" ]; then
    return 1
  fi
  for apk in "$APP_SRC"/*.apk; do
    if [ ! -f "$apk" ]; then
      return 1
    fi
    BASE=$(basename "$apk")
    WANT=$(wc -c < "$apk" | tr -d ' ')
    GOT=$(wc -c < "$TARGET/$BASE" 2>/dev/null | tr -d ' ')
    if [ "$WANT" != "$GOT" ]; then
      log_msg "not visible apk=$BASE want=$WANT got=${GOT:-missing}"
      return 1
    fi
  done
  return 0
}

mount_framework() {
  if [ ! -d "$TARGET" ]; then
    log_msg "mount skip reason=target-missing path=$TARGET"
    return 1
  fi
  if ! stage_framework; then
    return 1
  fi
  if framework_visible; then
    log_msg "mount already visible path=$TARGET"
    return 0
  fi
  mount -o bind "$MNT/XiaomiServiceFramework" "$TARGET"
  log_msg "bind path=$TARGET exit=$?"
  if framework_visible; then
    log_msg "mount visible path=$TARGET"
    return 0
  fi
  log_msg "mount failed path=$TARGET"
  return 1
}

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
