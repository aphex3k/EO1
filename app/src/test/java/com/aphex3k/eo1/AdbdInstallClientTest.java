package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // ------------------------------------------------------------------- frame

    @Test
    public void frame_encodesHexLengthPrefix() {
        assertEquals("000chost:version", new String(AdbdInstallClient.frame("host:version"), US_ASCII));
    }

    @Test
    public void frame_payloadLengthIsCorrect() {
        byte[] framed = AdbdInstallClient.frame("shell:echo hi");
        int payloadLen = framed.length - 4;
        assertEquals("shell:echo hi".length(), payloadLen);
        assertEquals("000d", new String(framed, 0, 4, US_ASCII));
        assertEquals("shell:echo hi", new String(framed, 4, payloadLen, US_ASCII));
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

    // ------------------------------------------------------------ install e2e

    @Test
    public void install_successAgainstFakeAdbd() throws Exception {
        FakeAdbd fake = new FakeAdbd("Success\nEO1PM_RC=0");
        try {
            AdbdInstallClient client = new AdbdInstallClient("127.0.0.1", fake.port());
            AdbdInstallClient.Result r = client.install(APK);
            assertEquals(AdbdInstallClient.Outcome.SUCCESS, r.outcome);
            assertEquals("host:version", fake.commands.get(0));
            assertEquals(
                    "shell:pm install -r '/sdcard/Download/eo1-update.apk'; echo EO1PM_RC=$?",
                    fake.commands.get(1));
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
    public void probe_trueWhenPortOpen_falseWhenClosed() throws Exception {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = s.getLocalPort();
        assertTrue(AdbdInstallClient.probe("127.0.0.1", port));
        s.close();
        assertFalse(AdbdInstallClient.probe("127.0.0.1", port));
    }

    // ------------------------------------------------------------------ fake

    /**
     * Speaks just enough of the adb wire protocol to serve one {@code
     * host:version} and one {@code shell:} command. With {@code shellOutput ==
     * null} the shell command is rejected with a FAIL status instead.
     */
    private static final class FakeAdbd {
        private final ServerSocket server;
        private final List<String> commands = new CopyOnWriteArrayList<String>();
        private final String shellOutput;

        FakeAdbd(String shellOutput) throws IOException {
            this.shellOutput = shellOutput;
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Thread t = new Thread(null, new Runnable() {
                @Override
                public void run() {
                    try {
                        serveOne();
                    } catch (IOException ignored) {
                    }
                }
            }, "fake-adbd");
            t.setDaemon(true);
            t.start();
        }

        private void serveOne() throws IOException {
            Socket s = server.accept();
            try {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                commands.add(readCommand(in)); // host:version
                out.write("OKAY".getBytes(US_ASCII));
                out.write("0004".getBytes(US_ASCII));
                out.write("0001".getBytes(US_ASCII));
                out.flush();
                commands.add(readCommand(in)); // shell:...
                if (shellOutput == null) {
                    out.write("FAILunknown service".getBytes(US_ASCII));
                } else {
                    out.write("OKAY".getBytes(US_ASCII));
                    out.write(shellOutput.getBytes(Charset.forName("UTF-8")));
                }
                out.flush();
            } finally {
                s.close();
            }
        }

        int port() {
            return server.getLocalPort();
        }

        void close() {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }

        private static String readCommand(InputStream in) throws IOException {
            byte[] len = new byte[4];
            readFully(in, len);
            int n = Integer.parseInt(new String(len, US_ASCII), 16);
            byte[] payload = new byte[n];
            readFully(in, payload);
            return new String(payload, US_ASCII);
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
    }
}
