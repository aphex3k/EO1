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
                null, 4L, new File(uploadDir, "x.jpg").getAbsolutePath());
        assertNull(backend.resolveThumbnailFallback(a));
    }

    @Test
    public void mediaTypeForExtensionWithAndWithoutDot() {
        assertEquals(MediaType.VIDEO, LocalMediaBackend.mediaTypeForExtension(".mp4"));
        assertEquals(MediaType.VIDEO, LocalMediaBackend.mediaTypeForExtension("mp4"));
        assertEquals(MediaType.IMAGE, LocalMediaBackend.mediaTypeForExtension("jpeg"));
        assertNull(LocalMediaBackend.mediaTypeForExtension("txt"));
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
