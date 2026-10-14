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

    /** Pause between disable and enable so the framework can settle. */
    public static final long WIFI_TOGGLE_SETTLE_MS = 3000L;

    public static final List<String> WIFI_REPAIR_COMMANDS = Collections.unmodifiableList(
            Arrays.asList(CMD_WIFI_DISABLE, CMD_WIFI_ENABLE));
    public static final List<String> REBOOT_COMMANDS = Collections.unmodifiableList(
            Arrays.asList(CMD_REBOOT));

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
        return KNOWN_COMMANDS.contains(command) || REBOOT_COMMANDS.contains(command);
    }
}
