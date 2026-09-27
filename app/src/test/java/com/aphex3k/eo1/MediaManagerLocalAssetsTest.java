package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

public class MediaManagerLocalAssetsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private MediaManager mediaManager;
    private File uploadDir;

    @Before
    public void setUp() throws Exception {
        mediaManager = new MediaManager(
                mock(MediaManagerListener.class),
                mock(SettingsManager.class),
                mock(ApiServiceGenerator.ProgressListener.class));
        uploadDir = temp.newFolder("uploaded");
    }

    @Test
    public void localAssetIdIsDeterministic() throws Exception {
        String id = MediaManager.localAssetIdFor("clip.mp4");
        assertEquals(id, MediaManager.localAssetIdFor("clip.mp4"));
        assertFalse(id.equals(MediaManager.localAssetIdFor("other.mp4")));
    }

    @Test
    public void addLocalUploadedAssetsPopulatesRotation() throws Exception {
        writeFile("one.jpg");
        writeFile("two.mp4");
        writeFile("notes.txt"); // not media, must be ignored

        mediaManager.addLocalUploadedAssets(uploadDir);

        assertEquals(2, mediaManager.rotationListSize());
        assertTrue(mediaManager.isLocalAssetId(MediaManager.localAssetIdFor("one.jpg")));
        assertTrue(mediaManager.isLocalAssetId(MediaManager.localAssetIdFor("two.mp4")));
        assertFalse(mediaManager.isLocalAssetId(MediaManager.localAssetIdFor("notes.txt")));
    }

    @Test
    public void rebuildWithOnlyLocalFilesIsNonEmpty() throws Exception {
        writeFile("only.jpg");
        mediaManager.addLocalUploadedAssets(uploadDir);
        // A non-empty list means the showNextImageLocked path will not throw NoMediaFoundException.
        assertTrue(mediaManager.rotationListSize() > 0);
    }

    @Test
    public void clearWhenDirectoryEmpty() {
        mediaManager.addLocalUploadedAssets(uploadDir);
        assertEquals(0, mediaManager.rotationListSize());
    }

    private void writeFile(String name) throws Exception {
        File f = new File(uploadDir, name);
        FileWriter w = new FileWriter(f);
        try {
            w.write("data");
        } finally {
            w.close();
        }
    }
}
