package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM tests for the repair ladder's Wi-Fi branch — the one that would have recovered the
 * 2026-10-13 wedge without anyone walking over to the frame.
 */
public class DeviceRepairTest {

    private static final class FakeExecutor implements DeviceRepair.Executor {
        final List<String> executed = new ArrayList<>();
        final Map<String, AdbdInstallClient.Result> answers =
                new HashMap<String, AdbdInstallClient.Result>();

        FakeExecutor answering(String command, AdbdInstallClient.Outcome outcome, String detail) {
            answers.put(command, new AdbdInstallClient.Result(outcome, detail));
            return this;
        }

        @Override
        public AdbdInstallClient.Result run(String command) {
            assertTrue("repair commands must come from the fixed vocabulary: " + command,
                    DeviceRepair.isKnownCommand(command));
            executed.add(command);
            AdbdInstallClient.Result answer = answers.get(command);
            return answer != null ? answer
                    : new AdbdInstallClient.Result(AdbdInstallClient.Outcome.SUCCESS, "");
        }
    }

    private static final class RecordingSleeper implements DeviceRepair.Sleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long ms) {
            sleeps.add(ms);
        }
    }

    @Test
    public void theCommandVocabularyIsExactlyTheFixedConstants() {
        // The AGENTS rule for this channel is "never interpolate input into the command line";
        // pinning the strings is what keeps that true when someone edits the ladder.
        assertEquals("svc wifi disable", DeviceRepair.CMD_WIFI_DISABLE);
        assertEquals("svc wifi enable", DeviceRepair.CMD_WIFI_ENABLE);
        assertEquals("reboot", DeviceRepair.CMD_REBOOT);
        assertEquals(Arrays.asList(DeviceRepair.CMD_WIFI_DISABLE, DeviceRepair.CMD_WIFI_ENABLE),
                DeviceRepair.WIFI_REPAIR_COMMANDS);
        assertFalse(DeviceRepair.isKnownCommand("svc wifi disable; rm -rf /data"));
        assertFalse(DeviceRepair.isKnownCommand("getprop ro.serialno"));
        assertFalse(DeviceRepair.isKnownCommand(""));
    }

    @Test
    public void cyclesTheRadioThroughTheFramework() throws Exception {
        FakeExecutor executor = new FakeExecutor();
        RecordingSleeper sleeper = new RecordingSleeper();

        List<DeviceRepair.Step> steps = DeviceRepair.repairWifi(executor, sleeper);

        assertEquals(DeviceRepair.WIFI_REPAIR_COMMANDS, executor.executed);
        assertEquals("settle between disable and enable",
                java.util.Collections.singletonList(DeviceRepair.WIFI_TOGGLE_SETTLE_MS), sleeper.sleeps);
        assertEquals(2, steps.size());
        assertTrue(steps.get(0).ok());
        assertTrue(steps.get(1).ok());
    }

    @Test
    public void stopsAtOnceWhenAdbdCannotBeReached() throws Exception {
        FakeExecutor executor = new FakeExecutor()
                .answering(DeviceRepair.CMD_WIFI_DISABLE, AdbdInstallClient.Outcome.UNREACHABLE,
                        "SocketTimeoutException");
        RecordingSleeper sleeper = new RecordingSleeper();

        List<DeviceRepair.Step> steps = DeviceRepair.repairWifi(executor, sleeper);

        assertEquals("no point waiting out a timeout per command",
                java.util.Collections.singletonList(DeviceRepair.CMD_WIFI_DISABLE), executor.executed);
        assertTrue(sleeper.sleeps.isEmpty());
        String described = DeviceRepair.describe(steps);
        assertTrue(described, described.contains("svc wifi disable=unreachable (SocketTimeoutException)"));
    }

    @Test
    public void aRefusedCommandStillGetsTheSecondTry() throws Exception {
        FakeExecutor executor = new FakeExecutor()
                .answering(DeviceRepair.CMD_WIFI_DISABLE, AdbdInstallClient.Outcome.PM_FAILED, "exit status 1");

        List<DeviceRepair.Step> steps = DeviceRepair.repairWifi(executor, new RecordingSleeper());

        assertEquals("disable failing must not skip the enable",
                DeviceRepair.WIFI_REPAIR_COMMANDS, executor.executed);
        assertFalse(steps.get(0).ok());
        assertTrue(steps.get(1).ok());
    }

    @Test
    public void rebootIssuesTheSingleFixedCommand() {
        FakeExecutor executor = new FakeExecutor();
        List<DeviceRepair.Step> steps = DeviceRepair.reboot(executor);
        assertEquals(java.util.Collections.singletonList(DeviceRepair.CMD_REBOOT), executor.executed);
        assertEquals(1, steps.size());
        assertTrue(steps.get(0).ok());
    }
}
