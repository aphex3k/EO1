#!/system/bin/sh

if [ "$(getprop ubootenv.var.boardtype)" = "m8b_dongle" ]; then
    setprop hw.boardtype.dongle true
fi
