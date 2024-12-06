package com.aphex3k.immichApi;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;

import java.util.Locale;

@Keep
public class ImmichApiServerVersionResponse extends ImmichApiResponse {

    @SerializedName("major")
    private int major;
    @SerializedName("minor")
    private int minor;
    @SerializedName("patch")
    private int patch;

    public int getMajor() { return major; }
    public int getMinor() { return minor; }
    public int getPatch() { return patch; }

    public Semver getVersion() {

        String versionString = String.format(Locale.US,"%d.%d.%d", major, minor, patch);

        Semver version = null;

        try {
            version = new Semver(versionString, Semver.SemverType.STRICT);
        }
        catch (SemverException e1) {
            try {
                version = new Semver(versionString, Semver.SemverType.LOOSE);
            }
            catch (SemverException e2) {

            }
        }
        return version;
    }
}
