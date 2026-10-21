package com.aphex3k.eo1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Repair actions the frame can take for itself when a subsystem has stopped working, executed
 * through the device's own adbd on {@code 127.0.0.1:5555} (see {@link AdbdInstallClient}).
 *
 * <p>The frame needs root here. After the framework restart of 2026-10-13 the ROM never brought the
 * Wi-Fi stack back up — the supplicant was absent and the radio sat in "unknown state" — so the
 * frame stayed offline for 13 hours while the app dutifully reported "no network".
 * {@code WifiManager} needs {@code CHANGE_WIFI_STATE}, which this app deliberately does not hold;
 * the ROM's adbd runs as root and can cycle the radio without adding a permission.
 *
 * <p>Cycling through {@code svc wifi} is the right lever precisely because it goes via WifiService:
 * the ROM registers its supplicant under a service name that is not the AOSP one
 * ({@code init.svc.p2p_supplicant} on the EO2, and {@code init.svc.wpa_supplicant} does not exist
 * there), so poking {@code ctl.start} by name would be a guess. WifiService knows how its own
 * vendor bits start. Whether the cycle worked is answered by re-checking connectivity on the next
 * tick, not by reading internals.
 *
 * <p>As on the install path, every command line is a fixed constant — nothing from the network, the
 * configuration, or an upload is ever interpolated into it. The constants below are the entire
 * vocabulary, and {@link #isKnownCommand(String)} plus the unit test keep it that way.
 */
public final class DeviceRepair {

    /** Turns Wi-Fi off at the framework level (takes the supplicant client down with it). */
    public static final String CMD_WIFI_DISABLE = "svc wifi disable";
    /** Turns Wi-Fi back on; this is what restarts the vendor Wi-Fi stack. */
    public static final String CMD_WIFI_ENABLE = "svc wifi enable";
    /** Cold-restarts the frame. Last resort, and opt-in. */
    public static final String CMD_REBOOT = "reboot";
    /**
     * Silences the ROM's vendor OTA poller by taking the whole {@code com.geniatech.upgrade} package
     * ({@code /system/app/Update.apk}) out of intent resolution. The poller keeps an hourly
     * {@code AlarmManager} alarm ({@code act=com.upgrade.time.checkupdate}) whose broadcast starts
     * {@code UpdateReceiver} → {@code IntervalCheckUpdateService} → a plain-HTTP GET to
     * {@code www.geniatech.net}; that domain is a dead CNAME chain, so every hour — and on every
     * BOOT_COMPLETED / Wi-Fi / ethernet state change, which reach the same receiver — the frame
     * wakes to print an {@code UnknownHostException} to {@code System.err}.
     *
     * <p>The package, not the receiver, is the unit because a component list is only as good as the
     * components someone wrote down: the same package also exports {@code .UpdateService},
     * {@code IntervalCheckUpdateService} and six activities, any of which an explicit
     * {@code startService} or broadcast can reach while only a receiver is off — and a firmware
     * update can add a trigger this file never enumerated. It is also the only form the frame can
     * verify for itself: {@link VendorPollers} reads a package-level state back through
     * {@code getApplicationEnabledSetting}, which on API 19 needs no permission this app lacks.
     *
     * <p>Two consequences. {@code com.geniatech.upgrade} is {@code android.uid.system}, so a
     * package-level disable goes through the framework's force-stop path ({@code PACKAGE_CHANGED}
     * with {@code EXTRA_DONT_KILL_APP} clear) rather than a quiet component toggle — which is why
     * {@link VendorPollers} asks first, so this is issued for a package that is not already disabled
     * and not once per launch forever. And disabling does not dequeue an alarm that is already armed:
     * {@code AlarmManagerService} cancels alarms for removals, not for enable-state changes, so the
     * hourly wake keeps happening — delivering nothing — until the next boot, after which nothing
     * re-arms it, because BOOT_COMPLETED no longer resolves to this package.
     *
     * <p>The asymmetry with {@link #CMD_DISABLE_EO_UPDATER_CHECK} below is deliberate, not an
     * unfinished migration: the Electric Objects poller was verified landed as two component
     * disables, and widening it buys the same service-path coverage at the cost of a second
     * system-uid force-stop. Do not "finish" it without evidence that a path outside those two
     * receivers still wakes a frame. A ROM without either package is not a failure: {@code pm}
     * reports an error, {@link VendorPollers} reads it as {@code ABSENT}, and we log it once.
     */
    public static final String CMD_DISABLE_VENDOR_UPDATER = "pm disable com.geniatech.upgrade";
    /** The package this command names, kept separate so {@link VendorPollers} can ask about it. */
    public static final String PKG_VENDOR_UPDATER = "com.geniatech.upgrade";
    /**
     * Silences the Electric Objects cloud updater ({@code /system/app/com.electricobjects.updater.apk},
     * same {@code android.uid.system}), which wakes every <em>15</em> minutes on
     * {@code repeatInterval=900000}. This is the poller this project exists to make irrelevant: the
     * frame's display must not depend on Electric Objects' cloud, and a system-uid package holding
     * {@code REBOOT} and {@code WRITE_SECURE_SETTINGS} should not be taking instructions from it.
     */
    public static final String CMD_DISABLE_EO_UPDATER_CHECK =
            "pm disable com.electricobjects.updater/.CheckUpdateReceiver";
    /**
     * The other receiver in the same package. {@code .CheckUpdateReceiver} declares no intent
     * filter, so it is only ever reached by the alarm's explicit broadcast; the boot receiver is
     * what runs at {@code BOOT_COMPLETED} and starts {@code ElectricUpdateService} directly. Cut
     * only the first and every boot still does one cloud round trip (and re-arms the alarm), so
     * both components have to go.
     */
    public static final String CMD_DISABLE_EO_UPDATER_BOOT =
            "pm disable com.electricobjects.updater/.BootCompletedReceiver";
    /** The package those two components live in, for {@link VendorPollers}. */
    public static final String PKG_EO_UPDATER = "com.electricobjects.updater";

    /** Pause between disable and enable so the framework can settle. */
    public static final long WIFI_TOGGLE_SETTLE_MS = 3000L;

    public static final List<String> WIFI_REPAIR_COMMANDS = Collections.unmodifiableList(
            Arrays.asList(CMD_WIFI_DISABLE, CMD_WIFI_ENABLE));
    public static final List<String> REBOOT_COMMANDS = Collections.unmodifiableList(
            Arrays.asList(CMD_REBOOT));
    public static final List<String> VENDOR_UPDATER_COMMANDS = Collections.unmodifiableList(
            Arrays.asList(CMD_DISABLE_VENDOR_UPDATER, CMD_DISABLE_EO_UPDATER_CHECK,
                    CMD_DISABLE_EO_UPDATER_BOOT));

    private static final Set<String> KNOWN_COMMANDS = Collections.unmodifiableSet(
            new HashSet<String>(WIFI_REPAIR_COMMANDS));

    /** Runs one fixed command line; injected so the sequence logic is testable without sockets. */
    public interface Executor {
        AdbdInstallClient.Result run(String command);
    }

    /** Sleep hook, so tests do not actually wait out the Wi-Fi toggle settle. */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /** One repair step and what became of it — the raw material for a log line and {@code /state}. */
    public static final class Step {
        public final String command;
        public final AdbdInstallClient.Outcome outcome;
        public final String detail;

        Step(String command, AdbdInstallClient.Outcome outcome, String detail) {
            this.command = command;
            this.outcome = outcome;
            this.detail = detail == null ? "" : detail;
        }

        public boolean ok() {
            return outcome == AdbdInstallClient.Outcome.SUCCESS;
        }
    }

    private DeviceRepair() {
    }

    /**
     * Cycles the radio — the framework's own restart of the vendor Wi-Fi stack.
     *
     * <p>Stops at the first step adbd could not be reached for: if the loopback daemon is dead, the
     * remaining commands cannot land either, and the ladder should escalate (next attempt after the
     * repair spacing, then reboot) instead of waiting out one socket timeout per command.
     *
     * @return the steps actually taken, in order
     */
    public static List<Step> repairWifi(Executor executor, Sleeper sleeper) throws InterruptedException {
        List<Step> steps = new ArrayList<Step>();

        Step disable = runStep(executor, CMD_WIFI_DISABLE);
        steps.add(disable);
        if (disable.outcome == AdbdInstallClient.Outcome.UNREACHABLE) {
            return steps;
        }

        sleeper.sleep(WIFI_TOGGLE_SETTLE_MS);
        steps.add(runStep(executor, CMD_WIFI_ENABLE));
        return steps;
    }

    /** Reboots the frame. */
    public static List<Step> reboot(Executor executor) {
        List<Step> steps = new ArrayList<Step>(1);
        steps.add(runStep(executor, CMD_REBOOT));
        return steps;
    }

    /**
     * Issues every silencing command the ROM needs, one per entry in {@link
     * #VENDOR_UPDATER_COMMANDS} — the whole package where the package is the unit, the named
     * components where it is not. Best effort, no fallback: whatever {@code pm} said is the answer,
     * and a frame missing one of the packages carries on to the next. As in {@link #repairWifi}, an
     * adbd that could not be reached at all stops the sequence — the remaining commands cannot land
     * either.
     *
     * <p>What this returns describes the transport, not the frame: a {@code =ok} proves adbd answered,
     * and an already-silent frame must not force-stop a package again just to hear one. Ask {@link
     * VendorPollers} which is which, and call this only for a poller it reports as not yet silenced
     * the way this vocabulary intends.
     */
    public static List<Step> silenceVendorUpdater(Executor executor) {
        List<Step> steps = new ArrayList<Step>(VENDOR_UPDATER_COMMANDS.size());
        for (String command : VENDOR_UPDATER_COMMANDS) {
            Step step = runStep(executor, command);
            steps.add(step);
            if (step.outcome == AdbdInstallClient.Outcome.UNREACHABLE) {
                break;
            }
        }
        return steps;
    }

    private static Step runStep(Executor executor, String command) {
        AdbdInstallClient.Result result = executor.run(command);
        return new Step(command, result.outcome, result.detail);
    }

    /** Renders steps for the log, e.g. {@code "svc wifi disable=ok; svc wifi enable=failed (…)"}. */
    public static String describe(List<Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(step.command).append('=');
            if (step.ok()) {
                sb.append("ok");
            } else {
                sb.append(step.outcome.name().toLowerCase(java.util.Locale.US));
            }
            if (!step.detail.isEmpty()) {
                sb.append(" (").append(step.detail).append(')');
            }
        }
        return sb.toString();
    }

    /** True when a command is one of the fixed repair lines (guards against interpolated input). */
    public static boolean isKnownCommand(String command) {
        return KNOWN_COMMANDS.contains(command) || REBOOT_COMMANDS.contains(command)
                || VENDOR_UPDATER_COMMANDS.contains(command);
    }
}
