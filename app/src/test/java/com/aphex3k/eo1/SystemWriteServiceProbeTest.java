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
    public void aFoundBinderIsPresentAndANullOneIsAbsent() {
        assertEquals(SystemWriteServiceProbe.PRESENT,
                SystemWriteServiceProbe.classify(Boolean.TRUE, false));
        assertEquals(SystemWriteServiceProbe.ABSENT,
                SystemWriteServiceProbe.classify(Boolean.FALSE, false));
    }

    /** The asymmetry the whole class exists for: a failed read is never an absent read. */
    @Test
    public void anUnreadableLookupIsNotReportedAsAbsent() {
        assertEquals(SystemWriteServiceProbe.UNREADABLE,
                SystemWriteServiceProbe.classify(Boolean.FALSE, true));
        assertEquals("and it outranks the null it happens to have",
                SystemWriteServiceProbe.UNREADABLE,
                SystemWriteServiceProbe.classify(null, true));
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
