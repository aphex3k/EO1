package com.aphex3k.eo1.mqtt;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;

import com.google.gson.JsonObject;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Objects;
import java.util.Arrays;

/// https://www.hivemq.com/blog/mqtt-client-library-encyclopedia-eclipse-paho-java/
///
/// <b>Every method Paho calls on this class may throw nothing at all.</b> That is not defensive style, it is the
/// contract: {@code CommsCallback} dispatches {@code connectComplete}, {@code connectionLost} and
/// {@code messageArrived} inside one {@code catch (Throwable)} region whose handler calls
/// {@code ClientComms.shutdownConnection()}. A single exception out of any of them is therefore a
/// broker-visible disconnect with no DISCONNECT packet — which is what a frame that "keeps dropping MQTT" looks
/// like from the server side, and it was the whole of that bug until this class reordered one null check.
public class MqttManager implements MqttCallbackExtended {

    // No-op logger to keep Paho off java.util.logging on API 19. Must stay
    // public: Paho's LoggerFactory instantiates the setLogger() class from its
    // own package via Class.forName(name).newInstance(); a non-public class
    // surfaces as MissingResourceException("Error locating the logging class").
    public static class NoOpLogger implements org.eclipse.paho.client.mqttv3.logging.Logger {
        @Override
        public void initialise(java.util.ResourceBundle bundle, String s, String s1) { }
        @Override
        public void setResourceName(String s) { }
        @Override
        public boolean isLoggable(int i) { return false; }
        @Override
        public void severe(String s, String s1, String s2) { }
        @Override
        public void severe(String s, String s1, String s2, Object[] objects) { }
        @Override
        public void severe(String s, String s1, String s2, Object[] objects, Throwable throwable) { }
        @Override
        public void warning(String s, String s1, String s2) { }
        @Override
        public void warning(String s, String s1, String s2, Object[] objects) { }
        @Override
        public void warning(String s, String s1, String s2, Object[] objects, Throwable throwable) { }
        @Override
        public void info(String s, String s1, String s2) { }
        @Override
        public void info(String s, String s1, String s2, Object[] objects) { }
        @Override
        public void info(String s, String s1, String s2, Object[] objects, Throwable throwable) { }
        @Override
        public void config(String s, String s1, String s2) { }
        @Override
        public void config(String s, String s1, String s2, Object[] objects) { }
        @Override
        public void config(String s, String s1, String s2, Object[] objects, Throwable throwable) { }
        @Override
        public void fine(String s, String s1, String s2) { }
        @Override
        public void fine(String s, String s1, String s2, Object[] objects) { }
        @Override
        public void fine(String s, String s1, String s2, Object[] objects, Throwable throwable) { }
        @Override
        public void finer(String s, String s1, String s2) { }
        @Override
        public void finer(String s, String s1, String s2, Object[] objects) { }
        @Override
        public void finer(String s, String s1, String s2, Object[] objects, Throwable throwable) { }
        @Override
        public void finest(String s, String s1, String s2) { }
        @Override
        public void finest(String s, String s1, String s2, Object[] objects) { }
        @Override
        public void finest(String s, String s1, String s2, Object[] objects, Throwable throwable) { }
        @Override
        public void log(int i, String s, String s1, String s2, Object[] objects, Throwable throwable) { }
        @Override
        public void trace(int i, String s, String s1, String s2, Object[] objects, Throwable throwable) { }
        @Override
        public String formatMessage(String s, Object[] objects) { return s; }
        @Override
        public void dumpTrace() { }
    }

    public static final String PLAYING = "PLAYING";
    /**
     * Distinct from {@link #PLAYING}, as every published coordinator payload and the AGENTS.md table say it is.
     * This was the literal {@code "PLAYING"} for the life of the feature, which made {@code PLAY_STATES} a
     * two-element array holding one value and meant a group coordinator reporting {@code GROUP_PLAYING} never
     * drew a banner.
     */
    public static final String GROUP_PLAYING = "GROUP_PLAYING";
    public static final String STOPPED = "STOPPED";
    public static final String PAUSED_PLAYBACK = "PAUSED_PLAYBACK";
    public static final String GROUP_STOPPED = "GROUP_STOPPED";
    public static final String GROUP_PAUSED = "GROUP_PAUSED";

    /** The only topic this client subscribes to. It publishes nothing. */
    public static final String TOPIC = "sonos/+";

    /**
     * QoS 0. This is <em>state</em>, not events — the banner wants whatever the coordinator's current transport
     * state is, and the next publish supersedes anything missed. QoS 1 additionally asked the broker to hold a
     * message until this class acknowledged it, and the acknowledgment is only sent once
     * {@code messageArrived} returns normally, so one unparseable payload became an outstanding redelivery.
     */
    static final int SUBSCRIBE_QOS = 0;

