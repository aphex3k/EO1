package com.aphex3k.eo1;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class OptionsBasicFragment extends Fragment {
    private SettingsManager settingsManager;
    private EditText userIdField, passwordField, hostField, intervalField;
    // Add other fields as needed
    public OptionsBasicFragment(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }
    public OptionsBasicFragment() {}
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.options_basic, container, false);
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        userIdField = view.findViewById(R.id.editTextUserId);
        passwordField = view.findViewById(R.id.editTextPassword);
        hostField = view.findViewById(R.id.editTextHost);
        intervalField = view.findViewById(R.id.editTextInterval);
        // Load values from settingsManager
        if (settingsManager != null) {
            var config = settingsManager.getConfiguration();
            userIdField.setText(config.userid != null ? config.userid : "");
            passwordField.setText(config.password != null ? config.password : "");
            hostField.setText(config.host != null ? config.host : "");
            intervalField.setText(String.valueOf(config.interval));
        }
    }
    public void saveToConfiguration() {
        if (settingsManager != null) {
            var config = settingsManager.getConfiguration();
            config.userid = userIdField.getText().toString();
            config.password = passwordField.getText().toString();
            config.host = hostField.getText().toString();
            try {
                config.interval = Integer.parseInt(intervalField.getText().toString());
            } catch (Exception ignored) {}
        }
    }
}
