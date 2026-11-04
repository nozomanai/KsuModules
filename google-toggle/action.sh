#!/system/bin/sh

LOG_STAGE=action
MODDIR=${0%/*}
if [ -f "$MODDIR/log.sh" ]; then
  . "$MODDIR/log.sh"
else
  log_msg() { echo "$1"; }
fi

# 能把服务重新拉起来的应用先停，partnersetup 和设置向导也在这批。
OFF_ORDER="
com.google.android.partnersetup
com.google.android.setupwizard
com.google.android.googlequicksearchbox
com.google.android.tts
com.android.hotwordenrollment.okgoogle
com.android.hotwordenrollment.xgoogle
com.google.android.inputmethod.latin
com.google.android.projection.gearhead
com.google.android.marvin.talkback
com.google.android.gms.location.history
com.google.android.syncadapters.contacts
com.google.android.syncadapters.calendar
com.google.android.configupdater
com.google.android.onetimeinitializer
com.google.android.feedback
com.google.android.apps.restore
com.google.android.printservice.recommendation
com.google.android.gms
com.google.android.gsf
com.android.vending
"

# 先恢复框架和商店，最后再放开设置向导和 partnersetup。
ON_ORDER="
com.google.android.gms
com.google.android.gsf
com.android.vending
com.google.android.gms.location.history
com.google.android.syncadapters.contacts
com.google.android.syncadapters.calendar
com.google.android.configupdater
com.google.android.onetimeinitializer
com.google.android.feedback
com.google.android.apps.restore
com.google.android.printservice.recommendation
com.google.android.googlequicksearchbox
com.google.android.tts
com.android.hotwordenrollment.okgoogle
com.android.hotwordenrollment.xgoogle
com.google.android.inputmethod.latin
com.google.android.projection.gearhead
com.google.android.marvin.talkback
com.google.android.setupwizard
com.google.android.partnersetup
"

pkg_state() {
  dumpsys package "$1" 2>/dev/null | awk -v u="$2:" '
    $1 == "User" && $2 == u {
      en = ""
      inst = ""
      n = split($0, f, " ")
      for (i = 1; i <= n; i++) {
        if (f[i] ~ /^enabled=/) {
          split(f[i], a, "=")
          en = a[2]
        } else if (f[i] ~ /^installed=/) {
          split(f[i], a, "=")
          inst = a[2]
        }
      }
      if (inst != "false" && en != "") {
        print en
        exit
      }
    }
  '
}

app_name() {
  case "$1" in
    com.google.android.gms) echo "Google Play 服务" ;;
    com.google.android.gsf) echo "Google 服务框架" ;;
    com.android.vending) echo "Google Play 商店" ;;
    com.google.android.partnersetup) echo "Google 合作伙伴设置" ;;
    com.google.android.setupwizard) echo "Google 设置向导" ;;
    com.google.android.gms.location.history) echo "Google 位置记录" ;;
    com.google.android.syncadapters.contacts) echo "Google 通讯录同步" ;;
    com.google.android.syncadapters.calendar) echo "Google 日历同步" ;;
    com.google.android.configupdater) echo "Google 配置更新" ;;
    com.google.android.onetimeinitializer) echo "Google 一次性初始化" ;;
    com.google.android.feedback) echo "Google 反馈" ;;
    com.google.android.apps.restore) echo "Google 恢复" ;;
    com.google.android.printservice.recommendation) echo "Google 打印推荐" ;;
    com.google.android.googlequicksearchbox) echo "Google" ;;
    com.google.android.tts) echo "谷歌语音服务" ;;
    com.android.hotwordenrollment.okgoogle) echo "Ok Google 语音注册" ;;
    com.android.hotwordenrollment.xgoogle) echo "Ok Google 语音注册" ;;
    com.google.android.inputmethod.latin) echo "Gboard" ;;
    com.google.android.projection.gearhead) echo "Android Auto" ;;
    com.google.android.marvin.talkback) echo "TalkBack" ;;
    *) echo "$1" ;;
  esac
}

FAILS=""
ACTED=""
note_fail() {
  FAILS="${FAILS}$1
"
  log_msg "失败  $1"
}

note_act() {
  ACTED="${ACTED}$1
"
  log_msg "$1"
}

apply_user() {
  USER_ID="$1"
  VERB="$2"
  ORDER="$3"
  for PKG in $ORDER; do
    STATE=$(pkg_state "$PKG" "$USER_ID")
    if [ -z "$STATE" ]; then
      log_msg "skip package=$PKG user=$USER_ID reason=not-installed"
      continue
    fi
    if [ "$VERB" = "disable-user" ]; then
      if [ "$STATE" = "2" ] || [ "$STATE" = "3" ]; then
        log_msg "skip package=$PKG user=$USER_ID state=$STATE"
        continue
      fi
      OUT=$(pm disable-user --user "$USER_ID" "$PKG" 2>&1)
      RC=$?
      LINE="冻结  $(app_name "$PKG")  $PKG  用户${USER_ID}"
      if [ "$RC" -ne 0 ]; then
        note_fail "$LINE  $OUT"
        continue
      fi
      note_act "$LINE"
      am force-stop --user "$USER_ID" "$PKG" >/dev/null 2>&1
    else
      if [ "$STATE" != "3" ]; then
        log_msg "skip package=$PKG user=$USER_ID state=$STATE"
        continue
      fi
      OUT=$(pm default-state --user "$USER_ID" "$PKG" 2>&1)
      RC=$?
      LINE="恢复  $(app_name "$PKG")  $PKG  用户${USER_ID}"
      if [ "$RC" -ne 0 ]; then
        note_fail "$LINE  $OUT"
        continue
      fi
      note_act "$LINE"
    fi
  done
}

NOW=$(pkg_state com.google.android.gms 0)
log_msg "gms user 0 state=${NOW:-<empty>}"

if [ "$NOW" = "3" ]; then
  MODE=on
  ORDER=$ON_ORDER
  VERB=default-state
  TITLE="谷歌服务已恢复"
else
  MODE=off
  ORDER=$OFF_ORDER
  VERB=disable-user
  TITLE="谷歌服务已冻结"
fi

apply_user 0 "$VERB" "$ORDER"
apply_user 999 "$VERB" "$ORDER"

AFTER=$(pkg_state com.google.android.gms 0)
log_msg "result mode=$MODE gms=$AFTER"

printf '%s\n' "$TITLE"
if [ -n "$ACTED" ]; then
  printf '%s\n' "$ACTED"
else
  printf '%s\n' "没有需要改动的包。"
fi
if [ -n "$FAILS" ]; then
  printf '%s\n%s\n' "这些包没有改成功：" "$FAILS"
fi
if [ "$MODE" = "off" ] && [ "$AFTER" != "3" ]; then
  printf '%s\n' "Play 服务仍在运行。"
fi
if [ "$MODE" = "on" ] && [ "$AFTER" = "3" ]; then
  printf '%s\n' "Play 服务仍是冻结状态。"
fi

if [ -n "$KSU" ] || [ -n "$APATCH" ]; then
  sleep 8
fi
exit 0