    /** Named rather than inherited: the broker log's {@code k60} was Paho's default, not this app's decision. */
    static final int KEEP_ALIVE_S = 60;

    /**
     * Bounds the one call on this path that blocks its caller — {@code connect()} runs on the UI thread when a
     * resume or a settings save reaches it, and Paho's own default is 30 s. A stall that long is exactly the
     * shape {@code KioskWatchdog} measures with {@code mainLoopAckAtMs}, so a dead broker must not be able to
     * walk a frame up the ladder on the strength of a decorative feature.
     */
    static final int CONNECT_TIMEOUT_S = 10;

    /**
     * Ceiling on Paho's own retry ladder, which starts at 1 s and doubles. Paho's default is 128 s; a frame
     * whose broker came back a moment ago should not wait two minutes to notice.
     */
    static final int MAX_RECONNECT_DELAY_MS = 60_000;

    /** Quiesce given to an intentional disconnect, so a quiet-hours transition cannot stall the UI thread. */
    private static final long DISCONNECT_QUIESCE_MS = 1_000L;

    /**
     * Ceiling on every synchronous wait Paho does on this client's behalf. Set above {@link #CONNECT_TIMEOUT_S}
     * so the protocol timeout is the thing that normally fires, and this is only the backstop for a peer that
     * accepts the socket and says nothing. Paho's own default is infinite.
     */
    static final long PROTOCOL_WAIT_MS = (CONNECT_TIMEOUT_S + 5L) * 1000L;

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

    /**
     * App-defined, because API 19 has no {@code java.util.function}. One seam so this class can report into the
     * durable record instead of onto stdout: the six {@code System.out}/{@code System.err} calls it used to make
     * are the only stdout in product source, they land in logcat's <em>main</em> buffer, and
     * {@code CrashSources} scrapes {@code -b system} — so an MQTT disconnect was invisible to both of the places
     * anyone actually looks, and to {@code /logs}.
     */
    public interface Reporter {
        void mqttEvent(String message, boolean severe);
    }

    /**
     * One client per manager, and it never changes. That is the whole answer to the stale-callback race:
     * {@link #stop()} ends this object's usefulness, and the caller builds a new manager rather than a new
     * client, so a callback still in flight from the closed client lands on a manager whose {@code suspended}
     * is already true and does nothing. It cannot reach — let alone close — whoever replaced it, because from
     * its point of view it is still the only one there is.
     */
    private final MqttClient client;
    /** Guards teardown, not the socket — nothing blocking happens inside it. */
    private final Object lifecycle = new Object();
    private volatile boolean suspended;

    // Custom TypeAdapterFactory to apply UPPERCASE mapping only to nested objects
    private static final Gson gson = new GsonBuilder().create();

    private final PlaybackListener playbackListener;
    @Nullable
    private final Reporter reporter;
    private volatile String lastError = "";
    private volatile long connectedAtMs;
    private volatile long lastMessageAtMs;
    private volatile int connectAttempts;
    private volatile int reconnects;
    /**
     * Read only to answer one question at connect time: whether the root bundle is on. Paho builds its
     * own TLS, so it cannot inherit the decision every {@code OkHttpClient} gets — and a broker
     * connection that quietly kept trusting the 2013 store after the rest of the app moved on would
     * make the setting mean two different things on one frame.
     */
    @Nullable
    private final Context context;
    /** Dialled address, reported in {@code /state} so a reader can tell it from the configured host. */
    private final String uri;
    private final String clientId;

    /// uri = "tcp://broker.mqttdashboard.com:1883"
    /// clientId must be stable and unique per frame — see {@code SettingsManager.mqttClientId()}.
    public MqttManager (String uri, String clientId, PlaybackListener playbackListener,
                        @Nullable Context context, @Nullable Reporter reporter)
            throws MqttException {

        this.playbackListener = playbackListener;
        this.context = context;
        this.reporter = reporter;
        this.uri = uri;
        this.clientId = clientId;

        // Use no-op logger to avoid MissingResourceException on API 19
        org.eclipse.paho.client.mqttv3.logging.LoggerFactory.setLogger(MqttManager.NoOpLogger.class.getName());

        client = new MqttClient(uri, clientId, new MemoryPersistence());

        // Bounds how long any synchronous Paho call waits for its protocol response. connectionTimeout does
        // NOT do this: it covers the socket/CONNECT phase, while MqttClient.connect() finishes by waiting on
        // this field — whose default is -1, i.e. forever (MqttClient.<init> stores 0xffffffffffffffff and
        // getTimeToWait() hands it straight to waitForCompletion). So a broker that accepts the TCP
        // connection and never answers CONNACK would hold the frame's main looper indefinitely, from a
        // resume or a panel wake, which is exactly what KioskWatchdog measures when it escalates. subscribe()
        // waits on the same field, so this one call bounds the SUBACK too.
        client.setTimeToWait(PROTOCOL_WAIT_MS);

        // MqttCallbackExtended, not MqttCallback: connectComplete is the only place this client can learn that
        // Paho brought the session back, and a clean-session reconnect with no re-subscribe is a frame that
        // reports itself connected while hearing nothing at all.
        client.setCallback(this);
    }

