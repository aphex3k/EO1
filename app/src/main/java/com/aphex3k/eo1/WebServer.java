package com.aphex3k.eo1;

import android.util.Log;

import com.aphex3k.update.ApkUploadResult;
import com.aphex3k.update.UpdateManifest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal, unsecured, plain-HTTP web server for LAN debugging, control, and media upload.
 *
 * <p>Hand-rolled on {@link ServerSocket}: one daemon accept thread plus a small fixed worker pool.
 * Every response uses {@code Connection: close}. The server tries port 80 (privileged; only works
 * for a system/root install) and falls back to 8080. It is always-on for the life of the app.
 *
 * <p>All threads are defensive: a stray exception must never reach the app's global
 * {@code UncaughtExceptionHandler} (which calls {@code System.exit(2)}).
 */
public class WebServer {

    private static final String TAG = "EO1-web";

    /** Per-file upload size cap (512 MB). */
    public static final long MAX_UPLOAD_BYTES = 512L * 1024L * 1024L;
    /** Aggregate cap for all parts of one upload request (2 GB). */
    public static final long MAX_UPLOAD_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;
    /** Maximum number of parts accepted per upload request. */
    public static final int MAX_UPLOAD_PARTS = 32;
    /**
     * Uploads whose {@code Content-Length} is at or below this are drained off the socket in one
     * bulk read and parsed from memory; larger ones stream straight to disk. The bulk-read path
     * avoids pinning the socket through many small reads (see {@link #handleUpload}).
     */
    public static final long MAX_IN_MEMORY_UPLOAD_BYTES = 16L * 1024L * 1024L;
    /** Body cap for POST /config and /config/import: a device configuration is a few KB at most. */
    public static final long MAX_CONFIG_BODY_BYTES = 512L * 1024L;
    /**
     * Body cap for POST /devicename. Generous against the name's own bound
     * ({@link SettingsManager#DEVICE_NAME_MAX_UTF8_BYTES} bytes, applied after this by the
     * sanitiser), so a rejected request can never be a silently truncated name.
     */
    public static final long MAX_DEVICE_NAME_BODY_BYTES = 512L;

    private static final int PORTS[] = {80, 8080};
    private static final int READ_TIMEOUT_MS = 60000;
    /**
     * Idle timeout while the request line + headers must arrive. Short on purpose: a slow-drip
     * connection (1 byte every ~55 s) must not be able to pin a worker through the 60 s body
     * timeout.
     */
    private static final int HEADER_READ_TIMEOUT_MS = 10000;
    /** Total request-header byte cap (bounds header memory on one connection). */
    private static final int MAX_HEADER_BYTES = 16384;
    private static final int WORKERS = 3;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final WebController controller;
    private final Object lock = new Object();

    private volatile boolean shuttingDown = false;
    // Written on the accept thread, read on the UI thread by shutdown()/getBoundPort().
    private volatile ServerSocket serverSocket;
    private volatile int boundPort = -1;
    private ExecutorService executor;
    private Thread acceptThread;

    public WebServer(WebController controller) {
        this.controller = controller;
    }

