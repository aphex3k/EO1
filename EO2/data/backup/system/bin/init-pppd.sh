#!/system/bin/sh
# An unforunate wrapper script 
# so that the exit code of pppd may be retrieved


# this is a workaround for issue #651747
#trap "/system/bin/sleep 1;exit 0" TERM
MODEMPORT=`/system/bin/getprop ril.modemport`
SCRIPT=`/system/bin/getprop ril.ppp.script`
USER=`/system/bin/getprop ril.ppp.user`
PWD=`/system/bin/getprop ril.ppp.pwd`
APNNAME=`/system/bin/getprop ril.ppp.apn_name`
MCCMNC=`/system/bin/getprop gsm.sim.operator.numeric`
SETCRM=`/system/bin/getprop NeadSetCRM`
PUREUART=`/system/bin/getprop ril.pureuart`

rm -f /dev/ttyS20
ln -s $MODEMPORT /dev/ttyS20

if [ "$PUREUART" = "1" ];then
	CHATSCRIBE="/system/bin/chat ABORT '\nBUSY\r' ABORT '\nNO ANSWER\r' ABORT '\nRINGING\r' TIMEOUT 30  '' AT OK AT+CGDCONT=1,\"IP\",\"$APNNAME\" '' ATDT*99# CONNECT "
else
	CHATSCRIBE="/system/bin/chat ABORT '\nBUSY\r' ABORT '\nNO ANSWER\r' ABORT '\nRINGING\r' TIMEOUT 30  '' \rAT  OK AT+CGDCONT=1,\"IP\",\"$APNNAME\"  '' ATDT*99***1# CONNECT  '' "
fi

/system/bin/log -t pppd "init-pppd.sh SCRIPT=$SCRIPT"
/system/bin/log -t pppd "init-pppd.sh user=$USER"
/system/bin/log -t pppd "init-pppd.sh pwd=$PWD"
/system/bin/log -t pppd "init-pppd.sh apn_name=$APNNAME"

if busybox [ "$SCRIPT" = "mcli-cdma" ] ; then
    if busybox [ "$SETCRM" = "1" ];then
        CHATSCRIBE="/system/bin/chat -s -S TIMEOUT 25 ABORT 'BUSY' '' AT '' ATH0 '' AT+CRM=1 '' ATDT#777 CONNECT"
    else
	    CHATSCRIBE="/system/bin/chat -s -S TIMEOUT 25 ABORT 'BUSY' '' AT '' ATH0 ''  ATDT#777 CONNECT"
	fi
elif busybox [ "$SCRIPT" = "mcli-tdscdma" ] ; then
	CHATSCRIBE="/system/bin/chat TIMEOUT 25 '' AT+CGDCONT=1,\"IP\",\"$APNNAME\" '' ATDT*99***1# CONNECT "
	SCRIPT=mcli-gsm
fi


#/system/bin/pppd file /system/etc/ppp/peers/mcli
if busybox [ "$USER" = "0" ];then
if busybox [ "$MCCMNC" = "40400" ];then
	USER="internet"
	PWD="internet"
elif busybox [ "$MCCMNC" = "40589" ];then
    USER="internet@internet.mtsindia.in"
	PWD="mts"
else
	USER="card"
	PWD="card"    
fi
fi

/system/bin/log -t pppd "Starting pppd user $USER password $PWD $CHATSCRIBE"

/system/bin/pppd call $SCRIPT user $USER password $PWD connect "$CHATSCRIBE"


/system/bin/log -t pppd "init-pppd.sh exited "


exit $PPPD_EXIT
