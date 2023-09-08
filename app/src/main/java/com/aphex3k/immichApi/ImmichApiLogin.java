package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

public class ImmichApiLogin {

    public ImmichApiLogin(String email, String password) {
        this.email = email;
        this.password = password;
    }

    @SerializedName("email")
    private String email;

    @SerializedName("password")
    private String password;

    public String getEmail() { return email; };

    public String getPassword() { return password; };
}
