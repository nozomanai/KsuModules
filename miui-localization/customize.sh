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
  if [ -d "$OLD/product/priv-app/$APP_NAME" ] || [ -d "$OLD/system/product/priv-app/$APP_NAME" ]; then
    log_msg "result=keep reason=already-provided-by-this-module package=$PKG old=$OLD"
    return 0
  fi

  log_msg "result=skip reason=already-installed package=$PKG"
  rm -rf "$APP_DIR"
  _rm_app=$?
  rm -f "$PERM_FILE"
  _rm_perm=$?
  log_msg "removed app_dir=$APP_DIR rc=$_rm_app perm=$PERM_FILE rc=$_rm_perm"
  _app_left=no
  _perm_left=no
  [ -e "$APP_DIR" ] && _app_left=yes
  [ -e "$PERM_FILE" ] && _perm_left=yes
  if [ "$_app_left" = yes ] || [ "$_perm_left" = yes ]; then
    log_msg "result=skip-failed package=$PKG app_left=$_app_left perm_left=$_perm_left"
  fi
}

skip_if_installed com.android.quicksearchbox \
  "$MODPATH/system/product/priv-app/MIUIQuickSearchBox" \
  "$MODPATH/system/product/etc/permissions/privapp-permissions-miui-localization.xml"

log_msg "install end"
