package com.aphex3k.eo1;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class OptionsMqttFragment extends Fragment {
    private SettingsManager settingsManager;
    private EditText mqttHostField, mqttPortField, mqttProtocolField, mqttUserField, mqttPasswordField;

    public OptionsMqttFragment(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }

    public OptionsMqttFragment() {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.options_mqtt, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mqttHostField = view.findViewById(R.id.editTextMqttHost);
        mqttPortField = view.findViewById(R.id.editTextMqttPort);
        mqttProtocolField = view.findViewById(R.id.editTextMqttProtocol);
        mqttUserField = view.findViewById(R.id.editTextMqttUser);
        mqttPasswordField = view.findViewById(R.id.editTextMqttPassword);
        if (settingsManager != null) {
            var config = settingsManager.getConfiguration();
            mqttHostField.setText(config.mqttHost != null ? config.mqttHost : "");
            mqttPortField.setText(config.mqttPort != 0 ? String.valueOf(config.mqttPort) : "");
            mqttProtocolField.setText(config.mqttProtocol != null ? config.mqttProtocol : "");
            mqttUserField.setText(config.mqttUser != null ? config.mqttUser : "");
            mqttPasswordField.setText(config.mqttPassword != null ? config.mqttPassword : "");
        }
    }

    public void saveToConfiguration() {
        if (settingsManager != null) {
            var config = settingsManager.getConfiguration();
            config.mqttHost = mqttHostField.getText().toString();
            try {
                config.mqttPort = Integer.parseInt(mqttPortField.getText().toString());
            } catch (Exception ignored) {}
            config.mqttProtocol = mqttProtocolField.getText().toString();
            config.mqttUser = mqttUserField.getText().toString();
            config.mqttPassword = mqttPasswordField.getText().toString();
        }
    }
}
