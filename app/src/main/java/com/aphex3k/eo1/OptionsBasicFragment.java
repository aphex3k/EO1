package com.aphex3k.eo1;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * Editor for the media backends (any number of Immich hosts plus the optional local
 * uploads backend) and the display settings. Rows are built programmatically; immich
 * rows expand in place to an editor. The working copy is only written back to the
 * live {@link Configuration} from {@link #saveToConfiguration()}.
 */
public class OptionsBasicFragment extends Fragment {
    private SettingsManager settingsManager;
    private LinearLayout backendListContainer;
    private Button addImmichButton;
    private Button addLocalButton;
    private EditText intervalField;
    private Spinner startHourSpinner, endHourSpinner, tzSpinner;
    private String[] allTimeZoneIds;

    /** Working copy of the configured backends; persisted on save. */
    private final List<ConfigurationBackendEntry> backends = new ArrayList<>();
    /** Rows currently shown in backendListContainer, in list order. */
    private final List<BackendRow> rows = new ArrayList<>();

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
        backendListContainer = view.findViewById(R.id.backendListContainer);
        addImmichButton = view.findViewById(R.id.btnAddImmich);
        addLocalButton = view.findViewById(R.id.btnAddLocal);
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

        addImmichButton.setOnClickListener(v -> addImmichBackend());
        addLocalButton.setOnClickListener(v -> addLocalBackend());

        if (settingsManager != null) {
            Configuration config = settingsManager.getConfiguration();
            for (ConfigurationBackendEntry entry : config.backendsOrEmpty()) {
                backends.add(copyOf(entry));
            }
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
        renderBackendList();
    }

    private static ConfigurationBackendEntry copyOf(ConfigurationBackendEntry e) {
        ConfigurationBackendEntry copy = new ConfigurationBackendEntry();
        copy.type = e.type;
        copy.id = e.id;
        copy.host = e.host;
        copy.userid = e.userid;
        copy.password = e.password;
        copy.apiVersion = e.apiVersion;
        return copy;
    }

    private void renderBackendList() {
        backendListContainer.removeAllViews();
        rows.clear();
        for (ConfigurationBackendEntry entry : backends) {
            BackendRow row = new BackendRow(entry);
            rows.add(row);
            backendListContainer.addView(row.container);
        }
        updateAddLocalButton();
    }

    private void updateAddLocalButton() {
        boolean hasLocal = false;
        for (ConfigurationBackendEntry entry : backends) {
            if (entry.isLocal()) {
                hasLocal = true;
                break;
            }
        }
        addLocalButton.setEnabled(!hasLocal);
    }

    /** Smallest free "immich-N" number, starting at 1. */
    private int nextImmichNumber() {
        int max = backends.size() + 1;
        boolean[] used = new boolean[max + 1];
        for (ConfigurationBackendEntry entry : backends) {
            if (entry.id != null) {
                for (int n = 1; n <= max; n++) {
                    if (entry.id.equals("immich-" + n)) {
                        used[n] = true;
                    }
                }
            }
        }
        for (int n = 1; n <= max; n++) {
            if (!used[n]) {
                return n;
            }
        }
        return max;
    }

    private void addImmichBackend() {
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_IMMICH;
        entry.id = "immich-" + nextImmichNumber();
        entry.host = "";
        entry.userid = "";
        entry.password = "";
        entry.apiVersion = ConfigurationBackendEntry.API_VERSION_AUTO;
        backends.add(entry);
        renderBackendList();
        // Open the new row's editor so credentials can be entered immediately.
        rows.get(rows.size() - 1).openEditor();
    }

    private void addLocalBackend() {
        ConfigurationBackendEntry entry = new ConfigurationBackendEntry();
        entry.type = ConfigurationBackendEntry.TYPE_LOCAL;
        entry.id = ConfigurationBackendEntry.TYPE_LOCAL;
        backends.add(entry);
        renderBackendList();
    }

    private void removeRow(BackendRow row) {
        backends.remove(row.entry);
        renderBackendList();
    }

    /**
     * Commits any open row editors, validates the working copy, and only then writes it
     * to the live configuration. Returns false with a naming Toast when an entry is
     * invalid, so the dialog's Save button can abort without dismissing.
     */
    public boolean saveToConfiguration() {
        if (settingsManager == null) {
            return true;
        }
        for (BackendRow row : rows) {
            if (row.editorOpen) {
                row.applyEditor();
                row.closeEditor();
            }
        }
        for (ConfigurationBackendEntry entry : backends) {
            String problem;
            if (entry.isImmich()) {
                problem = invalidImmichEntry(entry);
            } else if (entry.isLocal()) {
                problem = null;
            } else {
                problem = "unknown backend type '" + entry.type + "'";
            }
            if (problem != null) {
                String id = entry.id != null && !entry.id.isEmpty() ? entry.id : "backend";
                Toast.makeText(requireContext(), "Invalid " + id + ": " + problem, Toast.LENGTH_LONG).show();
                return false;
            }
        }
        Configuration config = settingsManager.getConfiguration();
        config.backends = new ArrayList<>(backends);
        try {
            // Clamp to >= 1: a 0/negative interval would make the rotation timer
            // reschedule itself in a tight loop.
            config.interval = Math.max(1, Integer.parseInt(intervalField.getText().toString()));
        } catch (Exception ignored) {}
        config.startQuietHour = Integer.parseInt(startHourSpinner.getSelectedItem().toString());
        config.endQuietHour = Integer.parseInt(endHourSpinner.getSelectedItem().toString());
        config.selectedTimeZoneId = tzSpinner.getSelectedItem().toString();
        return true;
    }

    /**
     * @return null when the entry is complete, otherwise a short description of the
     *         problem. Also normalizes apiVersion ("auto" / blank accepted, others must
     *         parse as a semver, mirroring the registry's pin parsing).
     */
    private static String invalidImmichEntry(ConfigurationBackendEntry entry) {
        String host = entry.host == null ? "" : entry.host.trim();
        if (host.isEmpty()) {
            return "server URL is required";
        }
        String lower = host.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "server URL must start with http:// or https://";
        }
        if (entry.userid == null || entry.userid.trim().isEmpty()) {
            return "username is required";
        }
        if (entry.password == null || entry.password.trim().isEmpty()) {
            return "password is required";
        }
        String api = entry.apiVersion == null ? "" : entry.apiVersion.trim();
        if (!ConfigurationBackendEntry.API_VERSION_AUTO.equalsIgnoreCase(api) && !api.isEmpty()) {
            boolean parseable = false;
            try {
                new Semver(api, Semver.SemverType.STRICT);
                parseable = true;
            } catch (SemverException ignored) {
                try {
                    new Semver(api, Semver.SemverType.LOOSE);
                    parseable = true;
                } catch (SemverException ignored2) {
                }
            }
            if (!parseable) {
                return "API version must be \"auto\" or a version like 3.1.0";
            }
        }
        if (api.isEmpty()) {
            entry.apiVersion = ConfigurationBackendEntry.API_VERSION_AUTO;
        }
        return null;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** One row in the backend list: a summary line and, for immich entries, an in-place editor. */
    private class BackendRow {
        final ConfigurationBackendEntry entry;
        final LinearLayout container;
        final TextView summary;
        LinearLayout editor;
        EditText hostField;
        EditText userIdField;
        EditText passwordField;
        EditText apiVersionField;
        boolean editorOpen;

        BackendRow(ConfigurationBackendEntry entry) {
            this.entry = entry;
            Context context = requireContext();
            this.container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            container.setPadding(0, dp(8), 0, dp(8));

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);

            this.summary = new TextView(context);
            summary.setSingleLine(true);
            summary.setEllipsize(TextUtils.TruncateAt.END);
            summary.setTextSize(16);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            summaryParams.setMarginEnd(dp(8));
            summary.setLayoutParams(summaryParams);
            header.addView(summary);

            if (entry.isImmich()) {
                Button editButton = new Button(context);
                editButton.setText("Edit");
                editButton.setOnClickListener(v -> openEditor());
                header.addView(editButton);
                buildEditor(context);
            }

            Button removeButton = new Button(context);
            removeButton.setText("Remove");
            removeButton.setOnClickListener(v -> removeRow(this));
            header.addView(removeButton);

            container.addView(header);
            if (editor != null) {
                editor.setVisibility(View.GONE);
                container.addView(editor);
            }
            updateSummary();
        }

        private void buildEditor(Context context) {
            this.editor = new LinearLayout(context);
            editor.setOrientation(LinearLayout.VERTICAL);

            hostField = new EditText(context);
            editor.addView(fieldRow(context, "Server", hostField));

            userIdField = new EditText(context);
            editor.addView(fieldRow(context, "Username", userIdField));

            passwordField = new EditText(context);
            passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            editor.addView(fieldRow(context, "Password", passwordField));

            apiVersionField = new EditText(context);
            apiVersionField.setHint("auto");
            editor.addView(fieldRow(context, "API version", apiVersionField));

            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            Button saveButton = new Button(context);
            saveButton.setText("Save");
            saveButton.setOnClickListener(v -> {
                applyEditor();
                closeEditor();
            });
            Button cancelButton = new Button(context);
            cancelButton.setText("Cancel");
            cancelButton.setOnClickListener(v -> closeEditor());
            saveButton.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cancelParams.setMarginStart(dp(8));
            cancelButton.setLayoutParams(cancelParams);
            buttons.addView(saveButton);
            buttons.addView(cancelButton);
            editor.addView(buttons);
        }

        private View fieldRow(Context context, String label, EditText field) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextSize(16);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.setMarginEnd(dp(8));
            labelView.setLayoutParams(labelParams);
            field.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(labelView);
            row.addView(field);
            return row;
        }

        void openEditor() {
            if (editor == null) {
                return;
            }
            hostField.setText(safe(entry.host));
            userIdField.setText(safe(entry.userid));
            passwordField.setText(safe(entry.password));
            apiVersionField.setText(entry.apiVersion != null ? entry.apiVersion : "");
            editor.setVisibility(View.VISIBLE);
            editorOpen = true;
        }

        void closeEditor() {
            editorOpen = false;
            if (editor != null) {
                editor.setVisibility(View.GONE);
            }
        }

        void applyEditor() {
            entry.host = hostField.getText().toString().trim();
            entry.userid = userIdField.getText().toString().trim();
            entry.password = passwordField.getText().toString().trim();
            String api = apiVersionField.getText().toString().trim();
            entry.apiVersion = api.isEmpty() ? ConfigurationBackendEntry.API_VERSION_AUTO : api;
            updateSummary();
        }

        void updateSummary() {
            if (entry.isLocal()) {
                summary.setText("Local uploads");
                return;
            }
            String host = entry.host == null ? "" : entry.host.trim();
            String api = entry.apiVersion == null ? "auto" : entry.apiVersion;
            StringBuilder sb = new StringBuilder("Immich");
            if (!host.isEmpty()) {
                sb.append(" — ").append(host);
            }
            sb.append(" (API: ").append(api).append(")");
            summary.setText(sb.toString());
        }
    }
}
