#!/system/bin/sh

LOG_STAGE=customize
. "$MODPATH/log.sh"

log_msg "install start MODPATH=$MODPATH"
log_msg "version=$(grep_prop version "$MODPATH/module.prop" 2>/dev/null) versionCode=$(grep_prop versionCode "$MODPATH/module.prop" 2>/dev/null)"

# Skip an app when the same package is already installed.
# Updating this module keeps an app that the module itself already provides.
skip_if_installed() {
  PKG="$1"
  APP_DIR="$2"
  PERM_FILE="$3"
  APP_NAME=$(basename "$APP_DIR")

  log_msg "check package=$PKG app=$APP_NAME"

  if ! command -v pm >/dev/null 2>&1; then
    log_msg "result=keep reason=pm-not-found package=$PKG"
    return 0
  fi

  PATHS=$(pm path --user 0 "$PKG" 2>/dev/null)
  log_msg "pm path --user 0 $PKG => ${PATHS:-<empty>}"
  if [ -z "$PATHS" ]; then
    log_msg "result=include reason=not-installed package=$PKG dir=$APP_DIR"
    return 0
  fi

  OLD="/data/adb/modules/miui-localization"
  REL=""
  case "$APP_DIR" in
    */system/product/*)
      REL=${APP_DIR#*system/product/}
      ;;
    */product/*)
      REL=${APP_DIR#*product/}
      ;;
  esac
  if [ -n "$REL" ] && { [ -d "$OLD/product/$REL" ] || [ -d "$OLD/system/product/$REL" ]; }; then
    log_msg "result=keep reason=already-provided-by-this-module package=$PKG rel=$REL"
    return 0
  fi

  log_msg "result=skip reason=already-installed package=$PKG"
  rm -rf "$APP_DIR"
  _rm_app=$?
  _rm_perm=skip
  if [ -n "$PERM_FILE" ]; then
    rm -f "$PERM_FILE"
    _rm_perm=$?
  fi
  log_msg "removed app_dir=$APP_DIR rc=$_rm_app perm=${PERM_FILE:-<none>} rc=$_rm_perm"
  _app_left=no
  _perm_left=no
  [ -e "$APP_DIR" ] && _app_left=yes
  if [ -n "$PERM_FILE" ] && [ -e "$PERM_FILE" ]; then
    _perm_left=yes
  fi
  if [ "$_app_left" = yes ] || [ "$_perm_left" = yes ]; then
    log_msg "result=skip-failed package=$PKG app_left=$_app_left perm_left=$_perm_left"
  fi
}

skip_if_installed com.android.quicksearchbox \
  "$MODPATH/system/product/priv-app/MIUIQuickSearchBox" \
  "$MODPATH/system/product/etc/permissions/privapp-permissions-miui-localization.xml"

skip_if_installed com.miui.voiceassist \
  "$MODPATH/system/product/priv-app/VoiceAssistAndroidT" \
  "$MODPATH/system/product/etc/permissions/privapp-permissions-miui-localization-voiceassist.xml"

skip_if_installed com.miui.voicetrigger \
  "$MODPATH/system/product/app/VoiceTrigger" \
  ""

skip_if_installed com.xiaomi.aiasst.service \
  "$MODPATH/system/product/app/MIUIAiasstService" \
  ""

skip_if_installed com.xiaomi.aireco \
  "$MODPATH/system/product/app/XiaoaiRecommendation" \
  ""

skip_if_installed com.xiaomi.mibrain.speech \
  "$MODPATH/system/product/data-app/MIUIXiaoAiSpeechEngine" \
  ""

skip_if_installed com.miui.voiceassistProxy \
  "$MODPATH/system/product/data-app/VoiceAssistProxy" \
  ""

skip_if_installed com.miui.voiceassistoverlay \
  "$MODPATH/system/product/overlay/VoiceAssistAndroidOverlay" \
  ""

log_msg "keep package=com.miui.weather2 path=system/product/priv-app/Weather reason=always-overlay"
log_msg "keep package=com.android.calendar path=system/product/priv-app/Calendar reason=always-overlay"

log_msg "install end"
