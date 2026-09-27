package com.aphex3k.eo1;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Thread-safe, low-overhead application log: an in-memory ring buffer of the most recent events plus
 * a small rolling on-disk file. This is the "past state" source for the web server's {@code /logs}
 * endpoints.
 *
 * <p>It is deliberately independent of {@code android.util.Log}: the app hooks its own curated event
 * stream (see {@code MainActivity.debugInformationProvided}) into this logger, so we capture real
 * events without intercepting every {@code Log} call.
 *
 * <p>The core operates on a plain {@link File} so it is unit-testable on the JVM;
 * {@link #AppLogger(Context)} is the convenience entry point.
 */
public final class AppLogger {

    /** Ring buffer capacity (number of retained recent events). */
    public static final int CAPACITY = 500;

    /** Rolling file size cap before rotation. */
    private static final long MAX_FILE_BYTES = 256L * 1024L;

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String FILE_NAME = "eo1-app.log";

    private static final class Entry {
        final long ts;
        final String level;
        final String tag;
        final String msg;

        Entry(long ts, String level, String tag, String msg) {
            this.ts = ts;
            this.level = level;
            this.tag = tag;
            this.msg = msg;
        }
    }

    private final File logFile;
    private final File rotatedFile;
    private final Entry[] ring = new Entry[CAPACITY];
    private int head = 0;
    private int count = 0;
    private final SimpleDateFormat tsFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    /**
     * Creates a logger that rolls the file at {@code logFile}.
     */
    public AppLogger(File logFile) {
        this.logFile = logFile;
        this.rotatedFile = new File(logFile.getParentFile(), logFile.getName() + ".1");
    }

    /**
     * Creates a logger using the app's internal files dir ({@code filesDir/eo1-app.log}).
     */
    public AppLogger(Context context) {
        this(new File(context.getFilesDir(), FILE_NAME));
    }

    /**
     * Records an INFO event.
     */
    public synchronized void info(String tag, String msg) {
        append("I", tag, msg);
    }

    /**
     * Records an ERROR event.
     */
    public synchronized void error(String tag, String msg) {
        append("E", tag, msg);
    }

    private void append(String level, String tag, String msg) {
        long ts = System.currentTimeMillis();
        Entry e = new Entry(ts, level, tag, msg);
        ring[head] = e;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        writeFileLine(e);
    }

    private void writeFileLine(Entry e) {
        try {
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                rotate();
            }
            Writer w = new OutputStreamWriter(new FileOutputStream(logFile, true), UTF_8);
            try {
                w.write(format(e));
                w.write('\n');
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
            // Logging must never crash the app.
        }
    }

    private void rotate() {
        try {
            if (rotatedFile.exists() && !rotatedFile.delete()) {
                // best effort
            }
            if (!logFile.renameTo(rotatedFile)) {
                // If rename fails, just start appending to a truncated file.
                FileOutputStream f = new FileOutputStream(logFile, false);
                f.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private String format(Entry e) {
        return tsFormat.format(new Date(e.ts)) + " " + e.level + " [" + e.tag + "] " + e.msg;
    }

    private Entry get(int indexFromOldest) {
        int pos;
        if (count < CAPACITY) {
            pos = indexFromOldest;
        } else {
            pos = (head + indexFromOldest) % CAPACITY;
        }
        return ring[pos];
    }

    /**
     * Returns the most recent {@code n} events as plain text lines, oldest first.
     */
    public synchronized String tail(int n) {
        int m = Math.min(Math.max(n, 0), count);
        StringBuilder sb = new StringBuilder();
        for (int i = count - m; i < count; i++) {
            sb.append(format(get(i))).append('\n');
        }
        return sb.toString();
    }

    /**
     * Returns the most recent {@code n} events as a JSON array string, oldest first.
     */
    public synchronized String tailJson(int n) {
        int m = Math.min(Math.max(n, 0), count);
        JsonArray arr = new JsonArray();
        for (int i = count - m; i < count; i++) {
            Entry e = get(i);
            JsonObject o = new JsonObject();
            o.addProperty("ts", e.ts);
            o.addProperty("level", e.level);
            o.addProperty("tag", e.tag);
            o.addProperty("msg", e.msg);
            arr.add(o);
        }
        return arr.toString();
    }

    /**
     * Returns the most recent {@code n} lines of the on-disk rolling log, oldest first.
     */
    public synchronized String tailFile(int n) {
        if (!logFile.exists()) {
            return "";
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new java.io.InputStreamReader(new FileInputStream(logFile), UTF_8));
            List<String> lines = new ArrayList<String>();
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
            int m = Math.min(Math.max(n, 0), lines.size());
            StringBuilder sb = new StringBuilder();
            for (int i = lines.size() - m; i < lines.size(); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
