package com.aphex3k.eo1;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.StatFs;
import android.os.SystemClock;

import com.google.gson.JsonObject;

import java.io.File;

/**
 * Lazy device-telemetry collectors for the web server's {@code /state} endpoint. Nothing here polls;
 * every value is read on demand. All APIs used are available on API 19.
 */
public final class DeviceTelemetry {

    private DeviceTelemetry() {
    }

    /**
     * Builds the telemetry fragment of the {@code /state} JSON: battery, memory, storage, wifi, uptime.
     * {@code memory} is RAM and this process's heap; {@code storage} is the partition the media lives
     * on. Both are on demand, and neither is a capacity claim the docs can go stale against.
     */
    public static JsonObject snapshot(Context context) {
        JsonObject o = new JsonObject();
        o.add("battery", battery(context));
        o.add("memory", memory(context));
        o.add("storage", storage(context.getFilesDir()));
        o.add("wifi", wifi(context));
        long uptimeMs = SystemClock.elapsedRealtime();
        JsonObject up = new JsonObject();
        up.addProperty("uptimeMs", uptimeMs);
        up.addProperty("uptime", humanizeUptime(uptimeMs));
        o.add("uptime", up);
        return o;
    }

    /**
     * Reads the sticky battery status.
     */
    public static JsonObject battery(Context context) {
        JsonObject o = new JsonObject();
        try {
            Intent batt = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batt != null) {
                // The extra keys are the stable Intent.EXTRA_BATTERY_* values ("level", "scale", ...);
                // use the literals because this SDK's stripped android.jar lacks those constants.
                int level = batt.getIntExtra("level", -1);
                int scale = batt.getIntExtra("scale", 100);
                int plugged = batt.getIntExtra("plugged", 0);
                int tempTenths = batt.getIntExtra("temperature", 0);
                o.addProperty("level", level);
                o.addProperty("scale", scale);
                o.addProperty("percent", scale > 0 ? Math.round(100f * level / scale) : -1);
                o.addProperty("plugged", plugged);
                o.addProperty("tempC", tempTenths / 10f);
            }
        } catch (Exception ignored) {
            // Leave empty object.
        }
        return o;
    }

    /**
     * Reads available/total RAM, and separately this process's Dalvik heap.
     *
     * <p>The heap keys are named {@code heap*} rather than folded into {@code availBytes}/{@code
     * totalBytes} because the two have been quoted as one figure: an allocation that is too big is
     * refused by the heap ceiling ({@code dalvik.vm.heapsize}, raised to its full value by
     * {@code android:largeHeap}), not by the size of the partition. The expressions match
     * {@code CrashReporter.deviceFacts}, which must not be able to disagree with this about what the
     * same two numbers mean.
     */
    public static JsonObject memory(Context context) {
        JsonObject o = new JsonObject();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                o.addProperty("availBytes", mi.availMem);
                o.addProperty("totalBytes", mi.totalMem);
                o.addProperty("lowMemory", mi.lowMemory);
            }
        } catch (Exception ignored) {
        }
        try {
            Runtime rt = Runtime.getRuntime();
            o.addProperty("heapUsedBytes", rt.totalMemory() - rt.freeMemory());
            o.addProperty("heapMaxBytes", rt.maxMemory());
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * Reads the filesystem {@code dir} lives on — in practice the {@code /data} partition the media
     * cache and uploads occupy. Sibling of {@link #memory(Context)} under deliberately parallel key
     * names, so RAM and disk cannot be read as one quantity.
     *
     * <p>One {@code statfs}, no binder and no directory walk: this runs on a web-server worker thread,
     * one of three, and a recursive size of the cache would occupy it for as long as the media pipeline
     * needs the thread that answers {@code /state}.
     */
    public static JsonObject storage(File dir) {
        long totalBytes = -1L;
        long availBytes = -1L;
        try {
            if (dir != null) {
                StatFs sf = new StatFs(dir.getAbsolutePath());
                // The byte getters rather than blocks x blockSize: they are long on their own, so there
                // is no int block count to truncate and no product to widen, and getAvailableBytes is
                // the quantity File.getUsableSpace() returns — the same one MediaCacheManager compares
                // SAFETY_MARGIN_BYTES against. So marginBytes and availBytes name one measurement
                // rather than two ways of computing it. Both are API 9, verified against the android-19
                // jar, so no NewApi risk on this floor.
                totalBytes = sf.getTotalBytes();
                availBytes = sf.getAvailableBytes();
            }
        } catch (Throwable ignored) {
        }
        return storageJson(totalBytes, availBytes);
    }

    /**
     * Pure half of {@link #storage(File)}: a negative is "the statfs did not answer", and then the key is
     * absent rather than {@code -1} or {@code 0} — a zero free-space reading is exactly what a partition
     * full of media says, and it must not also be what an unreadable one says.
     */
    static JsonObject storageJson(long totalBytes, long availBytes) {
        JsonObject o = new JsonObject();
        if (totalBytes >= 0L) {
            o.addProperty("totalBytes", totalBytes);
        }
        if (availBytes >= 0L) {
            o.addProperty("availBytes", availBytes);
            o.addProperty("marginBytes", MediaCacheManager.SAFETY_MARGIN_BYTES);
        }
        return o;
    }

    /**
     * Reads the current Wi-Fi IP address and SSID.
     */
    public static JsonObject wifi(Context context) {
        JsonObject o = new JsonObject();
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    int ip = info.getIpAddress();
                    o.addProperty("ip", ip == 0 ? "unknown" : ipToString(ip));
                    o.addProperty("ssid", cleanSsid(info.getSSID()));
                }
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * Converts the int returned by {@link WifiInfo#getIpAddress()} to dotted-quad form.
     *
     * <p>On this device the int is in host (little-endian) byte order, so the first octet sits in
     * the least-significant byte. Reading it the usual big-endian way yields a byte-swapped
     * address (e.g. 192.168.1.174 would render as "174.1.168.192"), hence the reversed order.
     */
    public static String ipToString(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    /**
     * Formats an uptime in ms as "d d? h m s".
     */
    public static String humanizeUptime(long ms) {
        long totalSec = ms / 1000L;
        long days = totalSec / 86400L;
        long hours = (totalSec % 86400L) / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        sb.append(hours).append("h ");
        sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString();
    }

    private static String cleanSsid(String ssid) {
        if (ssid == null) {
            return "unknown";
        }
        String s = ssid.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty() || s.equals("<unknown ssid>")) {
            return "unknown";
        }
        return s;
    }
}