    /**
     * Starts the server on a background daemon thread (bind + accept loop). Non-blocking.
     */
    public void start() {
        synchronized (lock) {
            if (acceptThread != null) {
                return;
            }
            shuttingDown = false;
            executor = Executors.newFixedThreadPool(WORKERS);
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    bindAndAccept();
                }
            }, "eo1-web-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
    }

    /**
     * Stops the server. Idempotent.
     */
    public void shutdown() {
        synchronized (lock) {
            shuttingDown = true;
        }
        ServerSocket ss = this.serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        Thread t = this.acceptThread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            acceptThread = null;
            serverSocket = null;
        }
    }

    public int getBoundPort() {
        return boundPort;
    }

    public boolean isRunning() {
        return boundPort > 0 && !shuttingDown;
    }

    private void bindAndAccept() {
        ServerSocket ss = null;
        int bound = -1;
        for (int i = 0; i < PORTS.length; i++) {
            int port = PORTS[i];
            try {
                ss = new ServerSocket(port, 64);
                bound = port;
                break;
            } catch (BindException e) {
                Log.i(TAG, "bind " + port + " failed: " + e.getMessage());
            } catch (IOException e) {
                Log.i(TAG, "bind " + port + " io error: " + e.getMessage());
            }
        }
        if (ss == null) {
            Log.e(TAG, "no port available; web server not started");
            shuttingDown = true;
            return;
        }
        this.serverSocket = ss;
        this.boundPort = bound;
        Log.i(TAG, "listening on port " + bound + " ip=" + safeIp());

        while (!shuttingDown) {
            Socket socket;
            try {
                socket = ss.accept();
            } catch (IOException e) {
                if (shuttingDown) {
                    break;
                }
                Log.e(TAG, "accept error: " + e.getMessage());
                continue;
            }
            try {
                socket.setSoTimeout(READ_TIMEOUT_MS);
            } catch (IOException ignored) {
            }
            try {
                executor.submit(new ConnectionHandler(socket));
            } catch (Throwable t) {
                Log.e(TAG, "submit failed", t);
                closeQuietly(socket);
            }
        }
        Log.i(TAG, "accept loop exited");
    }

    private String safeIp() {
        try {
            return controller.localIp();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** The trusted-network gate; any failure from the controller closes the gate. */
    private boolean trustedNetwork() {
        try {
            return controller.trustedNetwork();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The per-device configuration token. Any failure from the controller fails closed
     * (an empty token rejects every request).
     */
    private String trustedNetworkToken() {
        try {
            String t = controller.trustedNetworkToken();
            return t == null ? "" : t;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * The full configuration JSON served by {@code GET /config} and
     * {@code /config/download} (no longer embedded in the index page, so no HTML escaping).
     */
    private String configJson() {
        try {
            String json = controller.exportConfigurationJson();
            return json == null ? "{}" : json;
        } catch (Throwable t) {
            return "{}";
        }
    }

    /**
     * Classifies a request into a route key. Pure and static so it is unit-testable.
     */
    static String route(String method, String path) {
        if (path == null) {
            return "unknown";
        }
        if (path.equals("/") || path.equals("/index.html")) {
            return "index";
        }
        if (path.equals("/state")) {
            return "state";
        }
        if (path.equals("/logs")) {
            return "logsPage";
        }
        if (path.equals("/log.json")) {
            return "logJson";
        }
        if (path.equals("/log/file")) {
            return "logFile";
        }
        if (path.equals("/files")) {
            return "fileList";
        }
        if (path.startsWith("/files/")) {
            String rest = path.substring("/files/".length());
            if (rest.endsWith("/delete")) {
                return "fileDelete";
            }
            return "fileGet";
        }
        if (path.equals("/crashes")) {
            return "crashesPage";
        }
        if (path.equals("/crash.json")) {
            return "crashJson";
        }
        if (path.startsWith("/crash/")) {
            String rest = path.substring("/crash/".length());
            if (rest.endsWith("/delete")) {
                return "crashDelete";
            }
            return "crashGet";
        }
        // Diagnostics reports. A family of their own rather than another CrashStore kind, because the
        // event they document is a decision the frame made, not a death — and /crashes would announce
        // them as one.
        if (path.equals("/reports")) {
            return "reportsPage";
        }
        if (path.equals("/reports.json")) {
            return "reportJson";
        }
        if (path.equals("/reports/capture")) {
            return "reportCapture";
        }
        if (path.startsWith("/report/")) {
            String rest = path.substring("/report/".length());
            if (rest.endsWith("/delete")) {
                return "reportDelete";
            }
            return "reportGet";
        }
        if (path.equals("/upload")) {
            return "upload";
        }
        if (path.equals("/update")) {
            return "update";
        }
        if (path.equals("/control")) {
            return "control";
        }
        if (path.equals("/config/download")) {
            return "configDownload";
        }
        if (path.equals("/config/import")) {
            return "configImport";
        }
        if (path.equals("/config")) {
            return "config";
        }
        if (path.equals("/devicename")) {
            return "deviceName";
        }
        if (path.equals("/forget")) {
            return "forgetMedia";
        }
        if (path.equals("/health")) {
            return "health";
        }
        return "unknown";
    }

    /**
     * Actions that may only be fired by POST. They are admin/destructive — arbitrary key
     * injection, and forcing a kiosk-watchdog rung (whose top rung reboots the device) — or they
     * change something the frame will still be doing after this request, which is why
     * {@code brightness} is here: it walks the backlight step and <em>persists</em> the result, so a
     * cross-origin {@code <img src="http://frame:8080/control?action=brightness">} from any page the
     * operator ever visits would quietly dim a frame for good. Eight steps and it lands on the 0.3
     * floor. Other actions keep their existing GET/POST behavior.
     */
    private static final java.util.Set<String> POST_ONLY_ACTIONS =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(
                    java.util.Arrays.asList("keyevent", "watchdog", "brightness")));

    static boolean controlAllows(String method, String action) {
        if (action == null) {
            return false;
        }
        return !POST_ONLY_ACTIONS.contains(action) || "POST".equals(method);
    }

    /**
     * Compares a request's {@code token} parameter against the per-device configuration token,
     * constant-time. Fails closed on a missing parameter or an empty expected token
     * (settings unavailable).
     */
    static boolean configTokenOk(String provided, String expected) {
        if (provided == null || expected == null || expected.isEmpty()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                provided.getBytes(UTF_8), expected.getBytes(UTF_8));
    }

    /**
     * Same-origin check for the state-changing config endpoints ({@code POST /config},
     * {@code POST /config/import}). Browsers always send an {@code Origin} header (or at least
     * {@code Referer}) on a POST issued from a page, so any request carrying one of those
     * provenance headers must have been made from this frame's own origin (host of the
     * {@code Host} header, the port the client used). A page on an attacker's host cannot
     * match it. Requests without any provenance header (curl, other tools) pass — they are
     * still token-gated.
     */
    static boolean configWriteAllowed(Map<String, String> headers, int serverPort) {
        String origin = headers.get("origin");
        String referer = headers.get("referer");
        String url = origin != null ? origin : referer;
        if (url == null) {
            return true;
        }
        String hostHeader = headers.get("host");
        if (hostHeader == null) {
            return false;
        }
        int hostColon = hostHeader.lastIndexOf(':');
        String hostHost = hostColon >= 0 ? hostHeader.substring(0, hostColon) : hostHeader;
        int expectedPort = serverPort;
        if (hostColon >= 0) {
            try {
                expectedPort = Integer.parseInt(hostHeader.substring(hostColon + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return false;
        }
        String rest = url.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        int colon = authority.lastIndexOf(':');
        String urlHost;
        int urlPort;
        if (colon >= 0) {
            urlHost = authority.substring(0, colon);
            try {
                urlPort = Integer.parseInt(authority.substring(colon + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        } else {
            urlHost = authority;
            urlPort = 80;
        }
        return urlPort == expectedPort && urlHost.equalsIgnoreCase(hostHost);
    }

    /**
     * The trusted-network + token gate shared by all {@code /config*} routes.
     *
     * @return {@code null} when the request may proceed, otherwise the 403 error message.
     */
    private String configGateError(Map<String, String> params) {
        if (!trustedNetwork()) {
            return "trusted network is off";
        }
        if (!configTokenOk(params.get("token"), trustedNetworkToken())) {
            return "missing or invalid configuration token";
        }
        return null;
    }

    private final class ConnectionHandler implements Runnable {
        private final Socket socket;

        ConnectionHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                handle(socket);
            } catch (Throwable t) {
                Log.e(TAG, "connection error", t);
            } finally {
                closeQuietly(socket);
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream is = socket.getInputStream();
        OutputStream os = socket.getOutputStream();

        // Headers must arrive promptly; SO_TIMEOUT only fires on idle reads, so a 10 s cap
        // here prevents slow-drip connections from pinning a worker thread.
        socket.setSoTimeout(HEADER_READ_TIMEOUT_MS);

        String requestLine = readLine(is);
        if (requestLine == null || requestLine.trim().isEmpty()) {
            return;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            send(os, 400, "text/plain", "bad request");
            return;
        }
        final String method = parts[0].toUpperCase(Locale.US);
        String fullPath = parts[1];
        int q = fullPath.indexOf('?');
        String path = q >= 0 ? fullPath.substring(0, q) : fullPath;
        String query = q >= 0 ? fullPath.substring(q + 1) : "";

        Map<String, String> headers = readHeaders(is);

        socket.setSoTimeout(READ_TIMEOUT_MS); // restore the body-read timeout for uploads

        try {
            dispatch(method, path, query, headers, is, os);
        } catch (MultipartParser.MultipartException e) {
            Log.w(TAG, "upload rejected: " + e.getMessage());
            sendJson(os, 413, errorJson("upload exceeds size limit or malformed multipart"));
        } catch (Exception e) {
            Log.e(TAG, "handler error on " + method + " " + path, e);
            sendJson(os, 500, errorJson("internal error: " + e.getClass().getSimpleName()));
        } catch (Throwable t) {
            // Catch Errors too (NoSuchMethodError, OutOfMemoryError, ...) so the client gets a 500
            // instead of a silently-closed connection. Best effort: if the stream is already
            // broken the send throws and is swallowed; ConnectionHandler closes the socket.
            Log.e(TAG, "fatal handler error on " + method + " " + path, t);
            try {
                sendJson(os, 500, errorJson("internal error: " + t.getClass().getSimpleName()));
            } catch (Throwable ignored) {
                // nothing more we can do over this connection
            }
        }
    }

    private void dispatch(String method, String path, String query, Map<String, String> headers,
            InputStream is, OutputStream os) throws IOException {
        String routeKey = route(method, path);
        Map<String, String> params = parseQuery(query);

        switch (routeKey) {
            case "index":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "text/html; charset=utf-8", renderIndex());
                break;

            case "state":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "application/json", controller.buildStateJson());
                break;

            case "logsPage":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "text/html; charset=utf-8", renderLogsPage());
                break;

            case "logJson":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "application/json", controller.getLogTail(intParam(params, "lines", 200)));
                break;

            case "logFile":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "text/plain; charset=utf-8", controller.getFileLogTail(intParam(params, "lines", 200)));
                break;

            case "fileList":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "application/json", renderFileListJson());
                break;

            case "fileGet": {
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                String name = urlDecode(decodePathSegment(path));
                File f = controller.uploadedFile(name);
                if (f == null || !f.isFile()) {
                    send(os, 404, "text/plain", "not found");
                    return;
                }
                streamFile(os, f, contentTypeFor(f.getName()));
                break;
            }

            case "fileDelete": {
                if (!method.equals("DELETE") && !method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                String name = urlDecode(decodePathSegment(path, "/delete"));
                boolean ok = controller.deleteUploadedFile(name);
                sendJson(os, ok ? 200 : 404, ok ? okJson("deleted") : errorJson("not found"));
                break;
            }

            case "crashesPage":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "text/html; charset=utf-8", renderCrashesPage());
                break;

            case "crashJson":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "application/json", renderCrashListJson());
                break;

            case "crashGet": {
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                String name = urlDecode(segmentAfter(path, "/crash/", null));
                File f = controller.crashDumpFile(name);
                if (f == null || !f.isFile()) {
                    send(os, 404, "text/plain", "not found");
                    return;
                }
                streamFile(os, f, contentTypeFor(f.getName()));
                break;
            }

            case "crashDelete": {
                if (!method.equals("DELETE") && !method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                // Same-origin, like the config writes. A delete needs no secret, but this frame
                // renders arbitrary LAN pages into its own kiosk WebView, and a simple cross-origin
                // POST is enough to wipe the only evidence of the crash being investigated.
                if (!configWriteAllowed(headers, boundPort)) {
                    sendJson(os, 403, errorJson("cross-origin request rejected"));
                    return;
                }
                String name = urlDecode(segmentAfter(path, "/crash/", "/delete"));
                boolean ok = controller.deleteCrashDump(name);
                sendJson(os, ok ? 200 : 404, ok ? okJson("deleted") : errorJson("not found"));
                break;
            }

            case "reportsPage":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "text/html; charset=utf-8", renderReportsPage());
                break;

            case "reportJson":
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                send(os, 200, "application/json", renderReportListJson());
                break;

            case "reportCapture": {
                if (!method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                // Same-origin only, and deliberately not token-gated — the precedent is crashDelete
                // above: the flag that would carry the token can only be switched on at the dead
                // frame's own screen, which is the situation this button exists for.
                if (!configWriteAllowed(headers, boundPort)) {
                    sendJson(os, 403, errorJson("cross-origin request rejected"));
                    return;
                }
                // Blocks this worker for up to the capture deadline. Bounded, single-flighted and
                // spaced inside the activity, so a page reload loop cannot mint reports.
                DiagnosticsReport.Result r = controller.captureReport();
                JsonObject o = new JsonObject();
                if (r == null) {
                    o.addProperty("ok", false);
                    o.addProperty("msg", "no report was written: another capture is in flight, or"
                            + " one finished less than "
                            + (DiagnosticsReport.CAPTURE_SPACING_MS / 1000L) + "s ago, or the"
                            + " capture did not finish in time");
                } else {
                    o.addProperty("ok", r.ok);
                    o.addProperty("elapsedMs", r.elapsedMs);
                    o.addProperty("bytes", r.bytes);
                    if (r.ok) {
                        o.addProperty("name", r.name);
                        o.addProperty("url", "/report/" + urlEncode(r.name));
                    } else {
                        o.addProperty("msg", r.message);
                    }
                }
                sendJson(os, 200, o);
                break;
            }

            case "reportGet": {
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                String name = urlDecode(segmentAfter(path, "/report/", null));
                File f = controller.reportFile(name);
                if (f == null || !f.isFile()) {
                    send(os, 404, "text/plain", "not found");
                    return;
                }
                streamFile(os, f, contentTypeFor(f.getName()));
                break;
            }

            case "reportDelete": {
                if (!method.equals("DELETE") && !method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                if (!configWriteAllowed(headers, boundPort)) {
                    sendJson(os, 403, errorJson("cross-origin request rejected"));
                    return;
                }
                String name = urlDecode(segmentAfter(path, "/report/", "/delete"));
                boolean ok = controller.deleteReport(name);
                sendJson(os, ok ? 200 : 404, ok ? okJson("deleted") : errorJson("not found"));
                break;
            }

            case "upload":
                if (!method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                handleUpload(headers, is, os);
                break;

            case "update": {
                if (!method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                String updateGateError = configGateError(params);
                if (updateGateError != null) {
                    sendJson(os, 403, errorJson(updateGateError));
                    return;
                }
                if (!configWriteAllowed(headers, boundPort)) {
                    sendJson(os, 403, errorJson("cross-origin request rejected"));
                    return;
                }
                handleUpdateUpload(headers, is, os);
                break;
            }

            case "control": {
                if (!method.equals("GET") && !method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                String action = params.get("action");
                boolean fired = controlAllows(method, action) && controller.control(action, params);
                JsonObject o = new JsonObject();
                o.addProperty("ok", fired);
                o.addProperty("action", action);
                o.addProperty("fired", fired);
                send(os, 200, "application/json", o.toString());
                break;
            }

            case "config": {
                String gateError = configGateError(params);
                if (gateError != null) {
                    sendJson(os, 403, errorJson(gateError));
                    return;
                }
                if (method.equals("GET")) {
                    send(os, 200, "application/json", configJson());
                } else if (method.equals("POST")) {
                    if (!configWriteAllowed(headers, boundPort)) {
                        sendJson(os, 403, errorJson("cross-origin request rejected"));
                        return;
                    }
                    handleConfigPost(headers, is, os);
                } else {
                    send(os, 405, "text/plain", "method not allowed");
                }
                break;
            }

            case "configDownload": {
                String gateError = configGateError(params);
                if (gateError != null) {
                    sendJson(os, 403, errorJson(gateError));
                    return;
                }
                if (!method.equals("GET")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                byte[] configBytes = configJson().getBytes(UTF_8);
                writeHead(os, 200, "application/json", configBytes.length, "configuration.json");
                os.write(configBytes);
                os.flush();
                break;
            }

            case "configImport": {
                String gateError = configGateError(params);
                if (gateError != null) {
                    sendJson(os, 403, errorJson(gateError));
                    return;
                }
                if (!method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                if (!configWriteAllowed(headers, boundPort)) {
                    sendJson(os, 403, errorJson("cross-origin request rejected"));
                    return;
                }
                handleConfigPost(headers, is, os);
                break;
            }

            case "deviceName": {
                if (!method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                String nameGateError = configGateError(params);
                if (nameGateError != null) {
                    sendJson(os, 403, errorJson(nameGateError));
                    return;
                }
                if (!configWriteAllowed(headers, boundPort)) {
                    sendJson(os, 403, errorJson("cross-origin request rejected"));
                    return;
                }
                handleDeviceNamePost(headers, is, os);
                break;
            }

            case "forgetMedia": {
                // The honoured form of a service's removal request. POST-only and same-origin like the
                // other writes, and behind the configuration token: it deletes media this frame fetched
                // and marks it never to fetch again, which is a change to what the frame displays.
                if (!method.equals("POST")) {
                    send(os, 405, "text/plain", "method not allowed");
                    return;
                }
                String forgetGate = configGateError(params);
                if (forgetGate != null) {
                    sendJson(os, 403, errorJson(forgetGate));
                    return;
                }
                if (!configWriteAllowed(headers, boundPort)) {
                    sendJson(os, 403, errorJson("cross-origin request rejected"));
                    return;
                }
                handleForgetPost(params, os);
                break;
            }

            case "health":
                send(os, 200, "text/plain", "ok");
                break;

            default:
                send(os, 404, "text/plain", "not found");
                break;
        }
    }

    private void handleUpload(Map<String, String> headers, InputStream is, OutputStream os)
            throws IOException {
        String boundary = MultipartParser.boundaryFromContentType(headers.get("content-type"));
        if (boundary == null) {
            sendJson(os, 400, errorJson("expected multipart/form-data with a boundary"));
            return;
        }
        File uploadDir = controller.uploadedDir();
        if (!uploadDir.isDirectory() && !uploadDir.mkdirs()) {
            sendJson(os, 500, errorJson("cannot create upload dir"));
            return;
        }
        // Unique per-request temp dir so parallel uploads cannot collide on part names or
        // clean each other's in-progress uploads.
        File incoming = new File(uploadDir, "incoming_" + System.nanoTime());
        if (!incoming.isDirectory() && !incoming.mkdirs()) {
            sendJson(os, 500, errorJson("cannot create upload temp dir"));
            return;
        }

        try {
            // For bounded uploads, drain the whole body off the socket in a single bulk read and
            // parse it from memory. Streaming the body straight off the socket (MultipartParser's
            // many small reads) has been observed to stall the subsequent response write on the
            // target device; reading it in one go sidesteps that. Oversized or missing
            // Content-Length falls back to the direct streaming path.
            InputStream bodyStream = is;
            long contentLength = parseLongLenient(headers.get("content-length"));
            if (contentLength >= 0 && contentLength <= MAX_IN_MEMORY_UPLOAD_BYTES) {
                bodyStream = new ByteArrayInputStream(readAll(is, contentLength));
            }
            List<MultipartParser.Part> parsed = MultipartParser.parse(bodyStream, boundary, incoming,
                    MAX_UPLOAD_BYTES, MAX_UPLOAD_TOTAL_BYTES, MAX_UPLOAD_PARTS);

            List<String> saved = new ArrayList<String>();
            for (MultipartParser.Part p : parsed) {
                if (p.isFile()) {
                    String safe = UploadedMedia.safeFileName(p.filename);
                    String uniq = UploadedMedia.uniqueName(uploadDir, safe);
                    File dest = new File(uploadDir, uniq);
                    if (p.file.renameTo(dest)) {
                        saved.add(uniq);
                    } else {
                        cleanFile(p.file);
                    }
                }
            }

            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            JsonArray arr = new JsonArray();
            for (String s : saved) {
                arr.add(s);
            }
            o.add("files", arr);
            send(os, 200, "application/json", o.toString());
        } finally {
            cleanDir(incoming);
            incoming.delete();
        }
    }

    /**
     * {@code POST /update}: stages an APK uploaded from the web UI. Verification runs on the
     * device the same way as the self-update download path (size cap, SHA-256, signature
     * against the installed certificate, strictly newer versionCode); on success the APK is
     * moved into the update staging area, not installed. The response carries the computed
     * SHA-256 so the user can confirm it against the published release before installing.
     */
    private void handleUpdateUpload(Map<String, String> headers, InputStream is, OutputStream os)
            throws IOException {
        String boundary = MultipartParser.boundaryFromContentType(headers.get("content-type"));
        if (boundary == null) {
            sendJson(os, 400, errorJson("expected multipart/form-data with a boundary"));
            return;
        }
        File updatesDir = controller.apkUploadDir();
        if (updatesDir == null || (!updatesDir.isDirectory() && !updatesDir.mkdirs())) {
            sendJson(os, 500, errorJson("cannot create update dir"));
            return;
        }
        File incoming = new File(updatesDir, "apk_tmp_" + System.nanoTime());
        if (!incoming.isDirectory() && !incoming.mkdirs()) {
            sendJson(os, 500, errorJson("cannot create update temp dir"));
            return;
        }

        try {
            InputStream bodyStream = is;
            long contentLength = parseLongLenient(headers.get("content-length"));
            if (contentLength >= 0 && contentLength <= MAX_IN_MEMORY_UPLOAD_BYTES) {
                bodyStream = new ByteArrayInputStream(readAll(is, contentLength));
            }
            List<MultipartParser.Part> parsed = MultipartParser.parse(bodyStream, boundary, incoming,
                    UpdateManifest.MAX_APK_BYTES, UpdateManifest.MAX_APK_BYTES, 4);

            MultipartParser.Part apk = null;
            int fileParts = 0;
            for (MultipartParser.Part p : parsed) {
                if (p.isFile()) {
                    fileParts++;
                    if (apk == null) {
                        apk = p;
                    }
                }
            }
            if (apk == null) {
                sendJson(os, 400, errorJson("no file in upload"));
                return;
            }
            if (fileParts > 1) {
                sendJson(os, 400, errorJson("expected exactly one file"));
                return;
            }

            ApkUploadResult result = controller.uploadApk(apk.file);
            if (result == null) {
                sendJson(os, 500, errorJson("internal error: upload result unavailable"));
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", result.ok);
            o.addProperty("sha256", result.sha256);
            o.addProperty("sizeBytes", result.sizeBytes);
            o.addProperty("signatureValid", result.signatureValid);
            o.addProperty("reason", result.reason);
            sendJson(os, result.ok ? 200 : 400, o);
        } finally {
            cleanDir(incoming);
            incoming.delete();
        }
    }

    /**
     * Applies a raw JSON configuration body (shared by {@code POST /config} and
     * {@code POST /config/import}). The body is read off the socket with a hard cap before the
     * controller touches it.
     */
    private void handleConfigPost(Map<String, String> headers, InputStream is, OutputStream os)
            throws IOException {
        long declared = parseLongLenient(headers.get("content-length"));
        if (declared > MAX_CONFIG_BODY_BYTES) {
            sendJson(os, 413, errorJson("configuration exceeds " + (MAX_CONFIG_BODY_BYTES / 1024L)
                    + " KB size limit"));
            return;
        }
        // Read the declared length, not until EOF: a POSTing client (the config editor's Save
        // button, curl, fetch) keeps the connection open waiting for the response, so waiting for
        // end-of-stream deadlocks until the client times out — the write lands, but only after the
        // caller has given up. Only an undeclared body falls back to reading to EOF.
        byte[] body = declared >= 0 ? readAll(is, declared) : readBounded(is, MAX_CONFIG_BODY_BYTES);
        if (body.length > MAX_CONFIG_BODY_BYTES) {
            sendJson(os, 413, errorJson("configuration exceeds " + (MAX_CONFIG_BODY_BYTES / 1024L)
                    + " KB size limit"));
            return;
        }
        String error;
        try {
            error = controller.importConfigurationJson(new String(body, UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "config import failed", e);
            error = "internal error: " + e.getClass().getSimpleName();
        }
        if (error == null) {
            sendJson(os, 200, okJson("configuration applied"));
        } else {
            sendJson(os, 400, errorJson(error));
        }
    }

    /**
     * Applies a {@code POST /devicename} body as this frame's label. The name travels in the body
     * rather than the query string so it cannot end up in a logged request line or a pasted URL; an
     * empty body clears it. Only the declared length is read, for the reason given in
     * {@link #handleConfigPost}. Sanitising and the overlay refresh are the implementor's, not ours.
     */
    private void handleDeviceNamePost(Map<String, String> headers, InputStream is, OutputStream os)
            throws IOException {
        long declared = parseLongLenient(headers.get("content-length"));
        if (declared > MAX_DEVICE_NAME_BODY_BYTES) {
            sendJson(os, 413, errorJson("device name exceeds " + MAX_DEVICE_NAME_BODY_BYTES
                    + " bytes"));
            return;
        }
        byte[] body = declared >= 0 ? readAll(is, declared) : readBounded(is, MAX_DEVICE_NAME_BODY_BYTES);
        if (body.length > MAX_DEVICE_NAME_BODY_BYTES) {
            sendJson(os, 413, errorJson("device name exceeds " + MAX_DEVICE_NAME_BODY_BYTES
                    + " bytes"));
            return;
        }
        String name;
        try {
            name = controller.setDeviceName(new String(body, UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "device name write failed", e);
            sendJson(os, 500, errorJson("internal error: " + e.getClass().getSimpleName()));
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        // Echo the stored form, so a name the sanitiser shortened is visible instead of silent.
        o.addProperty("name", name);
        o.addProperty("cleared", name.isEmpty());
        sendJson(os, 200, o);
    }

    /**
     * Reads the whole request body into memory, stopping just past {@code limit} bytes so an
     * oversized body is detected (and rejected by the caller) instead of pinning the socket or
     * exhausting the heap.
     */
    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > limit) {
                break;
            }
        }
        return out.toByteArray();
    }

    /** Parses a non-negative long, returning -1 for null/empty/invalid input. */
    private static long parseLongLenient(String s) {
        if (s == null) {
            return -1;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Reads up to {@code total} bytes from {@code in} into a fresh array. Returns exactly the bytes
     * read; if the stream ends early it returns the (shorter) array. Bounded by {@code total} so a
     * bogus oversized {@code Content-Length} cannot exhaust the heap.
     */
    private static byte[] readAll(InputStream in, long total) throws IOException {
        if (total <= 0) {
            return new byte[0];
        }
        int n = (int) Math.min(total, (long) Integer.MAX_VALUE);
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        if (off == n) {
            return out;
        }
        byte[] trimmed = new byte[off];
        System.arraycopy(out, 0, trimmed, 0, off);
        return trimmed;
    }

    // --- rendering ---

    private String renderIndex() {
        File dir = controller.uploadedDir();
        UploadedMedia.FileInfo[] files = controller.listUploadedFiles();
        StringBuilder sb = new StringBuilder();
        String platform = controller.platform();
        String deviceName = deviceName();
        sb.append(indexTitleHtml(platform, deviceName));
        sb.append("<style>body{font:14px monospace;background:#111;color:#eee;padding:16px}"
                + "table{border-collapse:collapse}td,th{padding:4px 8px;text-align:left}"
                + "button{margin:2px}a{color:#8cf}</style>");
        sb.append(indexHeadingHtml(platform, deviceName, safeIp(), boundPort));
        // Attribution a configured service requires, rendered as text and never as a logo: Flickr's
        // terms forbid its mark without written permission, and permit it only less prominently than the
        // application's own name.
        String termsNotice;
        try {
            termsNotice = controller.serviceTermsNotice();
        } catch (Exception e) {
            termsNotice = "";
        }
        if (termsNotice != null && !termsNotice.isEmpty()) {
            sb.append("<p><small>").append(escapeHtml(termsNotice)).append("</small></p>");
        }
        sb.append("<p>").append(escapeHtml(controller.appBuildSummary())).append("</p>");
        sb.append("<p><a href=\"/state\">state</a> · <a href=\"/logs\">logs</a> · ")
                .append("<a href=\"/crashes\">crashes</a> · ")
                .append("<a href=\"/reports\">reports</a> · ")
                .append("<a href=\"/files\">files (json)</a></p>");
        String crashNotice = crashNotice();
        if (crashNotice != null) {
            sb.append("<p style=\"color:#f88\">").append(crashNotice).append("</p>");
        }
        String notice = reportNotice();
        if (notice != null) {
            sb.append("<p style=\"color:#fb3\">").append(notice).append("</p>");
        }

        sb.append("<h2>Upload</h2>");
        sb.append("<form action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\">");
        sb.append("<input type=\"file\" name=\"file\" multiple><br>");
        sb.append("<button>Upload</button>");
        sb.append("</form>");

        sb.append("<h2>Files</h2>");
        sb.append("<table><tr><th>Name</th><th>Size</th><th></th><th></th></tr>");
        if (files.length == 0) {
            sb.append("<tr><td colspan=\"4\">no uploaded files</td></tr>");
        }
        for (UploadedMedia.FileInfo f : files) {
            sb.append("<tr><td>").append(escapeHtml(f.name)).append("</td>")
                    .append("<td>").append(f.size / 1024L).append(" KB</td>")
                    .append("<td><a href=\"/files/").append(escapeHtml(urlEncode(f.name))).append("\">Download</a></td>")
                    .append("<td><button onclick=\"del('").append(escapeHtmlJs(urlEncode(f.name))).append("')\">Delete</button></td>")
                    .append("</tr>");
        }
        sb.append("</table>");

        sb.append("<h2>Control</h2>");
        sb.append("<button onclick=\"ctrl('next')\">Next</button>")
                .append("<button onclick=\"ctrl('screen')\">Screen</button>")
                .append("<button onclick=\"ctrl('brightness','POST')\">Brightness+</button>")
                .append("<button onclick=\"ctrl('check-updates')\">Check updates</button>")
                .append("<button onclick=\"ctrl('install-staged')\">Install on the frame</button>")
                .append("<button onclick=\"ctrl('update-reset')\">Update reset</button>")
                .append("<div id=\"updateNotice\"></div>");
        if (BuildConfig.DEBUG) {
            sb.append("<br><br><b>Send key (admin)</b> <input type=\"number\" id=\"keycode\" min=\"0\" max=\"65535\" value=\"26\" style=\"width:5em\">")
                    .append("<button onclick=\"sendKey()\">Send</button>")
                    .append("<button onclick=\"fillKey(26)\">POWER</button>")
                    .append("<button onclick=\"fillKey(223)\">SLEEP</button>")
                    .append("<button onclick=\"fillKey(132)\">F2</button>")
                    .append("<button onclick=\"fillKey(134)\">F4</button>")
                    .append("<button onclick=\"fillKey(62)\">SPACE</button>");
        }

        sb.append(renderDiagnosticsSection());
        sb.append(renderConfigurationSection());
        sb.append(renderDeviceNameSection());
        sb.append(renderUpdateSection());

        sb.append("<script>");
        sb.append(controlSectionJs());
        sb.append("</script>");
        sb.append("<script>");
        sb.append(diagnosticsSectionJs());
        sb.append("</script>");
        return sb.toString();
    }

    /**
     * The index page's Diagnostics block: one button that produces the same file the frame produces
     * before it reboots itself, and the newest few of those files. Amber rather than red, and said
     * out loud to be not a crash — the thing a person scanning this page is looking for.
     */
    private String renderDiagnosticsSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Diagnostics</h2>");
        sb.append("<p>")
                .append("<button id=\"diagBtn\" onclick=\"captureReport()\">Capture now</button> ")
                .append("<span id=\"diagStatus\"></span></p>");
        sb.append("<p style=\"color:#999\">The same report the frame writes just before it reboots")
                .append(" itself. Not a crash dump: it is one frame's account of its own state at one")
                .append(" instant, and it is what to attach to a bug report.</p>");
        DiagnosticsReport.Info[] reports;
        try {
            reports = controller.listReports();
        } catch (Throwable t) {
            sb.append("<p>reports unreadable: ").append(escapeHtml(String.valueOf(t)))
                    .append("</p>");
            return sb.toString();
        }
        sb.append("<table><tr><th>When</th><th>Trigger</th><th>Size</th><th></th><th></th></tr>");
        if (reports.length == 0) {
            sb.append("<tr><td colspan=\"5\">no reports stored. Reports are kept for 30 days,")
                    .append(" or until the store passes 8 MB or 40 files.</td></tr>");
        }
        int shown = Math.min(reports.length, 5);
        for (int i = 0; i < shown; i++) {
            DiagnosticsReport.Info r = reports[i];
            String href = "/report/" + escapeHtml(urlEncode(r.name));
            sb.append("<tr><td>").append(escapeHtml(DiagnosticsReport.localTime(r.epochMillis)))
                    .append("</td>")
                    .append("<td>").append(escapeHtml(r.trigger)).append("</td>")
                    .append("<td>").append(r.sizeBytes / 1024L).append(" KB</td>")
                    .append("<td><a href=\"").append(href).append("\" download>Download</a></td>")
                    .append("<td><button onclick=\"delReport('")
                    .append(escapeHtmlJs(urlEncode(r.name))).append("')\">Delete</button></td>")
                    .append("</tr>");
        }
        sb.append("</table>");
        if (reports.length > shown) {
            sb.append("<p>").append(reports.length - shown)
                    .append(" more &mdash; <a href=\"/reports\">all reports</a></p>");
        }
        return sb.toString();
    }

    /**
     * The Diagnostics block's JavaScript. Its own {@code <script>} element and package-private so the
     * routing test can assert on the text it emits: a {@code <script>} is parsed whole before it runs,
     * so one stray token here would silently undefine {@code captureReport} while the page still
     * renders and the button still looks pressable.
     */
    static String diagnosticsSectionJs() {
        // The status is set before the fetch resolves, because a capture on a wedged frame can take
        // the whole deadline and a button that appears to do nothing invites a second click.
        return "function captureReport(){var b=document.getElementById('diagBtn');"
                + "var s=document.getElementById('diagStatus');"
                + "if(b)b.disabled=true;if(s)s.textContent='capturing...';"
                + "fetch('/reports/capture',{method:'POST'}).then(r=>r.json()).then(function(j){"
                + "if(s)s.textContent=j.ok?('wrote '+j.name+' ('+Math.round(j.bytes/1024)+' KB in '"
                + "+j.elapsedMs+'ms)'):(j.msg||'no report written');"
                + "if(j.ok)setTimeout(function(){location.reload();},600);})"
                + ".catch(function(){if(s)s.textContent='capture request failed';})"
                + ".then(function(){if(b)b.disabled=false;});}"
                + "function delReport(n){if(!confirm('Delete '+n+'?'))return;"
                + "fetch('/report/'+n+'/delete',{method:'POST'}).then(r=>r.json())"
                + ".then(function(){location.reload();});}";
    }

    /**
     * One line saying what the frame has already written about itself. Reads {@code stats()} only, for
     * the reason given at {@link #crashNotice()}: the landing page must not open every stored report.
     */
    private String reportNotice() {
        try {
            DiagnosticsReport.Stats s = DiagnosticsReport.stats(controller.reportDir());
            if (s.count == 0) {
                return null;
            }
            String refused = DiagnosticsReport.refusedDispatch(controller.reportDir());
            return "latest diagnostics report: " + escapeHtml(s.lastTrigger == null ? "?" : s.lastTrigger)
                    + " at " + escapeHtml(DiagnosticsReport.localTime(s.lastAtMs))
                    // A -reboot- name says the frame asked. This is where a reader learns nothing
                    // listened, which is the difference between a self-healed frame and a wedged one.
                    + (refused == null ? "" : " &mdash; <b>its reboot command was refused</b> ("
                            + escapeHtml(refused) + ")")
                    + " &mdash; see <a href=\"/reports\">reports</a>.";
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The index page's own JavaScript, package-private so the routing test can assert on the text it emits.
     * A {@code <script>} element is parsed whole before it runs, so one misplaced token in here silently
     * undefines every function in it while the page still renders — hence: no fragment below may leave a
     * string literal open for the next one to close.
     */
    static String controlSectionJs() {
        return "function ctrl(a,m){fetch('/control?action='+a,{method:m||'GET'}).then(r=>r.json()).then(j=>alert(j.action+':'+(j.fired?'fired':'unknown')));}"
                // An update no silent transport can install stays staged and says so here, instead of
                // putting a confirmation dialog on an unattended screen. textContent, never innerHTML:
                // expectedVersionName arrives in a manifest fetched off the network.
                + "function updateNotice(){fetch('/state').then(r=>r.json()).then(function(j){"
                + "var n=document.getElementById('updateNotice');var u=j&&j.update;if(!n)return;"
                + "if(u&&u.needsHumanConfirmation){n.textContent=\"Update \""
                + "+(u.expectedVersionName||u.expectedVersionCode)"
                + "+\" is staged, but this frame cannot install it by itself (install mode: \""
                + "+u.installMode+\"). Use the Install button above, then confirm the dialog on the frame - an OTG keyboard or mouse works. It stays staged and retryable until then\";}"
                + "else if(u&&u.state=='INSTALL_PENDING'&&u.installFiredAtMs){"
                + "n.textContent=\"An install is in flight; the frame retries on its own if it does not land.\";}"
                + "else{n.textContent='';}}).catch(function(){});}"
                + "updateNotice();"
                + "function fillKey(n){document.getElementById('keycode').value=n;}"
                + "function sendKey(){var c=document.getElementById('keycode').value;fetch('/control?action=keyevent&code='+encodeURIComponent(c),{method:'POST'}).then(r=>r.json()).then(j=>alert('keyevent '+(document.getElementById('keycode').value)+':'+(j.fired?'fired':'failed')));}"
                + "function del(n){if(!confirm('Delete '+n+'?'))return;"
                + "fetch('/files/'+n+'/delete',{method:'POST'}).then(r=>r.json()).then(j=>location.reload());}";
    }

    /**
     * This frame's device name, read defensively: the header is on every page, and a controller that
     * cannot reach its preferences must yield "no name" rather than a failed request.
     */
    private String deviceName() {
        try {
            String n = controller.deviceName();
            return n == null ? "" : n;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * A device name's contribution to a page title or heading: {@code "Kitchen · "}, or nothing at all
     * when the frame was never named — which is what keeps every header byte-identical to the one this
     * server produced before the field existed. Escaped here, so no caller can forget to.
     */
    static String deviceNamePrefixHtml(String deviceName) {
        String label = deviceName == null ? "" : deviceName.trim();
        return label.isEmpty() ? "" : escapeHtml(label) + " · ";
    }

    /** The doctype and {@code <title>} of the index page. See {@link #deviceNamePrefixHtml}. */
    static String indexTitleHtml(String platform, String deviceName) {
        return "<!doctype html><meta charset=\"utf-8\"><title>"
                + deviceNamePrefixHtml(deviceName) + escapeHtml(platform) + "</title>";
    }

    /**
     * Forgets one asset ({@code asset} param) or a whole backend's worth ({@code backend} alone).
     * Reports how many the backend stopped holding, because "0" from a backend that has no such
     * obligation and "0" because nothing matched are different answers to give a person acting on a
     * removal request.
     */
    private void handleForgetPost(java.util.Map<String, String> params, java.io.OutputStream os)
            throws IOException {
        String backendId = params.get("backend");
        if (backendId == null || backendId.trim().isEmpty()) {
            sendJson(os, 400, errorJson("a backend id is required"));
            return;
        }
        int count;
        try {
            count = controller.forgetMedia(backendId.trim(), trimToNull(params.get("asset")));
        } catch (Exception e) {
            sendJson(os, 500, errorJson("forget failed: " + e.getClass().getSimpleName()));
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("forgotten", count);
        sendJson(os, 200, out);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The index page's {@code <h1>}. */
    static String indexHeadingHtml(String platform, String deviceName, String ip, int port) {
        return "<h1>" + deviceNamePrefixHtml(deviceName) + escapeHtml(platform)
                + " frame @ " + escapeHtml(ip) + " (port " + port + ")</h1>";
    }

    /**
     * The Device name section of the index page. The name is always visible in the header above; this
     * is the editor, and like the other write paths it is offered only with trusted network on, since
     * the label is per-device and the gate that keeps one frame's settings off another's is the right
     * gate for it.
     */
    private String renderDeviceNameSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Device name</h2>");
        if (!trustedNetwork()) {
            sb.append("<p>Set in the <i>About</i> tab of the on-device options dialog. Renaming from "
                    + "the browser is disabled while <i>Trusted Network</i> is off there.</p>");
            return sb.toString();
        }
        sb.append("<p>A label for <b>this</b> frame only. It is not part of the configuration document, "
                + "so exporting or importing a configuration cannot relabel another frame. It appears in "
                + "the header above and in the frame's own debug overlay. Empty clears it.</p>");
        sb.append("<p><input id=\"devName\" type=\"text\" size=\"32\" maxlength=\"")
                .append(SettingsManager.DEVICE_NAME_MAX_CHARS)
                .append("\" value=\"").append(escapeHtml(deviceName()))
                .append("\" placeholder=\"Kitchen\"> ")
                .append("<button onclick=\"nameFrame()\">Set</button></p>");
        sb.append("<div id=\"devNameStatus\"></div>");
        sb.append("<script>");
        sb.append(deviceNameSectionJs());
        sb.append("</script>");
        return sb.toString();
    }

    /**
     * The device-name editor's JavaScript. Package-private so the routing test can assert the write
     * really is a POST to the gated endpoint, and that it reaches for the same remembered token the
     * configuration editor stores it under.
     */
    static String deviceNameSectionJs() {
        return "function nameFrame(){"
                + "var v=document.getElementById('devName').value;"
                + "var t=(document.getElementById('cfgToken').value||'').trim();"
                + "if(!t){try{t=localStorage.getItem('eo1.cfg.token')||'';}catch(e){}}"
                + "if(!t){alert('Enter the configuration token first (options dialog on the frame).');return;}"
                + "document.getElementById('devNameStatus').textContent='saving...';"
                + "fetch('/devicename?token='+encodeURIComponent(t),{method:'POST',body:v})"
                + ".then(function(r){return r.json().then(function(j){return {s:r.status,j:j};});})"
                + ".then(function(x){"
                + "var st=document.getElementById('devNameStatus');"
                + "if(!x.j||!x.j.ok){st.textContent='rejected: '+((x.j&&x.j.msg)||('HTTP '+x.s));return;}"
                + "rememberToken(t);"
                + "document.getElementById('devName').value=x.j.name;"
                // The header is server-rendered, so the only honest way to show the new name in it is
                // to re-read the page. Brief pause first, so the verdict is visible.
                + "st.textContent=(x.j.cleared?'name cleared':'name saved')+', reloading...';"
                + "setTimeout(function(){location.reload();},600);})"
                + ".catch(function(e){document.getElementById('devNameStatus').textContent='failed: '+e;});}";
    }

    /**
     * The Configuration section of the index page. With trusted network off it is a notice; on,
     * it asks for the per-device configuration token and loads the full editor (backends
     * table, credentials, interval, time zone, quiet-hour cron rows, self-update, MQTT, Save,
     * Export/Import) from {@code GET /config} with the token. The configuration JSON is never
     * embedded in the page itself.
     */
    private String renderConfigurationSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Configuration</h2>");
        if (!trustedNetwork()) {
            sb.append("<p>Trusted network is <b>off</b> — full configuration (credentials, backends, "
                    + "import/export) is disabled. Enable the <i>Trusted Network</i> checkbox in the "
                    + "on-device options dialog to allow it. The read-only summary is in "
                    + "<a href=\"/state\">state</a>.</p>");
            return sb.toString();
        }
        sb.append("<p>Full device configuration — credentials are shown, stored cleartext on the "
                + "device. Loading and saving require the per-device <b>configuration token</b> "
                + "(shown in the on-device options dialog next to the <i>Trusted Network</i> "
                + "checkbox; the browser remembers it in localStorage). Save applies to the "
                + "running device (time zone re-applied, rotation restarted).</p>");
        sb.append("<p>Configuration token: "
                + "<input id=\"cfgToken\" type=\"password\" size=\"32\" style=\"font-family:monospace\" "
                + "placeholder=\"from the on-device options dialog\"> "
                + "<button onclick=\"loadConfig()\">Load</button></p>");
        sb.append("<div id=\"cfgEditor\" style=\"display:none\">");
        sb.append("<table id=\"cfgBackends\"></table>");
        // A removal request has no inbound channel on a LAN-only kiosk, so the honest answer is this
        // control: paste the asset key — /state's media.current.key names the photo on screen — and the
        // frame stops showing it and stops fetching it, durably. Leaving the field empty and choosing a
        // backend forgets everything that backend has ever listed.
        sb.append("<p>Removal request — asset key (leave empty to forget a whole Flickr backend): "
                + "<input id=\"forgetAsset\" size=\"48\" placeholder=\"flickr-1:...\"></p>");
        sb.append("<p><button onclick=\"addCfgBackend('immich')\">+ Add Immich backend</button> ")
            .append("<button onclick=\"addCfgBackend('flickr')\">+ Add Flickr backend</button> ")
                .append("<button onclick=\"addCfgBackend('local')\">+ Add local uploads</button> ")
                .append("<button onclick=\"addCfgBackend('web')\">+ Add website</button> ")
                .append("<button onclick=\"forgetMediaForRow()\">Forget this Flickr asset</button> ")
                .append("<button onclick=\"exportConfig()\">Export configuration</button> ")
                .append("<button onclick=\"document.getElementById('cfgImportFile').click()\">Import configuration</button></p>");
        sb.append("<input type=\"file\" id=\"cfgImportFile\" accept=\".json,application/json\" "
                + "style=\"display:none\" onchange=\"importConfig(this)\">");
        sb.append("<table>")
                .append("<tr><th>Slideshow interval (minutes)</th><td><input id=\"cfgInterval\" type=\"number\" min=\"1\"></td></tr>")
                .append("<tr><th>Time zone (IANA id)</th><td><input id=\"cfgTimezone\" size=\"30\" placeholder=\"America/Los_Angeles\"></td></tr>")
                .append("<tr><th>Self-update manifest URL (empty = off)</th><td><input id=\"cfgUpdateUrl\" size=\"50\" placeholder=\"http://host/eo1/update-manifest.json\"></td></tr>")
                .append("<tr><th>Update check interval (minutes)</th><td><input id=\"cfgUpdateCheck\" type=\"number\" min=\"5\" max=\"1440\"></td></tr>")
                .append("<tr><th>Reboot if nothing else recovers it</th><td><input id=\"cfgAutoReboot\" type=\"checkbox\"> ")
                .append("<span style=\"color:#666\">last rung of the kiosk watchdog. Re-driving the rotation, ")
                .append("tearing the player down, cycling Wi-Fi and restarting the app happen whether or not ")
                .append("this is on — it only licenses the final reboot.</span></td></tr>")
                .append("<tr><th>MQTT host</th><td><input id=\"cfgMqttHost\" size=\"30\"></td></tr>")
                .append("<tr><th>MQTT port</th><td><input id=\"cfgMqttPort\" type=\"number\"></td></tr>")
                .append("<tr><th>MQTT protocol</th><td><input id=\"cfgMqttProtocol\" size=\"10\"></td></tr>")
                .append("<tr><th>MQTT user</th><td><input id=\"cfgMqttUser\" size=\"20\"></td></tr>")
                .append("<tr><th>MQTT password</th><td><input id=\"cfgMqttPassword\" type=\"password\" size=\"20\"></td></tr>")
                .append("</table>");
        sb.append("<h3>Quiet hours (cron: minute hour day month weekday)</h3>");
        sb.append("<div id=\"cfgQuiet\"></div>");
        sb.append("<button onclick=\"addCfgQuiet()\">+ Add quiet window</button><br>");
        sb.append("<p><button onclick=\"saveConfig()\">Save configuration</button></p>");
        sb.append("</div>");
        sb.append("<script>");
        sb.append("var CONFIG=null;");
        sb.append(cfgSectionJs());
        sb.append("</script>");
        return sb.toString();
    }

    /**
     * The config editor's JavaScript. Package-private so the round-trip test can assert that every
     * backend field the editor renders is also re-serialized by {@code collectCfg()} — a field that
     * renders but is not collected is deleted from the user's configuration on the next save.
     */
    static String cfgSectionJs() {
        return "function escCfg(s){s=(s===undefined||s===null)?'':String(s);"
                + "return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&#39;');}"
                + "function cfgBackends(){return Array.isArray(CONFIG.backends)?CONFIG.backends:[];}"
                + "function renderCfgBackends(){"
                + "var t=document.getElementById('cfgBackends');"
                + "t.innerHTML='<tr><th>ID</th><th>Type</th><th>Enabled</th><th>Server</th><th>Username</th><th>Password</th><th>API</th><th></th></tr>';"
                + "cfgBackends().forEach(function(b,i){"
                + "var tr=document.createElement('tr');"
                + "var h='<td>'+escCfg(b.id)+'</td><td>'+escCfg(b.type)+'</td>';"
                + "if(b.type==='immich'||b.type==='web'){"
                + "var ph=(b.type==='web')?'http://host/page':'http://immich:2283';"
                + "h+='<td><input type=\"checkbox\" class=\"cfgEnabled\" '+(b.enabled!==false?'checked':'')+'></td>'"
                + "+'<td><input class=\"cfgHost\" size=\"30\" value=\"'+escCfg(b.host)+'\" placeholder=\"'+ph+'\"></td>';"
                + "if(b.type==='immich'){"
                + "h+='<td><input class=\"cfgUser\" size=\"15\" value=\"'+escCfg(b.userid)+'\"></td>'"
                + "+'<td><input class=\"cfgPass\" type=\"password\" size=\"15\" value=\"'+escCfg(b.password)+'\"></td>'"
                + "+'<td><input class=\"cfgApi\" size=\"10\" value=\"'+escCfg(b.apiVersion||'auto')+'\"></td>';"
                + "}else{h+='<td colspan=\"3\"></td>';}"
                + "}else if(b.type==='flickr'){"
                // Three columns' worth of cells, so the row still sums to the eight-column header: a
                // Flickr entry has no server and no username. Both fields it does have must still be
                // rendered, because collectCfg() replaces cfg.backends wholesale and a field no row
                // draws is a field that silently disappears on save.
                + "h+='<td><input type=\"checkbox\" class=\"cfgEnabled\" '+(b.enabled!==false?'checked':'')+'></td>'"
                + "+'<td colspan=\"3\">stream <input class=\"cfgSource\" size=\"30\" value=\"'+escCfg(b.source)+'\" placeholder=\"https://www.flickr.com/photos/you/\"> '"
                + "+'key <input class=\"cfgKey\" type=\"password\" size=\"30\" value=\"'+escCfg(b.apiKey)+'\" placeholder=\"application key\"></td>'"
                + "+'<td></td>';"
                + "}else{h+='<td colspan=\"5\">local uploads (files stored on this frame - no server, no login)</td>';}"
                + "h+='<td><button onclick=\"removeCfgBackend('+i+')\">Remove</button></td>';"
                + "tr.innerHTML=h;t.appendChild(tr);});}"
                + "function nextBackendId(prefix){"
                + "var max=0;var re=new RegExp('^'+prefix+'-(\\\\d+)$');"
                + "cfgBackends().forEach(function(b){var m=re.exec(String(b.id||''));if(m){var n=parseInt(m[1],10);if(n>max)max=n;}});"
                + "return prefix+'-'+(max+1);}"
                + "function addCfgBackend(type){"
                + "if(type==='local'&&cfgBackends().some(function(b){return b.type==='local';})){alert('Local uploads backend already present');return;}"
                + "if(!CONFIG.backends)CONFIG.backends=[];"
                + "if(type==='immich'){CONFIG.backends.push({type:'immich',id:nextBackendId('immich'),host:'',userid:'',password:'',apiVersion:'auto',enabled:true});}"
                + "else if(type==='web'){CONFIG.backends.push({type:'web',id:nextBackendId('web'),host:'',enabled:true});}"
                + "else if(type==='flickr'){CONFIG.backends.push({type:'flickr',id:nextBackendId('flickr'),apiKey:'',source:'',enabled:true});}"
                + "else{CONFIG.backends.push({type:'local',id:'local'});}"
                + "renderCfgBackends();}"
                + "function removeCfgBackend(i){if(!CONFIG.backends)return;CONFIG.backends.splice(i,1);renderCfgBackends();}"
                + "function forgetMediaForRow(){"
                + "var f=cfgBackends().filter(function(b){return b.type==='flickr';});"
                + "if(!f.length){alert('No Flickr backend configured on this frame.');return;}"
                + "if(f.length>1){forgetFlickrChooser(f);return;}"
                + "forgetMedia(f[0].id);}"
                + "function forgetFlickrChooser(list){"
                + "var names=list.map(function(b){return b.id;}).join(', ');"
                + "var pick=prompt('Which Flickr backend? One of: '+names);"
                + "if(pick&&list.some(function(b){return b.id===pick.trim();}))forgetMedia(pick.trim());}"
                + "function forgetMedia(backendId){"
                + "var t=cfgTokenValue();"
                + "if(!t){alert('Enter the configuration token first (options dialog on the frame).');return;}"
                + "var q='/forget?token='+encodeURIComponent(t)+'&backend='+encodeURIComponent(backendId);"
                + "var a=document.getElementById('forgetAsset');"
                + "if(a&&a.value.trim())q+='&asset='+encodeURIComponent(a.value.trim());"
                + "fetch(q,{method:'POST'}).then(function(r){return r.json();})"
                + ".then(function(j){alert(j.forgotten===undefined?(j.error||'failed'):('stopped holding '+j.forgotten+' asset(s)'));})"
                + ".catch(function(e){alert('Forget failed: '+e);});}"
                + "function renderCfgQuiet(){"
                + "var c=document.getElementById('cfgQuiet');c.innerHTML='';"
                + "(Array.isArray(CONFIG.quietHours)?CONFIG.quietHours:[]).forEach(function(w,i){"
                + "var d=document.createElement('div');"
                + "d.innerHTML='<input class=\"cfgQuietWin\" size=\"30\" style=\"font-family:monospace\" value=\"'+escCfg(w)+'\">'"
                + "+' <button onclick=\"removeCfgQuiet('+i+')\">Remove</button>';"
                + "c.appendChild(d);});}"
                + "function addCfgQuiet(){if(!CONFIG.quietHours)CONFIG.quietHours=[];CONFIG.quietHours.push('* 22-23,0-6 * * *');renderCfgQuiet();}"
                + "function removeCfgQuiet(i){if(!CONFIG.quietHours)return;CONFIG.quietHours.splice(i,1);renderCfgQuiet();}"
                + "function collectCfg(){"
                + "var rows=Array.prototype.slice.call(document.querySelectorAll('#cfgBackends tr')).slice(1);"
                + "var cfg=JSON.parse(JSON.stringify(CONFIG));"
                + "cfg.backends=rows.map(function(tr){"
                + "var b={id:String(tr.cells[0].textContent),type:String(tr.cells[1].textContent)};"
                // Both host-bearing types must be re-serialized here: rows.map() replaces
                // cfg.backends wholesale, so a field no row renders is dropped on save. For a web
                // row that field is its only content — the page URL.
                + "if(b.type==='immich'||b.type==='web'){"
                + "b.enabled=!!tr.querySelector('.cfgEnabled').checked;"
                + "b.host=(tr.querySelector('.cfgHost').value||'').trim();}"
                + "if(b.type==='flickr'){"
                + "b.enabled=!!tr.querySelector('.cfgEnabled').checked;"
                + "b.source=(tr.querySelector('.cfgSource').value||'').trim();"
                + "b.apiKey=tr.querySelector('.cfgKey').value||'';"
                + "return b;}"
                + "if(b.type==='immich'){"
                + "b.userid=(tr.querySelector('.cfgUser').value||'').trim();"
                + "b.password=tr.querySelector('.cfgPass').value||'';"
                + "var api=(tr.querySelector('.cfgApi').value||'').trim();"
                + "b.apiVersion=api||'auto';}"
                + "return b;});"
                + "cfg.quietHours=Array.prototype.map.call(document.querySelectorAll('.cfgQuietWin'),function(el){return (el.value||'').trim();})"
                + ".filter(function(s){return s.length>0;});"
                + "cfg.interval=Math.max(1,parseInt(document.getElementById('cfgInterval').value,10)||1);"
                + "cfg.updateManifestUrl=(document.getElementById('cfgUpdateUrl').value||'').trim();"
                + "var uc=parseInt(document.getElementById('cfgUpdateCheck').value,10);"
                + "cfg.updateCheckIntervalMinutes=isNaN(uc)?120:Math.max(5,Math.min(1440,uc));"
                + "cfg.autoReboot=!!document.getElementById('cfgAutoReboot').checked;"
                + "cfg.selectedTimeZoneId=(document.getElementById('cfgTimezone').value||'').trim();"
                + "cfg.mqttHost=(document.getElementById('cfgMqttHost').value||'').trim();"
                + "var mp=parseInt(document.getElementById('cfgMqttPort').value,10);"
                + "cfg.mqttPort=isNaN(mp)?1883:mp;"
                + "cfg.mqttProtocol=(document.getElementById('cfgMqttProtocol').value||'').trim();"
                + "cfg.mqttUser=(document.getElementById('cfgMqttUser').value||'').trim();"
                + "cfg.mqttPassword=document.getElementById('cfgMqttPassword').value||'';"
                + "return cfg;}"
                + "function cfgTokenValue(){return (document.getElementById('cfgToken').value||'').trim();}"
                + "function rememberToken(t){try{localStorage.setItem('eo1.cfg.token',t);}catch(e){}}"
                + "function loadConfig(){"
                + "var t=cfgTokenValue();"
                + "if(!t){alert('Enter the configuration token first (options dialog on the frame).');return;}"
                + "fetch('/config?token='+encodeURIComponent(t))"
                + ".then(function(r){return r.json().then(function(j){if(!r.ok)throw new Error(j.msg||('HTTP '+r.status));return j;});})"
                + ".then(function(j){CONFIG=j;rememberToken(t);document.getElementById('cfgEditor').style.display='';fillCfg();})"
                + ".catch(function(e){alert('Load failed: '+e);});}"
                + "function fillCfg(){"
                + "document.getElementById('cfgInterval').value=CONFIG.interval||5;"
                + "document.getElementById('cfgTimezone').value=CONFIG.selectedTimeZoneId||'';"
                + "document.getElementById('cfgUpdateUrl').value=CONFIG.updateManifestUrl||'';"
                + "document.getElementById('cfgUpdateCheck').value=CONFIG.updateCheckIntervalMinutes||120;"
                + "document.getElementById('cfgAutoReboot').checked=CONFIG.autoReboot===true;"
                + "document.getElementById('cfgMqttHost').value=CONFIG.mqttHost||'';"
                + "document.getElementById('cfgMqttPort').value=CONFIG.mqttPort||1883;"
                + "document.getElementById('cfgMqttProtocol').value=CONFIG.mqttProtocol||'';"
                + "document.getElementById('cfgMqttUser').value=CONFIG.mqttUser||'';"
                + "document.getElementById('cfgMqttPassword').value=CONFIG.mqttPassword||'';"
                + "renderCfgBackends();renderCfgQuiet();}"
                + "function saveConfig(){"
                + "if(!CONFIG){alert('Load the configuration first.');return;}"
                + "var t=cfgTokenValue();"
                + "if(!t){alert('Enter the configuration token first.');return;}"
                + "fetch('/config?token='+encodeURIComponent(t),{method:'POST',body:JSON.stringify(collectCfg())})"
                + ".then(function(r){return r.json();})"
                + ".then(function(j){alert(j.ok?'Configuration saved':'Error: '+j.msg);if(j.ok)location.reload();})"
                + ".catch(function(e){alert('Save failed: '+e);});}"
                + "function exportConfig(){"
                + "var t=cfgTokenValue();"
                + "if(!t){alert('Enter the configuration token first.');return;}"
                + "location.href='/config/download?token='+encodeURIComponent(t);}"
                + "function importConfig(input){"
                + "var f=input.files&&input.files[0];input.value='';"
                + "if(!f)return;"
                + "var t=cfgTokenValue();"
                + "if(!t){alert('Enter the configuration token first.');return;}"
                + "var rd=new FileReader();"
                + "rd.onload=function(){"
                + "fetch('/config/import?token='+encodeURIComponent(t),{method:'POST',body:rd.result})"
                + ".then(function(r){return r.json();})"
                + ".then(function(j){alert(j.ok?'Configuration imported':'Error: '+j.msg);if(j.ok)location.reload();})"
                + ".catch(function(e){alert('Import failed: '+e);});};"
                + "rd.readAsText(f);}"
                + "(function(){var t='';"
                + "try{t=localStorage.getItem('eo1.cfg.token')||'';}catch(e){}"
                + "if(t){document.getElementById('cfgToken').value=t;loadConfig();}})();";
    }

    /**
     * The "Update (APK upload)" section of the index page. With trusted network off it is a
     * notice; on, it offers the APK upload form, gated by the same per-device configuration
     * token as the config endpoints (same input, remembered in localStorage). After a
     * successful upload the computed SHA-256 is shown so the user can check it against the
     * published release; Install/Reset reuse the {@code /control} actions. When an APK is
     * already staged, an on-load {@code /state} fetch re-shows the checksum and the buttons
     * after a page reload.
     */
    private String renderUpdateSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Update (APK upload)</h2>");
        if (!trustedNetwork()) {
            sb.append("<p>APK upload is disabled — it requires the <i>Trusted Network</i> "
                    + "checkbox in the on-device options dialog.</p>");
            return sb.toString();
        }
        sb.append("<p>Upload a signed release APK to stage it for install. The upload is verified "
                + "the same way as the self-update download (size cap, SHA-256, signature against "
                + "the installed app, strictly newer versionCode). The computed SHA-256 is shown "
                + "after upload — check it against the published release before installing. The "
                + "staged APK is not installed automatically.</p>");
        sb.append("<p><input type=\"file\" id=\"apkFile\" accept=\".apk,application/vnd.android.package-archive\"> "
                + "<button onclick=\"uploadApk()\">Upload APK</button></p>");
        sb.append("<div id=\"apkStatus\"></div>");
        sb.append("<script>");
        sb.append(updateSectionJs());
        sb.append("</script>");
        return sb.toString();
    }

    static String updateSectionJs() {
        return "function stagedBlock(sha,bytes){"
                + "var h='<p><b>SHA-256: </b><code style=\"display:block;word-break:break-all\">'+sha+'</code><br>'"
                + "+bytes+' bytes. Verify this checksum against the published release before installing.</p>'"
                + "+'<p><button id=\"apkInstall\">Install on the frame</button> '"
                + "+'<button id=\"apkReset\">Discard staged</button></p>';"
                + "document.getElementById('apkStatus').innerHTML=h;"
                + "document.getElementById('apkInstall').onclick=function(){ctrl('install-staged');};"
                + "document.getElementById('apkReset').onclick=function(){ctrl('update-reset');};}"
                + "function uploadApk(){"
                + "var f=document.getElementById('apkFile').files[0];"
                + "if(!f){alert('Choose an APK file first.');return;}"
                + "var t=(document.getElementById('cfgToken').value||'').trim();"
                + "if(!t){try{t=localStorage.getItem('eo1.cfg.token')||'';}catch(e){}}"
                + "if(!t){alert('Enter the configuration token first (options dialog on the frame).');return;}"
                + "var fd=new FormData();fd.append('file',f);"
                + "document.getElementById('apkStatus').innerHTML='Uploading...';"
                + "fetch('/update?token='+encodeURIComponent(t),{method:'POST',body:fd})"
                + ".then(function(r){return r.json();})"
                + ".then(function(j){"
                + "document.getElementById('apkFile').value='';"
                + "if(j.ok){stagedBlock(j.sha256,j.sizeBytes);}"
                + "else{document.getElementById('apkStatus').innerHTML='Rejected: '+j.reason;}})"
                + ".catch(function(e){document.getElementById('apkStatus').innerHTML='Upload failed: '+e;});}"
                + "(function(){"
                + "fetch('/state')"
                + ".then(function(r){return r.json();})"
                + ".then(function(j){"
                + "var u=j&&j.update;"
                + "if(u&&u.stagedApk&&u.stagedApk.present&&u.stagedSha256){"
                + "stagedBlock(u.stagedSha256,u.stagedApk.bytes);}})"
                + ".catch(function(){});"
                + "})();";
    }

    private String renderLogsPage() {
        return "<!doctype html><meta charset=\"utf-8\"><title>"
                + deviceNamePrefixHtml(deviceName())
                + escapeHtml(controller.platform())
                + " logs</title>"
                + "<style>body{font:12px monospace;background:#111;color:#eee;padding:8px}"
                + "a{color:#8cf}</style>"
                + "<p><a href=\"/\">back</a> · <a href=\"/log/file?lines=500\">raw file</a></p>"
                + "<pre id=\"logs\"></pre>"
                + "<script>async function poll(){try{const r=await fetch('/log.json?lines=300');"
                + "const j=await r.json();document.getElementById('logs').textContent=j.map(e=>e.ts+' '+e.level+' ['+e.tag+'] '+e.msg).join('\\n');}"
                + "catch(e){}}poll();setInterval(poll,3000);</script>";
    }

    /** One line above the fold when this frame is holding crash reports. */
    /**
     * The {@code /reports} page: every diagnostics report the frame has kept, with what made it write
     * each one. Same shape as {@code /crashes} — server-rendered, plain reload link — because this
     * store only changes when the ladder reaches its last rung or someone presses the button.
     */
    private String renderReportsPage() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><meta charset=\"utf-8\"><title>")
                .append(deviceNamePrefixHtml(deviceName()))
                .append(escapeHtml(controller.platform())).append(" diagnostics reports</title>")
                .append("<style>body{font:12px monospace;background:#111;color:#eee;padding:8px}")
                .append("table{border-collapse:collapse}td,th{padding:3px 8px;text-align:left}")
                .append("a{color:#8cf}button{font:11px monospace}</style>")
                .append("<p><a href=\"/\">back</a> · <a href=\"/reports.json\">json</a> · ")
                .append("<a href=\"/reports\">reload</a></p>");

        DiagnosticsReport.Info[] reports;
        try {
            reports = controller.listReports();
        } catch (Throwable t) {
            sb.append("<p>report store unreadable: ").append(escapeHtml(String.valueOf(t)))
                    .append("</p>");
            return sb.toString();
        }
        if (reports.length == 0) {
            sb.append("<p>no diagnostics reports stored. The frame writes one when the kiosk watchdog")
                    .append(" reaches its reboot rung (whether or not the reboot was allowed) and when")
                    .append(" the button on the <a href=\"/\">landing page</a> is pressed. Reports are")
                    .append(" kept for 30 days, or until the store passes 8 MB or 40 files, and the")
                    .append(" newest non-manual one is always kept.</p>");
            return sb.toString();
        }
        sb.append("<p>").append(reports.length)
                .append(" report(s). A <code>-reboot-</code> name means the frame was about to reboot")
                .append(" itself; <code>-exhausted-</code> means it reached that rung and was not")
                .append(" allowed to, or adbd refused; <code>-manual-</code> means someone asked.")
                .append("</p>");
        sb.append("<table><tr><th>When</th><th>Trigger</th><th>Reason</th><th>Size</th>")
                .append("<th>Build</th><th></th><th></th></tr>");
        for (DiagnosticsReport.Info r : reports) {
            String href = "/report/" + escapeHtml(urlEncode(r.name));
            sb.append("<tr><td>").append(escapeHtml(DiagnosticsReport.localTime(r.epochMillis)))
                    .append("</td>")
                    .append("<td>").append(escapeHtml(r.trigger)).append("</td>")
                    .append("<td>").append(escapeHtml(r.reason == null ? r.name : r.reason))
                    .append("</td>")
                    .append("<td>").append(r.sizeBytes / 1024L).append(" KB</td>")
                    .append("<td>").append(r.versionCode).append("</td>")
                    .append("<td><a href=\"").append(href).append("\" download>Download</a></td>")
                    .append("<td><button onclick=\"delReport('")
                    .append(escapeHtmlJs(urlEncode(r.name))).append("')\">Delete</button></td>")
                    .append("</tr>");
        }
        sb.append("</table>");
        sb.append("<script>function delReport(n){if(!confirm('Delete '+n+'?'))return;")
                .append("fetch('/report/'+n+'/delete',{method:'POST'}).then(r=>r.json())")
                .append(".then(function(){location.reload();});}</script>");
        return sb.toString();
    }

    /** The {@code /reports.json} listing: what the names carry, plus each one's header {@code Reason}. */
    private String renderReportListJson() {
        JsonArray arr = new JsonArray();
        DiagnosticsReport.Info[] reports;
        try {
            reports = controller.listReports();
        } catch (Throwable t) {
            return arr.toString();
        }
        for (DiagnosticsReport.Info r : reports) {
            JsonObject o = new JsonObject();
            o.addProperty("name", r.name);
            o.addProperty("trigger", r.trigger);
            o.addProperty("epochMillis", r.epochMillis);
            o.addProperty("versionCode", r.versionCode);
            o.addProperty("reason", r.reason);
            o.addProperty("size", r.sizeBytes);
            o.addProperty("lastModified", r.lastModified);
            arr.add(o);
        }
        return arr.toString();
    }

    private String crashNotice() {
        try {
            // stats(), not listCrashDumps(): the notice needs a count and a timestamp, both of which
            // are in the file names. Reading the store's headers here would open every report on
            // every load of the landing page, on a device slow enough to feel it.
            CrashStore.Stats stats = CrashStore.stats(controller.crashDir());
            if (stats.count == 0) {
                return null;
            }
            return stats.count + " crash report(s) stored, most recent "
                    + escapeHtml(CrashStore.localTime(stats.lastAtMs))
                    + " &mdash; see <a href=\"/crashes\">crashes</a>.";
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The {@code /crashes} page. Server-rendered with a plain reload link rather than the {@code
     * /logs} page's 3 s polling: the store only changes when a process dies or one starts.
     */
    private String renderCrashesPage() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><meta charset=\"utf-8\"><title>")
                .append(deviceNamePrefixHtml(deviceName()))
                .append(escapeHtml(controller.platform())).append(" crashes</title>")
                .append("<style>body{font:12px monospace;background:#111;color:#eee;padding:8px}")
                .append("table{border-collapse:collapse}td,th{padding:3px 8px;text-align:left}")
                .append("a{color:#8cf}button{font:11px monospace}</style>")
                .append("<p><a href=\"/\">back</a> · <a href=\"/crash.json\">json</a> · ")
                .append("<a href=\"/crashes\">reload</a></p>");

        CrashStore.Info[] dumps;
        try {
            dumps = controller.listCrashDumps();
        } catch (Throwable t) {
            // A listing that cannot be read must still answer 200, not wedge this thread.
            sb.append("<p>crash store unreadable: ").append(escapeHtml(String.valueOf(t))).append("</p>");
            return sb.toString();
        }
        if (dumps.length == 0) {
            sb.append("<p>no crash reports stored. Reports are kept for 14 days, or until the store ")
                    .append("passes 4 MB.<br>Native and ANR captures are scraped at startup — ")
                    .append("<a href=\"/state\">/state</a> reports whether each source was readable.</p>");
            return sb.toString();
        }
        sb.append("<p>").append(dumps.length)
                .append(" report(s). A release trace is obfuscated: retrace it against ")
                .append("<code>mapping-&lt;versionCode&gt;.txt.gz</code>, archived beside the APK.")
                .append("</p>");
        sb.append("<table><tr><th>When</th><th>Kind</th><th>Exception / source</th>")
                .append("<th>Size</th><th>Mapping</th><th></th><th></th></tr>");
        for (CrashStore.Info d : dumps) {
            String href = "/crash/" + escapeHtml(urlEncode(d.name));
            sb.append("<tr><td>").append(escapeHtml(CrashStore.localTime(d.epochMillis))).append("</td>")
                    .append("<td>").append(escapeHtml(d.kind)).append("</td>")
                    .append("<td>").append(escapeHtml(d.exception != null ? d.exception : d.name))
                    .append("</td>")
                    .append("<td>").append(d.sizeBytes / 1024L).append(" KB</td>")
                    .append("<td>mapping-").append(d.versionCode).append(".txt.gz</td>")
                    .append("<td><a href=\"").append(href).append("\">Download</a></td>")
                    .append("<td><button onclick=\"del('")
                    .append(escapeHtmlJs(urlEncode(d.name))).append("')\">Delete</button></td>")
                    .append("</tr>");
        }
        sb.append("</table>");
        sb.append("<script>function del(n){if(!confirm('Delete crash report '+n+'?'))return;")
                .append("fetch('/crash/'+encodeURIComponent(n)+'/delete',{method:'POST'})")
                .append(".then(function(){location.reload();});}</script>");
        return sb.toString();
    }

    private String renderCrashListJson() {
        JsonArray arr = new JsonArray();
        CrashStore.Info[] dumps;
        try {
            dumps = controller.listCrashDumps();
        } catch (Throwable t) {
            return arr.toString();
        }
        for (CrashStore.Info d : dumps) {
            JsonObject o = new JsonObject();
            o.addProperty("name", d.name);
            o.addProperty("kind", d.kind);
            o.addProperty("epochMillis", d.epochMillis);
            o.addProperty("versionCode", d.versionCode);
            o.addProperty("exception", d.exception);
            o.addProperty("size", d.sizeBytes);
            o.addProperty("lastModified", d.lastModified);
            arr.add(o);
        }
        return arr.toString();
    }

    private String renderFileListJson() {
        UploadedMedia.FileInfo[] files = controller.listUploadedFiles();
        JsonArray arr = new JsonArray();
        for (UploadedMedia.FileInfo f : files) {
            JsonObject o = new JsonObject();
            o.addProperty("name", f.name);
            o.addProperty("size", f.size);
            o.addProperty("lastModified", f.lastModified);
            arr.add(o);
        }
        return arr.toString();
    }

    // --- helpers ---

    private String decodePathSegment(String path) {
        return segmentAfter(path, "/files/", null);
    }

    private String decodePathSegment(String path, String suffix) {
        return segmentAfter(path, "/files/", suffix);
    }

    /** The one path segment after {@code prefix}, minus an optional trailing {@code suffix}. */
    private static String segmentAfter(String path, String prefix, String suffix) {
        String rest = path.substring(prefix.length());
        if (suffix != null && rest.endsWith(suffix)) {
            rest = rest.substring(0, rest.length() - suffix.length());
        }
        return rest;
    }

    private static int intParam(Map<String, String> params, String key, int dflt) {
        String v = params.get(key);
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String urlDecode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    // Escape for inside a single-quoted JS string.
    private static String escapeHtmlJs(String s) {
        return escapeHtml(s).replace("'", "&#39;");
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<String, String>();
        if (query == null || query.isEmpty()) {
            return m;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k;
            String v;
            if (eq >= 0) {
                k = pair.substring(0, eq);
                v = pair.substring(eq + 1);
            } else {
                k = pair;
                v = "";
            }
            m.put(urlDecode(k), urlDecode(v));
        }
        return m;
    }

    static String contentTypeFor(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        String e = name.substring(dot + 1).toLowerCase(Locale.US);
        if (e.equals("txt")) {
            return "text/plain; charset=utf-8";
        }
        if (e.equals("jpg") || e.equals("jpeg")) {
            return "image/jpeg";
        }
        if (e.equals("png")) {
            return "image/png";
        }
        if (e.equals("gif")) {
            return "image/gif";
        }
        if (e.equals("webp")) {
            return "image/webp";
        }
        if (e.equals("mp4") || e.equals("m4v")) {
            return "video/mp4";
        }
        if (e.equals("mov")) {
            return "video/quicktime";
        }
        if (e.equals("mkv")) {
            return "video/x-matroska";
        }
        if (e.equals("ts")) {
            return "video/mp2ts";
        }
        if (e.equals("webm")) {
            return "video/webm";
        }
        return "application/octet-stream";
    }

    private static JsonObject okJson(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("msg", msg);
        return o;
    }

    private static JsonObject errorJson(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("msg", msg);
        return o;
    }

    private void send(OutputStream os, int status, String contentType, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(UTF_8);
        writeHead(os, status, contentType, bytes.length);
        if (bytes.length > 0) {
            os.write(bytes);
        }
        os.flush();
    }

    private void sendJson(OutputStream os, int status, JsonObject body) throws IOException {
        send(os, status, "application/json", body.toString());
    }

    private void streamFile(OutputStream os, File f, String contentType) throws IOException {
        // Open before sending headers so a concurrently-deleted file yields a clean 500
        // instead of a 200 head followed by a failed body.
        FileInputStream in = new FileInputStream(f);
        long len = f.length();
        writeHead(os, 200, contentType, len, f.getName());
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        os.flush();
    }

    private void writeHead(OutputStream os, int status, String contentType, long contentLength)
            throws IOException {
        writeHead(os, status, contentType, contentLength, null);
    }

    private void writeHead(OutputStream os, int status, String contentType, long contentLength,
            String attachmentName) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(contentLength).append("\r\n");
        if (attachmentName != null) {
            head.append("Content-Disposition: attachment; filename=\"").append(escapeHtml(attachmentName))
                    .append("\"\r\n");
        }
        head.append("Connection: close\r\n");
        head.append("\r\n");
        os.write(head.toString().getBytes(UTF_8));
    }

    private static String statusText(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 500:
                return "Internal Server Error";
            default:
                return "OK";
        }
    }

    private static String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = is.read()) != -1) {
            if (c == '\n') {
                break;
            }
            out.write(c);
            if (out.size() > 8192) {
                return null;
            }
        }
        if (c == -1 && out.size() == 0) {
            return null;
        }
        byte[] b = out.toByteArray();
        int len = b.length;
        if (len > 0 && b[len - 1] == '\r') {
            len--;
        }
        return new String(b, 0, len, UTF_8);
    }

    private static Map<String, String> readHeaders(InputStream is) throws IOException {
        Map<String, String> m = new HashMap<String, String>();
        String line;
        int total = 0;
        while ((line = readLine(is)) != null && !line.isEmpty()) {
            total += line.length() + 1;
            if (total > MAX_HEADER_BYTES) {
                throw new IOException("header size limit exceeded");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                m.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        return m;
    }

    private static void cleanFile(File f) {
        if (f != null && f.exists() && !f.delete()) {
            // best effort
        }
    }

    private static void cleanDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isFile()) {
                    c.delete();
                }
            }
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
