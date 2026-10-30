package com.aphex3k.eo1;

import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaSource;
import com.aphex3k.media.MediaType;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The display path's half of the "a byte-identical fallback must never exist on the device" rule.
 *
 * <p>The acquisition path refuses a degraded source that is the original under a second name, and the
 * display path calls {@code resolveThumbnailFallback} with a record rebuilt from the asset key — whose
 * {@code sizeBytes} was always null, so every one of those guards was structurally incapable of
 * firing there. On an Immich with nothing transcoded that costs a second full download of a file the
 * frame already holds, on the path that runs precisely because the first one did not display.
 *
 * <p>These two functions are the whole decision; the fetch it prevents happens on a worker thread
 * behind a live Activity, which is not reachable from a JVM test.
 */
public class MediaManagerFallbackSourceTest {

    private static final MediaSource.Opener NOTHING = new MediaSource.Opener() {
        @Override
        public InputStream open() {
            return new ByteArrayInputStream(new byte[0]);
        }
    };

    private static MediaAsset video(String id, long sizeBytes) {
        return new MediaAsset(id, "immich-1", MediaType.VIDEO, 16245, "abc", "IMG_5353.MOV",
                "/remote/original/IMG_5353.MOV", sizeBytes, null, 1920, 1080);
    }

    private static Set<String> known(String... keys) {
        Set<String> s = new HashSet<String>();
        for (int i = 0; i < keys.length; i++) {
            s.add(keys[i]);
        }
        return s;
    }

    // ---------------------------------------------------------------- the record

    /**
     * The reason this was invisible: a rebuilt record has no size, so a size comparison can never be
     * true. {@code assetFromKey} on its own must stay exactly as bare as it is — it is what the tagging
     * path uses.
     */
    @Test
    public void aRecordBuiltFromAKeyAloneCarriesNoSize() {
        MediaAsset bare = MediaManager.fallbackAssetFor("immich-1:37454989", true, null);
        assertEquals(MediaType.VIDEO, bare.type);
        assertNull("and so every byte-identity guard against it is dark", bare.sizeBytes);
    }

    @Test
    public void theAssetInHandSuppliesWhatTheKeyCannot() {
        MediaAsset held = video("37454989", 31617645L);
        MediaAsset enriched = MediaManager.fallbackAssetFor(held.key(), true, held);
        assertEquals("the size the guards compare against", Long.valueOf(31617645L), enriched.sizeBytes);
        assertEquals("and the names the cache file is derived from", "IMG_5353.MOV",
                enriched.originalFileName);
        assertEquals(held.originalPath, enriched.originalPath);
        assertEquals(held.id, enriched.id);
        assertEquals(held.backendId, enriched.backendId);
    }

    /** An unrelated asset in hand is not this asset's metadata. */
    @Test
    public void aDifferentAssetIsNotBorrowed() {
        MediaAsset other = video("99999", 31617645L);
        MediaAsset mine = MediaManager.fallbackAssetFor("immich-1:37454989", true, other);
        assertNull("the size belongs to the other asset", mine.sizeBytes);
    }

    /**
     * The regression this whole design review exists to prevent. {@code showRenderSilentStill} calls
     * the display fallback with {@code isVideo=false} for a clip that is a VIDEO: that flag is what
     * selects {@code /thumbnail?size=preview} over {@code /video/playback}, and what gives the artifact
     * its {@code _fb.jpg} name. Enriching the record from {@code currentAsset} while letting its VIDEO
     * type through would make a silent clip re-fetch and re-decode the exact stream that produced no
     * pixels, under a name eviction and deletion no longer recognise.
     */
    @Test
    public void enrichmentNeverOverridesTheCallersChoiceOfStream() {
        MediaAsset held = video("37454989", 31617645L);
        MediaAsset still = MediaManager.fallbackAssetFor(held.key(), false, held);
        assertEquals("a video shown as its still preview stays an image-shaped request",
                MediaType.IMAGE, still.type);
        assertEquals("while still carrying the size that makes the duplicate guard work",
                Long.valueOf(31617645L), still.sizeBytes);
    }

    // ---------------------------------------------------------------- the verdict

