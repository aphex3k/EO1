package com.aphex3k.eo1;

import android.app.Activity;

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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class MediaManager implements MediaManagerInterface {

    private static final String INCOMPATIBLE_TAG_NAME = "EO1_INCOMPATIBLE";
    private static final long MAX_ASSET_BYTES = 1073741824L;
    private static final int SEARCH_PAGE_SIZE = 1000;
    private static final ReentrantLock downloadMutex = new ReentrantLock();
    private final WeakReference<SettingsManager> settingsManager;
    private final WeakReference<MediaManagerListener> listener;
    private final WeakReference<ApiServiceGenerator.ProgressListener> downloadProgressListener;
    private final ArrayList<ImmichApiAssetResponse> immichAssets = new ArrayList<>();
    private final VideoTranscodeManager videoTranscodeManager;
    private final HashSet<String> reactiveTranscodeAttempted = new HashSet<>();

    public MediaManager(MediaManagerListener listener, SettingsManager settingsManager, ApiServiceGenerator.ProgressListener downloadProgressListener) {
        this(listener, settingsManager, downloadProgressListener, new VideoTranscodeManager());
    }

    public MediaManager(MediaManagerListener listener, SettingsManager settingsManager,
                        ApiServiceGenerator.ProgressListener downloadProgressListener,
                        VideoTranscodeManager videoTranscodeManager) {
        this.listener = new WeakReference<>(listener);
        this.settingsManager = new WeakReference<>(settingsManager);
        this.downloadProgressListener = new WeakReference<>(downloadProgressListener);
        this.videoTranscodeManager = videoTranscodeManager;
    }

    public void showNextImage(Activity activity) {

        clearReactiveTranscodeAttempts();

        MediaManagerListener mediaManagerListener = this.listener.get();

        if (mediaManagerListener == null) {
            return;
        }

        new Thread(() -> {
            ImmichApiService apiService = null;
            ImmichApiAssetResponse assetResponse;
            File tempFile = null;

            if (immichAssets.isEmpty()) {

                SettingsManager settings = this.settingsManager.get();
                Configuration configuration = settings.getConfiguration();

                try {
                    apiService = ApiServiceGenerator.createService(ImmichApiService.class, configuration.host, activity, this.downloadProgressListener.get());
                }
                catch (Exception e) {
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(new MediaDownloadFailedException(e)));
                    return;
                }

                String userId;

                try {
                    Call<ImmichApiLoginResponse> service = apiService.login(new ImmichApiLogin(configuration.userid, configuration.password));
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
                    else if (loginResponse != null && !loginResponse.getUserId().isEmpty()){
                        userId = loginResponse.getUserId();
                    }
                    else {
                        throw new AuthenticationFailedException(-1);
                    }

                } catch (Exception e) {
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(e));
                    return;
                }

                if (userId == null || userId.isEmpty()) {
                    activity.runOnUiThread(() ->  mediaManagerListener.handleException(new InvalidCredentialsException()));
                    return;
                }

                try {
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
                } catch (Exception e) {
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(e));
                    return;
                }

                if (immichAssets.isEmpty()) {
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(new NoMediaFoundException()));
                    return;
                }

                Collections.shuffle(immichAssets);
            }

            do {
                assetResponse = immichAssets.remove(0);

                try {
                    tempFile = downloadAsset(
                            assetResponse.getId(),
                            assetResponse.getType(),
                            false,
                            assetResponse.getOriginalFileName(),
                            assetResponse.getOriginalPath(),
                            activity,
                            apiService);
                } catch (Exception e) {
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(e));
                }

            } while (!immichAssets.isEmpty() && (tempFile == null));

            ImmichApiAssetResponse finalAssetResponse = assetResponse;
            File playbackFile = tempFile;

            if (playbackFile != null && finalAssetResponse.getType() == ImmichType.VIDEO) {
                File prepared = prepareVideoForPlayback(activity, finalAssetResponse, playbackFile);
                if (prepared != null) {
                    if (!prepared.getAbsolutePath().equals(playbackFile.getAbsolutePath())) {
                        removeFromCache(playbackFile);
                    }
                    playbackFile = prepared;
                } else {
                    try {
                        File fallbackFile = downloadAsset(
                                finalAssetResponse.getId(),
                                ImmichType.VIDEO,
                                true,
                                finalAssetResponse.getOriginalFileName(),
                                finalAssetResponse.getOriginalPath(),
                                activity,
                                apiService);
                        removeFromCache(playbackFile);
                        playbackFile = fallbackFile;
                    } catch (Exception e) {
                        removeFromCache(playbackFile);
                        playbackFile = null;
                        activity.runOnUiThread(() -> mediaManagerListener.handleException(e));
                    }
                }
            }

            final File finalPlaybackFile = playbackFile;

            if (finalPlaybackFile != null) {
                activity.runOnUiThread(() -> {
                    if (finalAssetResponse.getType() == ImmichType.IMAGE) {
                        mediaManagerListener.displayPicture(finalPlaybackFile, finalAssetResponse.getId());

                    }
                    else if (finalAssetResponse.getType() == ImmichType.VIDEO) {
                        mediaManagerListener.displayVideo(finalPlaybackFile, finalAssetResponse.getId());
                    }
                });

                // Clean all files from the cache directory we have already downloaded
                File[] directoryListing = activity.getCacheDir().listFiles();
                if (directoryListing != null) {
                    for (File child : directoryListing) {
                        // Only delete the file that is not supposed to get displayed this moment
                        if (!child.getAbsolutePath().equals(finalPlaybackFile.getAbsolutePath())) {
                            removeFromCache(child);
                        }
                    }
                }
            }
            else {
                activity.runOnUiThread(() -> showNextImage(activity));
            }

        }).start();
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

    @NonNull
    private synchronized File downloadAsset(String uuid, ImmichType type, boolean fallback,
                                            String originalFileName, String originalPath,
                                            Activity activity, ImmichApiService apiService) throws NullPointerException, MediaDownloadFailedException, IOException {

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

        Response<ResponseBody> downloadResponse = !fallback ? apiService.downloadFile(uuid, null).execute() :
                type == ImmichType.IMAGE
                ? apiService.getAssetThumbnail(uuid, ImmichSizeFormat.thumbnail, null).execute()
                : apiService.playAssetVideo(uuid, null).execute();

        if (downloadResponse.isSuccessful() && downloadResponse.body() != null) {
            downloadMutex.lock();
            File cacheFile = new File(activity.getCacheDir(),
                    cacheFileName(uuid, originalFileName, originalPath, type, fallback));

            try (FileOutputStream outputStream = new FileOutputStream(cacheFile)) {
                try (InputStream inputStream = downloadResponse.body().byteStream()) {
                    byte[] buffer = new byte[1024*32];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
            }

            downloadMutex.unlock();
            return cacheFile;

        } else throw new MediaDownloadFailedException("Failed downloading immich asset.");

    }

    private File prepareVideoForPlayback(Activity activity,
                                         ImmichApiAssetResponse asset, File downloaded) {
        return videoTranscodeManager.prepareForPlayback(
                activity.getCacheDir(), asset.getId(), downloaded);
    }

    public boolean shouldAttemptReactiveTranscode(String assetId, File file) {
        if (assetId == null || file == null || isTranscodedFile(file)) {
            return false;
        }
        return !reactiveTranscodeAttempted.contains(assetId);
    }

    public void attemptReactiveTranscode(Activity activity, String assetId, File sourceFile,
                                         ReactiveTranscodeCallback callback) {
        if (!shouldAttemptReactiveTranscode(assetId, sourceFile)) {
            activity.runOnUiThread(callback::onTranscodeFailed);
            return;
        }
        recordReactiveTranscodeAttempt(assetId);

        new Thread(() -> {
            File transcoded = videoTranscodeManager.attemptReactiveTranscode(
                    activity.getCacheDir(), assetId, sourceFile);

            activity.runOnUiThread(() -> {
                if (transcoded != null) {
                    if (!transcoded.getAbsolutePath().equals(sourceFile.getAbsolutePath())) {
                        removeFromCache(sourceFile);
                    }
                    callback.onTranscodeSuccess(transcoded);
                } else {
                    callback.onTranscodeFailed();
                }
            });
        }).start();
    }

    public boolean isTranscodedFile(File file) {
        return VideoTranscodeManager.isTranscodedFile(file);
    }

    void clearReactiveTranscodeAttempts() {
        reactiveTranscodeAttempted.clear();
    }

    void recordReactiveTranscodeAttempt(String assetId) {
        reactiveTranscodeAttempted.add(assetId);
    }

    public interface ReactiveTranscodeCallback {
        void onTranscodeSuccess(File transcodedFile);
        void onTranscodeFailed();
    }

    @Override
    public  void displayThumbnailAsset(@NotNull Activity activity, @NotNull String assetId, @NotNull ImmichType type, boolean isVideo) {

        new Thread(() -> {
            try {

                File thumbnail = downloadAsset(assetId, type, true, null, null, activity, null);

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
        if (file.exists() && !file.delete()) {
            MediaManagerListener mediaManagerListener = this.listener.get();
            if (mediaManagerListener != null) {
                mediaManagerListener.debugInformationProvided(new DebugInformation("MediaManager.removeFromCache", "Unable to delete file "+file.getAbsolutePath()));
            }
        }
    }
}
