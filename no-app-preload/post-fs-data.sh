#!/system/bin/sh
# Runs before system.prop. Remove sticky persist records left by setprop,
# so disabling this module lets build.prop defaults apply on the next boot.
# system.prop then sets the in-memory values with resetprop -n.

resetprop -p -d persist.sys.prestart.proc
resetprop -p -d persist.sys.preload.enable