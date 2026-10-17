package com.aphex3k.eo1;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Minimal adb wire-protocol client used to install the staged update APK
 * without the system package-installer UI.
 *
 * <p>Sideloaded frames never hold {@code android.permission.INSTALL_PACKAGES}
 * (signature|system on this ROM), so installing from this app would show the
 * confirmation dialog — which a headless frame cannot confirm. The device's own
 * adbd on {@code 127.0.0.1:5555} runs {@code pm install} as the shell user
 * instead, and {@link #ensureTcpAdbd()} starts that daemon on a ROM (EO1) that
 * never configured it. This class speaks just enough of the classic adb wire
 * protocol (protocol 0, what Android 4.4.2 adbd speaks) to run one {@code pm
 * install} and read its output; nothing beyond {@code java.net} and reflection.
 */
public final class AdbdInstallClient {

    /** Outcome of an install attempt. */
    public enum Outcome {
        /** {@code pm install} reported success. */
        SUCCESS,
        /** adbd was reached but {@code pm install} reported a failure. */
        PM_FAILED,
        /** adbd was unreachable or the protocol exchange did not complete. */
        UNREACHABLE,
        /**
         * adbd is listening <em>because this call started it</em> — the caller owes a
         * {@link #teardownTcpAdbd()} when its action ends. Distinct from {@code SUCCESS} precisely
         * so a daemon the ROM owns is never mistaken for one the app opened.
         */
        ARMED
    }

    /** Outcome plus a short human-readable detail (log / {@code /state} friendly). */
    public static final class Result {
        public final Outcome outcome;
        public final String detail;

        Result(Outcome outcome, String detail) {
            this.outcome = outcome;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return outcome.name() + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
    }

    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 5555;

    /** Tells adbd which TCP port to bind; absent on the EO1 ROM, hence {@link #ensureTcpAdbd()}. */
    static final String PROP_TCP_PORT = "service.adb.tcp.port";
    /** init's service-control property; writing a service name to it restarts that service. */
    static final String PROP_RESTART_SERVICE = "ctl.restart";
    static final String SERVICE_ADBD = "adbd";
    /** {@code service.adb.tcp.port} value that means "do not listen" — what teardown writes back. */
    static final String PROP_TCP_PORT_OFF = "-1";

    /** Floor between adbd restarts, so a refusing ROM is asked once per window, not per tick. */
    static final long REARM_MIN_INTERVAL_MS = 10 * 60 * 1000L;
    private static final long REARM_SETTLE_MS = 1500L;
    private static final int REARM_PROBE_TRIES = 3;

    /** Wall-clock ms of the last re-arm attempt; package-visible so tests can reset it. */
    static volatile long lastRearmAttemptMs;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int PROBE_TIMEOUT_MS = 1000;
    /** {@code pm install} can be slow on the frame's storage. */
    private static final int READ_TIMEOUT_MS = 5 * 60 * 1000;
    /**
     * Repair commands are quick. A hung adbd must not hold the watchdog worker for minutes — on the
     * EO2, adbd accepted the loopback connection but never answered while a USB adb client was
     * attached, and 20s per command turned one repair into 80s of silence.
     */
    private static final int EXEC_TIMEOUT_MS = 8000;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset US_ASCII = Charset.forName("US-ASCII");

    private final String host;
    private final int port;

    public AdbdInstallClient() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    AdbdInstallClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Installs {@code apk} through the device's own adbd. The command line only
     * ever contains the APK's absolute path — never user/config input.
     *
     * @return SUCCESS when pm reported success; PM_FAILED when pm ran but failed
     *         (detail carries pm's output tail); UNREACHABLE when adbd could not
     *         be reached or the exchange broke down (detail carries the reason).
     */
    public Result install(File apk) {
        // The staged public copy lives in a world-readable dir so the shell
        // user adbd spawns can read it; quoting is belt-and-braces (the file
        // name is a fixed constant, no spaces today).
        String command = "shell:pm install -r '" + apk.getAbsolutePath() + "'; echo EO1PM_RC=$?";
        try {
            return classify(openAndRun(command, READ_TIMEOUT_MS));
        } catch (IOException e) {
            return unreachable(e);
        }
    }

    /**
     * Runs one shell command line through adbd and classifies it by exit status. Used by
     * {@link DeviceRepair}; callers must pass a fixed constant, never interpolated input.
     *
     * <p>adbd's shell user is root on this ROM, which is what makes the repair vocabulary
     * ({@code svc wifi …}, {@code setprop ctl.start …}, {@code reboot}) possible at all.
     */
    public Result exec(String command) {
        try {
            return classifyExec(openAndRun("shell:" + command + "; echo EO1RC=$?", EXEC_TIMEOUT_MS));
        } catch (IOException e) {
            return unreachable(e);
        }
    }

    /** Performs the adb handshake, runs {@code command}, and returns everything it printed. */
    private String openAndRun(String command, int readTimeoutMs) throws IOException {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendCommand(out, "host:version");
            String status = readStatus(in);
            if (!"OKAY".equals(status)) {
                throw new IOException("handshake rejected: " + status + readFailReason(in, socket));
            }
            readPayload(in); // protocol version; not needed further

            sendCommand(out, command);
            status = readStatus(in);
            if (!"OKAY".equals(status)) {
                throw new IOException("command rejected: " + status + readFailReason(in, socket));
            }

            return readUntilEof(in);
        } finally {
            closeQuietly(socket);
        }
    }

    private static Result unreachable(IOException e) {
        String msg = e.getMessage() == null ? "" : ": " + e.getMessage();
        return new Result(Outcome.UNREACHABLE, e.getClass().getSimpleName() + msg);
    }

    /** True when adbd accepts a connection on the default loopback endpoint. */
    public static boolean probe() {
        return probe(DEFAULT_HOST, DEFAULT_PORT);
    }

    /**
     * Makes sure adbd is listening, starting it if the ROM never did — <b>only if the operator
     * allowed that</b> ({@code allowed}, which is {@code SettingsManager.isLoopbackAdbEnabled()}:
     * <i>Trusted Network</i> and <i>Allow loopback ADB for update installs</i> both on).
     *
     * <p>Why it needs asking: there is no loopback-only mode. adbd binds {@code 0.0.0.0} and its
     * shell user is root on these ROMs, so arming it hands any host that can route to the frame an
     * unauthenticated root shell and free APK install — a wider door than the frame's plain-HTTP web
     * server, so the app never opens it as a side effect of an update. The EO1 ROM leaves
     * {@code service.adb.tcp.port} unset (loopback adbd is simply absent — a hard
     * {@code ECONNREFUSED}, verified with no host attached), the EO2 ROM sets it; the silent install
     * path and {@link DeviceRepair} need the daemon either way.
     *
     * <p>Who may ask: the install path, while an update is staged and being installed, and a
     * watchdog rung that is actually running. Nothing else — not launch, not a tick that found
     * nothing to do. Every caller that gets {@link Outcome#ARMED} closes it again with
     * {@link #teardownTcpAdbd()} when its action ends, so the window is the action rather than the
     * boot. Restarting adbd drops a host {@code adb} session attached over USB.
     *
     * @return ARMED when this call started the daemon (the caller owes a teardown), SUCCESS when it
     *         was already listening, UNREACHABLE with the reason otherwise — detail is
     *         log/{@code /state}-ready.
     */
    public static Result ensureTcpAdbd(boolean allowed) {
        return ensureTcpAdbd(allowed, TCP_TRANSPORT, REFLECTIVE_PROPERTY_WRITER, THREAD_SLEEPER,
                System.currentTimeMillis());
    }

    /** Testable core of {@link #ensureTcpAdbd(boolean)} — no sockets, no hidden API, no sleeping. */
    static Result ensureTcpAdbd(boolean allowed, Transport transport, PropertyWriter writer,
                                Sleeper sleeper, long nowMs) {
        if (transport.reachable()) {
            // The ROM's own daemon. Not ours to close, and restarting it would only interrupt it.
            return new Result(Outcome.SUCCESS, "already listening");
        }
        if (!allowed) {
            return new Result(Outcome.UNREACHABLE, "loopback adb not allowed (needs Trusted Network"
                    + " + Allow loopback ADB for update installs in the options dialog)");
        }
        if (!shouldRearm(false, lastRearmAttemptMs, nowMs)) {
            return new Result(Outcome.UNREACHABLE, "not listening; re-arm attempted recently");
        }
        lastRearmAttemptMs = nowMs;
        try {
            writer.set(PROP_TCP_PORT, String.valueOf(DEFAULT_PORT));
            writer.set(PROP_RESTART_SERVICE, SERVICE_ADBD);
        } catch (Throwable t) {
            return new Result(Outcome.UNREACHABLE, "ROM refused the property write: " + t);
        }
        for (int attempt = 0; attempt < REARM_PROBE_TRIES; attempt++) {
            sleeper.sleep(REARM_SETTLE_MS);
            if (transport.reachable()) {
                return new Result(Outcome.ARMED, "started adbd for this action");
            }
        }
        // The write landed but nothing came up. The caller still owes a close: the property is set,
        // so a late-starting adbd would come up open.
        return new Result(Outcome.ARMED, "started adbd, but it is not answering yet");
    }

    /**
     * Closes a window {@link #ensureTcpAdbd(boolean)} opened on this call's authority: writes
     * {@code -1} (adbd's "no TCP") and restarts the service. Call it only on an
     * {@link Outcome#ARMED} result — an EO2 whose ROM listens on its own must be left exactly as the
     * ROM configured it.
     */
    public static Result teardownTcpAdbd() {
        return teardownTcpAdbd(REFLECTIVE_PROPERTY_WRITER);
    }

    /** Testable core of {@link #teardownTcpAdbd()}. No settle wait: the restart reads what we wrote. */
    static Result teardownTcpAdbd(PropertyWriter writer) {
        try {
            writer.set(PROP_TCP_PORT, PROP_TCP_PORT_OFF);
            writer.set(PROP_RESTART_SERVICE, SERVICE_ADBD);
        } catch (Throwable t) {
            return new Result(Outcome.UNREACHABLE, "could not close loopback adb: " + t);
        }
        return new Result(Outcome.SUCCESS, "loopback adb closed");
    }

    /**
     * Pure gate: never touch a working adbd, and never restart it more than once per
     * {@link #REARM_MIN_INTERVAL_MS} — a ROM that refuses the write would otherwise pay an adbd
     * restart on every install attempt and every watchdog rung.
     */
    static boolean shouldRearm(boolean reachable, long lastAttemptMs, long nowMs) {
        return !reachable
                && (lastAttemptMs == 0L || nowMs - lastAttemptMs >= REARM_MIN_INTERVAL_MS);
    }

    /** Loopback reachability, injected so the gate is testable without sockets. */
    public interface Transport {
        boolean reachable();
    }

    /** One system-property write, injected so the re-arm sequence is testable off-device. */
    public interface PropertyWriter {
        void set(String key, String value) throws Exception;
    }

    /** Sleep hook, so tests do not wait out adbd's restart. */
    public interface Sleeper {
        void sleep(long ms);
    }

    private static final Transport TCP_TRANSPORT = new Transport() {
        @Override
        public boolean reachable() {
            return probe();
        }
    };

    private static final Sleeper THREAD_SLEEPER = new Sleeper() {
        @Override
        public void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };

    /**
     * {@code android.os.SystemProperties} is hidden, so it is absent from the compile-time
     * android.jar; reflection is the only way in. Both keys below are write-once constants —
     * nothing user-, network- or config-derived ever reaches a property.
     */
    private static final PropertyWriter REFLECTIVE_PROPERTY_WRITER = new PropertyWriter() {
        @Override
        public void set(String key, String value) throws Exception {
            Class<?> props = Class.forName("android.os.SystemProperties");
            props.getMethod("set", String.class, String.class).invoke(null, key, value);
        }
    };

    static boolean probe(String host, int port) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    /** Encodes an adb command: 4-digit hex length (ASCII) + payload. */
    static byte[] frame(String command) {
        byte[] payload = command.getBytes(US_ASCII);
        byte[] length = String.format(Locale.US, "%04x", payload.length).getBytes(US_ASCII);
        byte[] framed = new byte[length.length + payload.length];
        System.arraycopy(length, 0, framed, 0, length.length);
        System.arraycopy(payload, 0, framed, length.length, payload.length);
        return framed;
    }

    /**
     * Classifies {@code pm install} output. 4.4's pm prints a {@code Success}
     * line on success and {@code Failure [CODE ...]} on failure; anything else
     * (including empty output) is treated as a failure so the next tick retries.
     */
    static Result classify(String pmOutput) {
        if (pmOutput.indexOf("Success") >= 0) {
            return new Result(Outcome.SUCCESS, "");
        }
        return new Result(Outcome.PM_FAILED, oneLineTail(pmOutput, "pm produced no output"));
    }

    /**
     * Classifies {@link #exec} output by the exit status of the command. A missing marker means
     * the channel closed while the command was still running — which is exactly what {@code reboot}
     * does — so it counts as success; the caller re-checks the effect on the next rung anyway.
     */
    static Result classifyExec(String output) {
        int marker = output.lastIndexOf("EO1RC=");
        if (marker < 0) {
            return new Result(Outcome.SUCCESS, oneLineTail(output, ""));
        }
        String commandOutput = oneLineTail(output.substring(0, marker), "");
        int rc = -1;
        StringBuilder digits = new StringBuilder();
        int digitsEnd = Math.min(output.length(), marker + 6 + 3); // exit status is 0..255
        for (int i = marker + 6; i < digitsEnd && Character.isDigit(output.charAt(i)); i++) {
            digits.append(output.charAt(i));
        }
        if (digits.length() > 0) {
            rc = Integer.parseInt(digits.toString());
        }
        if (rc == 0) {
            return new Result(Outcome.SUCCESS, commandOutput);
        }
        return new Result(Outcome.PM_FAILED,
                commandOutput.isEmpty() ? "exit status " + rc : commandOutput + " (exit status " + rc + ")");
    }

    private static String oneLineTail(String output, String whenEmpty) {
        String oneLine = output.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > 200) {
            oneLine = oneLine.substring(oneLine.length() - 200);
        }
        return oneLine.isEmpty() ? whenEmpty : oneLine;
    }

    // ------------------------------------------------------------------ wire I/O

    private static void sendCommand(OutputStream out, String command) throws IOException {
        out.write(frame(command));
        out.flush();
    }

    /** Reads the 4-byte {@code OKAY}/{@code FAIL} status word. */
    private static String readStatus(InputStream in) throws IOException {
        byte[] status = new byte[4];
        readFully(in, status);
        return new String(status, US_ASCII);
    }

    /** After a FAIL status adbd appends an unlength-prefixed reason (≤64 bytes). */
    private static String readFailReason(InputStream in, Socket socket) {
        try {
            int previousTimeout = socket.getSoTimeout();
            socket.setSoTimeout(250);
            byte[] buf = new byte[64];
            int n = in.read(buf);
            String reason = n > 0 ? " " + new String(buf, 0, n, US_ASCII).trim() : "";
            socket.setSoTimeout(previousTimeout);
            return reason;
        } catch (IOException e) {
            // Reason is diagnostic only — a timeout or socket error just means we have
            // no more detail than the FAIL status itself.
            return "";
        }
    }

    /** Reads a length-prefixed payload (4-digit hex count + bytes). */
    private static String readPayload(InputStream in) throws IOException {
        byte[] len = new byte[4];
        readFully(in, len);
        int n = Integer.parseInt(new String(len, US_ASCII), 16);
        byte[] payload = new byte[n];
        readFully(in, payload);
        return new String(payload, US_ASCII);
    }

    /** Reads shell output until adbd closes the channel (command finished). */
    private static String readUntilEof(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (out.size() < MAX_OUTPUT_BYTES) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), UTF_8);
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("unexpected EOF");
            }
            off += n;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
