#!/system/bin/sh

sleep 2

if [ -z "`busybox ls -A /data/data/com.android.settings/`" ];then
		/system/bin/log -t factory_default "begain factory default!"
		if [ -e /system/data.tar.bz2 ];then
				busybox tar -jxvf /system/data.tar.bz2 -C /
				busybox chmod -R 777 /data
				busybox chown -R system /data
				busybox chgrp -R system /data
		fi
fi

if [ -z "`busybox ls -A /storage/sdcard0/Android/data/org.xbmc.kodi/`" ];then
		if [ -e /system/data.tar ];then
				/system/bin/log -t factory_default "begain xbmc default!"
				busybox tar -xvf /system/data.tar -C /storage/sdcard0/
				busybox chmod -R 777 /storage/sdcard0/Android/data/org.xbmc.kodi
		fi
fi
