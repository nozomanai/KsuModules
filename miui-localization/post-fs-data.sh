#!/system/bin/sh

LOG_STAGE=post-fs-data
MODDIR=${0%/*}
if [ -f "$MODDIR/log.sh" ]; then
  . "$MODDIR/log.sh"
else
  echo "log.sh missing script=$0" >> /data/adb/miui-localization.log
  exit 0
fi

MNT=/data/adb/miui-localization-mnt
SRC="$MODDIR/system/product"
if [ ! -d "$SRC/priv-app" ] && [ ! -d "$SRC/app" ] && [ ! -d "$SRC/data-app" ] && [ ! -d "$SRC/overlay" ]; then
  SRC="$MODDIR/product"
fi

log_msg "start moddir=$MODDIR src=$SRC"

stage_tree() {
  CATEGORY="$1"
  FROM="$SRC/$CATEGORY"
  if [ ! -d "$FROM" ]; then
    log_msg "stage skip reason=missing category=$CATEGORY"
    return 1
  fi
  FOUND=0
  for apk in "$FROM"/*/*.apk; do
    if [ ! -f "$apk" ]; then
      continue
    fi
    FOUND=1
    APP=$(basename "$(dirname "$apk")")
    BASE=$(basename "$apk")
    mkdir -p "$MNT/$CATEGORY/$APP"
    cp -a "$apk" "$MNT/$CATEGORY/$APP/$BASE"
    chmod 0755 "$MNT/$CATEGORY" "$MNT/$CATEGORY/$APP"
    chmod 0644 "$MNT/$CATEGORY/$APP/$BASE"
    chcon u:object_r:system_file:s0 "$MNT/$CATEGORY" "$MNT/$CATEGORY/$APP" "$MNT/$CATEGORY/$APP/$BASE"
    BYTES=$(wc -c < "$MNT/$CATEGORY/$APP/$BASE" | tr -d ' ')
    log_msg "staged category=$CATEGORY app=$APP bytes=$BYTES"
    LIBDIR="$(dirname "$apk")/lib"
    if [ -d "$LIBDIR" ]; then
      cp -a "$LIBDIR" "$MNT/$CATEGORY/$APP/lib"
      find "$MNT/$CATEGORY/$APP/lib" -type d -exec chmod 0755 {} \;
      find "$MNT/$CATEGORY/$APP/lib" -type f -exec chmod 0644 {} \;
      find "$MNT/$CATEGORY/$APP/lib" -exec chcon u:object_r:system_file:s0 {} \;
      log_msg "staged libs category=$CATEGORY app=$APP"
    fi
  done
  if [ "$FOUND" -eq 0 ]; then
    log_msg "stage skip reason=no-apk category=$CATEGORY"
    return 1
  fi
  return 0
}

mount_overlay() {
  CATEGORY="$1"
  TARGET="$2"
  MIEXT="$3"
  if [ ! -d "$MNT/$CATEGORY" ]; then
    log_msg "overlay skip reason=not-staged category=$CATEGORY"
    return 0
  fi
  if [ ! -d "$MIEXT" ] || [ ! -d "$TARGET" ]; then
    log_msg "overlay skip reason=target-missing category=$CATEGORY target=$TARGET miext=$MIEXT"
    return 0
  fi
  mount -t overlay overlay -o "lowerdir=${MNT}/${CATEGORY}:${MIEXT}:${TARGET},redirect_dir=on" "$TARGET"
  log_msg "overlay category=$CATEGORY exit=$? target=$TARGET"
}

mkdir -p "$MNT"
if grep -q " $MNT " /proc/mounts; then
  log_msg "tmpfs already mounted path=$MNT"
else
  mount -t tmpfs tmpfs "$MNT"
  log_msg "tmpfs mount path=$MNT exit=$?"
fi

ANY=0
for CATEGORY in priv-app app data-app overlay; do
  if stage_tree "$CATEGORY"; then
    ANY=1
  fi
done

if [ "$ANY" -eq 0 ]; then
  log_msg "result=skip reason=no-apk"
  exit 0
fi

