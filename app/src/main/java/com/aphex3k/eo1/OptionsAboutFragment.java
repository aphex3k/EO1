package com.aphex3k.eo1;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** Read-only: what this binary is, and who signed it. Nothing here writes to the configuration. */
public class OptionsAboutFragment extends Fragment {

    public OptionsAboutFragment() {
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
    }

    private static void text(View root, int id, String value) {
        TextView field = root.findViewById(id);
        if (field != null) {
            field.setText(value == null ? "" : value);
        }
    }
}
