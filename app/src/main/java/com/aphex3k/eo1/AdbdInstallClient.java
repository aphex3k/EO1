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
 * never configured it.
 *
 * <p><b>The wire protocol, not the local service protocol.</b> adbd — over USB or TCP — exchanges
 * 24-byte little-endian messages ({@code cmd, arg0, arg1, data_length, data_crc32, magic}) and the
 * {@code CNXN → OPEN → WRTE/OKAY → CLSE} sequence built from them. The {@code %04x}-length +
 * {@code "host:version"} framing that appears in adb documentation is what the <em>host's {@code adb
 * server}</em> (port 5037, or its local socket) accepts, and this class emitted exactly that to port
 * 5555: the daemon never recognised a request, so every {@link #install} and {@link #exec} spent its
 * read timeout and reported {@code UNREACHABLE}. Verified on an EO2 (2026-10-23) by sending the same
 * bytes to both endpoints — {@code 127.0.0.1:5037} answered {@code OKAY00040029}, the frame's adbd
 * answered nothing — while {@code adb connect} to that same port reached {@code uid=0(root)}.
 *
 * <p>Only the four messages a single one-shot {@code shell:} service needs are implemented, and no
 * {@code shell_v2} feature is advertised in the banner: advertising one invites the daemon to answer
 * with packet types this class does not pump. Nothing beyond {@code java.net}, {@code java.util.zip}
 * and reflection.
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

    /** Performs the adb handshake, opens {@code service} as a local service, returns what it printed. */
    private String openAndRun(String service, int readTimeoutMs) throws IOException {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true); // one small message per step; Nagle would pace the handshake
            socket.setSoTimeout(readTimeoutMs);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            writeMsg(out, A_CNXN, A_VERSION, A_MAXDATA, "host::");
            Msg hello = readMsg(in);
            if (hello == null) {
                throw new IOException("adbd closed before answering the handshake");
            }
            if (hello.cmd == A_AUTH) {
                // ro.adb.secure=1 means an RSA token handshake: no key exists on the frame, and none
                // should. Named here so a locked ROM reads as "authorization required", not as a
                // timeout — the failure mode this class mistook for an absent daemon for its whole
                // existence.
                throw new IOException("adbd requires authorization (A_AUTH); this frame has no key");
            }
            if (hello.cmd != A_CNXN) {
                throw new IOException("expected CNXN, got " + op(hello.cmd));
            }

            writeMsg(out, A_OPEN, LOCAL_ID, 0, service + "\0");
            Msg opened = readMsg(in);
            if (opened == null) {
                throw new IOException("adbd closed instead of answering OPEN " + service);
            }
            if (opened.cmd == A_CLSE) {
                throw new IOException("adbd refused the service " + service);
            }
            if (opened.cmd != A_OKAY) {
                throw new IOException("expected OKAY for OPEN, got " + op(opened.cmd));
            }
            long peer = opened.arg0; // the daemon's id for this channel; our writes must carry it

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (true) {
                Msg msg = readMsg(in);
                if (msg == null) {
                    break; // daemon hung up; whatever arrived is the whole answer
                }
                if (msg.cmd == A_WRTE) {
                    // Each WRTE must be acknowledged or adbd stops sending. Nothing here writes to
                    // the service's stdin, so arg1 carries our id and arg0 the daemon's.
                    writeMsg(out, A_OKAY, LOCAL_ID, peer, "");
                    output.write(msg.payload, 0, msg.payload.length);
                    if (output.size() >= MAX_OUTPUT_BYTES) {
                        break;
                    }
                } else if (msg.cmd == A_CLSE) {
                    break;
                } else if (msg.cmd != A_OKAY) {
                    // A stray OKAY acknowledges a write we never make; anything else is a protocol
                    // this client does not implement, and guessing would lose output silently.
                    throw new IOException("unexpected " + op(msg.cmd) + " while reading " + service);
                }
            }
            writeMsg(out, A_CLSE, LOCAL_ID, peer, "");
            return new String(output.toByteArray(), UTF_8);
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

    /**
     * adb message tags. These are the fourcc spellings, not the {@code 0x0100000N} numbers in the
     * 4.4-era {@code adb.h}: measured, not inferred — the EO2's adbd answered a {@code CNXN} tagged
     * by its ASCII name with {@code CNXN + "device::ro.product.name=eo2;..."}, and gave a classic
     * {@code 0x01000001}-tagged {@code CNXN} nothing at all (it never even closed the socket, which
     * is why the failure reads as a timeout rather than a refusal). {@code A_SYNC} is unused here;
     * {@code A_AUTH} is only ever recognised and reported, because answering it needs an RSA key
     * this app does not and must not carry.
     */
    static final long A_CNXN = fourcc("CNXN");
    static final long A_OPEN = fourcc("OPEN");
    static final long A_OKAY = fourcc("OKAY");
    static final long A_CLSE = fourcc("CLSE");
    static final long A_WRTE = fourcc("WRTE");
    static final long A_AUTH = fourcc("AUTH");

    /** A message tag as the wire carries it: four ASCII letters, little-endian. */
    static long fourcc(String tag) {
        byte[] b = tag.getBytes(US_ASCII);
        return (b[0] & 0xffL) | ((b[1] & 0xffL) << 8) | ((b[2] & 0xffL) << 16) | ((b[3] & 0xffL) << 24);
    }

    /** {@code magic} is always the command's bit complement — the daemon's own header check. */
    private static final long MAGIC_XOR = 0xffffffffL;
    private static final int HEADER_BYTES = 24;
    /** Offered version and max payload. adbd takes the minimum of the two sides for both. */
    private static final long A_VERSION = 0x01000001L;
    private static final long A_MAXDATA = 4096L;
    /** This client has one channel per connection, so its local id never has to move. */
    private static final long LOCAL_ID = 1L;

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

    /** One decoded adb message: the header's three interesting words plus its payload. */
    static final class Msg {
        final long cmd;
        final long arg0;
        final long arg1;
        final byte[] payload;

        Msg(long cmd, long arg0, long arg1, byte[] payload) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }

    /** Assembles a 24-byte little-endian header and its payload, exactly as adbd reads them. */
    static byte[] message(long cmd, long arg0, long arg1, byte[] payload) {
        byte[] wire = new byte[HEADER_BYTES + payload.length];
        put32(wire, 0, cmd);
        put32(wire, 4, arg0);
        put32(wire, 8, arg1);
        put32(wire, 12, payload.length);
        put32(wire, 16, checksum(payload));
        put32(wire, 20, cmd ^ MAGIC_XOR);
        System.arraycopy(payload, 0, wire, HEADER_BYTES, payload.length);
        return wire;
    }

    /**
     * The header's checksum word: the sum of the payload's bytes, not a CRC32.
     *
     * <p>Named for what the daemon computes. Measured both directions on an EO2 — a 286-byte banner
     * whose CRC32 is {@code 0x67a84469} travels as {@code 0x0000701d}, and adbd's own 72-byte reply
     * carries {@code 0x000019fa} — and sending the CRC32 instead is answered by a closed socket, so
     * this is not a field either side is tolerant about. A {@code data_crc32} name in older sources
     * describes the same bytes, which is how a crc32 looked like the right thing to put there.
     */
    static long checksum(byte[] payload) {
        long sum = 0L;
        for (int i = 0; i < payload.length; i++) {
            sum += payload[i] & 0xffL;
        }
        return sum & MAGIC_XOR;
    }

    private static void writeMsg(OutputStream out, long cmd, long arg0, long arg1, String payload)
            throws IOException {
        out.write(message(cmd, arg0, arg1, payload.getBytes(US_ASCII)));
        out.flush();
    }

    private static void put32(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xffL);
        b[off + 1] = (byte) ((v >>> 8) & 0xffL);
        b[off + 2] = (byte) ((v >>> 16) & 0xffL);
        b[off + 3] = (byte) ((v >>> 24) & 0xffL);
    }

    private static long get32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    /**
     * Reads one message, or {@code null} at a clean end of stream — which is how a finished shell
     * service says so, and why EOF mid-header stays an error rather than an answer.
     */
    private static Msg readMsg(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        byte[] head = new byte[HEADER_BYTES];
        head[0] = (byte) first;
        readFully(in, head, 1, HEADER_BYTES - 1);
        long cmd = get32(head, 0);
        long length = get32(head, 12);
        long crc = get32(head, 16);
        if (get32(head, 20) != (cmd ^ MAGIC_XOR)) {
            throw new IOException("adbd sent a message whose header contradicts itself");
        }
        if (length < 0L || length > MAX_OUTPUT_BYTES) {
            throw new IOException("adbd announced an impossible payload length: " + length);
        }
        byte[] payload = new byte[(int) length];
        if (length > 0L) {
            readFully(in, payload, 0, payload.length);
            if (checksum(payload) != crc && crc != 0L) {
                // A non-zero checksum that does not match means we are not reading what the daemon
                // wrote. Zero is tolerated because it is also what a peer that skips checksumming
                // sends, and failing a working transport over that would be the same self-inflicted
                // UNREACHABLE this class's framing error already was.
                throw new IOException("adbd payload failed its checksum");
            }
        }
        return new Msg(cmd, get32(head, 4), get32(head, 8), payload);
    }

    /** The opcode by name, so a protocol surprise in a log line identifies itself. */
    static String op(long cmd) {
        if (cmd == A_CNXN) return "CNXN";
        if (cmd == A_OPEN) return "OPEN";
        if (cmd == A_OKAY) return "OKAY";
        if (cmd == A_CLSE) return "CLSE";
        if (cmd == A_WRTE) return "WRTE";
        if (cmd == A_AUTH) return "AUTH";
        return String.format(Locale.US, "0x%08x", Long.valueOf(cmd));
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = in.read(buf, off + done, len - done);
            if (n < 0) {
                throw new IOException("adbd closed mid-message");
            }
            done += n;
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
