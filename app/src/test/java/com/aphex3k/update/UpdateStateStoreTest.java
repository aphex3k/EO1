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
