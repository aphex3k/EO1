package com.aphex3k.immichApi;

import com.google.gson.annotations.SerializedName;

public class ImmichApiPingResponse extends ImmichApiResponse {
    @SerializedName("res")
    private String res;

    public String getRes() {
        return res;
    }

    /**
     * If the ping request was successful
     * @return true if res is `pong`.
     */
    public boolean pong() {
        return "pong".equals(res);
    }
}
