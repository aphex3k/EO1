package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class WebServerRoutingTest {

    @Test
    public void indexRoutes() {
        assertEquals("index", WebServer.route("GET", "/"));
        assertEquals("index", WebServer.route("GET", "/index.html"));
    }

    @Test
    public void stateAndLogsRoutes() {
        assertEquals("state", WebServer.route("GET", "/state"));
        assertEquals("logsPage", WebServer.route("GET", "/logs"));
        assertEquals("logJson", WebServer.route("GET", "/log.json"));
        assertEquals("logFile", WebServer.route("GET", "/log/file"));
    }

    @Test
    public void fileRoutes() {
        assertEquals("fileList", WebServer.route("GET", "/files"));
        assertEquals("fileGet", WebServer.route("GET", "/files/photo.jpg"));
        assertEquals("fileGet", WebServer.route("GET", "/files/some%20name.mp4"));
        assertEquals("fileDelete", WebServer.route("DELETE", "/files/photo.jpg/delete"));
        assertEquals("fileDelete", WebServer.route("POST", "/files/photo.jpg/delete"));
    }

    @Test
    public void crashRoutes() {
        assertEquals("crashesPage", WebServer.route("GET", "/crashes"));
        assertEquals("crashJson", WebServer.route("GET", "/crash.json"));
        assertEquals("crashGet", WebServer.route("GET", "/crash/crash-1-a-java-9.txt"));
        assertEquals("crashGet", WebServer.route("GET", "/crash/crash-1-a-java-9.txt%20"));
        assertEquals("crashDelete", WebServer.route("DELETE", "/crash/crash-1-a-java-9.txt/delete"));
        assertEquals("crashDelete", WebServer.route("POST", "/crash/crash-1-a-java-9.txt/delete"));
        // The exact matches must win over the /crash/ prefix.
        assertEquals("crashJson", WebServer.route("GET", "/crash.json"));
        assertEquals("unknown", WebServer.route("GET", "/crash"));
        assertEquals("unknown", WebServer.route("GET", "/crashes/extra"));
    }

    @Test
    public void uploadControlHealthRoutes() {
        assertEquals("upload", WebServer.route("POST", "/upload"));
        assertEquals("control", WebServer.route("GET", "/control"));
        assertEquals("control", WebServer.route("POST", "/control"));
        assertEquals("health", WebServer.route("GET", "/health"));
    }

    @Test
    public void configRoutes() {
        assertEquals("config", WebServer.route("GET", "/config"));
        assertEquals("config", WebServer.route("POST", "/config"));
        assertEquals("configDownload", WebServer.route("GET", "/config/download"));
        assertEquals("configImport", WebServer.route("POST", "/config/import"));
    }

    @Test
    public void updateRoute() {
        // route() classifies by path only; the POST-only gate lives in dispatch().
        assertEquals("update", WebServer.route("POST", "/update"));
        assertEquals("update", WebServer.route("GET", "/update"));
    }

    @Test
    public void unknownRoutes() {
        // route() classifies by path only; method enforcement happens in dispatch().
        assertEquals("unknown", WebServer.route("GET", "/nope"));
        assertEquals("unknown", WebServer.route("GET", null));
        assertEquals("unknown", WebServer.route("GET", "/filez"));
        assertEquals("state", WebServer.route("PATCH", "/state"));
    }

    @Test
    public void adminActionsArePostOnly() {
        assertTrue(WebServer.controlAllows("POST", "keyevent"));
        assertFalse(WebServer.controlAllows("GET", "keyevent"));
        // Forcing a watchdog rung can reboot the device, so it may not be GET-reachable either.
        assertTrue(WebServer.controlAllows("POST", "watchdog"));
        assertFalse(WebServer.controlAllows("GET", "watchdog"));
        // Every other action keeps its existing GET/POST behavior.
        assertTrue(WebServer.controlAllows("GET", "next"));
        assertTrue(WebServer.controlAllows("POST", "next"));
        assertTrue(WebServer.controlAllows("GET", "check-updates"));
        assertFalse(WebServer.controlAllows("GET", null));
        assertFalse(WebServer.controlAllows("POST", null));
    }

    @Test
    public void configTokenOk() {
        assertTrue(WebServer.configTokenOk("abc123", "abc123"));
        assertFalse(WebServer.configTokenOk("abc124", "abc123"));
        assertFalse(WebServer.configTokenOk("", "abc123"));
        assertFalse(WebServer.configTokenOk(null, "abc123"));
        // Fail closed: an empty expected token (settings unavailable) rejects everything.
        assertFalse(WebServer.configTokenOk("abc123", ""));
        assertFalse(WebServer.configTokenOk("abc123", null));
    }

    @Test
    public void configWriteAllowsSameOriginAndNonBrowserClients() {
        // No provenance header at all (curl, tools): token-gated, so allowed here.
        assertTrue(WebServer.configWriteAllowed(new HashMap<String, String>(), 8080));

        // Same-origin browser POST: Origin matches Host + port.
        Map<String, String> ok = headers("host", "192.168.1.50:8080",
                "origin", "http://192.168.1.50:8080");
        assertTrue(WebServer.configWriteAllowed(ok, 8080));

        // Same host via an explicit-80 Host header, default-port origin, server on 80.
        Map<String, String> port80 = headers("host", "192.168.1.50",
                "origin", "http://192.168.1.50");
        assertTrue(WebServer.configWriteAllowed(port80, 80));

        // No Origin (older browser): a matching Referer is enough.
        Map<String, String> referer = headers("host", "192.168.1.50:8080",
                "referer", "http://192.168.1.50:8080/?page=cfg");
        assertTrue(WebServer.configWriteAllowed(referer, 8080));

        // Host header is case-insensitive for the host part.
        Map<String, String> hostCase = headers("host", "Frame.Local:8080",
                "origin", "http://frame.local:8080");
        assertTrue(WebServer.configWriteAllowed(hostCase, 8080));
    }

    @Test
    public void configWriteRejectsCrossOrigin() {
        // Attacker's page on another host.
        assertFalse(WebServer.configWriteAllowed(headers("host", "192.168.1.50:8080",
                "origin", "http://evil.example:8080"), 8080));
        // Attacker's page on the same port, different host.
        assertFalse(WebServer.configWriteAllowed(headers("host", "192.168.1.50:8080",
                "origin", "http://attacker.local:8080"), 8080));
        // Same host, different port.
        assertFalse(WebServer.configWriteAllowed(headers("host", "192.168.1.50:8080",
                "origin", "http://192.168.1.50:80"), 8080));
        // Default-port origin hitting a non-default port.
        assertFalse(WebServer.configWriteAllowed(headers("host", "192.168.1.50:8080",
                "origin", "http://192.168.1.50"), 8080));
        // Cross-origin referer (e.g. a page linking out and auto-submitting a form).
        assertFalse(WebServer.configWriteAllowed(headers("host", "192.168.1.50:8080",
                "referer", "http://evil.example/phish"), 8080));
        // Provenance header present but no Host header to attribute it to.
        assertFalse(WebServer.configWriteAllowed(headers(
                "origin", "http://192.168.1.50:8080"), 8080));
    }

    @Test
    public void contentTypeForTextAndMedia() {
        assertEquals("text/plain; charset=utf-8", WebServer.contentTypeFor("report.txt"));
        assertEquals("text/plain; charset=utf-8", WebServer.contentTypeFor("a.jpg.incompat.txt"));
        assertEquals("image/jpeg", WebServer.contentTypeFor("photo.jpg"));
        assertEquals("video/mp4", WebServer.contentTypeFor("clip.mp4"));
        assertEquals("application/octet-stream", WebServer.contentTypeFor("data.bin"));
    }

    /**
     * The config editor's {@code collectCfg()} rebuilds {@code backends} from what the rows render,
     * so a field that renders but is not collected is silently deleted on the next save — which for
     * a web row means losing its page URL. This guards the web branch through both halves.
     */
    @Test
    public void configEditorJsRoundTripsWebBackendRows() {
        String js = WebServer.cfgSectionJs();
        int renderAt = js.indexOf("function renderCfgBackends");
        int collectAt = js.indexOf("function collectCfg");
        assertTrue("render must branch on web rows",
                js.indexOf("b.type==='immich'||b.type==='web'", renderAt) > renderAt);
        assertTrue(renderAt >= 0 && collectAt > renderAt);

        String collect = js.substring(collectAt);
        assertTrue("collect must re-read the Enabled flag", collect.contains(".cfgEnabled"));
        assertTrue("collect must re-read the page URL", collect.contains(".cfgHost"));
        assertTrue("collect must keep web rows", collect.contains("b.type==='web'"));
    }

    @Test
    public void configEditorJsAddsWebBackendsWithGeneratedIds() {
        String js = WebServer.cfgSectionJs();
        assertTrue("a new web entry must carry an id, the host field and an Enabled flag",
                js.contains("{type:'web',id:nextBackendId('web'),host:'',enabled:true}"));
    }

    @Test
    public void deviceNameRoute() {
        // As everywhere else, route() classifies by path; the POST-only + token + same-origin
        // enforcement happens in dispatch().
        assertEquals("deviceName", WebServer.route("POST", "/devicename"));
        assertEquals("deviceName", WebServer.route("GET", "/devicename"));
    }

    /** Unnamed must be byte-identical to the header this server produced before the field existed. */
    @Test
    public void unsetDeviceNameLeavesTheHeaderAlone() {
        assertEquals("<!doctype html><meta charset=\"utf-8\"><title>EO2</title>",
                WebServer.indexTitleHtml("EO2", ""));
        assertEquals("<h1>EO2 frame @ 192.168.1.87 (port 8080)</h1>",
                WebServer.indexHeadingHtml("EO2", "", "192.168.1.87", 8080));
        // A controller that cannot reach its prefs reads as unnamed, never as the literal "null".
        assertEquals(WebServer.indexTitleHtml("EO2", ""), WebServer.indexTitleHtml("EO2", null));
        assertEquals(WebServer.indexHeadingHtml("EO2", "", "ip", 80),
                WebServer.indexHeadingHtml("EO2", "   ", "ip", 80));
    }

    @Test
    public void setDeviceNameLeadsBothHeaderForms() {
        assertEquals("<!doctype html><meta charset=\"utf-8\"><title>Kitchen · EO2</title>",
                WebServer.indexTitleHtml("EO2", "Kitchen"));
        assertEquals("<h1>Kitchen · EO2 frame @ 192.168.1.87 (port 8080)</h1>",
                WebServer.indexHeadingHtml("EO2", "Kitchen", "192.168.1.87", 8080));
    }

    /** The name is operator text rendered into a page: it may not open a tag or end an attribute. */
    @Test
    public void deviceNameCannotBreakOutOfTheHeader() {
        String hostile = "</h1><script>alert(1)</script>";
        String quoted = "a\"onload=\"alert(1)";
        for (String name : new String[]{hostile, quoted}) {
            String title = WebServer.indexTitleHtml("EO2", name);
            String heading = WebServer.indexHeadingHtml("EO2", name, "ip", 80);
            assertFalse("no script tag may survive", title.contains("<script")
                    || heading.contains("<script"));
            assertFalse("no tag may be closed early", heading.contains("</h1><"));
            assertFalse("quotes must not terminate the attribute",
                    title.substring(title.indexOf("<title>") + 7, title.indexOf("</title>")).contains("\""));
        }
        assertTrue(WebServer.indexHeadingHtml("EO2", hostile, "ip", 80).contains("&lt;script&gt;"));
    }

    /** The write must be a POST to the gated endpoint with the same remembered token as /config. */
    @Test
    public void deviceNameJsUsesTheGatedEndpointAndSharedToken() {
        String js = WebServer.deviceNameSectionJs();
        assertTrue(js.contains("'/devicename?token='"));
        assertTrue(js.contains("method:'POST'"));
        assertTrue("must fall back to the remembered token, not demand re-typing",
                js.contains("eo1.cfg.token"));
        assertTrue("the name travels in the body, not the query string",
                js.contains("body:v"));
    }

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
