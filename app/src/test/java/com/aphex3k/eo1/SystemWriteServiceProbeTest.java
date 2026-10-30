package com.aphex3k.eo1;

import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The read that decides whether supplying {@code libsystemwriteservice.so} fixes an EO1 or freezes it.
 *
 * <p>{@code libTsPlayer-jni.so} waits on the {@code system_write} registrant in a loop with no retry
 * counter and no timeout, holding a process-global mutex, from the main looper. So the three verdicts
 * here are not equally weighted: {@code absent} is the one that must never be reported by accident,
 * because it is the answer that decides not to ship the fix — and a probe that could not read is not
 * evidence that nothing is registered.
 */
public class SystemWriteServiceProbeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aFileThatIsThereIsPresent() throws Exception {
        File dir = tmp.newFolder("lib");
        File so = new File(dir, "libsystemwriteservice.so");
        write(so);
        assertEquals(SystemWriteServiceProbe.PRESENT,
                SystemWriteServiceProbe.probePath(so.getAbsolutePath()));
    }

    /** The ordinary case on a frame: the directory is there, the library is not. */
    @Test
    public void aFileThatIsNotThereIsAbsentNotUnknown() throws Exception {
        File dir = tmp.newFolder("empty-lib");
        assertEquals(SystemWriteServiceProbe.ABSENT,
                SystemWriteServiceProbe.probePath(new File(dir, "libsystemwriteservice.so")
                        .getAbsolutePath()));
        assertEquals("and so is a path whose parent was never installed",
                SystemWriteServiceProbe.ABSENT,
                SystemWriteServiceProbe.probePath("/vendor/lib/libsystemwriteservice.so"));
    }

    /**
     * The asymmetry the whole class exists for. {@code File.isFile()} returns false for a directory it
     * cannot search, exactly as it does for a missing file — and it does not throw, because Android
     * installs no security manager. So a ROM that <em>has</em> the client behind an unreadable
     * directory must not be reported as one that lacks it, because {@code absent} is the verdict that
     * sends an operator to replace a file that is already there.
     */
    @Test
    public void aDirectoryThatCannotBeSearchedIsNotAnAbsentFile() throws Exception {
        File dir = tmp.newFolder("locked-lib");
        File so = new File(dir, "libsystemwriteservice.so");
        write(so);
        assumeTrue("this host let us drop the search bit", dir.setExecutable(false));
        try {
            assertEquals(SystemWriteServiceProbe.UNREADABLE,
                    SystemWriteServiceProbe.probePath(so.getAbsolutePath()));
            assertEquals("and the aggregate refuses to guess",
                    SystemWriteServiceProbe.UNREADABLE,
                    SystemWriteServiceProbe.readClient(new String[]{so.getAbsolutePath()}).verdict);
        } finally {
            dir.setExecutable(true);
        }
    }

    @Test
    public void oneReachablePathSettlesWhatAnotherCannotTell() throws Exception {
        File locked = tmp.newFolder("locked2");
        File so = new File(locked, "libsystemwriteservice.so");
        write(so);
        File found = tmp.newFolder("lib2");
        File so2 = new File(found, "libsystemwriteservice.so");
        write(so2);
        boolean lockedOk = locked.setExecutable(false);
        // A present path anywhere in the list wins outright, whether or not a sibling was readable.
        SystemWriteServiceProbe.ClientRead r = SystemWriteServiceProbe.readClient(
                new String[]{so.getAbsolutePath(), so2.getAbsolutePath()});
        assertEquals(SystemWriteServiceProbe.PRESENT, r.verdict);
        assertEquals(so2.getAbsolutePath(), r.path);
        if (lockedOk) {
            locked.setExecutable(true);
        }
    }

    @Test
    public void theClientIsFoundWhereItWasPut() throws Exception {
        File here = tmp.newFolder("lib");
        File so = new File(here, "libsystemwriteservice.so");
        write(so);
        SystemWriteServiceProbe.ClientRead r = SystemWriteServiceProbe.readClient(
                new String[]{"/definitely/not/here/libsystemwriteservice.so", so.getAbsolutePath()});
        assertEquals(SystemWriteServiceProbe.PRESENT, r.verdict);
        assertEquals(so.getAbsolutePath(), r.path);
    }

    @Test
    public void aClientThatIsNowhereIsAbsentWithNoPath() {
        SystemWriteServiceProbe.ClientRead r = SystemWriteServiceProbe.readClient(new String[]{
                "/system/lib/libsystemwriteservice.so", "/vendor/lib/libsystemwriteservice.so"});
        assertEquals("nothing was registered under those names on the test host",
                SystemWriteServiceProbe.ABSENT, r.verdict);
        assertNull(r.path);
    }

    /**
     * {@code /state} is assembled on a web-server thread. A cached object that is mutated in place
     * races whoever is walking it, and AGENTS.md is explicit that an exception on that thread reaches
     * the handler that calls {@code System.exit(2)} — a diagnostics endpoint that can stop the frame.
     */
    @Test
    public void everyReportIsAFreshObject() {
        JsonObject a = SystemWriteServiceProbe.report();
        JsonObject b = SystemWriteServiceProbe.report();
        assertNotSame("the same instance handed to two readers is a torn read waiting to happen", a, b);
        assertTrue("and it names the verdict field", a.has("registrant"));
        assertTrue(a.has("romClient"));
        assertTrue(a.has("wouldLoadClientSafely"));
    }

    @Test
    public void pendingIsItsOwnVerdictNotAnAbsence() {
        assertEquals(SystemWriteServiceProbe.PENDING,
                SystemWriteServiceProbe.report().get("registrant").getAsString());
        assertFalse("a frame that has not measured yet must not read as one that cannot be fixed",
                SystemWriteServiceProbe.report().get("wouldLoadClientSafely").getAsBoolean());
    }

    private static void write(File f) throws Exception {
        FileWriter w = new FileWriter(f);
        try {
            w.write("not a real library, just bytes to find\n");
        } finally {
            w.close();
        }
    }
}
