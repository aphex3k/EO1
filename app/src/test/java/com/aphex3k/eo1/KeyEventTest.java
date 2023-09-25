package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.aphex3k.immichApi.ImmichApiLogin;
import com.aphex3k.immichApi.ImmichApiLoginResponse;

import retrofit2.Response;

public class KeyEventTest {

    @org.junit.Test
    public void EO1_TOP_BUTTON() throws Exception {
        assertEquals(KeyEvent.EO1_TOP_BUTTON, android.view.KeyEvent.KEYCODE_F2);
    }
    @org.junit.Test
    public void EO1_BACK_BUTTON() throws Exception {
        assertEquals(KeyEvent.EO1_BACK_BUTTON, android.view.KeyEvent.KEYCODE_F4);
    }
}
