package com.aphex3k.eo1;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import com.google.gson.JsonObject;

/**
 * Lazy device-telemetry collectors for the web server's {@code /state} endpoint. Nothing here polls;
 * every value is read on demand. All APIs used are available on API 19.
 */
public final class DeviceTelemetry {

    private DeviceTelemetry() {
    }

    /**
     * Builds the telemetry fragment of the {@code /state} JSON: battery, memory, wifi, uptime.
     */
    public static JsonObject snapshot(Context context) {
        JsonObject o = new JsonObject();
        o.add("battery", battery(context));
        o.add("memory", memory(context));
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
     * Reads available/total memory.
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
     * Converts a host-order IPv4 int to dotted-quad form.
     */
    public static String ipToString(int ip) {
        return ((ip >> 24) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 8) & 0xff) + "." + (ip & 0xff);
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
