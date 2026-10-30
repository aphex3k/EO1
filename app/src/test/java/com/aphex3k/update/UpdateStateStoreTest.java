package com.aphex3k.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

/**
 * JVM tests for {@link UpdateStateStore} against a plain file (the same path the app uses at
 * {@code context.getFilesDir()/update-state.json}).
 */
public class UpdateStateStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private UpdateState stateIn(UpdateState.State state, String lastError) {
        UpdateState s = UpdateState.idle();
        s.state = state;
        s.expectedVersionCode = 7;
        s.expectedVersionName = "1.7.0";
        s.manifestSha256 = "110009dcee21620b166f3abfecb5eff7a873be729d1c2d53822e7acc5f34eb9b";
        s.expectedSizeBytes = 12345;
        s.lastCheckedMs = 1700000000000L;
        s.lastError = lastError;
        s.attempts = 3;
        return s;
    }

    @Test
    public void missingFileReadsAsIdle() {
        UpdateStateStore store = new UpdateStateStore(new File(folder.getRoot(), "update-state.json"));
        UpdateState s = store.read();
        assertEquals(UpdateState.State.IDLE, s.state);
        assertEquals(0, s.attempts);
        assertEquals(0, s.lastCheckedMs);
    }

    @Test
    public void writeThenReadRoundTrips() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        UpdateStateStore store = new UpdateStateStore(f);
        assertTrue(store.write(stateIn(UpdateState.State.STAGED, "")));

        UpdateStateStore fresh = new UpdateStateStore(f);
        UpdateState s = fresh.read();
        assertEquals(UpdateState.State.STAGED, s.state);
        assertEquals(7, s.expectedVersionCode);
        assertEquals("1.7.0", s.expectedVersionName);
        assertEquals(12345, s.expectedSizeBytes);
        assertEquals(1700000000000L, s.lastCheckedMs);
        assertEquals(3, s.attempts);
        assertEquals("", s.lastError);
    }

    @Test
    public void corruptFileReadsAsIdle() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        FileWriter w = new FileWriter(f);
        w.write("{not valid json!!");
        w.close();
        assertEquals(UpdateState.State.IDLE, new UpdateStateStore(f).read().state);
    }

    @Test
    public void emptyFileReadsAsIdle() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        f.createNewFile();
        assertEquals(UpdateState.State.IDLE, new UpdateStateStore(f).read().state);
    }

    @Test
    public void jsonWithoutStateFieldReadsAsIdle() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        FileWriter w = new FileWriter(f);
        w.write("{}");
        w.close();
        assertEquals(UpdateState.State.IDLE, new UpdateStateStore(f).read().state);
    }

    @Test
    public void failedStateSurvivesRoundTrip() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        assertTrue(new UpdateStateStore(f).write(stateIn(UpdateState.State.FAILED, "sha256-mismatch")));

        UpdateState s = new UpdateStateStore(f).read();
        assertEquals(UpdateState.State.FAILED, s.state);
        assertEquals("sha256-mismatch", s.lastError);
        // pending-update metadata carries over so the next cycle can resume/compare
        assertEquals(7, s.expectedVersionCode);
    }

    @Test
    public void twoStoresShareOneFile() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        new UpdateStateStore(f).write(stateIn(UpdateState.State.DOWNLOADING, ""));
        UpdateState s = new UpdateStateStore(f).read();
        assertEquals(UpdateState.State.DOWNLOADING, s.state);
    }

    @Test
    public void writeCreatesParentDirectories() {
        File f = new File(folder.getRoot(), "a/b/c/update-state.json");
        assertTrue(new UpdateStateStore(f).write(UpdateState.idle()));
        assertTrue(f.isFile());
    }

    @Test
    public void writeNullIsRejected() {
        UpdateStateStore store = new UpdateStateStore(new File(folder.getRoot(), "update-state.json"));
        assertFalse(store.write(null));
    }

    /**
     * A state file written before these fields existed must read back with them unset — an absent
     * {@code installFiredAtMs} means "nothing is owed a settle window", not "long ago".
     */
    @Test
    public void preExistingStateFileReadsWithNewFieldsUnset() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        try (FileWriter w = new FileWriter(f)) {
            w.write("{\"state\":\"INSTALL_PENDING\",\"expectedVersionCode\":7,"
                    + "\"expectedVersionName\":\"1.7.0\",\"manifestSha256\":\"aa\","
                    + "\"expectedSizeBytes\":12345,\"lastCheckedMs\":1700000000000,"
                    + "\"lastError\":\"\",\"attempts\":3}");
        }
        UpdateState s = new UpdateStateStore(f).read();
        assertEquals(UpdateState.State.INSTALL_PENDING, s.state);
        assertEquals(0L, s.installFiredAtMs);
        assertFalse(s.needsHumanConfirmation);
        assertFalse("an old state file never claims an open adb window", s.armedTcpAdb);
        // Same reasoning as installFiredAtMs: a stage of unknown origin is not the update host's to
        // replace, so the absence of this field must read as "leave it alone", never as "manifest".
        assertEquals(UpdateState.SOURCE_UNKNOWN, s.stagedSource);
    }

    @Test
    public void stagedSourceRoundTrips() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        UpdateStateStore store = new UpdateStateStore(f);
        UpdateState uploaded = stateIn(UpdateState.State.STAGED, "");
        uploaded.stagedSource = UpdateState.SOURCE_UPLOAD;
        assertTrue(store.write(uploaded));

        assertEquals(UpdateState.SOURCE_UPLOAD, store.read().stagedSource);

        UpdateState fromHost = stateIn(UpdateState.State.STAGED, "");
        fromHost.stagedSource = UpdateState.SOURCE_MANIFEST;
        assertTrue(store.write(fromHost));
        assertEquals(UpdateState.SOURCE_MANIFEST, store.read().stagedSource);
    }

    @Test
    public void installTimingAndHumanFlagRoundTrip() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        UpdateStateStore store = new UpdateStateStore(f);
        UpdateState s = stateIn(UpdateState.State.STAGED, "needs-human-confirmation");
        s.installFiredAtMs = 1700000009000L;
        s.needsHumanConfirmation = true;
        s.armedTcpAdb = true;
        assertTrue(store.write(s));

        UpdateState read = store.read();
        assertEquals(1700000009000L, read.installFiredAtMs);
        assertTrue(read.needsHumanConfirmation);
        assertTrue("the process that dies mid-install must leave the next one a note to close adb",
                read.armedTcpAdb);
        assertEquals("needs-human-confirmation", read.lastError);
    }

    /**
     * The fetched identity has to reach disk, because it is the only thing that tells a later
     * process which of the two builds the bytes on disk are.
     */
    @Test
    public void fetchedIdentityRoundTrips() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        UpdateStateStore store = new UpdateStateStore(f);
        UpdateState s = stateIn(UpdateState.State.DOWNLOADING, "");
        s.stagedSource = UpdateState.SOURCE_UPLOAD;
        s.fetchVersionCode = 9;
        s.fetchVersionName = "1.9.0";
        s.fetchSha256 = "bb0009dcee21620b166f3abfecb5eff7a873be729d1c2d53822e7acc5f34eb9b";
        s.fetchSizeBytes = 999L;
        assertTrue(store.write(s));

        UpdateState read = store.read();
        assertEquals(UpdateState.SOURCE_UPLOAD, read.stagedSource);
        assertEquals(7, read.expectedVersionCode);
        assertEquals(9, read.fetchVersionCode);
        assertEquals("1.9.0", read.fetchVersionName);
        assertEquals(s.fetchSha256, read.fetchSha256);
        assertEquals(999L, read.fetchSizeBytes);
    }

    @Test
    public void deleteRemovesState() throws Exception {
        File f = new File(folder.getRoot(), "update-state.json");
        new UpdateStateStore(f).write(stateIn(UpdateState.State.STAGED, ""));
        assertTrue(f.exists());

        UpdateStateStore store = new UpdateStateStore(f);
        store.delete();
        assertFalse(f.exists());
        assertEquals(UpdateState.State.IDLE, store.read().state);
    }
}
