#!/system/bin/sh

if [ $1 = load ]; then
	echo "load realtek bt driver"
	insmod /system/lib/rtk_btusb.ko
else
	if [ $1 = unload ]; then
		echo "unload realtek bt driver"
		rmmod /system/lib/rtk_btusb.ko
	fi
fi
