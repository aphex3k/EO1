package com.aphex3k.eo1;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.aphex3k.giteaApi.GiteaApiGetReleasesResponse;
import com.aphex3k.giteaApi.GiteaApiService;
import com.aphex3k.giteaApi.GiteaAsset;
import com.vdurmont.semver4j.Semver;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.util.List;

import retrofit2.Response;

public class UpdateManager {

    private final WeakReference<UpdateManagerListener> listener;

    protected UpdateManager(UpdateManagerListener listener) {
        this.listener = new WeakReference<>(listener);
    }

    public final void checkForUpdates(Activity parent) {

        new Thread(() -> {
            try {
                Semver latestVersion = new Semver(BuildConfig.VERSION_NAME);
                GiteaApiGetReleasesResponse updateTo = null;

                GiteaApiService apiService = ApiServiceGenerator.createService(GiteaApiService.class, "https://gitea.codingmerc.com/");

                Response<List<GiteaApiGetReleasesResponse>> response = apiService.getReleases("michael", "EO1").execute();

                for (GiteaApiGetReleasesResponse release: response.body()) {
                    boolean isHigher = release.version().isGreaterThan(latestVersion);
                    boolean isStable = latestVersion.isStable();
                    if (isHigher && (BuildConfig.DEBUG || isStable)) {
                        latestVersion = release.version();
                        updateTo = release;
                    }
                }

                if (updateTo != null) {

                    String downloadUrlString = null;

                    for (GiteaAsset asset: updateTo.getAssets()) {
                        if (BuildConfig.DEBUG && asset.getName().equals("app-debug.apk")) {
                            downloadUrlString = asset.getBrowserDownloadUrl();
                        }
                        else if (!BuildConfig.DEBUG && asset.getName().equals("app-release.apk")) {
                            downloadUrlString = asset.getBrowserDownloadUrl();
                        }
                    }

                    if (downloadUrlString == null) {
                        throw new FileNotFoundException("Couldn't find expected download url for asset.");
                    }

                    final String fileName = "app-" + latestVersion.toStrict() + ".apk";

                    final String downloadedFilePath = Util.downloadFileSync(downloadUrlString, ApiServiceGenerator.getNewHttpClient(), fileName);

                    if (downloadedFilePath == null) {
                        throw new FileNotFoundException("File failed downloading...");
                    }
                    else {
                        parent.runOnUiThread(() -> {
                            Uri apkUri = Uri.fromFile(new File(downloadedFilePath));
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            parent.startActivity(intent);
                        });
                    }
                }
            }
            catch (Exception e)
            {
                UpdateManagerListener listener = this.listener.get();
                if (listener != null) {
                    parent.runOnUiThread(() -> listener.handleException(e));
                }
            }
        }).start();
    }
}
