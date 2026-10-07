package com.aphex3k.media.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

/**
 * Migrated from MediaManagerLocalAssetsTest: the local-upload scan now lives in
 * LocalMediaBackend. No Android classes are touched, so this runs on the JVM.
 */
public class LocalMediaBackendTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File uploadDir;
    private LocalMediaBackend backend;

    @Before
    public void setUp() throws Exception {
        uploadDir = temp.newFolder("uploaded");
        backend = new LocalMediaBackend("local", uploadDir);
    }

    @Test
    public void localAssetIdIsDeterministic() {
        String id = LocalMediaBackend.localAssetIdFor("clip.mp4");
        assertEquals(id, LocalMediaBackend.localAssetIdFor("clip.mp4"));
        assertFalse(id.equals(LocalMediaBackend.localAssetIdFor("other.mp4")));
    }

    @Test
    public void fetchCatalogListsOnlyRecognizedMedia() throws Exception {
        writeFile("one.jpg");
        writeFile("two.mp4");
        writeFile("notes.txt"); // not media, must be ignored

        List<MediaAsset> assets = backend.fetchCatalog();
        assertEquals(2, assets.size());

        MediaAsset jpg = null;
        MediaAsset mp4 = null;
        for (MediaAsset a : assets) {
            if ("one.jpg".equals(a.originalFileName)) {
                jpg = a;
            }
            if ("two.mp4".equals(a.originalFileName)) {
                mp4 = a;
            }
        }
        assertNotNull(jpg);
        assertNotNull(mp4);
        assertEquals(MediaType.IMAGE, jpg.type);
        assertEquals(MediaType.VIDEO, mp4.type);
        assertEquals("local", jpg.backendId);
        assertEquals(LocalMediaBackend.localAssetIdFor("one.jpg"), jpg.id);
        assertEquals(new File(uploadDir, "one.jpg").getAbsolutePath(), jpg.localPath);
        assertEquals(Long.valueOf(4), jpg.sizeBytes); // writeFile writes "data"
        assertEquals(-1, jpg.durationMs);
        assertNull(jpg.checksum);
    }

    @Test
    public void fetchCatalogNonEmptyForSingleMediaFile() throws Exception {
        writeFile("only.jpg");
        // A non-empty catalog means the rotation pipeline will not throw NoMediaFoundException.
        assertTrue(backend.fetchCatalog().size() > 0);
    }

    @Test
    public void fetchCatalogEmptyWhenDirectoryEmpty() {
        assertEquals(0, backend.fetchCatalog().size());
    }

    @Test
    public void subdirectoriesAreIgnored() throws Exception {
        new File(uploadDir, "inbox").mkdirs();
        assertEquals(0, backend.fetchCatalog().size());
    }

    @Test
    public void resolveOriginalReturnsLocalFile() throws Exception {
        File f = writeFile("clip.mp4");
        MediaAsset a = backend.fetchCatalog().get(0);
        MediaSource src = backend.resolveOriginal(a);
        assertNotNull(src);
        assertTrue(src.isLocal());
        assertEquals(f, src.localFile);
        assertNull(src.opener);
    }

    @Test
    public void resolveOriginalNullWhenFileDeleted() throws Exception {
        File f = writeFile("clip.mp4");
        MediaAsset a = backend.fetchCatalog().get(0);
        assertTrue(f.delete());
        assertNull(backend.resolveOriginal(a));
    }

    @Test
    public void metadataAndOptionalCapabilities() {
        assertEquals("local", backend.getId());
        assertEquals("local", backend.getType());
        assertNull(backend.describeVersion());
        MediaAsset a = new MediaAsset("x", "local", MediaType.IMAGE, -1, null, "x.jpg",
                null, 4L, new File(uploadDir, "x.jpg").getAbsolutePath(), 0, 0);
        assertNull(backend.resolveThumbnailFallback(a));
    }

    @Test
    public void mediaTypeForExtensionWithAndWithoutDot() {
        assertEquals(MediaType.VIDEO, LocalMediaBackend.mediaTypeForExtension(".mp4"));
        assertEquals(MediaType.VIDEO, LocalMediaBackend.mediaTypeForExtension("mp4"));
        assertEquals(MediaType.IMAGE, LocalMediaBackend.mediaTypeForExtension("jpeg"));
        assertNull(LocalMediaBackend.mediaTypeForExtension("txt"));
    }

    @Test
    public void fetchCatalogSkipsIncompatibleUploads() throws Exception {
        writeFile("good.jpg");
        writeFile("samsung.heic");
        writeFile("still.avif");
        writeFile("clip.h265");
        writeFile("movie.hevc");
        writeFile("lossless.av1");

        List<MediaAsset> assets = backend.fetchCatalog();
        assertEquals(1, assets.size());
        assertEquals("good.jpg", assets.get(0).originalFileName);
    }

    @Test
    public void fetchCatalogSkipsHeifContentNamedJpg() throws Exception {
        // ftyp box with the "heic" major brand: undecodable despite the .jpg name.
        byte[] heif = ftypBox("heic");
        java.nio.file.Files.write(new File(uploadDir, "disguised.jpg").toPath(), heif);
        writeFile("real.jpg");

        List<MediaAsset> assets = backend.fetchCatalog();
        assertEquals(1, assets.size());
        assertEquals("real.jpg", assets.get(0).originalFileName);
    }

    @Test
    public void fetchCatalogWritesSidecarForIncompatibleUpload() throws Exception {
        writeFile("samsung.heic");

        List<MediaAsset> assets = backend.fetchCatalog();
        assertEquals(0, assets.size());

        File sidecar = new File(uploadDir, "samsung.heic.incompat.txt");
        assertTrue("sidecar not written", sidecar.isFile());
        String content = new String(java.nio.file.Files.readAllBytes(sidecar.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("unsupported image format .heic"));
        assertTrue(content.contains("How to fix"));
        assertTrue(content.contains("samsung.heic.incompat.txt"));
        assertTrue(content.contains("\"check\":\"extension\""));
        assertTrue(content.contains("\"mediaType\":\"image\""));

        // The sidecar itself is not media and must never enter the catalog.
        List<MediaAsset> second = backend.fetchCatalog();
        assertEquals(0, second.size());
    }

    @Test
    public void existingSidecarExcludesAssetEvenWhenChecksPass() throws Exception {
        writeFile("ok.jpg");
        new File(uploadDir, "ok.jpg.incompat.txt").createNewFile();

        List<MediaAsset> assets = backend.fetchCatalog();
        assertEquals(0, assets.size());
    }

    @Test
    public void sidecarIsWrittenOnceAndNeverRewritten() throws Exception {
        writeFile("clip.h265");

        assertEquals(0, backend.fetchCatalog().size());
        File sidecar = new File(uploadDir, "clip.h265.incompat.txt");
        byte[] first = java.nio.file.Files.readAllBytes(sidecar.toPath());

        // A later scan must not overwrite or truncate the report (first failure is
        // authoritative; the user may even edit it).
        assertEquals(0, backend.fetchCatalog().size());
        byte[] second = java.nio.file.Files.readAllBytes(sidecar.toPath());
        assertEquals(first.length, second.length);
    }

    @Test
    public void fetchCatalogWritesContentCheckSidecarForHeifNamedJpg() throws Exception {
        byte[] heif = ftypBox("heic");
        java.nio.file.Files.write(new File(uploadDir, "disguised.jpg").toPath(), heif);

        List<MediaAsset> assets = backend.fetchCatalog();
        assertEquals(0, assets.size());

        File sidecar = new File(uploadDir, "disguised.jpg.incompat.txt");
        assertTrue(sidecar.isFile());
        String content = new String(java.nio.file.Files.readAllBytes(sidecar.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("unsupported image container heic"));
        assertTrue(content.contains("\"check\":\"content\""));
    }

    /** ISO 14496-12 {@code ftyp} box: size + type + major brand + minor. */
    private static byte[] ftypBox(String major) {
        int size = 16;
        byte[] b = new byte[size];
        b[0] = (byte) (size >> 8);
        b[1] = (byte) size;
        put(b, 4, "ftyp");
        put(b, 8, major);
        return b;
    }

    private static void put(byte[] b, int off, String fourCc) {
        System.arraycopy(fourCc.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, b, off, 4);
    }

    private File writeFile(String name) throws Exception {
        File f = new File(uploadDir, name);
        FileWriter w = new FileWriter(f);
        try {
            w.write("data");
        } finally {
            w.close();
        }
        return f;
    }
}
