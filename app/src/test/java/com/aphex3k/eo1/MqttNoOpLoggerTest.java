package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.paho.client.mqttv3.logging.Logger;
import org.eclipse.paho.client.mqttv3.logging.LoggerFactory;
import org.junit.Test;

/**
 * Paho's LoggerFactory instantiates the class named in setLogger() via
 * Class.forName(name).newInstance() from its own package
 * (org.eclipse.paho.client.mqttv3.logging). This test, in a different package,
 * encodes that contract: if MqttManager.NoOpLogger ever loses public
 * visibility or its public no-arg constructor, these fail the same way the
 * device fails - Paho swallows the reflective error and rethrows
 * MissingResourceException("Error locating the logging class").
 */
public class MqttNoOpLoggerTest {

    private static final String NO_OP_LOGGER = "com.aphex3k.eo1.mqtt.MqttManager$NoOpLogger";

    @Test
    @SuppressWarnings("deprecation") // newInstance() mirrors Paho's exact reflection call
    public void noOpLoggerIsInstantiableAcrossPackages() throws Exception {
        Object logger = Class.forName(NO_OP_LOGGER).newInstance();
        assertTrue("NoOpLogger must implement Paho's Logger interface",
                logger instanceof Logger);
    }

    @Test
    public void pahoLoggerFactoryReturnsNoOpLogger() {
        LoggerFactory.setLogger(NO_OP_LOGGER);
        // Same call shape Paho's TimerPingSender makes in its constructor
        Logger log = LoggerFactory.getLogger("org.eclipse.paho.client.mqttv3.internal.nls.logcat",
                "com.aphex3k.eo1.mqtt.MqttManager");
        assertNotNull(log);
        assertEquals(NO_OP_LOGGER, log.getClass().getName());
    }
}
