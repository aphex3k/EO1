package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

/**
 * JVM tests for {@link AdbdInstallClient}: the pure helpers plus full install
 * exchanges against a fake adbd on a loopback port. No Android framework
 * classes are exercised.
 */
public class AdbdInstallClientTest {

    private static final Charset US_ASCII = Charset.forName("US-ASCII");

    private static final File APK = new File("/sdcard/Download/eo1-update.apk");

    // ---------------------------------------------------------------- messages

    @Test
    public void message_isLittleEndianAndTheMagicIsTheCommandsComplement() {
        byte[] payload = "shell:echo hi".getBytes(US_ASCII);
        byte[] wire = AdbdInstallClient.message(AdbdInstallClient.A_OPEN, 1L, 0L, payload);
        assertEquals(24 + payload.length, wire.length);
        assertEquals("cmd", AdbdInstallClient.A_OPEN, word(wire, 0));
        assertEquals("arg0", 1L, word(wire, 4));
        assertEquals("arg1", 0L, word(wire, 8));
        assertEquals("data_length", payload.length, word(wire, 12));
        assertEquals("checksum", AdbdInstallClient.checksum(payload), word(wire, 16));
        assertEquals("magic", AdbdInstallClient.A_OPEN ^ 0xffffffffL, word(wire, 20));
    }

    @Test
    public void checksumIsTheByteSumAdbdComparesNotAcrcThirtyTwo() {
        // The header's checksum word is a sum of the payload's bytes. Sending a CRC32 there — which
        // is what the field's name in older sources suggests — is answered by a closed socket, so this
        // is pinned against the real numbers a working exchange produced on the EO2.
        String banner = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,"
                + "fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,"
                + "sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,"
                + "sendrecv_v2_dry_run_send,devicetracker_proto_format,devraw,app_info,"
                + "server_status,track_mdns";
        assertEquals(286, banner.length());
        assertEquals(0x701dL, AdbdInstallClient.checksum(banner.getBytes(US_ASCII)));
        assertEquals(0L, AdbdInstallClient.checksum(new byte[0]));
        assertEquals(0x61L + 0x62L, AdbdInstallClient.checksum("ab".getBytes(US_ASCII)));
    }

    @Test
    public void message_tagsAreTheFourccSpellingsThisRomsAdbdAnswers() {
        // Nothing in the adb exchange negotiates these, so the wrong spelling reads to the daemon as
        // garbage and to us as an unreachable device. Pinned to the ASCII on purpose: the EO2's adbd
        // answered a tag built from "CNXN" and ignored 0x01000001 entirely, which is only discoverable
        // by asking a real daemon — and this file's fake would happily agree with either.
        assertEquals(0x4e584e43L, AdbdInstallClient.A_CNXN);
        assertEquals(0x4e45504fL, AdbdInstallClient.A_OPEN);
        assertEquals(0x59414b4fL, AdbdInstallClient.A_OKAY);
        assertEquals(0x45534c43L, AdbdInstallClient.A_CLSE);
        assertEquals(0x45545257L, AdbdInstallClient.A_WRTE);
        assertEquals(0x48545541L, AdbdInstallClient.A_AUTH);
        assertEquals("tag is its own four letters on the wire", 0x4e584e43L,
                word(AdbdInstallClient.message(AdbdInstallClient.A_CNXN, 0L, 0L, new byte[0]), 0));
    }

    @Test
    public void opNamesTheMessagesSoARefusalReadsAsItself() {
        assertEquals("CNXN", AdbdInstallClient.op(AdbdInstallClient.A_CNXN));
        assertEquals("AUTH", AdbdInstallClient.op(AdbdInstallClient.A_AUTH));
        assertTrue(AdbdInstallClient.op(0x12345678L).contains("12345678"));
    }

    private static long word(byte[] wire, int at) {
        return (wire[at] & 0xffL) | ((wire[at + 1] & 0xffL) << 8)
                | ((wire[at + 2] & 0xffL) << 16) | ((wire[at + 3] & 0xffL) << 24);
    }

    // ----------------------------------------------------------------- classify

