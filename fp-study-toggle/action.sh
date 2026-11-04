#!/system/bin/sh

LOG_STAGE=action
MODDIR=${0%/*}
if [ -f "$MODDIR/log.sh" ]; then
  . "$MODDIR/log.sh"
else
  log_msg() { echo "$1"; }
fi

PROP=persist.vendor.sys.fp.disable_study

CUR=$(getprop "$PROP" 2>/dev/null | tr -d ' \r\n')
log_msg "before $PROP=${CUR:-<empty>}"

# 空或 0：学习开着，这次执行改为关闭。其它非 0 值都视为已关闭。
if [ -z "$CUR" ] || [ "$CUR" = "0" ]; then
  MODE=off
  NEXT=1
  TITLE="指纹学习已关闭"
else
  MODE=on
  NEXT=0
  TITLE="指纹学习已打开"
fi

if command -v resetprop >/dev/null 2>&1; then
  resetprop -p "$PROP" "$NEXT"
  RC=$?
  TOOL=resetprop
else
  setprop "$PROP" "$NEXT"
  RC=$?
  TOOL=setprop
fi

AFTER=$(getprop "$PROP" 2>/dev/null | tr -d ' \r\n')
log_msg "result mode=$MODE tool=$TOOL rc=$RC $PROP=${AFTER:-<empty>}"

if [ "$RC" -ne 0 ] || [ "$AFTER" != "$NEXT" ]; then
  printf '%s\n' "没有改成。"
  printf '%s\n' "当前 $PROP=${AFTER:-<empty>}，目标是 $NEXT。"
  if [ -n "$KSU" ] || [ -n "$APATCH" ]; then
    sleep 8
  fi
  exit 0
fi

printf '%s\n' "$TITLE"
if [ "$MODE" = "off" ]; then
  printf '%s\n' "成功解锁后不再把这次图像写进模板。"
  printf '%s\n' "现有指纹保持原样，干手指不会因此变好。"
  printf '%s\n' "请到设置里删掉全部指纹，用干手重新录入，并保持关闭。"
  printf '%s\n' "微湿时解开一次，也不会再把湿手指写回去。"
else
  printf '%s\n' "成功解锁后会把这次图像写回模板。"
  printf '%s\n' "如果只有微湿才能解开，模板会越来越偏向湿手指。"
  printf '%s\n' "干手经常失败时，不要保持打开。"
fi
printf '%s\n' "下次按下指纹时生效。想马上让指纹进程重读，重启一次。"

if [ -n "$KSU" ] || [ -n "$APATCH" ]; then
  sleep 8
fi
exit 0
