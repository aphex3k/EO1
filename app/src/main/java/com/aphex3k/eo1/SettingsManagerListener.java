package com.aphex3k.eo1;

import android.view.LayoutInflater;

import java.io.File;

public interface SettingsManagerListener extends ListenerInterface {
    void settingsChanged();
    File getFilesDir();
    LayoutInflater getLayoutInflater();
    Object getSystemService(String alarmService);
}