    @Test
    public void classify_success() {
        assertEquals(AdbdInstallClient.Outcome.SUCCESS,
                AdbdInstallClient.classify("Success\nEO1PM_RC=0").outcome);
    }

    @Test
    public void classify_failureCarriesPmCode() {
        AdbdInstallClient.Result r = AdbdInstallClient.classify(
                "Failure [INSTALL_FAILED_NO_STORAGE]\nEO1PM_RC=1");
        assertEquals(AdbdInstallClient.Outcome.PM_FAILED, r.outcome);
        assertTrue(r.detail.contains("INSTALL_FAILED_NO_STORAGE"));
    }

    @Test
    public void classify_emptyOutputIsPmFailed() {
        assertEquals(AdbdInstallClient.Outcome.PM_FAILED,
                AdbdInstallClient.classify("").outcome);
    }

    @Test
    public void classify_longOutputKeepsTail() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append('x');
        }
        sb.append("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]");
        AdbdInstallClient.Result r = AdbdInstallClient.classify(sb.toString());
        assertEquals(AdbdInstallClient.Outcome.PM_FAILED, r.outcome);
        assertTrue(r.detail.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"));
        assertTrue(r.detail.length() <= 200 + 32);
    }

    // -------------------------------------------------------------- classifyExec

    @Test
    public void classifyExec_zeroStatusSucceedsAndKeepsOutput() {
        AdbdInstallClient.Result r = AdbdInstallClient.classifyExec("running\nEO1RC=0");
        assertEquals(AdbdInstallClient.Outcome.SUCCESS, r.outcome);
        assertEquals("The repair sequence reads this back, so output must survive.", "running", r.detail);
    }

    @Test
    public void classifyExec_nonZeroStatusFails() {
        AdbdInstallClient.Result r = AdbdInstallClient.classifyExec("svc: permission denied\nEO1RC=1");
        assertEquals(AdbdInstallClient.Outcome.PM_FAILED, r.outcome);
        assertTrue(r.detail.contains("permission denied"));
        assertTrue(r.detail.contains("exit status 1"));
    }

    @Test
    public void classifyExec_missingMarkerMeansTheChannelDiedWithTheCommand() {
        // `reboot` tears down adbd before it can echo a status.
        assertEquals(AdbdInstallClient.Outcome.SUCCESS,
                AdbdInstallClient.classifyExec("").outcome);
    }

    // ------------------------------------------------------------ install e2e

    @Test
    public void install_successAgainstFakeAdbd() throws Exception {
        FakeAdbd fake = new FakeAdbd("Success\nEO1PM_RC=0");
        try {
            AdbdInstallClient client = new AdbdInstallClient("127.0.0.1", fake.port());
            AdbdInstallClient.Result r = client.install(APK);
            assertEquals(AdbdInstallClient.Outcome.SUCCESS, r.outcome);
            assertEquals("the handshake opened exactly one service", 1, fake.services().size());
            assertEquals(
                    "shell:pm install -r '/sdcard/Download/eo1-update.apk'; echo EO1PM_RC=$?",
                    fake.services().get(0));
        } finally {
            fake.close();
        }
    }

    @Test
    public void install_reportsPmFailure() throws Exception {
        FakeAdbd fake = new FakeAdbd("Failure [INSTALL_FAILED_NO_STORAGE]\nEO1PM_RC=1");
        try {
            AdbdInstallClient client = new AdbdInstallClient("127.0.0.1", fake.port());
            AdbdInstallClient.Result r = client.install(APK);
            assertEquals(AdbdInstallClient.Outcome.PM_FAILED, r.outcome);
            assertTrue(r.detail.contains("INSTALL_FAILED_NO_STORAGE"));
        } finally {
            fake.close();
        }
    }

    @Test
    public void install_unreachableWhenPortClosed() throws Exception {
        ServerSocket used = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = used.getLocalPort();
        used.close();
        AdbdInstallClient client = new AdbdInstallClient("127.0.0.1", port);
        AdbdInstallClient.Result r = client.install(APK);
        assertEquals(AdbdInstallClient.Outcome.UNREACHABLE, r.outcome);
    }

    @Test
    public void install_rejectedCommandIsUnreachable() throws Exception {
        FakeAdbd fake = new FakeAdbd(null); // no shell output; server sends FAIL for shell
        try {
            AdbdInstallClient client = new AdbdInstallClient("127.0.0.1", fake.port());
            AdbdInstallClient.Result r = client.install(APK);
            assertEquals(AdbdInstallClient.Outcome.UNREACHABLE, r.outcome);
        } finally {
            fake.close();
        }
    }

    @Test
    public void install_namesAuthorizationInsteadOfTimingOutOnIt() throws Exception {
        FakeAdbd fake = new FakeAdbd("Success\nEO1PM_RC=0", true);
        try {
            AdbdInstallClient client = new AdbdInstallClient("127.0.0.1", fake.port());
            AdbdInstallClient.Result r = client.install(APK);
            // A secure ROM answers the handshake with AUTH. This client has no RSA key and must not
            // grow one; what it must do is say so at once rather than spend the read timeout and
            // report the same UNREACHABLE it reports for a daemon that is simply not there.
            assertEquals(AdbdInstallClient.Outcome.UNREACHABLE, r.outcome);
            assertTrue(r.detail, r.detail.contains("authorization"));
        } finally {
            fake.close();
        }
    }

    @Test
    public void probe_trueWhenPortOpen_falseWhenClosed() throws Exception {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = s.getLocalPort();
        assertTrue(AdbdInstallClient.probe("127.0.0.1", port));
        s.close();
        assertFalse(AdbdInstallClient.probe("127.0.0.1", port));
    }

    // ------------------------------------------------------------ adbd re-arm

    @Test
    public void shouldRearm_neverWhenListening_oncePerWindowOtherwise() {
        assertFalse(AdbdInstallClient.shouldRearm(true, 0L, 1_000_000L));
        assertTrue(AdbdInstallClient.shouldRearm(false, 0L, 1_000_000L));
        assertFalse(AdbdInstallClient.shouldRearm(false, 1_000_000L,
                1_000_000L + AdbdInstallClient.REARM_MIN_INTERVAL_MS - 1L));
        assertTrue(AdbdInstallClient.shouldRearm(false, 1_000_000L,
                1_000_000L + AdbdInstallClient.REARM_MIN_INTERVAL_MS));
    }

    /**
     * A daemon the ROM started is not ours: no property may be written (a restart would drop a host
     * {@code adb} session for nothing) and the answer is not {@code ARMED}, so the caller has no
     * teardown duty either — that is how "leave EO2 as it is" is expressed.
     */
    @Test
    public void ensureTcpAdbd_leavesAWorkingAdbdAlone() {
        AdbdInstallClient.lastRearmAttemptMs = 0L;
        Recorder r = new Recorder(true, true);

        AdbdInstallClient.Result already =
                AdbdInstallClient.ensureTcpAdbd(true, r.transport, r.writer, r.sleeper, NOW);
        assertEquals(AdbdInstallClient.Outcome.SUCCESS, already.outcome);
        assertTrue("no property may be written: restarting a live adbd would drop the session"
                + " — " + r.writes, r.writes.isEmpty());
        assertNotEquals("not ours to close", AdbdInstallClient.Outcome.ARMED, already.outcome);
    }

    /**
     * The whole point of the flag: without both options-dialog checkboxes the app never opens a
     * root shell on the network, however much an install or a repair rung would like to.
     */
    @Test
    public void ensureTcpAdbd_neverArmsWithoutOperatorPermission() {
        AdbdInstallClient.lastRearmAttemptMs = 0L;
        Recorder r = new Recorder(false, true);

        AdbdInstallClient.Result denied =
                AdbdInstallClient.ensureTcpAdbd(false, r.transport, r.writer, r.sleeper, NOW);
        assertEquals(AdbdInstallClient.Outcome.UNREACHABLE, denied.outcome);
        assertTrue(denied.detail, denied.detail.contains("not allowed"));
        assertTrue("no property may be written", r.writes.isEmpty());
    }

    /** The EO1 case: nothing listening, the operator allowed it, ROM allows the write. */
    @Test
    public void ensureTcpAdbd_writesBothPropertiesAndOwesAClose() {
        AdbdInstallClient.lastRearmAttemptMs = 0L;
        Recorder r = new Recorder(false, true);

        assertEquals(AdbdInstallClient.Outcome.ARMED,
                AdbdInstallClient.ensureTcpAdbd(true, r.transport, r.writer, r.sleeper, NOW).outcome);
        assertEquals(2, r.writes.size());
        assertEquals("service.adb.tcp.port=5555", r.writes.get(0));
        assertEquals("ctl.restart=adbd", r.writes.get(1));
    }

    /** A ROM that refuses an app writing properties must cost one attempt, not a restart storm. */
    @Test
    public void ensureTcpAdbd_reportsRefusalAndDoesNotRetryInsideTheWindow() {
        AdbdInstallClient.lastRearmAttemptMs = 0L;
        Recorder r = new Recorder(false, false);

        AdbdInstallClient.Result refused = AdbdInstallClient.ensureTcpAdbd(true, r.transport,
                r.writer, r.sleeper, NOW);
        assertEquals(AdbdInstallClient.Outcome.UNREACHABLE, refused.outcome);
        assertTrue(refused.detail, refused.detail.contains("ROM refused"));
        assertEquals(1, r.writes.size());

        AdbdInstallClient.Result throttled = AdbdInstallClient.ensureTcpAdbd(true, r.transport,
                r.writer, r.sleeper, NOW + 1000L);
        assertEquals(AdbdInstallClient.Outcome.UNREACHABLE, throttled.outcome);
        assertEquals("the window must gate it, not the failure", 1, r.writes.size());
        assertTrue(throttled.detail, throttled.detail.contains("recently"));
    }

    /** The exposure window is one action: teardown writes "do not listen" and restarts the service. */
    @Test
    public void teardownTcpAdbd_closesThePortItOpened() {
        Recorder r = new Recorder(false, true);
        r.writes.clear();

        assertEquals(AdbdInstallClient.Outcome.SUCCESS,
                AdbdInstallClient.teardownTcpAdbd(r.writer).outcome);
        assertEquals(2, r.writes.size());
        assertEquals("service.adb.tcp.port=-1", r.writes.get(0));
        assertEquals("ctl.restart=adbd", r.writes.get(1));
    }

    /** Fakes for the three effects {@code ensureTcpAdbd} has in the world. */
    private static final class Recorder {
        final List<String> writes = new CopyOnWriteArrayList<String>();
        private boolean reachable;
        private final boolean writeAllowed;

        final AdbdInstallClient.Transport transport = new AdbdInstallClient.Transport() {
            @Override
            public boolean reachable() {
                return reachable;
            }
        };

        final AdbdInstallClient.PropertyWriter writer = new AdbdInstallClient.PropertyWriter() {
            @Override
            public void set(String key, String value) throws Exception {
                writes.add(key + "=" + value); // attempted writes, refused or not
                if (!writeAllowed) {
                    throw new SecurityException("permission denied");
                }
                if (AdbdInstallClient.PROP_RESTART_SERVICE.equals(key)) {
                    reachable = true;
                }
            }
        };

        final AdbdInstallClient.Sleeper sleeper = new AdbdInstallClient.Sleeper() {
            @Override
            public void sleep(long ms) {
            }
        };

        Recorder(boolean reachable, boolean writeAllowed) {
            this.reachable = reachable;
            this.writeAllowed = writeAllowed;
        }
    }

    private static final long NOW = 1_700_000_000_000L;

    // ------------------------------------------------------------------ fake

    /**
     * A daemon-side stub that answers the way {@code adbd} is specified to respond, not the way this
     * client reads. That distinction is the whole point of it: the fake this file used to carry
     * accepted the same hex-length framing the client sent, so {@code install} passed here and failed
     * on every frame ever built — and a test pair that agree with each other is how a protocol bug
     * survives nine years.
     *
     * <p>{@code shellOutput == null} refuses the {@code OPEN}; {@code authRequired} answers the
     * handshake with {@code AUTH}, which is what a {@code ro.adb.secure=1} ROM does to a host with no
     * key.
     */
    private static final class FakeAdbd {
        private static final long PEER_ID = 7L;

        private final ServerSocket server;
        private final List<String> services = new CopyOnWriteArrayList<String>();
        private final String shellOutput;
        private final boolean authRequired;

        FakeAdbd(String shellOutput) throws IOException {
            this(shellOutput, false);
        }

        FakeAdbd(String shellOutput, boolean authRequired) throws IOException {
            this.shellOutput = shellOutput;
            this.authRequired = authRequired;
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Thread t = new Thread(null, new Runnable() {
                @Override
                public void run() {
                    try {
                        serveOne();
                    } catch (IOException ignored) {
                        // The client closing early is a normal outcome of a refused service.
                    }
                }
            }, "fake-adbd");
            t.setDaemon(true);
            t.start();
        }

        private void serveOne() throws IOException {
            Socket s = server.accept();
            s.setTcpNoDelay(true);
            try {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                expect(read(in), AdbdInstallClient.A_CNXN);
                if (authRequired) {
                    send(out, AdbdInstallClient.A_AUTH, 1L, 2048L, "RSA");
                    return;
                }
                send(out, AdbdInstallClient.A_CNXN, 0x01000001L, 4096L, "device::");

                Received open = expect(read(in), AdbdInstallClient.A_OPEN);
                long localId = open.arg0;
                services.add(stripTrailingNul(new String(open.payload, US_ASCII)));
                if (shellOutput == null) {
                    send(out, AdbdInstallClient.A_CLSE, 0L, localId, "");
                    return;
                }
                send(out, AdbdInstallClient.A_OKAY, PEER_ID, localId, "");
                send(out, AdbdInstallClient.A_WRTE, PEER_ID, localId, shellOutput);
                read(in); // the ack adbd waits for before it would send another WRTE
                send(out, AdbdInstallClient.A_CLSE, PEER_ID, localId, "");
            } finally {
                s.close();
            }
        }

        int port() {
            return server.getLocalPort();
        }

        List<String> services() {
            return services;
        }

        void close() {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }

        private static void send(OutputStream out, long cmd, long arg0, long arg1, String payload)
                throws IOException {
            out.write(AdbdInstallClient.message(cmd, arg0, arg1, payload.getBytes(US_ASCII)));
            out.flush();
        }

        private static final class Received {
            final long cmd;
            final long arg0;
            final byte[] payload;

            Received(long cmd, long arg0, byte[] payload) {
                this.cmd = cmd;
                this.arg0 = arg0;
                this.payload = payload;
            }
        }

        private static Received read(InputStream in) throws IOException {
            byte[] head = new byte[24];
            readFully(in, head);
            long cmd = word(head, 0);
            int len = (int) word(head, 12);
            byte[] payload = new byte[len];
            if (len > 0) {
                readFully(in, payload);
            }
            if (word(head, 20) != (cmd ^ 0xffffffffL)) {
                throw new IOException("client sent a message whose magic does not match its command");
            }
            return new Received(cmd, word(head, 4), payload);
        }

        private static Received expect(Received got, long cmd) throws IOException {
            if (got.cmd != cmd) {
                throw new IOException("wanted " + AdbdInstallClient.op(cmd) + ", client sent "
                        + AdbdInstallClient.op(got.cmd));
            }
            return got;
        }

        private static String stripTrailingNul(String s) {
            return s.endsWith("\0") ? s.substring(0, s.length() - 1) : s;
        }

        private static long word(byte[] b, int at) {
            return (b[at] & 0xffL) | ((b[at + 1] & 0xffL) << 8)
                    | ((b[at + 2] & 0xffL) << 16) | ((b[at + 3] & 0xffL) << 24);
        }

        private static void readFully(InputStream in, byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    throw new IOException("client hung up mid-message");
                }
                off += n;
            }
        }
    }
}
