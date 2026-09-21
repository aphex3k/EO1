package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

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
    public void uploadControlHealthRoutes() {
        assertEquals("upload", WebServer.route("POST", "/upload"));
        assertEquals("control", WebServer.route("GET", "/control"));
        assertEquals("control", WebServer.route("POST", "/control"));
        assertEquals("health", WebServer.route("GET", "/health"));
    }

    @Test
    public void unknownRoutes() {
        // route() classifies by path only; method enforcement happens in dispatch().
        assertEquals("unknown", WebServer.route("GET", "/nope"));
        assertEquals("unknown", WebServer.route("GET", null));
        assertEquals("unknown", WebServer.route("GET", "/filez"));
        assertEquals("state", WebServer.route("PATCH", "/state"));
    }
}