    public void connect(String username, String password) throws MqttException {
        if (suspended) {
            // A manager that has been stopped is not restarted; it is replaced. Reconnecting a closed Paho
            // client is not possible (32111), so the only honest thing to do here is refuse and let the
            // caller's rebuild branch construct a fresh one.
            throw new MqttException(MqttException.REASON_CODE_CLIENT_CLOSED);
        }
        if (client.isConnected()) {
            return;
        }
        MqttConnectOptions options = buildOptions(username, password);

        if (uri != null && uri.startsWith("ssl")) {
            try {
                options.setSocketFactory(
                        com.aphex3k.eo1.ApiServiceGenerator.trustAnchoredSocketFactory(context));
            } catch (Exception e) {
                // Paho would then fall back to the platform default, which is what happened before this
                // line existed. Say so: a broker that authenticates against a 2013 store while the rest
                // of the app does not is a difference an operator can only find by being surprised.
                report("could not build the shared TLS context, using the device default: " + e, true);
            }
        }

        connectAttempts++;
        try {
            // No subscribe() follows this call on purpose. ConnectActionListener.onSuccess dispatches
            // connectComplete — with reconnect=false for the very first session, confirmed at bytecode —
            // and that is the one place the subscription is issued. Doing it here too would put two
            // SUBSCRIBEs on the wire from two threads for every connect.
            client.connect(options);
        } catch (MqttException e) {
            if (isAlreadyOnItsWay(e.getReasonCode())) {
                // Paho is already doing the thing this call was asking for — connected, connecting, or
                // standing down. It is not a failure and must not be recorded as one: a settings save reaches
                // connect() on a live client through settingsChanged()'s cold-start branch, and a core-loop
                // re-arm reaches it while Paho's own ladder is mid-backoff, so treating these as errors would
                // put one line in /logs per retry on a frame whose broker is merely down.
                return;
            }
            lastError = String.valueOf(e);
            report("connect to " + uri + " refused: " + e, true);
            throw e;
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        // Runs on Paho's callback thread, inside the catch(Throwable) region — so nothing here may throw, and
        // nothing here may take long.
        if (suspended) {
            // The panel asked for this link to be down. stop() force-closed the client that was reconnecting,
            // so a ladder that completes after it must do no session work at all: subscribing here would be
            // a sonos/+ filter on a broker the frame has decided to go quiet on.
            try {
                MqttClient stale = client;
                if (stale != null) {
                    stale.close(true);
                }
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            connectedAtMs = System.currentTimeMillis();
            if (reconnect) {
                reconnects++;
            }
            // THE re-subscribe. Paho 1.2.5 has no resubscribe path of its own (no method in the jar even
            // spells the word), and cleanSession=true means the broker forgot the subscription along with the
            // session. connect() still calls this for the first session, so subscribe is issued once per
            // CONNACK either way.
            subscribe();
            report((reconnect ? "reconnected" : "connected") + " to " + serverURI, false);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            report("completed a connect it could not finish subscribing on: " + t, true);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        if (suspended) {
            return;     // our own stop() caused this; reporting it as a loss would be a false alarm
        }
        try {
            lastError = String.valueOf(cause);
            // Paho reconnects from here — that is what setAutomaticReconnect bought. This line exists only so
            // the event outlives logcat's ~13 minutes.
            report("connection to " + uri + " lost, reconnecting without disturbing the Activity: " + cause,
                    true);
        } catch (Throwable t) {
            // Reporting must not be the thing that closes the session.
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // This client publishes nothing, so Paho has nothing to report here.
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Cannot throw. Not "should not" — see the class comment: a throw from this method is a disconnect as
        // far as the broker is concerned. Everything that can go wrong with a payload goes wrong in here.
        String payload = "";
        try {
            byte[] raw = message.getPayload();
            payload = raw == null ? "" : new String(raw);
            // Stamped before anything decides whether this body is ours to read. lastMessageAtMs is what
            // answers the one question /state cannot otherwise resolve — a client that is connected and deaf,
            // which is the same failure this class shipped to fix — and it is answered by delivery, not by
            // by successful parsing.
            lastMessageAtMs = System.currentTimeMillis();
        } catch (Throwable t) {
            lastError = "a delivery on " + topic + " could not even be read as bytes: " + t;
            report("dropped a " + topic + " message it could not read (" + t + ") — staying connected", true);
            return;
        }

        // Anything that is not a JSON object is not a coordinator state payload, and nothing has gone wrong.
        // `sonos/+` is one wildcard and the coordinator publishes other things underneath it: measured on a
        // live frame on 2026-11-03, `sonos/connected` carries a bare number, which Gson answers with
        // "Expected BEGIN_OBJECT but was NUMBER". Up to this line that became one severe report per
        // publish, in files/eo1-app.log — the durable record every other failure in this app is supposed to
        // be readable from, and which logcat's ~13 minutes cannot substitute for. A topic that will never
        // hold a Payload is filtered by shape, not by name: naming it would mean the next subtopic the
        // coordinator invents re-opens the same log.
        String trimmed = payload.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return;
        }

        Payload payloadObj;
        try {
            payloadObj = gson.fromJson(trimmed, Payload.class);
        } catch (Throwable t) {
            // An object that would not become a Payload is different: something on the wire claims to be a
            // state document and is not one this class can read. Worth the severe line.
            lastError = "unreadable state payload on " + topic + ": " + t;
            report("dropped a " + topic + " message it could not read (" + t + ") — staying connected", true);
            return;
        }

        try {
            if (payloadObj != null && playbackListener != null) {
                if (Arrays.asList(PLAY_STATES).contains(payloadObj.transportState)) {
                    playbackListener.onPlaybackStarted(payloadObj);
                } else if (Arrays.asList(STOP_STATES).contains(payloadObj.transportState)) {
                    playbackListener.onPlaybackStopped(payloadObj);
                }
            }
        } catch (Throwable t) {
            lastError = "listener rejected a payload on " + topic + ": " + t;
            // The banner is decorative: a listener that cannot handle a payload is a missed banner, never a
            // dropped session.
            report("a " + topic + " message drew no banner (" + t + ") — staying connected", true);
        }
    }

    public boolean isConnected() {
        try {
            return client.isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Stands the connection down because the frame should not be talking to a broker right now — the panel went
     * off, or MQTT stopped being configured.
     *
     * <p>This is not the same operation as {@code disconnect()}, and the difference is the reason it exists.
     * With {@code automaticReconnect} on, a client whose link dropped sits in Paho's backoff timer in the
     * <em>DISCONNECTED</em> state, and {@code ClientComms.disconnect()} is guarded on exactly that: it throws
     * 32101 without cancelling the timer. So stopping a reconnecting client by disconnecting it is impossible —
     * the ladder would bring the session back under this frame's stable id, in a quiet window, which is the
     * behaviour this whole change is meant to remove.
     *
     * <p>So: disconnect when the link is live (that is the one path Paho stops the ladder on), and otherwise
     * <b>force-close the object</b>. {@code ClientComms.close(force)} accepts a DISCONNECTED client, and CLOSED
     * is terminal — every connect path out of it throws 32111 — so a timer that fires later has nothing it is
     * allowed to do. This manager is finished after that: {@link #connect()} refuses, and the caller builds a
     * replacement manager rather than reviving this one.
     *
     * <p>Never throws: it is called from a screen-state transition and from {@code onDestroy}.
     */
    public void stop() {
        synchronized (lifecycle) {
            suspended = true;
            final MqttClient c = client;
            try {
                if (c.isConnected()) {
                    c.disconnect(DISCONNECT_QUIESCE_MS);
                }
            } catch (Throwable t) {
                // The link can drop between the test and the call, and then disconnect() throws without
                // cancelling the ladder — which is the whole reason close() is below rather than in an else.
                report("disconnecting on the way down threw, closing instead: " + t, false);
            } finally {
                try {
                    // Unconditional, and force: this is the only call that makes the object inert, so a client
                    // abandoned here would reconnect under this frame's stable id and evict its replacement.
                    // It also covers the clean path, where a successful disconnect leaves Paho's threads and
                    // persistence allocated on an object nobody holds.
                    c.close(true);
                } catch (Throwable t) {
                    report("could not close the MQTT client: " + t, true);
                }
            }
        }
    }

    /** Whether the frame has deliberately stood the link down — {@code /state} says so rather than guessing. */
    public boolean isSuspended() {
        return suspended;
    }

    /**
     * Releases the client for good. Nothing in this repo ever called {@code close()} before, so every Activity
     * recreation abandoned a live client; with {@code automaticReconnect} now on, an abandoned client would
     * reconnect under the same id and the broker would close the successor's session.
     */
    public void closeQuietly() {
        stop();
    }

    /** What {@code /state} serves. Never a credential — the uri is host and port only. */
    public JsonObject stateJson() {
        JsonObject o = new JsonObject();
        o.addProperty("connected", isConnected());
        // Distinguishes "the link is down" from "this frame is deliberately not dialling", which otherwise
        // read identically for the whole of a quiet window.
        o.addProperty("suspended", suspended);
        o.addProperty("uri", uri == null ? "" : uri);
        o.addProperty("clientId", clientId == null ? "" : clientId);
        o.addProperty("connectAttempts", connectAttempts);
        o.addProperty("reconnects", reconnects);
        o.addProperty("connectedAtMs", connectedAtMs);
        o.addProperty("lastMessageAtMs", lastMessageAtMs);
        o.addProperty("lastError", lastError);
        return o;
    }

    /**
     * One line for the durable record. Says "heard nothing for" separately from "not connected", because those
     * are the two failures a frame can report identically and an operator has to tell apart: a dead link, and a
     * live link whose subscription went missing.
     */
    public String describe() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        if (suspended) {
            sb.append("held (panel off)");
        } else {
            sb.append(isConnected() ? "connected" : "down");
        }
        sb.append(" broker=").append(uri == null ? "(unset)" : uri)
                .append(" id=").append(clientId == null ? "(unset)" : clientId)
                .append(" attempts=").append(connectAttempts)
                .append(" reconnects=").append(reconnects)
                .append(" heard=").append(lastMessageAtMs == 0L ? "never"
                        : ((now - lastMessageAtMs) / 1000L) + "s ago");
        if (!lastError.isEmpty()) {
            sb.append(" lastError=").append(lastError);
        }
        return sb.toString();
    }

    /**
     * Every knob this client needs, in one place so the reconnect contract is readable and testable without a
     * broker.
     *
     * <p>{@code automaticReconnect} is what lets this class be tied to the panel rather than to the Activity.
     * Before it was set, {@code connectionLost} printed and gave up, and the only caller of {@code connect()}
     * sat on {@code startCoreLoop}'s cold-start branch — which a resume cannot reach, because {@code onPause}
     * deliberately leaves {@code coreLoopStarted} true. One dropped link was therefore terminal for the life of
     * the process, and the frame stayed deaf until someone saved its settings or power-cycled it.
     */
    /** Reason codes that mean a session is arriving, not that a connect was refused. */
    static boolean isAlreadyOnItsWay(int reasonCode) {
        return reasonCode == MqttException.REASON_CODE_CLIENT_CONNECTED
                || reasonCode == MqttException.REASON_CODE_CLIENT_DISCONNECTING
                || reasonCode == MqttException.REASON_CODE_CONNECT_IN_PROGRESS;
    }

    static MqttConnectOptions buildOptions(@Nullable String username, @Nullable String password) {
        MqttConnectOptions options = new MqttConnectOptions();
        // An absent credential means that field is unset on the wire, never "use ours" — see AGENTS.md. This
        // used to hand whatever was configured straight to setUserName(null), and to password.toCharArray(),
        // which threw before a connection was ever attempted.
        if (username != null && !username.isEmpty()) {
            options.setUserName(username);
        }
        if (password != null && !password.isEmpty()) {
            options.setPassword(password.toCharArray());
        }
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setKeepAliveInterval(KEEP_ALIVE_S);
        options.setConnectionTimeout(CONNECT_TIMEOUT_S);
        options.setMaxReconnectDelay(MAX_RECONNECT_DELAY_MS);
        return options;
    }

    private void subscribe() {
        try {
            client.subscribe(TOPIC, SUBSCRIBE_QOS);
        } catch (MqttException e) {
            // A subscription the broker refused is reported, not retried: Paho's own ladder will issue it
            // again on the next connectComplete, and a second subscribe for one topic filter is idempotent
            // in MQTT, so nothing is lost by saying it out loud instead of building a retry path here.
            lastError = String.valueOf(e);
            report("connected but " + TOPIC + " was refused: " + e, true);
        }
    }

    private void report(String message, boolean severe) {
        try {
            if (reporter != null) {
                reporter.mqttEvent(message, severe);
            }
        } catch (Throwable t) {
            // A reporter that throws is not allowed to become a disconnect.
        }
    }
}