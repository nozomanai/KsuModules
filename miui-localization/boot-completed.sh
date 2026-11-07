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

log_msg "start script=$0 moddir=$MODDIR"

install_if_needed() {
  PKG="$1"
  PATHS=$(pm path --user 0 "$PKG" 2>/dev/null)
  log_msg "pm path --user 0 $PKG => ${PATHS:-<empty>}"
  if [ -n "$PATHS" ]; then
    log_msg "result=noop reason=already-installed package=$PKG"
    return 0
  fi
  pm install-existing --user 0 "$PKG" >/dev/null 2>&1
  RC=$?
  log_msg "pm install-existing --user 0 $PKG exit=$RC"
  AFTER=$(pm path --user 0 "$PKG" 2>/dev/null)
  log_msg "after install-existing path=${AFTER:-<empty>}"
}

install_if_needed com.android.quicksearchbox
install_if_needed com.miui.voiceassist
install_if_needed com.miui.weather2
install_if_needed com.android.calendar
install_if_needed com.miui.gallery
install_if_needed com.android.deskclock
install_if_needed com.miui.voicetrigger
install_if_needed com.xiaomi.mibrain.speech
install_if_needed com.xiaomi.aiasst.service
install_if_needed com.xiaomi.aireco
install_if_needed com.miui.voiceassistProxy
install_if_needed com.miui.voiceassistoverlay

restore_clock_data() {
  KEEP=/data/adb/miui-localization-clock-keep
  DONE=/data/adb/miui-localization.clock-restored
  if [ -f "$DONE" ] || [ ! -d "$KEEP/ce" ]; then
    return 0
  fi
  DEST=/data/user/0/com.android.deskclock
  if [ ! -d "$DEST" ]; then
    log_msg "clock restore skip reason=data-dir-missing"
    return 0
  fi
  OWN=$(stat -c '%u:%g' "$DEST" 2>/dev/null)
  cp -a "$KEEP/ce/." "$DEST/"
  if [ -d "$KEEP/de" ] && [ -d /data/user_de/0/com.android.deskclock ]; then
    cp -a "$KEEP/de/." /data/user_de/0/com.android.deskclock/
    chown -R "$OWN" /data/user_de/0/com.android.deskclock
    restorecon -R /data/user_de/0/com.android.deskclock >/dev/null 2>&1
  fi
  chown -R "$OWN" "$DEST"
  restorecon -R "$DEST" >/dev/null 2>&1
  am force-stop com.android.deskclock >/dev/null 2>&1
  touch "$DONE"
  log_msg "clock restore done owner=$OWN"
}

restore_clock_data

VA=$(pm path --user 0 com.miui.voiceassist 2>/dev/null)
if [ -z "$VA" ]; then
  log_msg "assistant skip reason=voiceassist-not-installed"
  exit 0
fi

MARK=/data/adb/miui-localization.assistant
if [ -f "$MARK" ]; then
  log_msg "assistant skip reason=already-applied mark=$MARK"
  exit 0
fi

TARGET="com.miui.voiceassist/com.xiaomi.voiceassistant.AssistInteractionService"
PREV=$(settings get secure voice_interaction_service 2>/dev/null)
cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.miui.voiceassist >/dev/null 2>&1
ROLE_RC=$?
settings put secure assistant "$TARGET"
SET_A=$?
settings put secure voice_interaction_service "$TARGET"
SET_V=$?
log_msg "assistant previous=${PREV:-<empty>} role_exit=$ROLE_RC assistant_exit=$SET_A voice_exit=$SET_V target=$TARGET"
if [ "$SET_A" -eq 0 ] && [ "$SET_V" -eq 0 ]; then
  touch "$MARK"
  log_msg "assistant mark written path=$MARK"
fi
exit 0
