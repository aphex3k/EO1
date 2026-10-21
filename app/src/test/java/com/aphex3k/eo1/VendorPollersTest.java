package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JVM tests for the adbd-free read side of vendor silencing — the half that decides whether a frame
 * opens a socket at all, and therefore the half whose mistakes are silent in the field.
 */
public class VendorPollersTest {

    private static final String VENDOR = DeviceRepair.PKG_VENDOR_UPDATER;
    private static final String VENDOR_RECEIVER = "com.geniatech.upgrade.UpdateReceiver";
    private static final String EO = DeviceRepair.PKG_EO_UPDATER;
    private static final String EO_CHECK = "com.electricobjects.updater.CheckUpdateReceiver";
    private static final String EO_BOOT = "com.electricobjects.updater.BootCompletedReceiver";

    /** Answers from a hand-written picture of {@code package-restrictions.xml}. */
    private static final class FakeReader implements VendorPollers.Reader {
        final Map<String, Integer> apps = new HashMap<String, Integer>();
        final Map<String, Integer> components = new HashMap<String, Integer>();
        boolean appsUnreadable;

        FakeReader app(String pkg, int state) {
            apps.put(pkg, state);
            return this;
        }

        FakeReader component(String pkg, String cls, int state) {
            components.put(pkg + "/" + cls, state);
            return this;
        }

        @Override
        public int applicationEnabledSetting(String packageName) {
            if (appsUnreadable) {
                return VendorPollers.UNREADABLE;
            }
            Integer state = apps.get(packageName);
            return state != null ? state : VendorPollers.NO_PACKAGE;
        }

