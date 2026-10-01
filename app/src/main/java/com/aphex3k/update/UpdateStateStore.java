package com.aphex3k.update;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * File-backed {@link UpdateState} store.
 *
 * <p>The state file must survive the installer killing this process, so writes are atomic
 * (temp file + rename) and any unreadable content degrades to {@code IDLE} — a corrupt state
 * must never take the frame down. JVM-testable on a plain {@link File}.
 */
public class UpdateStateStore {

    private final File file;

    public UpdateStateStore(File file) {
        this.file = file;
    }

    public static File defaultStateFile(Context context) {
        return new File(context.getFilesDir(), "update-state.json");
    }

    /**
     * @return the persisted state, or a fresh {@code IDLE} when the file is missing, empty
     *         or corrupt (never throws).
     */
    public synchronized UpdateState read() {
        if (!file.isFile()) {
            return UpdateState.idle();
        }
        try (FileReader reader = new FileReader(file)) {
            UpdateState state = new Gson().fromJson(reader, UpdateState.class);
            if (state == null || state.state == null) {
                return UpdateState.idle();
            }
            return state;
        } catch (Exception e) {
            return UpdateState.idle();
        }
    }

    /**
     * Atomically persists the state.
     *
     * @return false only on a real IO failure (disk full / bad rename); the previous state
     *         stays on disk.
     */
    public synchronized boolean write(UpdateState state) {
        if (state == null) {
            return false;
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        File tmp = new File(parent, file.getName() + ".tmp");
        try {
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(state);
            try (FileWriter writer = new FileWriter(tmp)) {
                writer.write(json);
            }
            if (file.exists() && !file.delete()) {
                // renameTo below still works on most filesystems; keep trying
            }
            return tmp.renameTo(file);
        } catch (Exception e) {
            return false;
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                // best effort
            }
        }
    }

    public synchronized void delete() {
        file.delete();
    }
}
