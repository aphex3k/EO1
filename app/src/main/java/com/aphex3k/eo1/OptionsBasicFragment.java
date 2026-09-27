package com.aphex3k.eo1;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.Arrays;
import java.util.TimeZone;

public class OptionsBasicFragment extends Fragment {
    private SettingsManager settingsManager;
    private EditText userIdField, passwordField, hostField, intervalField;
    private Spinner startHourSpinner, endHourSpinner, tzSpinner;
    private String[] allTimeZoneIds;

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
        startHourSpinner = view.findViewById(R.id.startHourSpinner);
        endHourSpinner = view.findViewById(R.id.endHourSpinner);
        tzSpinner = view.findViewById(R.id.tzSpinner);

        String[] hours = new String[24];
        for (int i = 0; i < 24; i++) {
            hours[i] = String.format("%02d", i);
        }
        ArrayAdapter<String> hourAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, hours);
        hourAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        startHourSpinner.setAdapter(hourAdapter);
        endHourSpinner.setAdapter(hourAdapter);

        allTimeZoneIds = TimeZone.getAvailableIDs();
        ArrayAdapter<String> tzAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, allTimeZoneIds);
        tzAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tzSpinner.setAdapter(tzAdapter);

        if (settingsManager != null) {
            Configuration config = settingsManager.getConfiguration();
            userIdField.setText(config.userid != null ? config.userid : "");
            passwordField.setText(config.password != null ? config.password : "");
            hostField.setText(config.host != null ? config.host : "");
            intervalField.setText(String.valueOf(config.interval));
            if (config.startQuietHour != -1) {
                startHourSpinner.setSelection(config.startQuietHour);
            }
            if (config.endQuietHour != -1) {
                endHourSpinner.setSelection(config.endQuietHour);
            }
            if (config.selectedTimeZoneId != null && !config.selectedTimeZoneId.isEmpty()) {
                int tzIndex = Arrays.asList(allTimeZoneIds).indexOf(config.selectedTimeZoneId);
                if (tzIndex >= 0) {
                    tzSpinner.setSelection(tzIndex);
                }
            }
        }
    }

    public void saveToConfiguration() {
        if (settingsManager != null) {
            Configuration config = settingsManager.getConfiguration();
            config.userid = userIdField.getText().toString();
            config.password = passwordField.getText().toString();
            config.host = hostField.getText().toString();
            try {
                config.interval = Integer.parseInt(intervalField.getText().toString());
            } catch (Exception ignored) {}
            config.startQuietHour = Integer.parseInt(startHourSpinner.getSelectedItem().toString());
            config.endQuietHour = Integer.parseInt(endHourSpinner.getSelectedItem().toString());
            config.selectedTimeZoneId = tzSpinner.getSelectedItem().toString();
        }
    }
}
