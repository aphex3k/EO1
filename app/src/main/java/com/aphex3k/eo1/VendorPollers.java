package com.aphex3k.eo1;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the framework currently permits the ROM's two OTA pollers to do, read with no permission, no
 * socket and no adbd — the difference between a frame that is <em>known</em> silent and one that is
 * merely hoped to be.
 *
 * <p>Until now the only evidence the app had about the pollers was the outcome of the {@code pm}
 * command that silences them, which is an answer about the transport rather than about the frame: on
 * any device with a host {@code adb} client attached, loopback adbd accepts the connection and never
 * reads a byte, so every launch reported {@code unreachable (SocketTimeoutException)} on a frame that
 * had been silent for days. This class is how that stops being unknowable. It also covers the EO1,
 * whose ROM runs no loopback adbd at all.
 *
 * <p>Reading another package's enabled state needs nothing special on these frames: API 19's
 * {@code PackageManagerService.getApplicationEnabledSetting} / {@code getComponentEnabledSetting}
 * enforce only {@code enforceCrossUserPermission} — the <em>setters</em> are the ones that consult
 * {@code CHANGE_COMPONENT_ENABLED_STATE}, which this app deliberately does not hold. (Later Android
 * documents that permission on the getters too; these frames are 4.4.2 and {@code targetSdk} 19.) If
 * a ROM ever refuses the read, every verdict comes back {@link #UNVERIFIABLE} and the caller falls
 * back to the old write-once behaviour, so a tighter framework degrades this to what it used to be,
 * never to a false "silenced".
 *
 * <p>The rule that decides a verdict, and the one place this is easy to get wrong: <b>ask the package
 * first and the components second, never components alone.</b> {@code
 * PackageSettingBase.getCurrentEnabledStateLPr} reads only the enabled/disabled component sets, so
 * every component of a package disabled at the package level answers {@code DEFAULT} — a
 * components-first check would report a correctly package-silenced frame as armed forever and make it
 * re-issue the disable, and that package's force-stop, on every single launch.
 */
public final class VendorPollers {

    private static final String TAG = "EO1";

    /** Silent: nothing in this poller can be started or receive a broadcast. */
    public static final int QUIET = 0;
    /** At least one wake path is still live. */
    public static final int ARMED = 1;
    /** This ROM does not ship that package — nothing to silence, and not a failure. */
    public static final int ABSENT = 2;
    /** The read itself failed, so the answer is unknown — never reported as {@link #QUIET}. */
    public static final int UNVERIFIABLE = 3;

    /** Sentinel from {@link Reader}: the package is not installed. */
    public static final int NO_PACKAGE = -1;
    /** Sentinel from {@link Reader}: the framework refused or broke the read. */
    public static final int UNREADABLE = -2;

    /**
     * The enabled-state reads, behind an interface so the verdict logic is unit-tested without a
     * {@code PackageManager}. API 19 has no {@code java.util.function}, hence the app-defined type.
     */
    public interface Reader {
        /** A {@code COMPONENT_ENABLED_STATE_*}, or {@link #NO_PACKAGE} / {@link #UNREADABLE}. */
        int applicationEnabledSetting(String packageName);

        /** A {@code COMPONENT_ENABLED_STATE_*}, or {@link #NO_PACKAGE} / {@link #UNREADABLE}. */
        int componentEnabledSetting(String packageName, String className);
    }

    /**
     * One poller: the package it lives in, the components that actually wake it, the command
     * {@link DeviceRepair} issues for it, and whether that command works at package level. The
     * intended form matters as much as the wake path — a frame that was silenced by an older build's
     * receiver-only command reads {@link #ARMED} here, which is what gets the wider package-level
     * disable applied to it once, after which it is quiet for good.
     */
    public static final class Poller {
        public final String packageName;
        public final boolean packageLevel;
        /** Fully-qualified class names — the form the framework's settings table keys on. */
        public final String[] wakeComponents;
        public final String[] commands;

        Poller(String packageName, boolean packageLevel, String[] wakeComponents, String[] commands) {
            this.packageName = packageName;
            this.packageLevel = packageLevel;
            this.wakeComponents = wakeComponents;
            this.commands = commands;
        }
    }

    public static final List<Poller> POLLERS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Poller(DeviceRepair.PKG_VENDOR_UPDATER, true,
                    new String[] {"com.geniatech.upgrade.UpdateReceiver"},
                    new String[] {DeviceRepair.CMD_DISABLE_VENDOR_UPDATER}),
            new Poller(DeviceRepair.PKG_EO_UPDATER, false,
                    new String[] {"com.electricobjects.updater.CheckUpdateReceiver",
                            "com.electricobjects.updater.BootCompletedReceiver"},
                    new String[] {DeviceRepair.CMD_DISABLE_EO_UPDATER_CHECK,
                            DeviceRepair.CMD_DISABLE_EO_UPDATER_BOOT})));

    /** One poller and what the framework said about it. */
    public static final class Verdict {
        public final Poller poller;
        public final int state;
        /** Human-readable why, empty when the state alone says everything. */
        public final String detail;

        Verdict(Poller poller, int state, String detail) {
            this.poller = poller;
            this.state = state;
            this.detail = detail == null ? "" : detail;
        }

        public boolean quiet() {
            return state == QUIET || state == ABSENT;
        }

        /** Renders one verdict the way {@link DeviceRepair#describe} renders steps. */
        @Override
        public String toString() {
            return poller.packageName + "=" + name(state) + (detail.isEmpty() ? "" : "(" + detail + ")");
        }
    }

    private VendorPollers() {
    }

    /** A {@link Reader} over the real framework. The reads are binder calls, not sockets. */
    public static Reader readerFor(final Context context) {
        return new Reader() {
            @Override
            public int applicationEnabledSetting(String packageName) {
                return readSetting(packageName, null, context);
            }

            @Override
            public int componentEnabledSetting(String packageName, String className) {
                return readSetting(packageName, className, context);
            }
        };
    }

    private static int readSetting(String packageName, String className, Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return className == null
                    ? pm.getApplicationEnabledSetting(packageName)
                    : pm.getComponentEnabledSetting(new android.content.ComponentName(
                            packageName, className));
        } catch (IllegalArgumentException e) {
            // pm's own "Unknown package" — this ROM simply does not ship that poller.
            return NO_PACKAGE;
        } catch (SecurityException e) {
            Log.w(TAG, "vendor pollers: read refused for " + packageName + ": " + e);
            return UNREADABLE;
        } catch (RuntimeException e) {
            // A binder hiccup is an unknown, never an armed and never a quiet.
            Log.w(TAG, "vendor pollers: read failed for " + packageName + ": " + e);
            return UNREADABLE;
        }
    }

    /** Both pollers as the framework sees them, in {@link #POLLERS} order. Never throws. */
    public static List<Verdict> audit(Reader reader) {
        List<Verdict> verdicts = new ArrayList<Verdict>(POLLERS.size());
        for (int i = 0; i < POLLERS.size(); i++) {
            Poller poller = POLLERS.get(i);
            try {
                verdicts.add(read(reader, poller));
            } catch (Throwable t) {
                // A diagnostic thread must not be the thing that takes the frame down.
                Log.w(TAG, "vendor pollers: " + poller.packageName + ": " + t);
                verdicts.add(new Verdict(poller, UNVERIFIABLE, shortError(t)));
            }
        }
        return verdicts;
    }

    private static Verdict read(Reader reader, Poller poller) {
        int appState = reader.applicationEnabledSetting(poller.packageName);
        if (appState == NO_PACKAGE) {
            return new Verdict(poller, ABSENT, "not installed");
        }
        if (appState == UNREADABLE) {
            return new Verdict(poller, UNVERIFIABLE, "package setting unreadable");
        }
        if (isDisabled(appState)) {
            // Both pollers, either command: the package is out of resolution, so nothing inside it
            // can be started, and the components below would answer DEFAULT if we asked them.
            return new Verdict(poller, QUIET, "package " + settingName(appState));
        }

        List<String> live = new ArrayList<String>();
        List<String> unread = new ArrayList<String>();
        List<String> off = new ArrayList<String>();
        for (int i = 0; i < poller.wakeComponents.length; i++) {
            String cls = poller.wakeComponents[i];
            int state = reader.componentEnabledSetting(poller.packageName, cls);
            if (state == UNREADABLE) {
                unread.add(simpleName(cls));
            } else if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                off.add(simpleName(cls));
            } else {
                live.add(simpleName(cls) + "=" + settingName(state));
            }
        }

        if (poller.packageLevel) {
            // This poller's command works at package level, so components are evidence, not the
            // verdict: an older build's receiver-only disable closed the wake path and left
            // .UpdateService, IntervalCheckUpdateService and the vendor UI all resolvable.
            return new Verdict(poller, ARMED, "package " + settingName(appState)
                    + (off.size() == 0 ? "" : ", wake path closed by the older component form ("
                            + join(off) + "), the rest of the package is not"));
        }
        if (unread.size() > 0 && live.isEmpty()) {
            return new Verdict(poller, UNVERIFIABLE, "components unreadable: " + join(unread));
        }
        if (live.isEmpty()) {
            return new Verdict(poller, QUIET,
                    poller.wakeComponents.length + " wake components disabled");
        }
        return new Verdict(poller, ARMED, "still live: " + join(live));
    }

    /** True when every poller on this frame is silent, or this ROM does not ship it. */
    public static boolean allQuiet(List<Verdict> verdicts) {
        for (int i = 0; i < verdicts.size(); i++) {
            if (!verdicts.get(i).quiet()) {
                return false;
            }
        }
        return true;
    }

    /** One line for {@code /logs} and {@code /state}, e.g. {@code "a=quiet(…); b=armed(…)"}. */
    public static String summarize(List<Verdict> verdicts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < verdicts.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(verdicts.get(i));
        }
        return sb.toString();
    }

    /**
     * The commands a human can run over USB to finish what this launch could not, for every poller
     * still armed. A frame whose loopback adbd is deaf is silent only once someone types these, so
     * the line that reports the problem has to carry the fix.
     */
    public static String needsHuman(List<Verdict> verdicts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < verdicts.size(); i++) {
            Verdict v = verdicts.get(i);
            if (v.state != ARMED) {
                continue;
            }
            for (int c = 0; c < v.poller.commands.length; c++) {
                sb.append(sb.length() == 0 ? "needs human: adb shell " : " && adb shell ")
                        .append(v.poller.commands[c]);
            }
        }
        return sb.toString();
    }

    private static boolean isDisabled(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private static String name(int verdictState) {
        switch (verdictState) {
            case QUIET: return "quiet";
            case ARMED: return "armed";
            case ABSENT: return "absent";
            default: return "unverifiable";
        }
    }

    /** The framework's own vocabulary, so {@code 1} never reads like "disabled" to the next reader. */
    private static String settingName(int state) {
        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT: return "DEFAULT";
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED: return "ENABLED";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED: return "DISABLED";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER: return "DISABLED_USER";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return "DISABLED_UNTIL_USED";
            case NO_PACKAGE: return "not installed";
            case UNREADABLE: return "unreadable";
            default: return "state " + state;
        }
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String shortError(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
