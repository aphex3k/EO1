package com.aphex3k.eo1;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;

import com.aphex3k.immichApi.ImmichApiAssetResponse;
import com.aphex3k.immichApi.ImmichApiGetAlbumResponse;
import com.aphex3k.immichApi.ImmichApiLogin;
import com.aphex3k.immichApi.ImmichApiLoginResponse;
import com.aphex3k.immichApi.ImmichApiMetadataSearchBody;
import com.aphex3k.immichApi.ImmichApiMetadataSearchResponse;
import com.aphex3k.immichApi.ImmichApiService;
import com.aphex3k.immichApi.ImmichApiTag;
import com.aphex3k.immichApi.ImmichApiTagAssetBody;
import com.aphex3k.immichApi.ImmichApiTagAssetResponse;
import com.aphex3k.immichApi.ImmichApiTagResponse;
import com.aphex3k.immichApi.ImmichExifInfo;
import com.aphex3k.immichApi.ImmichSizeFormat;
import com.aphex3k.immichApi.ImmichType;

import org.jetbrains.annotations.NotNull;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class MediaManager implements MediaManagerInterface {

    private static final String TAG = "EO1";

    private static final String INCOMPATIBLE_TAG_NAME = "EO1_INCOMPATIBLE";
    private static final long MAX_ASSET_BYTES = 1073741824L;
    private static final int SEARCH_PAGE_SIZE = 1000;
    /** Recognised video extensions for uploaded files (lowercase, no dot). */
    private static final Set<String> VIDEO_EXTENSIONS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("mp4", "mov", "mkv", "avi", "ts", "m4v", "webm", "3gp", "mpg", "mpeg", "hevc", "h265")));
    /** Recognised image extensions for uploaded files (lowercase, no dot). */
    private static final Set<String> IMAGE_EXTENSIONS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng")));
    private static final ReentrantLock downloadMutex = new ReentrantLock();
    /** Prevents overlapping showNextImage worker threads (timer + retry + MQTT). */
    private final AtomicBoolean showNextInFlight = new AtomicBoolean(false);
    private final WeakReference<SettingsManager> settingsManager;
    private final WeakReference<MediaManagerListener> listener;
    private final WeakReference<ApiServiceGenerator.ProgressListener> downloadProgressListener;
    private final ArrayList<ImmichApiAssetResponse> immichAssets = new ArrayList<>();
    /**
     * Per-asset video duration (ms) from Immich metadata. Populated as assets are displayed so
     * the TsPlayer path can avoid a system MediaMetadataRetriever (which grabs the Amlogic
     * video-buffer node and starves the in-process codec). Absent when unknown.
     */
    private final ConcurrentHashMap<String, Integer> videoDurationMsByAsset =
            new ConcurrentHashMap<String, Integer>();
    /**
     * Maps synthetic local-asset ids (deterministic UUIDs) to the uploaded file name. Rebuilt
     * from the persistent {@code filesDir/uploaded} directory at each rotation rebuild and
     * published wholesale (volatile swap) so UI-thread readers never see an empty/partial map.
     */
    private volatile Map<String, String> localAssetIdToFilename = new HashMap<>();
    private final MediaCacheManager mediaCacheManager;
    private volatile String currentPlaybackPath;
    /** Cache dir of the rotation currently in progress; guards {@link #removeFromCache}'s location check. */
    private volatile File mediaCacheDir;

    public MediaManager(MediaManagerListener listener, SettingsManager settingsManager,
                        ApiServiceGenerator.ProgressListener downloadProgressListener) {
        this(listener, settingsManager, downloadProgressListener, new MediaCacheManager());
    }

    public MediaManager(MediaManagerListener listener, SettingsManager settingsManager,
                        ApiServiceGenerator.ProgressListener downloadProgressListener,
                        MediaCacheManager mediaCacheManager) {
        this.listener = new WeakReference<>(listener);
        this.settingsManager = new WeakReference<>(settingsManager);
        this.downloadProgressListener = new WeakReference<>(downloadProgressListener);
        this.mediaCacheManager = mediaCacheManager != null ? mediaCacheManager : new MediaCacheManager();
    }

    public void showNextImage(Activity activity) {

        MediaManagerListener mediaManagerListener = this.listener.get();

        if (mediaManagerListener == null) {
            Log.i(TAG, "showNextImage: no listener, abort");
            return;
        }

        if (!showNextInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "showNextImage: already in flight, skip parallel start");
            return;
        }

        new Thread(() -> {
            try {
                showNextImageLocked(activity, mediaManagerListener);
            } finally {
                showNextInFlight.set(false);
            }
        }).start();
    }

    private void showNextImageLocked(Activity activity, MediaManagerListener mediaManagerListener) {
            ImmichApiService apiService = null;
            ImmichApiAssetResponse assetResponse;
            File tempFile = null;

            Log.i(TAG, "showNextImage: start (cachedAssets=" + immichAssets.size() + ")");
            mediaCacheDir = activity.getCacheDir();

            if (immichAssets.isEmpty()) {

                try {
                    apiService = fetchImmichAssets(activity);
                } catch (Exception e) {
                    // Do not stop the rotation on an Immich failure: fall back to local uploads.
                    Log.e(TAG, "showNextImage: Immich fetch failed, falling back to local uploads", e);
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(e));
                    apiService = null;
                }

                addLocalUploadedAssets(activity);

                if (immichAssets.isEmpty()) {
                    Log.e(TAG, "showNextImage: no media found (no Immich assets, no local uploads)");
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(new NoMediaFoundException()));
                    return;
                }

                Log.i(TAG, "showNextImage: loaded " + immichAssets.size() + " assets");
                Collections.shuffle(immichAssets);
            }

            do {
                assetResponse = immichAssets.remove(0);

                try {
                    if (isLocalAsset(assetResponse)) {
                        // Local upload: use the on-disk file directly, no Immich download.
                        tempFile = resolveLocalAssetFile(activity, assetResponse);
                    } else {
                        Long expectedBytes = null;
                        if (assetResponse.getExifInfo() != null) {
                            expectedBytes = assetResponse.getExifInfo().getFileSizeInByte();
                        }
                        tempFile = downloadAsset(
                                assetResponse.getId(),
                                assetResponse.getType(),
                                false,
                                assetResponse.getOriginalFileName(),
                                assetResponse.getOriginalPath(),
                                activity,
                                apiService,
                                assetResponse.getChecksum(),
                                expectedBytes);
                    }
                } catch (Exception e) {
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(e));
                }

            } while (!immichAssets.isEmpty() && (tempFile == null));

            ImmichApiAssetResponse finalAssetResponse = assetResponse;
            File playbackFile = tempFile;

            // No client-side transcoding/conversion: the downloaded original is used as-is.
            // Undecodable images (HEIC, corrupt) and incompatible videos fall back to the
            // Immich preview / playback stream via the display error path
            // (Glide/VideoPlayer onError -> assetFallback -> displayThumbnailAsset).
            final File finalPlaybackFile = playbackFile;

            if (finalPlaybackFile != null) {
                currentPlaybackPath = finalPlaybackFile.getAbsolutePath();
                if (finalAssetResponse.getType() == ImmichType.VIDEO) {
                    // Immich reports asset duration in MILLISECONDS (ffprobe-verified: a 4.033s
                    // clip has duration=4033). Store as-is — a *1000 here makes the seamless-loop
                    // timer fire ~67min late, so short clips play once then freeze at EOS.
                    Integer durationMs = finalAssetResponse.getDuration();
                    if (durationMs != null && durationMs > 0) {
                        videoDurationMsByAsset.put(finalAssetResponse.getId(), durationMs);
                    }
                }
                activity.runOnUiThread(() -> {
                    if (finalAssetResponse.getType() == ImmichType.IMAGE) {
                        mediaManagerListener.displayPicture(finalPlaybackFile, finalAssetResponse.getId());

                    }
                    else if (finalAssetResponse.getType() == ImmichType.VIDEO) {
                        mediaManagerListener.displayVideo(finalPlaybackFile, finalAssetResponse.getId());
                    }
                });
            }
            else {
                // Release the in-flight guard before re-posting the retry. The worker's finally
                // (showNextImage) clears showNextInFlight only after showNextImageLocked returns;
                // without this, the UI thread may execute the posted retry while the flag is
                // still set and drop it (lost wakeup).
                showNextInFlight.set(false);
                activity.runOnUiThread(() -> showNextImage(activity));
            }
    }

    /**
     * Logs in and fetches all albums + timeline assets into {@link #immichAssets}.
     *
     * @return the authenticated Immich service, for later downloads.
     * @throws Exception on any authentication or fetch failure (caller decides the fallback).
     */
    private ImmichApiService fetchImmichAssets(Activity activity) throws Exception {
        SettingsManager settings = this.settingsManager.get();
        Configuration configuration = settings.getConfiguration();
        Log.i(TAG, "showNextImage: fetching albums from " + configuration.host);

        ImmichApiService apiService;
        try {
            apiService = ApiServiceGenerator.createService(
                    ImmichApiService.class, configuration.host, activity, this.downloadProgressListener.get());
        } catch (Exception e) {
            throw new MediaDownloadFailedException(e);
        }

        Call<ImmichApiLoginResponse> service =
                apiService.login(new ImmichApiLogin(configuration.userid, configuration.password));
        Response<ImmichApiLoginResponse> call = service.execute();
        ImmichApiLoginResponse loginResponse = call.body();
        if (call.code() == 401) {
            throw new AuthenticationFailedException(call.code());
        }
        if (call.code() == 404) {
            throw new AuthenticationUnavailableException(call.code());
        }
        if (call.isSuccessful() && loginResponse == null) {
            throw new AuthenticationUnavailableException(call.code());
        }
        String userId;
        if (loginResponse != null && !loginResponse.getUserId().isEmpty()) {
            userId = loginResponse.getUserId();
        } else {
            throw new AuthenticationFailedException(-1);
        }
        if (userId == null || userId.isEmpty()) {
            throw new InvalidCredentialsException();
        }

        // Owned albums (isShared=false) and shared albums (isShared=true)
        for (int i = 0; i < 2; i++) {
            Response<List<ImmichApiGetAlbumResponse>> albumsResponse =
                    apiService.getAllAlbums(i == 1, null).execute();

            List<ImmichApiGetAlbumResponse> albums =
                    albumsResponse.body() != null ? albumsResponse.body() : new ArrayList<ImmichApiGetAlbumResponse>(0);

            for (ImmichApiGetAlbumResponse album : albums) {
                if (album.getId() == null || album.getAssetCount() == 0) {
                    continue;
                }
                addAssetsFromSearch(apiService, new ImmichApiMetadataSearchBody(1, SEARCH_PAGE_SIZE)
                        .withAlbumIds(Collections.singletonList(album.getId())));
            }
        }

        // All timeline assets visible to this account
        addAssetsFromSearch(apiService, new ImmichApiMetadataSearchBody(1, SEARCH_PAGE_SIZE));
        return apiService;
    }

    private void addAssetsFromSearch(@NonNull ImmichApiService apiService, @NonNull ImmichApiMetadataSearchBody firstPage)
            throws IOException {
        int page = firstPage.getPage() != null ? firstPage.getPage() : 1;
        int pageSize = firstPage.getSize() != null ? firstPage.getSize() : SEARCH_PAGE_SIZE;
        int count = pageSize;

        while (count == pageSize) {
            ImmichApiMetadataSearchBody body = new ImmichApiMetadataSearchBody(page, pageSize)
                    .withAlbumIds(firstPage.getAlbumIds())
                    .withIsFavorite(firstPage.getIsFavorite())
                    .withIsNotInAlbum(firstPage.getIsNotInAlbum());

            Response<ImmichApiMetadataSearchResponse> assetsResponse = apiService.getAllAssets(body).execute();

            List<ImmichApiAssetResponse> items = null;
            if (assetsResponse.body() != null && assetsResponse.body().getAssets() != null) {
                items = assetsResponse.body().getAssets().getItems();
            }

            if (assetsResponse.isSuccessful() && items != null && !items.isEmpty()) {
                for (ImmichApiAssetResponse asset : items) {
                    if (isCompatibleAsset(asset)) {
                        addCompatibleAsset(asset);
                    }
                }
            }

            page++;
            count = items != null ? items.size() : 0;
        }
    }

    private boolean isCompatibleAsset(@NonNull ImmichApiAssetResponse asset) {
        ImmichExifInfo exif = asset.getExifInfo();
        if (exif == null) {
            return false;
        }
        Long fileSize = exif.getFileSizeInByte();
        if (fileSize == null || fileSize > MAX_ASSET_BYTES) {
            return false;
        }
        return !Boolean.TRUE.equals(asset.getIsTrashed());
    }

    private void addCompatibleAsset(@NonNull ImmichApiAssetResponse asset) {
        if (asset.getType() == ImmichType.VIDEO || asset.getType() == ImmichType.IMAGE) {
            immichAssets.add(asset);
        }
    }

    static String cacheFileName(String uuid, String originalFileName, String originalPath,
                                ImmichType type, boolean fallback) {
        if (fallback) {
            return uuid + defaultExtension(type);
        }

        String extension = extensionFromFileName(originalFileName);
        if (extension == null) {
            extension = extensionFromFileName(originalPath);
        }
        if (extension == null) {
            extension = defaultExtension(type);
        }
        return uuid + extension;
    }

    static String extensionFromFileName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }

        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }

        String extension = name.substring(dot + 1).toLowerCase();
        if (!extension.matches("[a-z0-9]{1,10}")) {
            return null;
        }
        return "." + extension;
    }

    private static String defaultExtension(ImmichType type) {
        return type == ImmichType.VIDEO ? ".mp4" : ".jpg";
    }

    /** True if this asset is a synthetic entry for a local uploaded file. */
    public boolean isLocalAsset(ImmichApiAssetResponse asset) {
        return asset != null && asset.getId() != null
                && localAssetIdToFilename.containsKey(asset.getId());
    }

    /** True if the given asset id belongs to a local uploaded file. */
    public boolean isLocalAssetId(String assetId) {
        return assetId != null && localAssetIdToFilename.containsKey(assetId);
    }

    /**
     * Video duration in milliseconds for an asset, from Immich metadata. Returns -1 when the
     * asset is unknown or Immich did not report a duration.
     */
    public int getVideoDurationMs(String assetId) {
        if (assetId == null) {
            return -1;
        }
        Integer ms = videoDurationMsByAsset.get(assetId);
        return ms != null ? ms : -1;
    }

    /** Package-visible test hook: current rotation-list size. */
    int rotationListSize() {
        return immichAssets.size();
    }

    /**
     * Deterministic UUID derived from the file name. Stable across rotation rebuilds so a local
     * upload keeps the same synthetic asset id between rebuilds.
     */
    static String localAssetIdFor(String filename) {
        return UUID.nameUUIDFromBytes(filename.getBytes(Charset.forName("UTF-8"))).toString();
    }

    /** Maps an extension (with or without leading dot) to an {@link ImmichType}. */
    private static ImmichType mediaTypeForExtension(String extWithDot) {
        String e = extWithDot.startsWith(".") ? extWithDot.substring(1) : extWithDot;
        if (VIDEO_EXTENSIONS.contains(e)) {
            return ImmichType.VIDEO;
        }
        if (IMAGE_EXTENSIONS.contains(e)) {
            return ImmichType.IMAGE;
        }
        return ImmichType.OTHER;
    }

    /** Resolves a local asset to its on-disk file, or null if it was deleted. */
    private File resolveLocalAssetFile(Activity activity, ImmichApiAssetResponse asset) {
        String fname = localAssetIdToFilename.get(asset.getId());
        if (fname == null) {
            return null;
        }
        File f = new File(UploadedMedia.dirFor(activity), fname);
        return f.isFile() ? f : null;
    }

    /**
     * Scans the persistent upload directory and appends a synthetic asset entry for each
     * recognised media file into {@link #immichAssets}. Re-run on every rotation rebuild so the
     * set always reflects the current directory.
     */
    void addLocalUploadedAssets(Activity activity) {
        int added = addLocalUploadedAssets(UploadedMedia.dirFor(activity));
        if (added > 0) {
            Log.i(TAG, "showNextImage: added " + added + " local uploaded asset(s)");
        }
    }

    /**
     * Package-visible variant operating on a directory, for unit testing. Deliberately free of
     * Android calls (no Log) so it runs on the JVM.
     *
     * @return the number of local assets appended to the rotation list.
     */
    int addLocalUploadedAssets(File dir) {
        Map<String, String> rebuilt = new HashMap<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int added = 0;
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            String ext = extensionFromFileName(f.getName());
            if (ext == null) {
                continue;
            }
            ImmichType type = mediaTypeForExtension(ext);
            if (type != ImmichType.IMAGE && type != ImmichType.VIDEO) {
                continue;
            }
            String id = localAssetIdFor(f.getName());
            rebuilt.put(id, f.getName());
            immichAssets.add(syntheticLocalAsset(id, type, f.getName(), f.length()));
            added++;
        }
        // Atomic publish: readers see either the previous complete map or the new complete map,
        // never an empty/partial one mid-rebuild.
        localAssetIdToFilename = rebuilt;
        return added;
    }

    /**
     * Builds a synthetic {@link ImmichApiAssetResponse} for a local file. The id is a deterministic
     * UUID (see {@link #localAssetIdFor}).
     */
    private static ImmichApiAssetResponse syntheticLocalAsset(String id, ImmichType type,
            String name, long bytes) {
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("type", type.name());
        root.addProperty("originalFileName", name);
        JsonObject exif = new JsonObject();
        exif.addProperty("fileSizeInByte", bytes);
        root.add("exifInfo", exif);
        root.addProperty("isTrashed", false);
        return new Gson().fromJson(root.toString(), ImmichApiAssetResponse.class);
    }

    @NonNull
    private synchronized File downloadAsset(String uuid, ImmichType type, boolean fallback,
                                            String originalFileName, String originalPath,
                                            Activity activity, ImmichApiService apiService)
            throws NullPointerException, MediaDownloadFailedException, IOException {
        return downloadAsset(uuid, type, fallback, originalFileName, originalPath, activity, apiService, null, null);
    }

    /**
     * Downloads (or verifies a cached) asset file. For non-fallback downloads, the cached file
     * is re-verified against the server-reported SHA-1 checksum ({@code expectedChecksum},
     * Base64 per the Immich API) and/or expected size before reuse; freshly downloaded bytes
     * are digested in-flight and a hex sidecar ({@code <file>.sha1}) is written. Mismatched
     * files are discarded and re-fetched once.
     */
    @NonNull
    private synchronized File downloadAsset(String uuid, ImmichType type, boolean fallback,
                                            String originalFileName, String originalPath,
                                            Activity activity, ImmichApiService apiService,
                                            String expectedChecksum, Long expectedBytes)
            throws NullPointerException, MediaDownloadFailedException, IOException {

        if (apiService == null) {
            SettingsManager settings = this.settingsManager.get();
            if (settings != null) {
                Configuration configuration = settings.getConfiguration();
                apiService = ApiServiceGenerator.createService(ImmichApiService.class, configuration.host, activity, this.downloadProgressListener.get());
                apiService.login(new ImmichApiLogin(configuration.userid, configuration.password)).execute();
            }
        }

        if (apiService == null) {
            throw new MediaDownloadFailedException("Unable to create Immich service");
        }

        File cacheDir = activity.getCacheDir();
        File cacheFile = new File(cacheDir,
                cacheFileName(uuid, originalFileName, originalPath, type, fallback));

        // Reuse the original for non-fallback downloads only after an integrity check;
        // Immich fallback streams (preview / /video/playback) may differ from the original
        // bytes, so they are always re-fetched.
        if (!fallback && cacheFile.exists() && cacheFile.length() > 0) {
            if (MediaIntegrity.isCacheFileUsable(cacheFile, expectedChecksum, expectedBytes)) {
                return cacheFile;
            }
            Log.w(TAG, "downloadAsset: cached " + cacheFile.getName()
                    + " failed integrity check, discarding and re-downloading");
            deleteCachedFile(cacheFile);
        }

        long bytesNeeded = expectedBytes != null && expectedBytes > 0
                ? expectedBytes
                : MAX_ASSET_BYTES;
        if (!mediaCacheManager.ensureSpace(cacheDir, bytesNeeded, protectedCachePaths(null))) {
            throw new MediaDownloadFailedException("Insufficient cache space for asset download");
        }

        // One retry: transient transport corruption is rare, a persistent mismatch means the
        // asset is unusable and should be skipped until the next rotation cycle.
        for (int attempt = 1; attempt <= 2; attempt++) {
            Response<ResponseBody> downloadResponse = !fallback ? apiService.downloadFile(uuid, null).execute() :
                    type == ImmichType.IMAGE
                    ? apiService.getAssetThumbnail(uuid, ImmichSizeFormat.preview, null).execute()
                    : apiService.playAssetVideo(uuid, null).execute();

            if (!downloadResponse.isSuccessful() || downloadResponse.body() == null) {
                if (downloadResponse.body() != null) {
                    downloadResponse.body().close();
                }
                throw new MediaDownloadFailedException("Failed downloading immich asset.");
            }

            byte[] digest;
            downloadMutex.lock();
            try (ResponseBody body = downloadResponse.body()) {
                digest = MediaIntegrity.writeStreamToFile(cacheFile, body.byteStream());
            } catch (IOException e) {
                deleteCachedFile(cacheFile);
                throw e;
            } finally {
                downloadMutex.unlock();
            }

            if (MediaIntegrity.isDownloadValid(cacheFile, digest, fallback, expectedChecksum, expectedBytes)) {
                if (!fallback) {
                    try {
                        MediaIntegrity.writeSidecar(cacheFile, MediaIntegrity.toHex(digest));
                    } catch (IOException e) {
                        Log.w(TAG, "downloadAsset: failed to write integrity sidecar for " + cacheFile.getName(), e);
                    }
                }
                return cacheFile;
            }

            Log.w(TAG, "downloadAsset: download of " + cacheFile.getName()
                    + " failed integrity check, attempt " + attempt + "/2");
            deleteCachedFile(cacheFile);
        }

        throw new MediaDownloadFailedException("Downloaded asset " + uuid + " failed integrity check");
    }

    private void deleteCachedFile(File file) {
        if (file == null) {
            return;
        }
        MediaIntegrity.deleteSidecar(file);
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "downloadAsset: could not delete cache file " + file.getAbsolutePath());
        }
    }

    @Override
    public  void displayThumbnailAsset(@NotNull Activity activity, @NotNull String assetId, @NotNull ImmichType type, boolean isVideo) {

        new Thread(() -> {
            try {

                File thumbnail = downloadAsset(assetId, type, true, null, null, activity, null, null, null);

                activity.runOnUiThread(() -> {
                    MediaManagerListener mediaManagerListener = this.listener.get();
                    if (mediaManagerListener != null) {
                        if (Boolean.TRUE.equals(isVideo)) {
                            mediaManagerListener.displayVideo(thumbnail, null);
                        }
                        else {
                            mediaManagerListener.displayPicture(thumbnail, null);
                        }
                    }
                });
            }
            catch (Exception e) {
                activity.runOnUiThread(() -> {
                    MediaManagerListener mediaManagerListener = this.listener.get();

                    if (mediaManagerListener != null) {
                        mediaManagerListener.debugInformationProvided(new DebugInformation("Thumbnail", "Failed to get thumbnail for asset: " + assetId));
                    }
                });
            }
        }).start();
    }
    @Override
    public void tagAssetAsIncompatible(@NotNull String assetId) {
        new Thread(() -> {

            String incompatibleTagId = null;

            SettingsManager settings = this.settingsManager.get();

            if (settings == null) {
                return;
            }

            Configuration configuration = settings.getConfiguration();

            ImmichApiService apiService = ApiServiceGenerator.createService(ImmichApiService.class, configuration.host, null, null);

            try {
                Response<ImmichApiLoginResponse> login = apiService.login(new ImmichApiLogin(configuration.userid, configuration.password)).execute();

                if (login.isSuccessful()) {
                    Response<List<ImmichApiTagResponse>> allTagsResponse = apiService.getAllTags().execute();

                    if (allTagsResponse.isSuccessful()) {

                        List<ImmichApiTagResponse> allTags = allTagsResponse.body();

                        if (allTags != null) {

                            for (ImmichApiTagResponse tag : allTags) {
                                if (INCOMPATIBLE_TAG_NAME.equals(tag.getName())) {
                                    incompatibleTagId = tag.getId();
                                    break;
                                }
                            }
                        }

                        if (incompatibleTagId == null) {
                            Response<ImmichApiTagResponse> createTag = apiService.createTag(new ImmichApiTag(INCOMPATIBLE_TAG_NAME)).execute();

                            if (createTag.isSuccessful() && createTag.body() != null) {
                                incompatibleTagId = createTag.body().getId();
                            }
                        }

                        if (incompatibleTagId != null) {
                            Call<List<ImmichApiTagAssetResponse>> service = apiService.tagAssets(incompatibleTagId, new ImmichApiTagAssetBody(Collections.singletonList(assetId)));
                            Response<List<ImmichApiTagAssetResponse>> addTag = service.execute();
                            if (!addTag.isSuccessful()) {
                                throw new ImmichApiTagException();
                            }
                        }
                    }
                }
            }
            catch (Exception e) {
                MediaManagerListener mediaManagerListener = this.listener.get();

                if (mediaManagerListener != null) {
                    mediaManagerListener.debugInformationProvided(new DebugInformation("Incompatible", "Failed to tag asset as incompatible: " + assetId));
                }
            }
        }).start();
    }

    public void removeFromCache(File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        // Only ever delete cache-owned media (UUID-named originals or _eo1-suffixed files). This
        // protects local uploads (filesDir/uploaded) from being deleted on a playback failure.
        if (!MediaCacheManager.isOwnedMediaCacheFile(file)) {
            Log.i(TAG, "removeFromCache: skipping non-cache file " + file.getName());
            return;
        }
        // Location check: even when the name matches the cache pattern, only delete files that
        // actually live in the media cache dir (never a persistent upload elsewhere).
        File cacheDir = this.mediaCacheDir;
        if (cacheDir == null || file.getParentFile() == null || !file.getParentFile().equals(cacheDir)) {
            Log.i(TAG, "removeFromCache: skipping file outside cache dir " + file.getName());
            return;
        }
        if (file.exists()) {
            MediaIntegrity.deleteSidecar(file);
            if (!file.delete()) {
                MediaManagerListener mediaManagerListener = this.listener.get();
                if (mediaManagerListener != null) {
                    mediaManagerListener.debugInformationProvided(new DebugInformation("MediaManager.removeFromCache", "Unable to delete file "+file.getAbsolutePath()));
                }
            }
        }
    }

    private HashSet<String> protectedCachePaths(File extra) {
        HashSet<String> protectedPaths = new HashSet<>();
        if (currentPlaybackPath != null) {
            protectedPaths.add(currentPlaybackPath);
        }
        if (extra != null) {
            protectedPaths.add(extra.getAbsolutePath());
        }
        return protectedPaths;
    }

}
