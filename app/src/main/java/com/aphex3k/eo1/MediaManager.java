package com.aphex3k.eo1;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.aphex3k.immichApi.ImmichApiAssetResponse;
import com.aphex3k.immichApi.ImmichApiGetAlbumResponse;
import com.aphex3k.immichApi.ImmichApiLogin;
import com.aphex3k.immichApi.ImmichApiLoginResponse;
import com.aphex3k.immichApi.ImmichApiService;
import com.aphex3k.immichApi.ImmichExifInfo;
import com.aphex3k.immichApi.ImmichType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class MediaManager implements MediaManagerInterface {

    private final WeakReference<SettingsManager> settingsManager;
    private final WeakReference<MediaManagerListener> listener;
    private Integer rollingCache = 0;
    private final ArrayList<ImmichApiAssetResponse> immichAssets = new ArrayList<>();

    public MediaManager(MediaManagerListener listener, SettingsManager settingsManager) {
        this.listener = new WeakReference<>(listener);
        this.settingsManager = new WeakReference<>(settingsManager);
    }

    public void showNextImage(Activity activity) {

        MediaManagerListener listener = this.listener.get();

        if (listener == null) {
            return;
        }

        new Thread(() -> {
            SettingsManager settingsManager = this.settingsManager.get();
            Configuration configuration = settingsManager.getConfiguration();

            ImmichApiService apiService = ApiServiceGenerator.createService(ImmichApiService.class, configuration.host);

            String userId = null;

            try {
                ImmichApiLoginResponse loginResponse = apiService.login(new ImmichApiLogin(configuration.userid, configuration.password)).execute().body();
                assert loginResponse != null;
                userId = loginResponse.getUserId();

            } catch (Exception e) {
                activity.runOnUiThread(() -> listener.handleException(e));
                return;
            }

            if (userId == null || userId.equals("")) {
                activity.runOnUiThread(() ->  listener.handleException(new InvalidCredentialsException()));
                return;
            }

            ImmichApiAssetResponse assetResponse;
            File tempFile = null;

            if (immichAssets.size() == 0) {

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
                    activity.runOnUiThread(() -> listener.handleException(e));
                    return;
                }
            }

            Collections.shuffle(immichAssets);

            do {
                assetResponse = immichAssets.remove(0);

                String uuid = assetResponse.getId();

                try {
                    Response<ResponseBody> downloadResponse = apiService.serveFile(uuid, false, false, null).execute();

                    if (downloadResponse.isSuccessful() && downloadResponse.body() != null) {

                        File cacheFile = new File(activity.getCacheDir(), "asset-" + rollingCache + ".dat");
                        try (FileOutputStream outputStream = new FileOutputStream(cacheFile)) {
                            try (InputStream inputStream = downloadResponse.body().byteStream()) {
                                byte[] buffer = new byte[1024];
                                int bytesRead;
                                while ((bytesRead = inputStream.read(buffer)) != -1) {
                                    outputStream.write(buffer, 0, bytesRead);
                                }
                            }
                        }
                        tempFile = cacheFile;
                    } else throw new Exception("Failed downloading immich asset.");
                } catch (Exception e) {
                    activity.runOnUiThread(() -> listener.handleException(e));
                }

            } while (immichAssets.size() > 0 && (tempFile == null));

            ImmichApiAssetResponse finalAssetResponse = assetResponse;
            File finalTempFile = tempFile;

            rollingCache = rollingCache >= 5 ? 0 : rollingCache + 1;

            if (finalTempFile != null) {
                activity.runOnUiThread(() -> {
                    if (finalAssetResponse.getType() == ImmichType.IMAGE) {
                        listener.displayPicture(finalTempFile);
                    }
                    else if (finalAssetResponse.getType() == ImmichType.VIDEO) {
                        listener.displayVideo(finalTempFile);
                    }
                });
            }
            else {
                showNextImage(activity);
            }

        }).start();
    }

    /** @noinspection ResultOfMethodCallIgnored*/
    public void removeFromCache(File file) {
        file.delete();
    }
}
