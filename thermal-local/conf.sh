#!/system/bin/sh
MODDIR=${0%/*}
GEN="$MODDIR/generated"
CFG="$MODDIR/configs"
LIST="$CFG/installed.txt"
DEST=/data/vendor/thermal/config
DEX="$MODDIR/thermal-crypt.dex"
LOG=/data/adb/thermal-local.log
cmd=$1
name=$2
ns=0
case " $* " in
  *" --ns "*) ns=1 ;;
esac

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null || true
}

valid_name() {
  case "$1" in
    thermal-*.conf) ;;
    *) echo "文件名不对" >&2; return 1 ;;
  esac
  case "$1" in
    *..*|*/*|*\\*) echo "文件名不对" >&2; return 1 ;;
  esac
  return 0
}

crypt() {
  rm -f "$3"
  CLASSPATH="$DEX" /system/bin/app_process /system/bin ThermalCrypt "$1" "$2" "$3" >> "$LOG" 2>&1
  [ -s "$3" ]
}

find_src() {
  if [ -s "$CFG/$1" ]; then
    printf '%s\n' "$CFG/$1"
    return 0
  fi
  for src in "$DEST/$1" "/odm/etc/$1" "/vendor/etc/$1"; do
    if [ -f "$src" ]; then
      printf '%s\n' "$src"
      return 0
    fi
  done
  return 1
}

install_conf() {
  src=$1
  target=$2
  if [ -f "$DEST/$target" ]; then
    chattr -i "$DEST/$target" 2>/dev/null || true
  fi
  cp -f "$src" "$DEST/$target" || return 1
  chown root:system "$DEST/$target" 2>/dev/null || true
  chmod 644 "$DEST/$target"
  chcon u:object_r:thermal_data_file:s0 "$DEST/$target" 2>/dev/null || true
  chattr +i "$DEST/$target" 2>/dev/null || true
  return 0
}

remember() {
  mkdir -p "$CFG"
  if [ ! -f "$LIST" ] || ! grep -qx "$1" "$LIST" 2>/dev/null; then
    printf '%s\n' "$1" >> "$LIST"
  fi
}

restore_one() {
  item=$1
  if [ -f "$GEN/$item" ]; then
    install_conf "$GEN/$item" "$item"
    log "restored $item"
  fi
}

if [ "$ns" != 1 ] && [ "$cmd" != "read" ] && [ ! -d "$DEST" ]; then
  exec nsenter -t 1 -m -- sh "$0" "$@" --ns
fi

case "$cmd" in
  read)
    valid_name "$name" || exit 1
    if ! src=$(find_src "$name"); then
      if [ "$ns" != 1 ]; then
        exec nsenter -t 1 -m -- sh "$0" read "$name" --ns
      fi
      echo "找不到 $name" >&2
      exit 1
    fi
    case "$src" in
      "$CFG/$name")
        cat "$src"
        exit 0
        ;;
    esac
    mkdir -p "$GEN"
    if ! crypt d "$src" "$GEN/.read-$name"; then
      echo "解密失败" >&2
      exit 1
    fi
    cat "$GEN/.read-$name"
    rm -f "$GEN/.read-$name"
    ;;
  write)
    valid_name "$name" || exit 1
    if [ ! -d "$DEST" ]; then
      echo "找不到温控目录" >&2
      exit 1
    fi
    mkdir -p "$CFG" "$GEN"
    cat > "$CFG/$name.tmp"
    if [ ! -s "$CFG/$name.tmp" ]; then
      rm -f "$CFG/$name.tmp"
      echo "内容是空的" >&2
      exit 1
    fi
    mv -f "$CFG/$name.tmp" "$CFG/$name"
    if ! crypt e "$CFG/$name" "$GEN/$name"; then
      echo "加密失败" >&2
      exit 1
    fi
    if ! crypt d "$GEN/$name" "$GEN/.check-$name"; then
      echo "回读失败" >&2
      exit 1
    fi
    if ! cmp -s "$CFG/$name" "$GEN/.check-$name"; then
      rm -f "$GEN/.check-$name"
      echo "加密后和原文不一致" >&2
      exit 1
    fi
    rm -f "$GEN/.check-$name"
    if ! install_conf "$GEN/$name" "$name"; then
      echo "写入温控目录失败" >&2
      exit 1
    fi
    remember "$name"
    stop mi_thermald 2>/dev/null || true
    sleep 1
    start mi_thermald 2>/dev/null || true
    log "saved $name"
    printf '%s\n' saved
    ;;
  restore)
    if [ ! -s "$LIST" ] || [ ! -d "$DEST" ]; then
      exit 0
    fi
    while IFS= read -r item; do
      valid_name "$item" || continue
      restore_one "$item"
    done < "$LIST"
    ;;
  purge)
    if [ ! -s "$LIST" ] || [ ! -d "$DEST" ]; then
      exit 0
    fi
    while IFS= read -r item; do
      valid_name "$item" || continue
      if [ -f "$DEST/$item" ]; then
        chattr -i "$DEST/$item" 2>/dev/null || true
        rm -f "$DEST/$item"
        log "purged $item"
      fi
    done < "$LIST"
    ;;
  *)
    echo "usage: conf.sh read|write|restore|purge" >&2
    exit 1
    ;;
esac
