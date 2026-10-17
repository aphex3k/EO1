package com.aphex3k.eo1;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class OptionsDialogFragment extends DialogFragment {
    private static final String[] TAB_TITLES = {"Media", "MQTT"};
    private SettingsManager settingsManager;
    private OptionsBasicFragment basicFragment;
    private OptionsMqttFragment mqttFragment;
    private View dialogRoot;

    public OptionsDialogFragment(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.options, container, false);
        TabLayout tabLayout = view.findViewById(R.id.optionsTabLayout);
        ViewPager2 viewPager = view.findViewById(R.id.optionsViewPager);
        basicFragment = new OptionsBasicFragment(settingsManager);
        mqttFragment = new OptionsMqttFragment(settingsManager);
        viewPager.setAdapter(new OptionsPagerAdapter(this, basicFragment, mqttFragment));
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> tab.setText(TAB_TITLES[position])).attach();

        // Add Save button programmatically at the bottom
        Button saveButton = new Button(getContext());
        saveButton.setText("Save");
        ((ViewGroup) view).addView(saveButton);
        saveButton.setOnClickListener(v -> {
            // The media tab validates first; on failure it toasts the offending backend
            // and the dialog stays open.
            if (!basicFragment.saveToConfiguration()) {
                return;
            }
            mqttFragment.saveToConfiguration();
            try {
                settingsManager.saveConfiguration();
                settingsManager.updateTimeZone(requireContext());
                if (getActivity() instanceof SettingsManagerListener) {
                    ((SettingsManagerListener) getActivity()).settingsChanged();
                }
                Toast.makeText(getContext(), "Settings saved", Toast.LENGTH_SHORT).show();
                dismiss();
            } catch (Exception e) {
                Toast.makeText(getContext(), "Failed to save settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        dialogRoot = view;
        return view;
    }

    @Override
    public void onDestroyView() {
        // Every key binding lives on MainActivity's window. A backend field left focused here keeps
        // its input connection alive past this window's death, and on API 19 hardware keys then go to
        // that editor instead of reaching the activity — which silences C, Space and the soft buttons
        // while everything else about the frame still looks healthy.
        releaseInputFocus();
        dialogRoot = null;
        super.onDestroyView();
    }

    private void releaseInputFocus() {
        FragmentActivity activity = getActivity();
        View root = dialogRoot;
        if (root != null) {
            View focused = root.findFocus();
            if (focused != null) {
                focused.clearFocus();
            }
            if (activity != null) {
                InputMethodManager imm = (InputMethodManager)
                        activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
                }
            }
        }
        if (activity != null) {
            View anchor = activity.findViewById(R.id.rootView);
            if (anchor != null) {
                anchor.requestFocus();
            }
        }
    }
}