    @Test
    public void aPlaybackStreamTheSizeOfTheOriginalIsNotASecondSource() {
        MediaAsset asset = video("a1", 31617645L);
        MediaSource same = MediaSource.remoteVideoPlayback(NOTHING, Long.valueOf(31617645L));
        assertTrue("no transcode exists: fetching this is fetching the file again",
                MediaManager.hasNoDistinctFallback(asset.key(), asset, same, true, known()));
    }

    @Test
    public void aGenuinelySmallerTranscodeIsStillFetched() {
        MediaAsset asset = video("a2", 31617645L);
        MediaSource smaller = MediaSource.remoteVideoPlayback(NOTHING, Long.valueOf(8000000L));
        assertFalse(MediaManager.hasNoDistinctFallback(asset.key(), asset, smaller, true, known()));
    }

    @Test
    public void anEndpointThatWouldNotSayItsSizeIsFetched() {
        // The conservative direction: a probe that cannot answer is read as "worth fetching", never as
        // "nothing to degrade to" — the second one silently skips a fallback that would have worked.
        MediaAsset asset = video("a3", 31617645L);
        MediaSource unsized = MediaSource.remoteVideoPlayback(NOTHING, null);
        assertFalse(MediaManager.hasNoDistinctFallback(asset.key(), asset, unsized, true, known()));
    }

    @Test
    public void aPreviewThumbnailIsNeverTreatedAsADuplicate() {
        MediaAsset asset = video("a4", 31617645L);
        MediaSource preview = MediaSource.remoteThumbnail(NOTHING);
        assertFalse(MediaManager.hasNoDistinctFallback(asset.key(), asset, preview, true, known()));
    }

    /**
     * The mark is a stand-in for a probe that could not answer — never an override of one that did. An
     * operator who generates transcodes yesterday gets the new encode on the next failure, not after a
     * power-cycle: metadata is the one thing in this pipeline allowed to be stale, and a verdict the
     * frame cannot undo would be a lie about the user's library.
     */
    @Test
    public void aFreshDistinctSizeOutranksTheRememberedMark() {
        MediaAsset asset = video("a5b", 31617645L);
        MediaSource transcoded = MediaSource.remoteVideoPlayback(NOTHING, Long.valueOf(8000000L));
        assertFalse("the transcode exists now, so it is the fallback",
                MediaManager.hasNoDistinctFallback(asset.key(), asset, transcoded, true,
                        known(asset.key())));
    }

    @Test
    public void aFreshIdenticalSizeConfirmsTheMarkWithoutNeedingIt() {
        MediaAsset asset = video("a5c", 31617645L);
        MediaSource same = MediaSource.remoteVideoPlayback(NOTHING, Long.valueOf(31617645L));
        assertTrue(MediaManager.hasNoDistinctFallback(asset.key(), asset, same, true, known()));
    }

    /** A mark taken once, so the cycle after it does not re-probe the endpoint for the same answer. */
    @Test
    public void aRememberedVerdictIsHonoredWhenTheProbeCannotAnswer() {
        MediaAsset asset = video("a5", 31617645L);
        MediaSource probeCouldNotAnswer = MediaSource.remoteVideoPlayback(NOTHING, null);
        assertTrue(MediaManager.hasNoDistinctFallback(asset.key(), asset,
                probeCouldNotAnswer, true, known(asset.key())));
    }

    /**
     * The other half of the same mark: a render-silent clip is shown as a still, and honouring
     * "this clip has no distinct video fallback" on an image-shaped call would leave it showing
     * nothing at all.
     */
    @Test
    public void aRememberedVideoVerdictDoesNotSuppressAStillPreview() {
        MediaAsset asset = video("a6", 31617645L);
        MediaSource preview = MediaSource.remoteThumbnail(NOTHING);
        assertFalse("a still preview is always a different artifact",
                MediaManager.hasNoDistinctFallback(asset.key(), asset, preview, false,
                        known(asset.key())));
    }

    @Test
    public void noSourceIsNotTheSameAsADuplicateSource() {
        // A backend with no fallback at all belongs to the branch that says so, not to this one —
        // otherwise the log claims a transcode comparison that never happened.
        MediaAsset asset = video("a7", 31617645L);
        assertFalse(MediaManager.hasNoDistinctFallback(asset.key(), asset, null, true,
                known(asset.key())));
    }
}
