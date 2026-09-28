package com.aphex3k.eo1.mqtt;

import android.content.Context;

import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Objects;
import java.util.Arrays;

/// https://www.hivemq.com/blog/mqtt-client-library-encyclopedia-eclipse-paho-java/
public class MqttManager {

    public static final String PLAYING = "PLAYING";
    public static final String GROUP_PLAYING = "PLAYING";
    public static final String STOPPED = "STOPPED";
    public static final String PAUSED_PLAYBACK = "PAUSED_PLAYBACK";
    public static final String GROUP_STOPPED = "GROUP_STOPPED";
    public static final String GROUP_PAUSED = "GROUP_PAUSED";

    public static String[] PLAY_STATES = {
            PLAYING,
            GROUP_PLAYING,
    };

    public static String[] STOP_STATES = {
            STOPPED,
            PAUSED_PLAYBACK,
            GROUP_STOPPED,
            GROUP_PAUSED
    };

    private final MqttClient client;

    // Custom TypeAdapterFactory to apply UPPERCASE mapping only to nested objects
    private static final Gson gson = new GsonBuilder().create();

    private final PlaybackListener playbackListener;
    /// uri = "tcp://broker.mqttdashboard.com:1883"
    public MqttManager (String uri, PlaybackListener playbackListener) throws MqttException {

        this.playbackListener = playbackListener;

        client = new MqttClient(
                uri, //URI
                MqttClient.generateClientId(), //ClientId
                new MemoryPersistence()); //Persistence

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                System.out.println("Connection Lost: " + cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                var payload = new String(message.getPayload());
                try {
                    Payload payloadObj = gson.fromJson(payload, Payload.class);
                    if (Arrays.asList(PLAY_STATES).contains(payloadObj.transportState)) {
                        if (playbackListener != null) {
                            playbackListener.onPlaybackStarted(payloadObj);
                        }
                    }
                    else if (Arrays.asList(STOP_STATES).contains(payloadObj.transportState))
                    {
                        if (playbackListener != null) {
                            playbackListener.onPlaybackStopped(payloadObj);
                        }
                    }
                    if (payloadObj != null && payload.trim().startsWith("{")) {
                        System.out.println("[MQTT] Topic: " + topic + " Payload (as object): " + gson.toJson(payloadObj));
                    } else {
                        System.out.println("[MQTT] Topic: " + topic + " Payload (raw): " + payload);
                    }
                } catch (JsonSyntaxException e) {
                    System.out.println("[MQTT] Topic: " + topic + " Payload (raw, not JSON): " + payload);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                System.out.println("Delivery Complete: " + token);
            }
        });
    }

    public void connect(String username, String password) throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(username);
        options.setPassword(password.toCharArray());

        client.connect(options);
        // https://www.hivemq.com/blog/mqtt-essentials-part-5-mqtt-topics-best-practices/#heading-mqtt-topics-wildcards-best-practices-mqtt-essentials-part-5
        subscribe("sonos/+");
    }

    public void disconnect() throws MqttException {
        // paho throws MqttException(32101) when disconnecting a client that is
        // not connected (never connected, connect still in flight, or broker
        // connection already lost); skipping keeps onPause log-clean.
        if (client.isConnected()) {
            client.disconnect();
        }
    }

    private void subscribe(String topic) throws MqttException {
        client.subscribe(topic);
    }

    private void unsubscribe(String topic) throws MqttException {
        client.unsubscribe(topic);
    }
}
