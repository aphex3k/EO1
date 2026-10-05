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
 * confirmation dialog — which a headless frame cannot confirm. The ROM's adbd
 * listens on {@code 127.0.0.1:5555} (build.prop {@code service.adb.tcp.port}),
 * however, and shell commands may {@code pm install}. This class speaks just
 * enough of the classic adb wire protocol (protocol 0, what Android 4.4.2 adbd
 * speaks) to run one {@code pm install} and read its output; nothing beyond
 * {@code java.net}.
 */
public final class AdbdInstallClient {

    /** Outcome of an install attempt. */
    public enum Outcome {
        /** {@code pm install} reported success. */
        SUCCESS,
        /** adbd was reached but {@code pm install} reported a failure. */
        PM_FAILED,
        /** adbd was unreachable or the protocol exchange did not complete. */
        UNREACHABLE
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

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int PROBE_TIMEOUT_MS = 1000;
    /** {@code pm install} can be slow on the frame's storage. */
    private static final int READ_TIMEOUT_MS = 5 * 60 * 1000;
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
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendCommand(out, "host:version");
            String status = readStatus(in);
            if (!"OKAY".equals(status)) {
                return new Result(Outcome.UNREACHABLE,
                        "handshake rejected: " + status + readFailReason(in, socket));
            }
            readPayload(in); // protocol version; not needed further

            sendCommand(out, command);
            status = readStatus(in);
            if (!"OKAY".equals(status)) {
                return new Result(Outcome.UNREACHABLE,
                        "command rejected: " + status + readFailReason(in, socket));
            }

            return classify(readUntilEof(in));
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : ": " + e.getMessage();
            return new Result(Outcome.UNREACHABLE, e.getClass().getSimpleName() + msg);
        } finally {
            closeQuietly(socket);
        }
    }

    /** True when adbd accepts a connection on the default loopback endpoint. */
    public static boolean probe() {
        return probe(DEFAULT_HOST, DEFAULT_PORT);
    }

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
        String oneLine = pmOutput.replace('\n', ' ').trim();
        if (oneLine.length() > 200) {
            oneLine = oneLine.substring(oneLine.length() - 200);
        }
        return new Result(Outcome.PM_FAILED,
                oneLine.isEmpty() ? "pm produced no output" : oneLine);
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
