#!/system/bin/sh
	if [ "$(getprop hw.ddr.size)" = "512" ]; then
		echo $((256*1024*1024))  > /sys/block/zram0/disksize
		echo $((4096)) > /proc/sys/vm/mem_management_thresh
	else
		echo $((500*1024*1024))  > /sys/block/zram0/disksize
		echo $((8192)) > /proc/sys/vm/mem_management_thresh
	fi
	mkswap /dev/block/zram0
	swapon /dev/block/zram0
	sleep 1
	mkfs.ext2 -b 4096 /dev/block/zram0
	sleep 1
	mount -t rootfs -o remount -rw rootfs /
	mkdir /swap_zram0
	sleep 1
	mount -t rootfs -o remount -r rootfs /
	mount -t ext2 -o rw /dev/block/zram0 /swap_zram0

