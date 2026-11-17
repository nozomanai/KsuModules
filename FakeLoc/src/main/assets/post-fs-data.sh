#!/system/bin/sh
# Restore the runtime allow-list after a module update replaces this directory.
MODDIR=${0%/*}
STATE=/data/adb/fakeloc
mkdir -p "$MODDIR/packages" "$STATE"
if [ -f "$STATE/scope" ]; then
  for f in "$MODDIR/packages"/*; do
    [ -f "$f" ] || continue
    name=${f##*/}
    [ "$name" = ".all" ] && continue
    rm -f "$f"
  done
  while IFS= read -r pkg; do
    [ -n "$pkg" ] || continue
    : > "$MODDIR/packages/$pkg"
  done < "$STATE/scope"
fi
if [ -f "$STATE/config.json" ]; then
  cp "$STATE/config.json" "$MODDIR/config.json"
  chmod 644 "$MODDIR/config.json"
fi
