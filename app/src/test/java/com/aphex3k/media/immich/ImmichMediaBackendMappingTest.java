package com.aphex3k.media.immich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.immichApi.ImmichApiAssetResponse;
import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaType;

import com.google.gson.Gson;
import org.junit.Test;

/**
 * Pure-mapping tests: no HTTP, no backend construction. DTOs are built via Gson
 * because ImmichApiAssetResponse has no public setters.
 */
public class ImmichMediaBackendMappingTest {

    private static final Gson gson = new Gson();

    private static ImmichApiAssetResponse asset(String json) {
        return gson.fromJson(json, ImmichApiAssetResponse.class);
    }

    @Test
    public void mapsAllConsumedFields() {
        ImmichApiAssetResponse r = asset("{\"id\":\"abc123\",\"type\":\"IMAGE\","
                + "\"duration\":12345,\"checksum\":\"Zm9v\",\"originalFileName\":\"a.jpg\","
                + "\"originalPath\":\"/photos/a.jpg\",\"isTrashed\":false,"
                + "\"exifInfo\":{\"fileSizeInByte\":2048}}");
        MediaAsset a = ImmichMediaBackend.toMediaAsset("immich-1", r);
        assertEquals("abc123", a.id);
        assertEquals("immich-1", a.backendId);
        assertEquals(MediaType.IMAGE, a.type);
        assertEquals(12345, a.durationMs);
        assertEquals("Zm9v", a.checksum);
        assertEquals("a.jpg", a.originalFileName);
        assertEquals("/photos/a.jpg", a.originalPath);
        assertEquals(Long.valueOf(2048), a.sizeBytes);
        assertNull(a.localPath);
        assertEquals("immich-1:abc123", a.key());
    }

    @Test
    public void videoTypeMapsToVideo() {
        ImmichApiAssetResponse r = asset("{\"id\":\"v1\",\"type\":\"VIDEO\",\"exifInfo\":{\"fileSizeInByte\":1}}");
        assertEquals(MediaType.VIDEO, ImmichMediaBackend.toMediaAsset("immich-2", r).type);
    }

    @Test
    public void missingDurationAndSizeDefaults() {
        ImmichApiAssetResponse r = asset("{\"id\":\"x\",\"type\":\"IMAGE\",\"exifInfo\":{\"fileSizeInByte\":10}}");
        MediaAsset a = ImmichMediaBackend.toMediaAsset("immich-1", r);
        assertEquals(-1, a.durationMs);
        assertEquals(Long.valueOf(10), a.sizeBytes);
        assertNull(a.checksum);
        assertNull(a.localPath);
    }

    @Test
    public void compatibleRequiresExifInfo() {
        assertFalse(ImmichMediaBackend.isCompatibleAsset(
                asset("{\"id\":\"a\",\"type\":\"IMAGE\"}")));
    }

    @Test
    public void compatibleRejectsOversized() {
        assertFalse(ImmichMediaBackend.isCompatibleAsset(
                asset("{\"id\":\"a\",\"type\":\"IMAGE\",\"exifInfo\":{\"fileSizeInByte\":1073741825}}")));
    }

    @Test
    public void compatibleAcceptsExactlyAtLimit() {
        assertTrue(ImmichMediaBackend.isCompatibleAsset(
                asset("{\"id\":\"a\",\"type\":\"IMAGE\",\"exifInfo\":{\"fileSizeInByte\":1073741824}}")));
    }

    @Test
    public void compatibleRejectsTrashed() {
        assertFalse(ImmichMediaBackend.isCompatibleAsset(
                asset("{\"id\":\"a\",\"type\":\"IMAGE\",\"isTrashed\":true,\"exifInfo\":{\"fileSizeInByte\":10}}")));
    }

    @Test
    public void compatibleRejectsNonMediaTypes() {
        assertFalse(ImmichMediaBackend.isCompatibleAsset(
                asset("{\"id\":\"a\",\"type\":\"AUDIO\",\"exifInfo\":{\"fileSizeInByte\":10}}")));
        assertFalse(ImmichMediaBackend.isCompatibleAsset(
                asset("{\"id\":\"a\",\"type\":\"OTHER\",\"exifInfo\":{\"fileSizeInByte\":10}}")));
        assertFalse(ImmichMediaBackend.isCompatibleAsset(asset("{\"id\":\"a\"}")));
    }

    @Test
    public void compatibleAcceptsMediaTypes() {
        assertTrue(ImmichMediaBackend.isCompatibleAsset(
                asset("{\"id\":\"a\",\"type\":\"IMAGE\",\"exifInfo\":{\"fileSizeInByte\":10}}")));
        assertTrue(ImmichMediaBackend.isCompatibleAsset(
                asset("{\"id\":\"a\",\"type\":\"VIDEO\",\"exifInfo\":{\"fileSizeInByte\":10}}")));
    }

    @Test
    public void nullFileSizeIsIncompatible() {
        assertFalse(ImmichMediaBackend.isCompatibleAsset(
                asset("{\"id\":\"a\",\"type\":\"IMAGE\",\"exifInfo\":{}}")));
    }
}