        @Override
        public int componentEnabledSetting(String packageName, String className) {
            Integer state = components.get(packageName + "/" + className);
            return state != null ? state : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    private static VendorPollers.Verdict verdict(List<VendorPollers.Verdict> vs, String pkg) {
        for (int i = 0; i < vs.size(); i++) {
            if (vs.get(i).poller.packageName.equals(pkg)) {
                return vs.get(i);
            }
        }
        throw new AssertionError("no verdict for " + pkg + " in " + VendorPollers.summarize(vs));
    }

    @Test
    public void aPackageDisabledWholeIsQuietEvenThoughEveryComponentReadsDefault() {
        // The trap this whole class exists to avoid: getComponentEnabledSetting consults only the
        // component sets, so a package disabled at package level answers DEFAULT for everything it
        // contains. A components-first check would call this frame armed and force-stop the package
        // again on every single launch.
        FakeReader reader = new FakeReader().app(VENDOR, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        List<VendorPollers.Verdict> verdicts = VendorPollers.audit(reader);

        VendorPollers.Verdict vendor = verdict(verdicts, VENDOR);
        assertEquals(VendorPollers.QUIET, vendor.state);
        assertTrue(vendor.detail, vendor.detail.contains("DISABLED"));
        assertTrue(VendorPollers.allQuiet(verdicts));
    }

    @Test
    public void componentDisablesAreQuietForThePollerWhoseCommandIsComponentLevel() {
        FakeReader reader = new FakeReader()
                .app(EO, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                .component(EO, EO_CHECK, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                .component(EO, EO_BOOT, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                .app(VENDOR, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        List<VendorPollers.Verdict> verdicts = VendorPollers.audit(reader);

        assertEquals(VendorPollers.QUIET, verdict(verdicts, EO).state);
        assertTrue(VendorPollers.allQuiet(verdicts));
    }

    @Test
    public void aWakePathClosedByTheOldReceiverOnlyFormIsNotTheIntendedSilence() {
        // Exactly the state of the EO2 this change was written for: the package carries an explicit
        // ENABLED (left behind by a bench `pm enable`) and only .UpdateReceiver is in
        // <disabled-components>. The hourly wake is genuinely dead — but .UpdateService,
        // IntervalCheckUpdateService and the vendor UI paths are all still resolvable, so this frame
        // has to be handed the package-level command once. Reporting it quiet is how the widening
        // would never land anywhere.
        FakeReader reader = new FakeReader()
                .app(VENDOR, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                .component(VENDOR, VENDOR_RECEIVER, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                .app(EO, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        List<VendorPollers.Verdict> verdicts = VendorPollers.audit(reader);

        VendorPollers.Verdict vendor = verdict(verdicts, VENDOR);
        assertEquals(VendorPollers.ARMED, vendor.state);
        assertTrue(vendor.detail, vendor.detail.contains("wake path closed by the older component form"));
        assertFalse(VendorPollers.allQuiet(verdicts));
        assertEquals("one write, and it is the package-level one",
                Arrays.asList(DeviceRepair.VENDOR_UPDATER_COMMANDS.get(0)),
                Arrays.asList(VendorPollers.needsHuman(verdicts).replace("needs human: adb shell ", "")
                        .split(" && adb shell ")));
    }

    @Test
    public void anUntouchedFrameIsArmedAndItsFixTravelsInTheLogLine() {
        FakeReader reader = new FakeReader()
                .app(VENDOR, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                .app(EO, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);

        List<VendorPollers.Verdict> verdicts = VendorPollers.audit(reader);

        assertFalse(VendorPollers.allQuiet(verdicts));
        String fix = VendorPollers.needsHuman(verdicts);
        assertTrue(fix, fix.contains("adb shell pm disable com.geniatech.upgrade"));
        assertTrue(fix, fix.contains("adb shell pm disable com.electricobjects.updater/.CheckUpdateReceiver"));
        assertTrue(fix, fix.contains("adb shell pm disable com.electricobjects.updater/.BootCompletedReceiver"));
    }

    @Test
    public void aRomWithoutThosePollersNeedsNothingAndNobodysAttention() {
        FakeReader reader = new FakeReader();

        List<VendorPollers.Verdict> verdicts = VendorPollers.audit(reader);

        assertEquals(VendorPollers.ABSENT, verdict(verdicts, VENDOR).state);
        assertTrue("an absent poller is a silent poller", VendorPollers.allQuiet(verdicts));
        assertEquals("", VendorPollers.needsHuman(verdicts));
    }

    @Test
    public void aReadThatWasRefusedIsNeverReportedAsSilent() {
        // A future or customized framework that gates the getter behind CHANGE_COMPONENT_ENABLED_STATE
        // must send the frame down the old write-once path, not convince it that it is quiet.
        FakeReader reader = new FakeReader().app(EO, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        reader.appsUnreadable = true;

        List<VendorPollers.Verdict> verdicts = VendorPollers.audit(reader);

        assertEquals(VendorPollers.UNVERIFIABLE, verdict(verdicts, VENDOR).state);
        assertFalse(VendorPollers.allQuiet(verdicts));
    }

    @Test
    public void aComponentWhosePackageIsDisabledDoesNotHaveToBeDisabledTwice() {
        FakeReader reader = new FakeReader()
                .app(VENDOR, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
                .app(EO, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                .component(EO, EO_CHECK, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        List<VendorPollers.Verdict> verdicts = VendorPollers.audit(reader);

        assertEquals(VendorPollers.QUIET, verdict(verdicts, VENDOR).state);
        assertEquals("one of the EO wake paths is still live",
                VendorPollers.ARMED, verdict(verdicts, EO).state);
        assertTrue(verdict(verdicts, EO).detail,
                verdict(verdicts, EO).detail.contains("BootCompletedReceiver"));
    }

    @Test
    public void theAuditAndTheVocabularyDescribeTheSamePollers() {
        // DeviceRepair owns the commands, VendorPollers owns what a closed poller looks like. Either
        // file changing alone makes the frame report a verdict that no command can produce, so the
        // pairing is pinned here rather than left to a reader noticing.
        Set<String> commands = new HashSet<String>();
        List<String> flattened = new ArrayList<String>();
        for (int i = 0; i < VendorPollers.POLLERS.size(); i++) {
            VendorPollers.Poller p = VendorPollers.POLLERS.get(i);
            commands.addAll(Arrays.asList(p.commands));
            flattened.addAll(Arrays.asList(p.commands));
        }
        assertEquals(new HashSet<String>(DeviceRepair.VENDOR_UPDATER_COMMANDS), commands);
        assertEquals("same commands, same order",
                DeviceRepair.VENDOR_UPDATER_COMMANDS, flattened);
    }
}
