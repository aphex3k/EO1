package com.aphex3k.eo1;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

public class ConfigurationTest {
    @org.junit.Test
    public void DeserializationTest() throws Exception {

        String testString = "{host:'test'}";

        Configuration configuration = new Gson().fromJson(testString, Configuration.class);

        assertTrue(!configuration.host.isEmpty());
        assertNull(configuration.userid);
    }
}
