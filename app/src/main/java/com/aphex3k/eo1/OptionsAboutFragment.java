package com.aphex3k.eo1;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * What this binary is and who signed it — read-only, and nothing on this tab writes to
 * {@code configuration.json}. The exception is the device name at the top: it is stored in the
 * key/value store instead, on its own button rather than on the dialog's Save, because a page that
 * has been swiped away has no views left to read (see {@code res/layout/options_about.xml}).
 */
public class OptionsAboutFragment extends Fragment {

    private final SettingsManager settingsManager;
    private EditText deviceNameField;

    public OptionsAboutFragment(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }

    /** Required: the pager re-instantiates a page reflectively when restoring saved state. */
    public OptionsAboutFragment() {
        this.settingsManager = null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.options_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        BuildFacts.Signer signer = BuildFacts.signing(view.getContext());
        text(view, R.id.textViewAboutVersion, BuildFacts.versionName());
        text(view, R.id.textViewAboutBuild, String.valueOf(BuildFacts.buildNumber()));
        text(view, R.id.textViewAboutVariant, BuildFacts.variant());
        text(view, R.id.textViewAboutDate, BuildFacts.buildStampForDisplay());
        text(view, R.id.textViewAboutSource, BuildFacts.source());
        text(view, R.id.textViewAboutFingerprint, signer.fingerprint);
        text(view, R.id.textViewAboutSubject, signer.subject);
        text(view, R.id.textViewAboutSerial, signer.serial);
        text(view, R.id.textViewAboutValidity, signer.validity());
        text(view, R.id.textViewAboutAlgorithm, signer.algorithm);
        text(view, R.id.textViewAboutVerdict, "Signing key: " + signer.keyVerdict);

        // A service's required notice belongs on a surface a person can actually see. On a chrome-less
        // kiosk that is this tab and the LAN page header, not the README: the API terms ask for this
        // sentence whenever the product uses the service, so it appears exactly when a backend that
        // needs it is configured and not otherwise.
        text(view, R.id.textViewAboutServiceTerms, serviceTermsNotice());

        deviceNameField = view.findViewById(R.id.editTextDeviceName);
        if (deviceNameField != null) {
            // Seeded from the store, never from restored view state (the field is saveEnabled=false):
            // the stored name is the truth, this field is a view of it.
            deviceNameField.setText(settingsManager != null ? settingsManager.deviceName() : "");
        }
        Button set = view.findViewById(R.id.btnSetDeviceName);
        if (set != null) {
            set.setOnClickListener(v -> commitDeviceName());
        }
    }

    private void commitDeviceName() {
        Context context = getContext();
        EditText field = deviceNameField;
        if (context == null || field == null) {
            return;
        }
        if (settingsManager == null) {
            Toast.makeText(context, "Device name unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        String stored = settingsManager.setDeviceName(field.getText().toString());
        // Show what was stored, not what was typed: the store trims and bounds the name.
        field.setText(stored);
        field.setSelection(stored.length());
        Toast.makeText(context, stored.isEmpty() ? "Device name cleared"
                : "Device name set: " + stored, Toast.LENGTH_SHORT).show();
    }

    String serviceTermsNotice() {
        try {
            return Configuration.requiredServiceNotices(
                    settingsManager == null ? null : settingsManager.getConfiguration());
        } catch (Exception ignored) {
            // An unreadable configuration must not blank the rest of this tab.
            return "";
        }
    }

    private static void text(View root, int id, String value) {
        TextView field = root.findViewById(id);
        if (field != null) {
            field.setText(value == null ? "" : value);
        }
    }
}
