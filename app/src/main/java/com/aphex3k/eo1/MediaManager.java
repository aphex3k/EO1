package com.aphex3k.eo1;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aphex3k.immichApi.ImmichApiAssetResponse;
import com.aphex3k.immichApi.ImmichApiGetAlbumResponse;
import com.aphex3k.immichApi.ImmichApiLogin;
import com.aphex3k.immichApi.ImmichApiLoginResponse;
import com.aphex3k.immichApi.ImmichApiService;
import com.aphex3k.immichApi.ImmichApiTag;
import com.aphex3k.immichApi.ImmichApiTagAssetBody;
import com.aphex3k.immichApi.ImmichApiTagAssetResponse;
import com.aphex3k.immichApi.ImmichApiTagResponse;
import com.aphex3k.immichApi.ImmichExifInfo;
import com.aphex3k.immichApi.ImmichTagType;
import com.aphex3k.immichApi.ImmichType;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class MediaManager implements MediaManagerInterface {

    private static final String INCOMPATIBLE_TAG_NAME = "EO1_INCOMPATIBLE";
    private final WeakReference<SettingsManager> settingsManager;
    private final WeakReference<MediaManagerListener> listener;
    private final ArrayList<ImmichApiAssetResponse> immichAssets = new ArrayList<>();

    public MediaManager(MediaManagerListener listener, SettingsManager settingsManager) {
        this.listener = new WeakReference<>(listener);
        this.settingsManager = new WeakReference<>(settingsManager);
    }

    public void showNextImage(Activity activity) {

        MediaManagerListener mediaManagerListener = this.listener.get();

        if (mediaManagerListener == null) {
            return;
        }

        new Thread(() -> {
            SettingsManager settings = this.settingsManager.get();
            Configuration configuration = settings.getConfiguration();

            ImmichApiService apiService = ApiServiceGenerator.createService(ImmichApiService.class, configuration.host);

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
                assert loginResponse != null;
                userId = loginResponse.getUserId();

            } catch (Exception e) {
                activity.runOnUiThread(() -> mediaManagerListener.handleException(e));
                return;
            }

            if (userId == null || userId.equals("")) {
                activity.runOnUiThread(() ->  mediaManagerListener.handleException(new InvalidCredentialsException()));
                return;
            }

            ImmichApiAssetResponse assetResponse;
            File tempFile = null;

            if (immichAssets.isEmpty()) {

                try {
                    Response<List<ImmichApiGetAlbumResponse>> sharedAlbumsResponse = apiService.getAllAlbums(true, null).execute();

                    List<ImmichApiGetAlbumResponse> responseBody = sharedAlbumsResponse.body();

                    if (sharedAlbumsResponse.isSuccessful() && responseBody != null) {

                        for (ImmichApiGetAlbumResponse r : responseBody) {
                            List<ImmichApiAssetResponse> assetList = r.getAssets();

                            if (assetList.isEmpty()) {
                                assetList = Objects.requireNonNull(apiService.getAlbumInfo(r.getId(), false, null).execute().body()).getAssets();
                            }

                            for (ImmichApiAssetResponse asset : assetList) {
                                ImmichExifInfo exif = asset.getExifInfo();
                                if (exif == null || exif.getFileSizeInByte() > 1073741824) {
                                    continue;
                                }
                                if (asset.getType() == ImmichType.IMAGE || asset.getType() == ImmichType.VIDEO) {
                                    immichAssets.add(asset);
                                }
                            }
                        }
                    }

                    Response<List<ImmichApiGetAlbumResponse>> albumsResponse = apiService.getAllAlbums(false, null).execute();

                    responseBody = albumsResponse.body();

                    if (albumsResponse.isSuccessful() && responseBody != null) {

                        for (ImmichApiGetAlbumResponse r : responseBody) {
                            List<ImmichApiAssetResponse> assetList = r.getAssets();

                            if (assetList.isEmpty()) {
                                assetList = Objects.requireNonNull(apiService.getAlbumInfo(r.getId(), false, null).execute().body()).getAssets();
                            }
                            for (ImmichApiAssetResponse asset : assetList) {
                                ImmichExifInfo exif = asset.getExifInfo();
                                if (exif == null || exif.getFileSizeInByte() > 1073741824) {
                                    continue;
                                }
                                if (asset.getType() == ImmichType.IMAGE || asset.getType() == ImmichType.VIDEO) {
                                    immichAssets.add(asset);
                                }
                            }
                        }
                    }

                } catch (Exception e) {
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(e));
                    return;
                }
            }

            Collections.shuffle(immichAssets);

            do {
                assetResponse = immichAssets.remove(0);

                String uuid = assetResponse.getId();

                try {
                    tempFile = downloadAsset(uuid, false, activity, apiService);
                } catch (Exception e) {
                    activity.runOnUiThread(() -> mediaManagerListener.handleException(e));
                }

            } while (!immichAssets.isEmpty() && (tempFile == null));

            ImmichApiAssetResponse finalAssetResponse = assetResponse;
            File finalTempFile = tempFile;

            if (finalTempFile != null) {
                activity.runOnUiThread(() -> {
                    if (finalAssetResponse.getType() == ImmichType.IMAGE) {
                        mediaManagerListener.displayPicture(finalTempFile, finalAssetResponse.getId());
                    }
                    else if (finalAssetResponse.getType() == ImmichType.VIDEO) {
                        mediaManagerListener.displayVideo(finalTempFile, finalAssetResponse.getId());
                    }
                });

                // Clean all files from the cache directory we have already downloaded
                File[] directoryListing = activity.getCacheDir().listFiles();
                if (directoryListing != null) {
                    for (File child : directoryListing) {
                        // Only delete the file that is not supposed to get displayed this moment
                        if (!child.getAbsolutePath().equals(finalTempFile.getAbsolutePath()) && child.getAbsolutePath().endsWith(".dat")) {
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

    @NonNull
    private File downloadAsset(String uuid, boolean thumbnail, Activity activity, @Nullable ImmichApiService apiService) throws Exception {

        if (apiService == null) {
            SettingsManager settings = this.settingsManager.get();
            if (settings != null) {
                Configuration configuration = settings.getConfiguration();
                apiService = ApiServiceGenerator.createService(ImmichApiService.class, configuration.host);
                Response<ImmichApiLoginResponse> service = apiService.login(new ImmichApiLogin(configuration.userid, configuration.password)).execute();
            }
        }

        Response<ResponseBody> downloadResponse = apiService.serveFile(uuid, thumbnail, false, null).execute();

        if (downloadResponse.isSuccessful() && downloadResponse.body() != null) {

            File cacheFile = new File(activity.getCacheDir(), uuid + (thumbnail ? "-thumb.dat" : ".dat"));
            try (FileOutputStream outputStream = new FileOutputStream(cacheFile)) {
                try (InputStream inputStream = downloadResponse.body().byteStream()) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
            }
            return cacheFile;
        } else throw new MediaDownloadFailedException("Failed downloading immich asset.");

    }

    @Override
    public  void displayThumbnailAsset(Activity activity, String assetId, Boolean isVideo) {

        new Thread(() -> {
            try {

                File thumbnail = downloadAsset(assetId, true, activity, null);

                activity.runOnUiThread(() -> {
                    MediaManagerListener mediaManagerListener = this.listener.get();
                    if (mediaManagerListener != null) {
                        if (isVideo) {
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

            ImmichApiService apiService = ApiServiceGenerator.createService(ImmichApiService.class, configuration.host);

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
                            Response<ImmichApiTagResponse> createTag = apiService.createTag(new ImmichApiTag(INCOMPATIBLE_TAG_NAME, ImmichTagType.CUSTOM)).execute();

                            if (createTag.isSuccessful()) {

                                assert createTag.body() != null;
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

            mediaManagerListener.debugInformationProvided(new DebugInformation("MediaManager.removeFromCache", "Unable to delete file "+file.getAbsolutePath()));
        }
    }
}
