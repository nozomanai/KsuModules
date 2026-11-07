#!/system/bin/sh
# Drop com.xiaomi.xmsf from packages.xml so PackageManager scans the mounted APK.
# Sourced by post-fs-data.sh. The China framework is signed by Xiaomi; this phone's
# copy was re-signed, and a mismatched record is ignored.

drop_xmsf_record() {
  AWK_FILE=${AWK_FILE:-$MODDIR/reset-packages.awk}
  if [ ! -f "$AWK_FILE" ]; then
    log_msg "package reset skip reason=awk-missing file=$AWK_FILE"
    return 1
  fi
  if ! command -v abx2xml >/dev/null 2>&1 || ! command -v xml2abx >/dev/null 2>&1; then
    log_msg "package reset skip reason=abx-tool-missing"
    return 1
  fi
  PKGXML=/data/system/packages.xml
  if [ ! -f "$PKGXML" ]; then
    log_msg "package reset skip reason=packages-xml-missing"
    return 1
  fi
  WORK=/data/local/tmp/xiaomi-mipush-reset
  rm -rf "$WORK"
  mkdir -p "$WORK"
  if ! abx2xml "$PKGXML" "$WORK/packages.xml"; then
    log_msg "package reset fail reason=abx2xml"
    return 1
  fi
  awk -f "$AWK_FILE" "$WORK/packages.xml" > "$WORK/packages-new.xml"
  if ! grep -q "</packages>" "$WORK/packages-new.xml"; then
    log_msg "package reset fail reason=missing-root-end"
    return 1
  fi
  if grep -q 'package name="com.xiaomi.xmsf"' "$WORK/packages-new.xml"; then
    log_msg "package reset fail reason=xmsf-remains"
    return 1
  fi
  if ! grep -q 'name="android"' "$WORK/packages-new.xml"; then
    log_msg "package reset fail reason=android-missing"
    return 1
  fi
  if ! xml2abx "$WORK/packages-new.xml" "$WORK/packages.abx"; then
    log_msg "package reset fail reason=xml2abx"
    return 1
  fi
  if ! abx2xml "$WORK/packages.abx" "$WORK/packages-check.xml"; then
    log_msg "package reset fail reason=recheck"
    return 1
  fi
  if grep -q 'package name="com.xiaomi.xmsf"' "$WORK/packages-check.xml"; then
    log_msg "package reset fail reason=xmsf-remains-after-abx"
    return 1
  fi
  cp -a "$PKGXML" /data/adb/xiaomi-mipush.packages.xml.bak
  cp -a "$WORK/packages.abx" "$PKGXML"
  chown system:system "$PKGXML"
  chmod 660 "$PKGXML"
  chcon u:object_r:system_data_file:s0 "$PKGXML"
  rm -rf "$WORK"
  log_msg "package reset written"
  return 0
}

wipe_xmsf_data() {
  rm -rf /data/system/package_cache
  rm -rf /data/user/0/com.xiaomi.xmsf /data/user_de/0/com.xiaomi.xmsf
  rm -rf /data/misc/profiles/cur/0/com.xiaomi.xmsf /data/misc/profiles/ref/com.xiaomi.xmsf
  rm -f /data/dalvik-cache/arm64/product@app@XiaomiServiceFramework@* \
    /data/misc/apexdata/com.android.art/dalvik-cache/arm64/product@app@XiaomiServiceFramework@*
  for dir in /data/app/com.xiaomi.xmsf-* /data/app/*/com.xiaomi.xmsf-*; do
    if [ -d "$dir" ]; then
      rm -rf "$dir"
      log_msg "removed updated apk dir=$dir"
    fi
  done
  log_msg "xmsf data cleared"
}
