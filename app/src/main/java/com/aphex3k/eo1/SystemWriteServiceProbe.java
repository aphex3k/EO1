package com.aphex3k.eo1;

import com.google.gson.JsonObject;

import java.io.File;
import java.lang.reflect.Method;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether this ROM has the Amlogic system-write service, read without loading anything.
 *
 * <p>{@code libTsPlayer-jni.so} needs {@code libsystemwriteservice.so}, and its
 * {@code getSystemWriteService()} polls for the {@code system_write} registrant in a loop with no
 * retry counter and no timeout — {@code usleep(500ms)} back to {@code getService}, holding a
 * process-global mutex the whole time, reached from the main looper by {@code createPlayer}. So
 * supplying the missing client library is safe on a frame that registers the service and freezes the
 * frame on one that does not, and those two states look identical from the linker's error alone. This
 * class is the read that tells them apart, taken through the framework rather than through the vendor
 * blob, so taking it cannot itself hang anything.
 *
 * <p>The lookup is {@code ServiceManager.getService(String)}, which is one {@code CHECK_SERVICE}
 * transaction to servicemanager and returns null when nothing is registered — unlike
 * {@code waitForService}, which is the waiting variant. It still runs on a worker thread, because
 * "cannot block" is a reading of AOSP rather than a measurement of this ROM, and the price of being
 * wrong is a frame with a dead UI. Every outcome is reported, including the ones where the probe could
 * not answer: an unreadable verdict must never read as an absent one, because "absent" is the answer
 * that decides not to ship a fix.
 */
public final class SystemWriteServiceProbe {

    public static final String PENDING = "pending";
    public static final String PRESENT = "present";
    public static final String ABSENT = "absent";
    public static final String UNREADABLE = "unreadable";

    /** The name {@code libTsPlayer-jni.so} waits on, read from its own {@code .rodata}. */
    static final String SERVICE_NAME = "system_write";

    private static final String SONAME = "libsystemwriteservice.so";

    /** Fixed paths — never derived from configuration, and the only reason they are named here. */
    private static final String[] CLIENT_PATHS = {
            "/system/lib/" + SONAME,
            "/vendor/lib/" + SONAME,
    };

    private static final String[] AMSTREAM_DIRS = {"/dev", "/sys/class"};

    /**
     * The last settled answer. Swapped by reference, never mutated in place: {@code /state} is
     * assembled on a web-server thread, and a reader that walks a half-written object is an exception
     * on a thread whose contract is that it never reaches the crash handler.
     */
    private static volatile Snapshot cached = Snapshot.pending();

    private static final AtomicBoolean started = new AtomicBoolean(false);

    private SystemWriteServiceProbe() {
    }

    /**
     * Asks once per process, on a daemon thread, and replaces the cached answer when it settles.
     *
     * <p>Once, and not once per caller: {@code MainActivity#onCreate} runs again on every activity
     * recreation, and this is a read of a static table that cannot change during a process's life.
     */
    public static void refreshAsync() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cached = measure();
                } catch (Throwable ignored) {
                    // The point of this probe is that it can be wrong without cost. Nothing here may
                    // become the reason a frame stops working.
                    cached = Snapshot.failed();
                }
            }
        }, "eo1-swprobe");
        t.setDaemon(true);
        t.start();
    }

    /** @return a fresh object describing the last settled answer; safe to hand straight to Gson. */
    public static JsonObject report() {
        return cached.toJson();
    }

    private static Snapshot measure() {
        ClientRead client = readClient(CLIENT_PATHS);
        return new Snapshot(readRegistrant(), client.verdict, client.path, countAmstreamNodes());
    }

    /** The reflective read, separated so the classification can be tested without a framework. */
    static String classify(Boolean found, boolean lookupThrew) {
        if (lookupThrew) {
            return UNREADABLE;
        }
        return Boolean.TRUE.equals(found) ? PRESENT : ABSENT;
    }

    /** The client library's state on this ROM, and where it was found. */
    static final class ClientRead {
        final String verdict;
        final String path;

        ClientRead(String verdict, String path) {
            this.verdict = verdict;
            this.path = path;
        }
    }

    /**
     * Package-visible for tests. A found path wins outright; a directory this process could not read
     * is reported as unreadable rather than as a missing file, because "absent" is the verdict that
     * decides to ship a replacement for it.
     */
    static ClientRead readClient(String[] paths) {
        boolean unreadable = false;
        for (int i = 0; i < paths.length; i++) {
            try {
                if (new File(paths[i]).isFile()) {
                    return new ClientRead(PRESENT, paths[i]);
                }
            } catch (SecurityException e) {
                unreadable = true;
            }
        }
        return new ClientRead(unreadable ? UNREADABLE : ABSENT, null);
    }

    private static int countAmstreamNodes() {
        int n = 0;
        for (int i = 0; i < AMSTREAM_DIRS.length; i++) {
            File[] kids = new File(AMSTREAM_DIRS[i]).listFiles();
            if (kids == null) {
                continue;
            }
            for (int k = 0; k < kids.length; k++) {
                String name = kids[k].getName();
                if (name != null && name.startsWith("amstream")) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * {@code android.os.ServiceManager} is hidden, so it is absent from the compile-time android.jar
     * and reflection is the only way in — the same shape as the {@code SystemProperties} write in
     * {@code AdbdInstallClient}.
     */
    private static String readRegistrant() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            Object binder = getService.invoke(null, SERVICE_NAME);
            return classify(binder != null, false);
        } catch (Throwable t) {
            return UNREADABLE;
        }
    }

    private static final class Snapshot {

        private final String registrant;
        private final String clientVerdict;
        private final String clientPath;
        private final int amstreamNodes;

        Snapshot(String registrant, String clientVerdict, String clientPath, int amstreamNodes) {
            this.registrant = registrant;
            this.clientVerdict = clientVerdict;
            this.clientPath = clientPath;
            this.amstreamNodes = amstreamNodes;
        }

        static Snapshot pending() {
            return new Snapshot(PENDING, PENDING, null, -1);
        }

        static Snapshot failed() {
            return new Snapshot(UNREADABLE, UNREADABLE, null, -1);
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("registrant", registrant);
            o.addProperty("service", SERVICE_NAME);
            o.addProperty("romClient", clientVerdict);
            o.addProperty("clientPath", clientPath);
            // TsPlayer drives the Amlogic planes through /dev/amstream*; -1 means "not counted", which
            // is not the same claim as 0.
            o.addProperty("amstreamNodes", amstreamNodes);
            o.addProperty("wouldLoadClientSafely", PRESENT.equals(registrant));
            return o;
        }
    }
}