mount_overlay priv-app /product/priv-app /mnt/vendor/mi_ext/product/priv-app
mount_overlay app /product/app /mnt/vendor/mi_ext/product/app
mount_overlay data-app /product/data-app /mnt/vendor/mi_ext/product/data-app
mount_overlay overlay /product/overlay /mnt/vendor/mi_ext/product/overlay

PERM_SRC="$SRC/etc/permissions"
if [ -d "$PERM_SRC" ] && [ -d /mnt/vendor/mi_ext/product/etc/permissions ] && [ -d /product/etc/permissions ]; then
  mkdir -p "$MNT/permissions"
  chmod 0755 "$MNT/permissions"
  chcon u:object_r:system_file:s0 "$MNT/permissions"
  PERM_N=0
  for xml in "$PERM_SRC"/*.xml; do
    if [ ! -f "$xml" ]; then
      continue
    fi
    BASE=$(basename "$xml")
    cp -a "$xml" "$MNT/permissions/$BASE"
    chmod 0644 "$MNT/permissions/$BASE"
    chcon u:object_r:system_file:s0 "$MNT/permissions/$BASE"
    log_msg "staged perm=$BASE"
    PERM_N=$((PERM_N + 1))
  done
  if [ "$PERM_N" -gt 0 ]; then
    mount -t overlay overlay -o "lowerdir=${MNT}/permissions:/mnt/vendor/mi_ext/product/etc/permissions:/product/etc/permissions,redirect_dir=on" /product/etc/permissions
    log_msg "overlay permissions exit=$? count=$PERM_N"
  else
    log_msg "permissions overlay skipped reason=no-xml"
  fi
else
  log_msg "permissions overlay skipped src=$PERM_SRC"
fi

for REL in \
  priv-app/MIUIQuickSearchBox/MIUIQuickSearchBox.apk \
  priv-app/VoiceAssistAndroidT/VoiceAssistAndroidT.apk \
  priv-app/Weather/Weather.apk \
  priv-app/Calendar/Calendar.apk \
  priv-app/MiuiGallery/MiuiGallery.apk \
  app/DeskClock/DeskClock.apk \
  app/VoiceTrigger/VoiceTrigger.apk \
  app/MIUIAiasstService/MIUIAiasstService.apk \
  app/XiaoaiRecommendation/XiaoaiRecommendation.apk \
  data-app/MIUIXiaoAiSpeechEngine/MIUIXiaoAiSpeechEngine.apk \
  data-app/VoiceAssistProxy/VoiceAssistProxy.apk \
  overlay/VoiceAssistAndroidOverlay/VoiceAssistAndroidOverlay.apk
do
  if [ -f "/product/$REL" ]; then
    log_msg "result=mounted path=/product/$REL"
  else
    log_msg "result=not-visible path=/product/$REL"
  fi
done

# The image APKs are signed by MIUI. This phone's copies are signed by Xiaomi.eu.
# PackageManager keeps the old record and then runs the old code against the new
# resources, which crashes weather. Drop those two records once so the next scan
# installs the mounted APKs. Calendar events live in CalendarProvider and stay.
reset_localized_packages() {
  MARK=/data/adb/miui-localization.reset
  if [ -f "$MARK" ]; then
    log_msg "package reset skip reason=already-done"
    return 0
  fi
  if [ ! -f "$MODDIR/reset-packages.awk" ]; then
    log_msg "package reset skip reason=awk-missing"
    return 0
  fi
  WAPK=/product/priv-app/Weather/Weather.apk
  CAPK=/product/priv-app/Calendar/Calendar.apk
  if [ ! -f "$WAPK" ] || [ ! -f "$CAPK" ]; then
    log_msg "package reset skip reason=apk-not-mounted"
    return 0
  fi
  WSIZE=$(wc -c < "$WAPK" | tr -d ' ')
  CSIZE=$(wc -c < "$CAPK" | tr -d ' ')
  if [ "$WSIZE" != "52074942" ] || [ "$CSIZE" != "16409126" ]; then
    log_msg "package reset skip reason=unexpected-size weather=$WSIZE calendar=$CSIZE"
    return 0
  fi
  if ! command -v abx2xml >/dev/null 2>&1 || ! command -v xml2abx >/dev/null 2>&1; then
    log_msg "package reset skip reason=abx-tool-missing"
    return 0
  fi
  PKGXML=/data/system/packages.xml
  if [ ! -f "$PKGXML" ]; then
    log_msg "package reset skip reason=packages-xml-missing"
    return 0
  fi
  WORK=/data/local/tmp/miui-loc-reset
  rm -rf "$WORK"
  mkdir -p "$WORK"
  if ! abx2xml "$PKGXML" "$WORK/packages.xml"; then
    log_msg "package reset fail reason=abx2xml"
    return 0
  fi
  awk -f "$MODDIR/reset-packages.awk" "$WORK/packages.xml" > "$WORK/packages-new.xml"
  if ! grep -q "</packages>" "$WORK/packages-new.xml"; then
    log_msg "package reset fail reason=missing-root-end"
    return 0
  fi
  if grep -q 'package name="com.miui.weather2"' "$WORK/packages-new.xml" || grep -q 'packageName="com.miui.weather2"' "$WORK/packages-new.xml"; then
    log_msg "package reset fail reason=weather-remains"
    return 0
  fi
  if ! grep -q 'name="android"' "$WORK/packages-new.xml"; then
    log_msg "package reset fail reason=android-missing"
    return 0
  fi
  if ! xml2abx "$WORK/packages-new.xml" "$WORK/packages.abx"; then
    log_msg "package reset fail reason=xml2abx"
    return 0
  fi
  if ! abx2xml "$WORK/packages.abx" "$WORK/packages-check.xml"; then
    log_msg "package reset fail reason=recheck"
    return 0
  fi
  if grep -q 'package name="com.miui.weather2"' "$WORK/packages-check.xml" || grep -q 'packageName="com.miui.weather2"' "$WORK/packages-check.xml"; then
    log_msg "package reset fail reason=weather-remains-after-abx"
    return 0
  fi
  cp -a "$PKGXML" /data/adb/miui-localization.packages.xml.bak
  cp -a "$WORK/packages.abx" "$PKGXML"
  chown system:system "$PKGXML"
  chmod 660 "$PKGXML"
  chcon u:object_r:system_data_file:s0 "$PKGXML"
  rm -rf /data/system/package_cache
  rm -f /data/dalvik-cache/arm64/product@priv-app@Weather@Weather.apk@* \
    /data/dalvik-cache/arm64/product@priv-app@Calendar@Calendar.apk@*
  rm -rf /data/user/0/com.miui.weather2 /data/user_de/0/com.miui.weather2 \
    /data/user/0/com.android.calendar /data/user_de/0/com.android.calendar \
    /data/misc/profiles/cur/0/com.miui.weather2 /data/misc/profiles/ref/com.miui.weather2 \
    /data/misc/profiles/cur/0/com.android.calendar /data/misc/profiles/ref/com.android.calendar
  touch "$MARK"
  log_msg "package reset done weather=$WSIZE calendar=$CSIZE"
}

# Gallery and clock are signed by Xiaomi.eu on this phone. Drop only those two
# records. Weather and calendar were already reset and must keep their data.
reset_gallery_clock() {
  MARK=/data/adb/miui-localization.reset-gallery-clock
  if [ -f "$MARK" ]; then
    log_msg "gallery clock reset skip reason=already-done"
    return 0
  fi
  if [ ! -f "$MODDIR/reset-packages-more.awk" ]; then
    log_msg "gallery clock reset skip reason=awk-missing"
    return 0
  fi
  GAPK=/product/priv-app/MiuiGallery/MiuiGallery.apk
  CAPK=/product/app/DeskClock/DeskClock.apk
  if [ ! -f "$GAPK" ] || [ ! -f "$CAPK" ]; then
    log_msg "gallery clock reset skip reason=apk-not-mounted"
    return 0
  fi
  GSIZE=$(wc -c < "$GAPK" | tr -d ' ')
  CSIZE=$(wc -c < "$CAPK" | tr -d ' ')
  if [ "$GSIZE" != "147648906" ] || [ "$CSIZE" != "17266533" ]; then
    log_msg "gallery clock reset skip reason=unexpected-size gallery=$GSIZE clock=$CSIZE"
    return 0
  fi
  if [ ! -f /product/priv-app/MiuiGallery/lib/arm64/libapp_gallery.so ]; then
    log_msg "gallery clock reset skip reason=gallery-lib-missing"
    return 0
  fi
  if ! command -v abx2xml >/dev/null 2>&1 || ! command -v xml2abx >/dev/null 2>&1; then
    log_msg "gallery clock reset skip reason=abx-tool-missing"
    return 0
  fi
  PKGXML=/data/system/packages.xml
  if [ ! -f "$PKGXML" ]; then
    log_msg "gallery clock reset skip reason=packages-xml-missing"
    return 0
  fi
  WORK=/data/local/tmp/miui-loc-reset-more
  rm -rf "$WORK"
  mkdir -p "$WORK"
  if ! abx2xml "$PKGXML" "$WORK/packages.xml"; then
    log_msg "gallery clock reset fail reason=abx2xml"
    return 0
  fi
  awk -f "$MODDIR/reset-packages-more.awk" "$WORK/packages.xml" > "$WORK/packages-new.xml"
  if ! grep -q "</packages>" "$WORK/packages-new.xml"; then
    log_msg "gallery clock reset fail reason=missing-root-end"
    return 0
  fi
  if grep -q 'package name="com.miui.gallery"' "$WORK/packages-new.xml" || grep -q 'packageName="com.miui.gallery"' "$WORK/packages-new.xml"; then
    log_msg "gallery clock reset fail reason=gallery-remains"
    return 0
  fi
  if grep -q 'package name="com.android.deskclock"' "$WORK/packages-new.xml" || grep -q 'packageName="com.android.deskclock"' "$WORK/packages-new.xml"; then
    log_msg "gallery clock reset fail reason=clock-remains"
    return 0
  fi
  if ! grep -q 'package name="com.miui.weather2"' "$WORK/packages-new.xml"; then
    log_msg "gallery clock reset fail reason=weather-missing"
    return 0
  fi
  if ! xml2abx "$WORK/packages-new.xml" "$WORK/packages.abx"; then
    log_msg "gallery clock reset fail reason=xml2abx"
    return 0
  fi
  if ! abx2xml "$WORK/packages.abx" "$WORK/packages-check.xml"; then
    log_msg "gallery clock reset fail reason=recheck"
    return 0
  fi
  if grep -q 'package name="com.miui.gallery"' "$WORK/packages-check.xml"; then
    log_msg "gallery clock reset fail reason=gallery-remains-after-abx"
    return 0
  fi
  cp -a "$PKGXML" /data/adb/miui-localization.packages-gallery-clock.xml.bak
  cp -a "$WORK/packages.abx" "$PKGXML"
  chown system:system "$PKGXML"
  chmod 660 "$PKGXML"
  chcon u:object_r:system_data_file:s0 "$PKGXML"
  rm -rf /data/system/package_cache
  rm -f /data/dalvik-cache/arm64/product@priv-app@MiuiGallery@MiuiGallery.apk@* \
    /data/dalvik-cache/arm64/product@app@DeskClock@DeskClock.apk@*
  rm -rf /data/user/0/com.miui.gallery /data/user_de/0/com.miui.gallery \
    /data/user/0/com.android.deskclock /data/user_de/0/com.android.deskclock \
    /data/misc/profiles/cur/0/com.miui.gallery /data/misc/profiles/ref/com.miui.gallery \
    /data/misc/profiles/cur/0/com.android.deskclock /data/misc/profiles/ref/com.android.deskclock
  touch "$MARK"
  log_msg "gallery clock reset done gallery=$GSIZE clock=$CSIZE"
}

# The domestic repeat list, including legal workdays, is hidden while
# miui.os.Build.IS_INTERNATIONAL_BUILD is true. The patched clock reports
# itself as domestic. Its signature is no longer the image signature, so the
# package record has to be dropped once. Alarm data is copied aside and put
# back after the new package is created.
reset_clock_repeat() {
  MARK=/data/adb/miui-localization.reset-clock-repeat
  if [ -f "$MARK" ]; then
    log_msg "clock repeat skip reason=already-done"
    return 0
  fi
  if [ ! -f "$MODDIR/reset-packages-clock.awk" ]; then
    log_msg "clock repeat skip reason=awk-missing"
    return 0
  fi
  CAPK=/product/app/DeskClock/DeskClock.apk
  if [ ! -f "$CAPK" ]; then
    log_msg "clock repeat skip reason=apk-not-mounted"
    return 0
  fi
  CSIZE=$(wc -c < "$CAPK" | tr -d ' ')
  if [ "$CSIZE" != "17366862" ]; then
    log_msg "clock repeat skip reason=unexpected-size clock=$CSIZE"
    return 0
  fi
  if ! command -v abx2xml >/dev/null 2>&1 || ! command -v xml2abx >/dev/null 2>&1; then
    log_msg "clock repeat skip reason=abx-tool-missing"
    return 0
  fi
  PKGXML=/data/system/packages.xml
  if [ ! -f "$PKGXML" ]; then
    log_msg "clock repeat skip reason=packages-xml-missing"
    return 0
  fi
  KEEP=/data/adb/miui-localization-clock-keep
  rm -rf "$KEEP"
  mkdir -p "$KEEP/ce" "$KEEP/de"
  if [ -d /data/user/0/com.android.deskclock ]; then
    cp -a /data/user/0/com.android.deskclock/. "$KEEP/ce/"
  fi
  if [ -d /data/user_de/0/com.android.deskclock ]; then
    cp -a /data/user_de/0/com.android.deskclock/. "$KEEP/de/"
  fi
  log_msg "clock repeat backup ce=$(find "$KEEP/ce" -type f 2>/dev/null | wc -l | tr -d ' ')"
  WORK=/data/local/tmp/miui-loc-reset-clock
  rm -rf "$WORK"
  mkdir -p "$WORK"
  if ! abx2xml "$PKGXML" "$WORK/packages.xml"; then
    log_msg "clock repeat fail reason=abx2xml"
    return 0
  fi
  awk -f "$MODDIR/reset-packages-clock.awk" "$WORK/packages.xml" > "$WORK/packages-new.xml"
  if ! grep -q "</packages>" "$WORK/packages-new.xml"; then
    log_msg "clock repeat fail reason=missing-root-end"
    return 0
  fi
  if grep -q 'package name="com.android.deskclock"' "$WORK/packages-new.xml" || grep -q 'packageName="com.android.deskclock"' "$WORK/packages-new.xml"; then
    log_msg "clock repeat fail reason=clock-remains"
    return 0
  fi
  if ! grep -q 'package name="com.miui.gallery"' "$WORK/packages-new.xml"; then
    log_msg "clock repeat fail reason=gallery-missing"
    return 0
  fi
  if ! xml2abx "$WORK/packages-new.xml" "$WORK/packages.abx"; then
    log_msg "clock repeat fail reason=xml2abx"
    return 0
  fi
  if ! abx2xml "$WORK/packages.abx" "$WORK/packages-check.xml"; then
    log_msg "clock repeat fail reason=recheck"
    return 0
  fi
  if grep -q 'package name="com.android.deskclock"' "$WORK/packages-check.xml"; then
    log_msg "clock repeat fail reason=clock-remains-after-abx"
    return 0
  fi
  cp -a "$PKGXML" /data/adb/miui-localization.packages-clock.xml.bak
  cp -a "$WORK/packages.abx" "$PKGXML"
  chown system:system "$PKGXML"
  chmod 660 "$PKGXML"
  chcon u:object_r:system_data_file:s0 "$PKGXML"
  rm -rf /data/system/package_cache
  rm -f /data/dalvik-cache/arm64/product@app@DeskClock@DeskClock.apk@*
  rm -rf /data/user/0/com.android.deskclock /data/user_de/0/com.android.deskclock \
    /data/misc/profiles/cur/0/com.android.deskclock /data/misc/profiles/ref/com.android.deskclock
  rm -f /data/adb/miui-localization.clock-restored
  touch "$MARK"
  log_msg "clock repeat reset done clock=$CSIZE"
}

# The localized SMS, dialer, and yellow page are gone. Drop their package
# records once so the stock copies are installed again. Message text stays
# in the telephony provider. Contact rows stay in the contacts provider.
drop_phone_localization() {
  MARK=/data/adb/miui-localization.drop-phone
  if [ -f "$MARK" ]; then
    log_msg "phone drop skip reason=already-done"
    return 0
  fi
  if [ ! -f "$MODDIR/reset-packages-phone.awk" ]; then
    log_msg "phone drop skip reason=awk-missing"
    return 0
  fi
  if ! command -v abx2xml >/dev/null 2>&1 || ! command -v xml2abx >/dev/null 2>&1; then
    log_msg "phone drop skip reason=abx-tool-missing"
    return 0
  fi
  PKGXML=/data/system/packages.xml
  if [ ! -f "$PKGXML" ]; then
    log_msg "phone drop skip reason=packages-xml-missing"
    return 0
  fi
  WORK=/data/local/tmp/miui-loc-drop-phone
  rm -rf "$WORK"
  mkdir -p "$WORK"
  if ! abx2xml "$PKGXML" "$WORK/packages.xml"; then
    log_msg "phone drop fail reason=abx2xml"
    return 0
  fi
  awk -f "$MODDIR/reset-packages-phone.awk" "$WORK/packages.xml" > "$WORK/packages-new.xml"
  if ! grep -q "</packages>" "$WORK/packages-new.xml"; then
    log_msg "phone drop fail reason=missing-root-end"
    return 0
  fi
  if grep -q 'package name="com.android.mms"' "$WORK/packages-new.xml" || grep -q 'package name="com.android.contacts"' "$WORK/packages-new.xml" || grep -q 'package name="com.android.incallui"' "$WORK/packages-new.xml" || grep -q 'package name="com.miui.yellowpage"' "$WORK/packages-new.xml"; then
    log_msg "phone drop fail reason=package-remains"
    return 0
  fi
  if ! grep -q 'package name="com.android.deskclock"' "$WORK/packages-new.xml"; then
    log_msg "phone drop fail reason=clock-missing"
    return 0
  fi
  if ! xml2abx "$WORK/packages-new.xml" "$WORK/packages.abx"; then
    log_msg "phone drop fail reason=xml2abx"
    return 0
  fi
  if ! abx2xml "$WORK/packages.abx" "$WORK/packages-check.xml"; then
    log_msg "phone drop fail reason=recheck"
    return 0
  fi
  if grep -q 'package name="com.android.mms"' "$WORK/packages-check.xml"; then
    log_msg "phone drop fail reason=mms-remains-after-abx"
    return 0
  fi
  cp -a "$PKGXML" /data/adb/miui-localization.packages-phone-drop.xml.bak
  cp -a "$WORK/packages.abx" "$PKGXML"
  chown system:system "$PKGXML"
  chmod 660 "$PKGXML"
  chcon u:object_r:system_data_file:s0 "$PKGXML"
  rm -rf /data/system/package_cache
  rm -f /data/dalvik-cache/arm64/product@priv-app@Mms@Mms.apk@* \
    /data/dalvik-cache/arm64/product@priv-app@Contacts@Contacts.apk@* \
    /data/dalvik-cache/arm64/product@priv-app@InCallUI@InCallUI.apk@* \
    /data/dalvik-cache/arm64/product@priv-app@MIUIYellowPage@MIUIYellowPage.apk@*
  rm -rf /data/user/0/com.android.mms /data/user_de/0/com.android.mms \
    /data/user/0/com.android.contacts /data/user_de/0/com.android.contacts \
    /data/user/0/com.android.incallui /data/user_de/0/com.android.incallui \
    /data/user/0/com.miui.yellowpage /data/user_de/0/com.miui.yellowpage \
    /data/misc/profiles/cur/0/com.android.mms /data/misc/profiles/ref/com.android.mms \
    /data/misc/profiles/cur/0/com.android.contacts /data/misc/profiles/ref/com.android.contacts \
    /data/misc/profiles/cur/0/com.android.incallui /data/misc/profiles/ref/com.android.incallui \
    /data/misc/profiles/cur/0/com.miui.yellowpage /data/misc/profiles/ref/com.miui.yellowpage
  touch "$MARK"
  log_msg "phone drop done"
}

reset_localized_packages
reset_gallery_clock
reset_clock_repeat
drop_phone_localization
