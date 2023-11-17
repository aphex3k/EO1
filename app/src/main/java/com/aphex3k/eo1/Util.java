package com.aphex3k.eo1;
import android.os.Environment;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
public class Util {
    public static String downloadFileSync(String downloadUrl, OkHttpClient client, String fileName) {
        Request request = new Request.Builder().url(downloadUrl).build();
        Response response;
        try {
            response = client.newCall(request).execute();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        if (!response.isSuccessful()) {
            System.err.println("Failed to download file: " + response);
            return null;
        }

        String filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + File.separator + fileName;

        File f = new File(filePath);

        try (FileOutputStream output = new FileOutputStream(f)) {
            output.write(response.body().bytes());
            output.flush();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return filePath;
    }
}
