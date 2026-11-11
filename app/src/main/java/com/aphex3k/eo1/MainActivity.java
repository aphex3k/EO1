package com.aphex3k.eo1;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP;
import static android.os.PowerManager.SCREEN_DIM_WAKE_LOCK;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.aphex3k.media.MediaAsset;
import com.aphex3k.media.MediaType;
import com.aphex3k.media.web.WebsiteBackend;
import com.aphex3k.eo1.mqtt.MqttManager;
import com.aphex3k.eo1.mqtt.Payload;
import com.aphex3k.eo1.mqtt.PlaybackListener;
import com.aphex3k.update.ApkUploadResult;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.dd.crop.TextureVideoView;
import com.example.tsplayer.TsPlayerNative;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.aphex3k.eo1.trust.MergedTrust;
import com.aphex3k.eo1.trust.RootBundle;
import com.aphex3k.eo1.trust.TrustCensus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.MalformedJsonException;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.TimeZone;

import okhttp3.HttpUrl;

public class MainActivity extends AppCompatActivity implements BrightnessManagerListener, EventManagerListener, SettingsManagerListener, UpdateManagerListener, MediaManagerListener, ConnectionManagerListener, ApiServiceGenerator.ProgressListener, WebController, KioskWatchdog.Host {

    /**
    Amount of milliseconds in a minute
     */
    private static final long MILLIS = 60000;
    private static final long VIDEO_WATCHDOG_POLL_MS = 2000L;
    private static final long VIDEO_STALL_THRESHOLD_MS = 8000L;
    /**
     * Shortest window worth trusting as a playback-rate sample. Deliberately below
     * {@link #VIDEO_STALL_THRESHOLD_MS}, so a clip that is merely slow is reported as slow before
     * it is misread as stalled.
     */
    private static final long VIDEO_RATE_WINDOW_MS = 5000L;
    // The seam's own thresholds (a tail's headway bound and how far past the end a reading is still
    // believed) moved to VideoLoopSeamRule with the decision they tune.
    /**
     * How long a video may take to put its first frame on screen. A clip whose bytes are accepted but
     * never composited raises no error and stops no clock, so a deadline on the picture is the only way
     * to notice that class at all. Set well above this hardware's slowest observed warm-up: a false
     * positive costs one descent down the ladder, never a dropped asset.
     */
    private static final long VIDEO_PAINT_TIMEOUT_MS = 12000L;
    /** How often {@link #VIDEO_PAINT_TIMEOUT_MS} is re-checked while an attempt is live. */
    private static final long VIDEO_PAINT_POLL_MS = 500L;
    /**
     * How long a page may take to show signs of life. Loads that neither paint nor report an error
     * (a black-holed TCP connect) are caught here rather than left covering the screen.
     */
    private static final long WEB_LOAD_TIMEOUT_MS = 30000L;
    /** Minimum gap between download-progress logcat lines. */
    private static final long DOWNLOAD_PROGRESS_LOG_INTERVAL_MS = 5000L;
    /** Denominator of the download bar's fill. Per-mille, so any {@code Content-Length} fits. */
    private static final int DOWNLOAD_BAR_MAX = 1000;
    /** Logcat tag matched by debug.sh follow_logcat filter. */
    private static final String TAG = "EO1";
    /** Tag the options dialog is shown under, and the idempotency key for {@link #showConfigurationUI()}. */
    private static final String OPTIONS_DIALOG_TAG = "OptionsDialog";
    private View lastVisibleView;
    private String lastVisibleAsset = "";
    private String activeVideoAssetId = "";
    private ImageView imageView;
    private TextureVideoView mediaPlayerVideoView;
    private TsVideoView tsVideoView;
    /**
     * The root of the display layout, and the only view this Activity trusts for the panel's size.
     *
     * <p>A video view cannot be the source: {@code onCreate} sets the inactive one to {@code GONE} and
     * {@link #selectVideoPlayer} hides whichever engine is idle, and a {@code GONE} view is never
     * measured — its width stays 0 forever. On an EO2, whose whole point is that one of the two views
     * is hidden while the other plays, that would leave the aspect rule permanently dark. The root is
     * {@code match_parent}, is never hidden, and holds every media view as a {@code fill_parent} child,
     * so its box <em>is</em> the viewport {@code TextureVideoView.cropScaleFactors} is handed — the same
     * number, not a proxy for it.
     */
    private View rootView;
    /** Chrome-less browser view for web-page assets; created on first use only. */
    private WebView webView;
    private String activeWebAssetId = "";
    /** Page currently on screen; "" once it has been abandoned. Its url identifies live callbacks. */
    private String webUrlOnScreen = "";
    /** Set once the current load has painted, so a dead sub-resource is not a failed page. */
    private boolean webLoadProducedPaint;
    private Runnable webLoadWatchdogRunnable;
    /** Sticky: this ROM offered no usable WebView provider, so web assets are skipped from here on. */
    private boolean webViewUnavailable;
    /**
     * The controller currently compositing video. {@code volatile} because {@code /state} reads which
     * engine it is on the web server's thread, and a torn read there says the frame has an engine it
     * has not been given.
     */
    private volatile VideoPlayerController videoPlayer;
    private MediaPlayerController mediaPlayerController;
    private TsPlayerController tsPlayerController;
    private boolean preferTsPlayer;
    /** The three inputs to {@link #preferTsPlayer}, kept so /state can show how it was decided. */
    private String tsSilicon = VideoSilicon.UNKNOWN;
    private String tsGateAxis = "model:denied";
    private boolean tsModelAllows;
    private boolean tsAllowed;
    /**
     * The native engine's own answers, read once and only ever inside the gate.
     *
     * <p>They are state rather than calls because Java evaluates a method's arguments before calling
     * it: writing {@code TsPlayerNative.isAvailable()} inside a log line or a {@code /state} field
     * initializes the class, and its static block runs {@code System.loadLibrary}. On a frame the gate
     * denies, that puts back the exact warning the gate was added to keep off the log — and there are
     * seven such reads here, so guarding one of them is not guarding the path.
     */
    private boolean tsLibraryLoaded;
    private String tsLoadError;
    private String tsBoardPlatform;
    private int tsFallbackCount;
    /**
     * What the panel is holding, and the only source any report may use for a per-clip fact.
     *
     * <p>{@code volatile} because {@code /state} builds its document on a web server worker thread, and
     * a handoff mid-document must not be able to emit half of one asset and half of another — so
     * {@code buildStateJson} reads this into a local <em>once</em> and answers every field from that.
     *
     * <p>Only ever assigned by {@link #panelNowProven}, which is called at a display path's proof point.
     * The display <em>in flight</em> is {@link #attempt}, because deciding to show something and having it
     * on the wall are different instants on every one of these paths.
     */
    /**
     * The panel as one immutable value: what is on it, how many times it has changed hands, and when it
     * last did.
     *
     * <p>One object rather than three fields because {@code /state} reads it on a web worker while the UI
     * thread keeps rotating. Reported separately, the record and its counters can come from different
     * moments — a handoff between the two reads prints a clip's path beside the next display's handoff
     * timestamp, which is the same mixing this change set exists to stop, one field further down.
     */
    private static final class PanelState {
        final PanelDisplay display;
        final long changes;
        final long changedAtMs;

        PanelState(PanelDisplay display, long changes, long changedAtMs) {
            this.display = display;
            this.changes = changes;
            this.changedAtMs = changedAtMs;
        }
    }

    private volatile PanelState panelState;

    /** What the panel provably holds, or null before the first display reaches it. */
    @Nullable
    private PanelDisplay panel() {
        PanelState state = panelState;
        return state == null ? null : state.display;
    }
    /**
     * What a display path is currently putting on the panel, whether or not anything arrived yet.
     *
     * <p>Carries the routing measurement, the file and the rung of the attempt, so {@code /state} can say
     * "this is being tried" ({@code media.attempt}) while {@code media.screen} still names what is provably
     * up — and so the ladder's own bookkeeping (the loop seam's file duration, an engine handover's clip
     * facts) reads the attempt it belongs to rather than a panel that may not have changed over.
     */
    private volatile PanelDisplay attempt;
    /**
     * The record the video controller was last handed, and nothing else.
     *
     * <p>Ownership, not currency. {@code panel} says what is displayed; this says which attempt a player's
     * numbers describe. They differ on a video-to-video change — the outgoing clip is still the panel while
     * the controller already plays the incoming one — and reading position from one to answer a report about
     * the other is the mix this field exists to make impossible.
     *
     * <p>Its lifetime is the controller's, not the panel's: it is set when a file is handed over and cleared
     * only by the release or reassignment itself — {@code releaseVideoDisplay()} and {@code
     * selectVideoPlayer}'s release branch. A promotion by a photo, a page or a blank panel leaves it alone on
     * purpose, because that player may be merely hidden and still running, and forgetting its identity there
     * is what made a screen toggle during a pending preview wake to a clip that is playing with nothing
     * naming it. Quoting a released player is prevented by the ownership test in the staleness check, not by
     * clearing a field as a side effect of something else happening.
     */
    private volatile PanelDisplay videoCompositing;
    /**
     * Whether the video surface currently in hand has ever <em>proven</em> content, by either engine.
     *
     * <p>The distinction the derivation needs and a record cannot supply on its own: a clip being attempted
     * has a surface that is VISIBLE and nothing drawn on it. Without this, any re-derivation landing inside a
     * video→video preparation window — a Brightness+ wake, the web screen action, a quiet-hours edge — would
     * adopt the incoming record as the panel and bump the handoff count on a wall still showing the outgoing
     * clip's last frame. Setting it is {@code revealVideoSurface()}'s job, which is the proof point for both
     * engines; it is cleared when a file is handed to a controller and when one is taken away.
     */
    private volatile boolean videoSurfaceProven;
    /** The page a load was started for, so its callbacks can prove the right thing or be ignored. */
    private volatile PanelDisplay webLoadRecord;
    /**
     * The page the browser is actually <em>showing</em> — which is not the page it is loading.
     *
     * <p>One {@code WebView} object is reused for every page, so navigating from A to B leaves A's rendered
     * content on screen until B paints. Conflating the two made a web→web rotation adopt B the instant its
     * view came up: both reports named a page that had not loaded, the handoff count advanced on an
     * unchanged wall, and {@code media.attempt} disappeared because the record had been "adopted". A
     * pending load is an attempt; a painted page is the panel.
     */
    private volatile PanelDisplay webProvenRecord;
    /**
     * Whether the load in flight may still move the surfaces: uncover a photograph on a timeout, abandon the
     * page on an error.
     *
     * <p>Turned off by {@link #retirePendingWebLoad()} when another display becomes the attempt, because a
     * page the frame has stopped waiting for must not act on the wall any more. It says nothing about the
     * page's <em>identity</em>, which stays live so {@code onPageFinished} can still record what the browser
     * ended up painting.
     *
     * <p>Main looper only, and not {@code volatile} because that would be decoration: every way into a
     * display method is posted — {@code MediaManager} hands its display calls to a {@code UiPoster} that is
     * {@code Activity::runOnUiThread} in production, and the fallback paths post the same way — and the
     * writers here are {@code setPanelAttempt}, {@code displayWebsite} and {@code abandonWebPage}, while the
     * readers are the WebView's callbacks and the load watchdog's handler. A future off-thread caller would
     * need to post, not to publish this flag: {@code volatile} would make the write visible and leave the
     * retirement still free to race the display it is meant to follow.
     */
    private boolean webLoadOwnsSurfaces = true;
    /**
     * The one record for "the panel is blank", so repeated reads of an unchanged blank wall are not repeated
     * handoffs. Cleared whenever something real is adopted, which lets a later blank report the time it
     * actually went blank rather than the first time it ever did.
     */
    @Nullable
    private volatile PanelDisplay emptyPanel;
    /** One cached record for a visible image whose asset is not identified — see {@link #unidentifiedPhoto()}. */
    @Nullable
    private volatile PanelDisplay unknownPhoto;
    /** Sequence number for each display <em>attempt</em>, feeding {@link PanelDisplay#generation}. */
    private final AtomicLong panelGeneration = new AtomicLong();
    /**
     * The panel's measured viewport, last published. Every aspect decision in this Activity reads
     * through {@link #currentPanelShape()} and this holds the same value for reporting, so the number
     * an operator reads is the number the predicate used rather than a second opinion about the wall.
     */
    private MediaCompatibility.PanelShape lastFrameShape;
    /** {@link #lastFrameShape} as a word, for the /state field that has always read portrait/landscape. */
    private String lastFrameOrientation;
    /**
     * Whether routing put the asset in hand on TsPlayer. {@code false} means MediaPlayer is the only
     * engine that clip will ever be given, which is what makes the {@code ORIGINAL_REPLAY} rung a
     * second engine rather than a repeat of the first.
     */
    private boolean lastRoutingAllowedTsPlayer = true;
    /**
     * How many display attempts TsPlayer has had at the bytes in hand. {@code ORIGINAL_REPLAY} exists
     * to try an engine that has not been tried, so it may not run once that engine has used up its
     * budget — replaying a deterministic create/surface failure buys nothing but a longer outage.
     * Bounded rather than boolean so a genuinely transient native failure still gets a second turn.
     */
    private int tsAttemptsForActiveAsset;
    /** Attempts TsPlayer is allowed per asset before the ladder must move on. */
    private static final int MAX_ENGINE_ATTEMPTS = 2;
    /**
     * Counts only the loop restarts <em>this app</em> issues on the platform player — from
     * {@code onVideoEnd}, or from the position watchdog's loop seam when a clip never reports an end.
     *
     * <p>It is not a lap counter and cannot be one. {@code setLooping(true)} makes the platform player
     * loop inside itself, and a clip looping that way produces no callback at all, so a perfectly
     * healthy MediaPlayer lap leaves these at {@code 0} / {@code -1} forever. That is why the names
     * carry {@code manual}: an earlier draft reported them as {@code loopRestarts} / {@code loopCycleMs}
     * beside TsPlayer's genuine native timings under one key, which would have read as a stalled clip
     * on every clip that was fine. Liveness on this engine is {@code painted} and
     * {@code playbackRateX100}, not these.
     */
    private long mediaManualLoopRestarts;
    private long mediaManualLoopCycleMs = -1;
    private long mediaManualLoopAtMs = -1;
    /** Measured playback rate of the playing video, x100: 100 is realtime, 60 is 0.6x slow. */
    private int videoPlaybackRateX100 = -1;
    /** Spread of the rate windows since this display was armed, and how many there were. */
    private int videoPlaybackRateMinX100 = -1;
    private int videoPlaybackRateMaxX100 = -1;
    private int videoPlaybackRateSamples;
    private LinearProgressIndicator progressIndicator;
    private MqttManager mqttManager;
    /** Read off the web server's worker ({@code /state}'s parked-attempt predicate) and the watchdog's. */
    private volatile BrightnessManager brightnessManager;
    private HardwareCapabilities capabilities;
    private TextView debugOverlay;
    private EventManager eventManager;
    private UpdateManager updateManager;
    private MediaManager mediaManager;
    private SettingsManager settingsManager;
    /**
     * The debug board: one row per writer's key, each carrying the asset it is about. Keys are the fixed
     * set of writer names, so this cannot grow; what changes is which rows are printable, and that is
     * decided by {@link DebugBoard#visible} from the panel rather than by deleting anything here.
     */
    private final Map<String, DebugBoard.Row> debugInformation = new HashMap<>();
    private final Handler handler = new Handler();
    private PendingIntent pendingIntent;
    private Timer quietHoursTimer = new Timer(true);
    /** Parsed quiet-hour cron windows, replaced atomically on each setupQuietHours() re-arm. */
    private volatile List<CronExpression> quietCronExpressions = new ArrayList<CronExpression>();
    /** Last quiet-state applied by the scheduler; edge-triggering avoids fighting manual toggles. */
    private volatile boolean lastScheduledQuietState = false;
    /**
     * What {@link #writeScreenBrightness(float)} last put on the panel, for /state only. The
     * authority on what the brightness should be is {@link BrightnessManager#currentBrightness()};
     * keeping a second value like this one as an <em>input</em> anywhere is what made the frame
     * dim itself below the user's level. -1 means nothing has been applied yet.
     */
    private volatile float lastAppliedBrightness = -1.0f;
    /**
     * When this process last asserted the panel hold — see {@link #claimDisplayHold()}. 0 before the
     * first assert. {@code /state} pairs it with a live read of the window flag rather than this
     * timestamp, because the question this field exists to answer is "when did we ask for this", and
     * the question that gets a frame fixed is "is it still being held".
     */
    private volatile long screenHoldAtMs = 0L;
    /**
     * How many times the hold had to be ADDED to a window this process already believed it held. A
     * first assert is ordinary startup; a repair means the panel went unheld while the app was busy
     * showing it a picture, which is the event {@code /state}'s {@code hold.repairs} reports.
     */
    private volatile int screenHoldRepairs = 0;
    /**
     * True between a legitimate release and the next assert — quiet hours ending, or an Activity
     * re-creation handing its window over. Adding the flag back after one of those is the ordinary
     * wake, not a repair, and counting it as one would write a false "it went dark by itself" line
     * every morning at the end of the quiet window.
     */
    private volatile boolean screenHoldReleased = false;
    private ConnectionManager connectionManager;
    private AppLogger appLogger;
    private WebServer webServer;
    /** True once the stale incoming_* cleanup has run for this process (first onCreate only). */
    private static volatile boolean sStaleIncomingCleaned = false;
    /**
     * onCreate count for this process. A frame that "resets itself" is either having its Activity
     * re-created (this climbs, {@link #PROCESS_START_MS} does not) or restarting (both climb), and
     * /state is the only place left to tell them apart once the device is back on the wall.
     */
    private static volatile int sActivityCreateCount = 0;
    /** Process start, for the same reason: uptime with a low create count means neither happened. */
    private static final long PROCESS_START_MS = System.currentTimeMillis();
    /** Volatile because {@code /state} reads {@code isHeld()} off the web server's thread. */
    private volatile PowerManager.WakeLock screenOffWakeLock;
    /**
     * Reconciles shouldTheScreenBeOn with the real panel. On the EO2 the physical button is a
     * KEY_POWER intercepted by the OS (PhoneWindowManager), so the app's key handlers never fire
     * and the two states drift apart. Broadcasts our own turnScreenOn/turnScreenOff cause arrive
     * with the state already matching and no-op.
     */
    private final BroadcastReceiver displayStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                reconcileScreenState(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
            }
            catch (Exception e) {
                handleException(e);
            }
        }
    };
    private Handler bannerHandler = new Handler(Looper.getMainLooper());
    private Runnable bannerFadeRunnable;
    private String currentTrackId = null;
    private Runnable videoWatchdogRunnable;
    private String videoWatchdogAssetId = "";
    /**
     * The clip this watchdog was armed for, whose file length the loop seam needs for its <em>whole</em>
     * life. Reading it from the live attempt instead lost the duration the moment another display path
     * became the attempt — a render-silent asset chosen mid-playback, or a page that took the panel.
     */
    @Nullable
    private volatile PanelDisplay videoWatchdogRecord;
    private File videoWatchdogFile;
    private WeakReference<MainActivity> videoWatchdogActivityReference;
    /** Which rung of the display ladder the armed watchdog is watching; see {@link DisplayStage}. */
    private DisplayStage videoWatchdogStage = DisplayStage.ORIGINAL;
    private int videoWatchdogLastPosition = -1;
    private long videoWatchdogLastProgressMs = 0L;
    private boolean videoWatchdogRecoverAttempted = false;
    /** Start of the window the playback-rate estimate is measured across. */
    private long videoRateAnchorMs = 0L;
    private int videoRateAnchorPosition = -1;
    /**
     * Start of the <em>previous</em> poll's {@code getCurrentPosition()} call, on
     * {@link android.os.SystemClock#elapsedRealtime()}, so the interval handed to {@link VideoLoopSeamRule}
     * runs from the beginning of one query to the end of the next.
     *
     * <p>Both halves of that sentence are load-bearing, and each was the subject of a review round.
     * <b>Start-to-end rather than end-to-end</b> because a position read back from a player can be as old as
     * the call that produced it: an end-to-end interval is short by the previous query's own duration, and
     * {@link VideoLoopSeamRule}'s wrap test reads a too-short interval as proof that no lap could have
     * completed — a healthy wrap reported as a rewind, on exactly the clips whose lap is near one poll.
     * <b>Monotonic rather than wall</b> for the same reason at one remove: these frames are power-cycled and
     * run no time sync ({@code docs/TRUST.md} gate 4), so a correction can step the wall clock <em>back</em>
     * between two samples and shorten the interval while leaving it positive — which is the one case the
     * rule's own {@code sincePreviousMs > 0} defence cannot see.
     *
     * <p>The stall rung and the rate window deliberately keep the wall clock, and neither is immune to a
     * step — saying "it re-anchors" would be the kind of half-truth this file keeps having to correct. A
     * <em>backward</em> step is where the two differ: {@code measurePlaybackRate} re-anchors whenever
     * {@code now} falls behind its own anchor, so it loses that window's sample, while the bare
     * {@code now - videoWatchdogLastProgressMs} test goes negative and the stall rung simply waits. A
     * <em>forward</em> step reaches both: the rate window's elapsed grows, so it publishes a low rate that
     * the attempt keeps in {@code videoPlaybackRateMinX100} forever — a phantom stall in the one field that
     * is supposed to distinguish a uniformly-late pipeline from a hitching one — and the stall test clears
     * its threshold on a clip that was never stalled. Both exposures are pre-existing, named, and unfixed
     * here: this change is about the one subtraction whose error selects a {@code seekTo(0)} on the same
     * poll, and moving two more measurements onto another clock in the pass that fixes one is how a timing
     * change stops being reviewable. The switch is one argument at one call site whenever it is wanted.
     */
    private long videoWatchdogPrevQueryStartElapsedMs = 0L;
    /**
     * Watches whether the current video display attempt has produced a frame at all. The position
     * watchdog above cannot do this: it is armed from {@code onVideoPrepared} (so an attempt whose
     * {@code prepareAsync()} never returns is watched by nothing) and its only evidence is that the
     * timeline moved, which a decoder that accepts bytes and paints nothing keeps doing indefinitely.
     */
    private Runnable videoPaintRunnable;
    private String videoPaintAssetId = "";
    /**
     * The record this watchdog was armed for, so a frame proving late can only promote the display it
     * belongs to. Read from the panel-wide field instead, a superseded attempt could adopt whatever the
     * next one had put up.
     */
    @Nullable
    private volatile PanelDisplay videoPaintRecord;
    private File videoPaintFile;
    private WeakReference<MainActivity> videoPaintActivityReference;
    private DisplayStage videoPaintStage = DisplayStage.ORIGINAL;
    private long videoPaintStartMs;
    /** Latched once a frame of this attempt is on screen, so a later freeze is not a never-painted. */
    private volatile boolean videoPaintProduced;
    /** Whether the photo underneath has been stepped aside yet for this attempt. */
    private boolean videoPaintRevealed;
    /** Set while an unpainted attempt is parked by a pause or quiet hours, waiting to be resumed. */
    private boolean videoPaintAwaitingResume;
    private long videoPaintPausedMs;
    /** True after Immich slideshow / MQTT / version check have been started for this resume. */
    private boolean coreLoopStarted = false;
    /**
     * The single canonical rotation-timer Runnable. Handler callbacks are matched by Runnable
     * identity, and a method reference (this::runOnTimer) may yield a fresh lambda object on
     * each evaluation — posting one instance and removing a different one would leave a
     * duplicate pending tick (forked timer chain, rapid successive flips).
     */
    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            runOnTimer();
        }
    };
    /** The pending tick was explicitly cleared (onPause / network pause); the next start must re-arm it. */
    private boolean timerStopped = false;
    /**
     * Rotation ticks since process start; a spike here means the timer chain forked.
     *
     * <p>{@code volatile} because the diagnostics capture reads it from its own thread, and a
     * non-volatile {@code long} read across threads is not atomic even where the JIT makes it look so.
     */
    private volatile int timerTickCount = 0;
    /** Epoch ms of the next scheduled rotation tick; 0L when the rotation timer is stopped. */
    private volatile long nextRotationAtMs = 0L;
    /** Avoid spamming "Waiting for network…" while still offline. */
    private boolean networkWaitNotified = false;
    /** Last percent value logged for the in-flight download; -1 until the first progress line. Guarded by downloadProgressLogLock. */
    private long lastLoggedDownloadPercent = -1L;
    /** Epoch ms of the last download-progress logcat line; 0L before the first. Guarded by downloadProgressLogLock. */
    private long lastDownloadProgressLogAtMs = 0L;
    private final Object downloadProgressLogLock = new Object();

    /** Liveness ladder; runs on its own timer thread, so it keeps working if we are wedged. */
    private KioskWatchdog kioskWatchdog;
    /**
     * When the display loop last attempted something — a rotation tick, a manual {@code next}, a
     * display call that reached the panel's door. Diagnostic only: this is the "is the loop turning"
     * half of the pair reported by {@code /state} and a report, and it is stamped by
     * {@link #runOnTimer} one statement <em>before</em> {@code showNextImage} resolves, so it says
     * nothing whatsoever about whether content got on screen. It used to be the clock
     * {@link KioskWatchdog} escalated on, which is how a frame that kept ticking while drawing nothing
     * sat white for 19.8 hours with {@code autoReboot} granted — the timer refreshed the very age the
     * ladder was measuring. See {@link #lastDisplayProvenAtMs}, and do not reconnect it to the ladder.
     */
    private volatile long lastRotationActivityMs = 0L;
    /**
     * When something was last <em>proven</em> on the panel — a Glide resource ready, a page finished,
     * a video surface revealed. This is the clock {@link KioskWatchdog} escalates on, and only a proof
     * point may advance it: a timer tick, a rotation attempt, a display call that never reached the
     * screen and a recovery attempt all leave it alone, because anything that runs on a schedule and
     * moves this field re-creates the self-feeding clock above.
     *
     * <p>One exception, and it is a display rather than an attempt:
     * {@link #noteDisplayUnchanged(String)}, where the rotation lands on the asset already on the panel
     * and there is therefore nothing new to prove. It is not taken on that claim — it asks
     * {@link #reproveTarget()} whether the wall still holds the content, because the retained identity
     * can outlive it. Zero until the first proof, and a wall holding nothing matches nothing, so a
     * process that has never drawn anything cannot exempt itself, however many ticks it has had.
     *
     * <p>What this proves is that the app handed content to its own views on a main thread that was
     * running. It cannot prove that the compositor presented them: a Glide callback fires whether or
     * not surfaceflinger is alive. Per-frame pixel evidence exists only on the MediaPlayer video path
     * ({@code onSurfaceTextureUpdated}), so a wedge that leaves the app healthy and the display dead
     * after one successful draw is still invisible here. That is a narrower blind spot than the one
     * this field replaced, not a closed one.
     */
    private volatile long lastDisplayProvenAtMs = 0L;
    /**
     * {@link #onCreate}'s instant: what the wedge clock measures from while nothing has been proven
     * yet, so a frame that has never reached first draw ages out instead of reading as healthy. Set
     * once per Activity creation and never again — see {@link KioskWatchdog#escalationClockMs}.
     */
    private volatile long displayClockAnchorMs = 0L;
    private volatile long displayProvenCount;
    /** Displays that fell to the next rung of the ladder. A run rate, not a verdict on the library. */
    private volatile long displayFailedCount;
    /** Process restarts this app asked of itself; separates a rung-4 restart from a wedge. */
    private volatile int selfRestartCount;
    /** Start of the in-flight asset download; 0 when nothing is downloading. */
    private volatile long downloadStartedAtMs = 0L;
    /** Last byte-level progress event; lets a dead download stop counting as "in flight". */
    private volatile long downloadProgressAtMs = 0L;
    /**
     * OkHttp's read timeout is 25s, so past this much silence the response body is gone (finished,
     * failed, or abandoned) even though no {@code done} event ever arrived. Without this the marker
     * of one failed download would look like a permanent stall.
     */
    private static final long DOWNLOAD_PROGRESS_SILENCE_MS = 120_000L;
    /**
     * Last ack from the main thread to a watchdog probe. Seeded at construction rather than at 0,
     * because every reader of it is an age: {@code watchdog.mainLoopStallMs} and the durable dump both
     * compute {@code now - this}, so an unseeded field reported the Unix epoch (~56 years) for the gap
     * between {@code webServer.start()} binding and the baseline write in {@code onCreate} — a window
     * that also reopens on every Activity recreation, since the field is per-instance and the server is
     * answering {@code /state} before the baseline lands. DiagnosticsReport tells readers to treat
     * {@code *StallMs} as an age recomputed at capture, so that value was misread by design.
     */
    private volatile long mainLoopAckAtMs = System.currentTimeMillis();
    /** Bumped on the main thread by every probe acknowledgement; see {@link #mainLoopProgressToken()}. */
    private volatile long mainLoopAcks = 0L;
    /** When we first noticed we had no network; 0 while connected. */
    private volatile long offlineSinceMs = 0L;
    /** True while the activity is not in the foreground (settings dialog, screen off by policy). */
    private volatile boolean activityPaused = true;
    private final Runnable mainLoopProbeAck = new Runnable() {
        @Override
        public void run() {
            mainLoopAckAtMs = System.currentTimeMillis();
            mainLoopAcks++;
        }
    };
    /** Guards against two repairs overlapping; the watchdog ticks faster than adbd round-trips. */
    private volatile boolean repairRunning = false;
    /** One vendor-OTA silencing attempt per process — see {@link #silenceVendorUpdater()}. */
    private volatile boolean vendorUpdaterAttempted = false;
    /** Whether this window holds input focus — the precondition for every key binding. */
    private volatile boolean windowFocused = false;
    /** Simple class name of the view holding focus, or "none". Kept on the UI thread only. */
    private volatile String focusedView = "none";
    /** The last key this window was actually offered, so a dead keyboard reads as one in /state. */
    private volatile int lastKeyEventCode = 0;
    private volatile long lastKeyEventAtMs = 0L;

    @SuppressLint({"ServiceCast", "WrongConstant"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootView = findViewById(R.id.rootView);
        // The authority for "how big is this panel" is the layout pass, not a lifecycle callback.
        // onConfigurationChanged runs BEFORE the view tree is re-measured, so reading bounds there
        // can hand back the pre-rotation size, and a panel turned by the ROM post-processor need not
        // produce another focus callback at all. Republishing on every real size change closes both:
        // it is also the only path that survives displayVideo()'s same-path early return, which would
        // otherwise let a one-video pool keep the shape it measured before the frame was moved.
        rootView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = right - left;
                int height = bottom - top;
                if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                    publishFrameShape();
                }
            }
        });
        imageView = findViewById(R.id.imageView);
        mediaPlayerVideoView = findViewById(R.id.videoView);
        tsVideoView = findViewById(R.id.tsVideoView);
        debugOverlay = findViewById(R.id.debugOverlay);
        progressIndicator = findViewById(R.id.progressIndicator);
        // This window needs a focusable view of its own, or focus stays wherever an editor left it:
        // hardware keys are then routed to that editor's input connection and never reach
        // dispatchKeyEvent, which silently kills C, Space and the soft buttons.
        findViewById(R.id.rootView).requestFocus();

        // Detected before the video engine is chosen, not after: which board this is has become one of
        // the inputs to that choice, and a frame that reports its hardware twenty lines too late to
        // have used the answer is a report nobody can trust.
        this.capabilities = HardwareCapabilitiesFactory.detect(Build.DEVICE, Build.MODEL);
        Log.i(TAG, "hardware: " + capabilities.platform() + " (device=" + Build.DEVICE
                + ", model=" + Build.MODEL + ")");

        // Two axes, because they are two different questions: the model profile owns buttons and
        // sensors, the silicon owns the video engine. An i.MX6 frame and an Amlogic one can report the
        // same model string and need different answers, and the reverse is equally true — which is
        // precisely how a working EO2 loses its player to a firmware that renames it. Amlogic silicon
        // permits; anything unresolved defers to the model. See docs/RESEARCH.md §1.3.
        this.tsModelAllows = capabilities.supportsTsPlayer();
        this.tsBoardPlatform = VideoSilicon.boardPlatformProperty();
        this.tsSilicon = VideoSilicon.classify(Build.HARDWARE, Build.BOARD, tsBoardPlatform);
        this.tsAllowed = VideoSilicon.allowsTsPlayer(tsSilicon, tsModelAllows);
        this.tsGateAxis = VideoSilicon.decidingAxis(tsSilicon, tsModelAllows);

        mediaPlayerController = new MediaPlayerController(mediaPlayerVideoView);
        tsPlayerController = new TsPlayerController(tsVideoView);
        // The one place this class touches TsPlayerNative. Everything downstream reads the captured
        // fields, so a denied frame never initializes it and never attempts the load.
        if (tsAllowed) {
            this.tsLibraryLoaded = TsPlayerNative.isAvailable();
            this.tsLoadError = TsPlayerNative.getLoadError();
        }
        preferTsPlayer = BuildConfig.USE_TSPLAYER && tsAllowed && tsLibraryLoaded;
        videoPlayer = preferTsPlayer ? tsPlayerController : mediaPlayerController;
        Log.i(TAG, "video player: " + VideoEngineReport.engineWord(false, preferTsPlayer)
                + " (" + VideoEngineReport.availability(tsAllowed,
                        BuildConfig.USE_TSPLAYER, tsLibraryLoaded, tsLoadError)
                + " gate=" + tsGateAxis + " silicon=" + tsSilicon
                + " profile=" + capabilities.platform() + ")");

        // Hide the inactive SurfaceView/TextureView so only one compositor path is live.
        if (preferTsPlayer) {
            mediaPlayerVideoView.setVisibility(View.GONE);
        } else {
            tsVideoView.setVisibility(View.GONE);
        }

        progressIndicator.setVisibility(View.INVISIBLE);
        progressIndicator.setTrackColor(Color.argb(80,255,255,255));
        progressIndicator.setIndicatorColor(Color.argb(144,255,255,255));

        ViewCompat.setElevation(progressIndicator, ViewCompat.getElevation(imageView)+1);

        if (BuildConfig.DEBUG) {
            progressIndicator.setBackgroundColor(Color.blue(200));
            progressIndicator.setDrawingCacheBackgroundColor(Color.green(255));
        }

        sActivityCreateCount++;
        // Settings first: the brightness manager restores the user's level from its store, and a
        // frame that never restores it is a frame that dims itself after the next restart.
        this.settingsManager = new SettingsManager(this);
        Log.i(TAG, "created: activity #" + sActivityCreateCount + " (uptime "
                + ((System.currentTimeMillis() - PROCESS_START_MS) / 1000L) + "s)");
        this.brightnessManager = new BrightnessManager(this,
                (SensorManager) getSystemService(SENSOR_SERVICE), capabilities, this.settingsManager);
        // Put the restored level on the panel now rather than at the first rotation tick, which can
        // be the whole interval away: a frame that comes back dim is a frame that looks broken.
        applyCurrentBrightness();
        this.eventManager = new EventManager(this, capabilities);
        this.mediaManager = new MediaManager(this, this.settingsManager, this);
        // Before the first pool build, so the crop gate is armed for the asset this frame displays
        // first rather than dark until the next tick. The layout has not run yet at this point, so
        // this call may legitimately publish nothing; onWindowFocusChanged publishes again once it has.
        publishFrameShape();
        this.connectionManager = new ConnectionManager(this);

        // Always-on LAN web server (debug / control / media upload).
        // The process's single AppLogger is built in Eo1Application, so the ring already holds the
        // pre-onCreate history by the time a crash report asks for its lead-up.
        this.appLogger = Eo1Application.loggerFor(this);
        if (this.appLogger == null) {
            // Only reachable if Eo1Application#onCreate itself failed.
            this.appLogger = new AppLogger(this);
        }
        // Loaded before the update manager is acquired, not after. acquire() hands this Activity's
        // SettingsManager to a worker that survived the recreation, and getConfiguration() is a bare
        // field with no lazy read: any check already queued would otherwise see an empty
        // updateManifestUrl, settle as `manifest-url-not-configured`, and — because that path stamps
        // lastCheckedMs like any other — advance the check clock without ever contacting the host. A
        // frame would then silently skip an interval after every rotation of the screen.
        settingsManager.loadConfiguration();
        // Acquired after the logger, not first among the managers: building the update manager
        // reconciles the saved state against the staged files, and that work reports several lines.
        // Before this point those lines had nowhere durable to go — the manager's own listener path
        // writes through this activity's logger, which did not exist yet.
        this.updateManager = UpdateManager.acquire(this, this, this.settingsManager);
        // Said out loud, and written to the log that survives, because this is the one fact about a
        // frame's video path that cannot be recovered afterwards: logcat on a device that has been up for
        // hours is gone, and a panel that lost its preferred engine looks from outside exactly like a
        // panel showing a video that renders nothing. The line above reaches logcat only.
        //
        // Recorded on every launch rather than only on failure. An engine that loaded is a fact with
        // no other durable record — /state says which engine is active now, and nothing says which one
        // this frame booted choosing — so the three ways to end up on MediaPlayer (compiled out, wrong
        // silicon, library failed to load) would otherwise be indistinguishable after a reboot, and
        // only two of them are worth investigating.
        this.appLogger.info(TAG, "video engine: "
                + (preferTsPlayer ? VideoEngineReport.TS_PLAYER : VideoEngineReport.MEDIA_PLAYER)
                + " (" + VideoEngineReport.availability(tsAllowed,
                        BuildConfig.USE_TSPLAYER, tsLibraryLoaded, tsLoadError)
                + ", gate=" + tsGateAxis + ")");
        // What the backlight restore actually found, in the log that survives a reboot. A frame at
        // 0.3 has one of three histories and they are not the same report: a level that came off
        // disk, a level this process is guessing because the store would not answer, and a frame
        // nobody has ever adjusted. logcat will be gone before anyone reads it.
        this.appLogger.info(TAG, "backlight restored: step " + brightnessManager.getMinimumBrightnessStep()
                + " (floor " + brightnessManager.minimumBrightness() + ") source "
                + brightnessManager.stepSource() + ", store " + settingsManager.minimumBrightnessStoreState());
        if (CrashReporter.get() == null) {
            // ...and if its install failed too, the frame would otherwise be back on the framework
            // handler: a crash dialog on an unattended panel, and no relaunch alarm.
            CrashReporter.install(getApplicationContext());
        }
        CrashReporter reporter = CrashReporter.get();
        if (reporter != null) {
            // Either way the reporter points at the one live logger, not a discarded one.
            reporter.setLogger(this.appLogger);
        }
        // Remove stale upload temp dirs from a previously killed process. Only the first
        // onCreate of a given process may do this: after an Activity recreation the process
        // is still alive and an in-flight upload worker may own a live incoming_* dir.
        if (!sStaleIncomingCleaned) {
            sStaleIncomingCleaned = true;
            UploadedMedia.cleanStaleIncomingDirs(UploadedMedia.dirFor(this));
        }
        this.webServer = new WebServer(this);
        this.webServer.start();

        // The configuration was loaded above, before UpdateManager.acquire() — and so, incidentally,
        // before the web server started below, which is the ordering this comment always claimed.
        // configureMqttManager() no longer owns the load, and the update manager's startup
        // reconciliation happens once inside acquire(), before it can be handed any work.

        pendingIntent = PendingIntent.getActivity(
                getBaseContext(),
                0,
                new Intent(getIntent()),
                getIntent().getFlags());

        configureMqttManager();

        mainLoopAckAtMs = System.currentTimeMillis();
        // Start both clocks at process start. The rotation clock is a diagnostic of loop liveness;
        // the anchor is the wedge clock until a proof point takes over, because a frame that has never
        // gotten anything on screen — relaunched into a dead network, which is exactly the 2026-10-13
        // state, or into a wedged surfaceflinger, which is the 2026-11-08 one — must age out and
        // escalate too, not read as "healthy because nothing has happened yet".
        lastRotationActivityMs = mainLoopAckAtMs;
        displayClockAnchorMs = mainLoopAckAtMs;
        this.kioskWatchdog = new KioskWatchdog(this);
        this.kioskWatchdog.start();

        // The physical button on the EO2 is an OS-intercepted KEY_POWER: the display can
        // go dark or light without any key event reaching this activity. Watch for it.
        if (capabilities.supportsPowerButton()) {
            IntentFilter displayStateFilter = new IntentFilter();
            displayStateFilter.addAction(Intent.ACTION_SCREEN_ON);
            displayStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(displayStateReceiver, displayStateFilter);
        }

        silenceVendorUpdater();
    }

    private void configureMqttManager() {
        try {
            // Construct MQTT broker string from settings (loaded in onCreate, before this runs)
            String mqttProtocol = settingsManager.getConfiguration().mqttProtocol;
            String mqttHost = settingsManager.getConfiguration().mqttHost;
            int mqttPort = settingsManager.getConfiguration().mqttPort;
            if (mqttProtocol == null || mqttProtocol.isEmpty()) mqttProtocol = "tcp";
            if (mqttPort <= 0) mqttPort = 1883;

            if (mqttHost == null || mqttHost.isEmpty()) {
                // MQTT stopped being configured — through the options dialog or a /config/import that dropped
                // the host. Let the live client go rather than keep dialling a broker nobody named. Same
                // helper as every other teardown, because closeQuietly() blocks and this runs on the caller's
                // thread; connectMqttIfConfigured() already retired the manager before calling through here,
                // so in that path this is a no-op.
                retireMqttManager();
                mqttBrokerUri = null;
            } else {
                String mqttBroker = String.format("%s://%s:%d", mqttProtocol, mqttHost, mqttPort);

                // Stable and unique per frame, so the broker log names the box instead of minting a new
                // pahoXXXX on every process start. An empty answer means the preferences store would not say,
                // and Paho rejects an empty id outright — so fall back to a generated one, which degrades to
                // "this frame is anonymous" rather than to "MQTT cannot start".
                String clientId = settingsManager.mqttClientId();
                if (clientId.isEmpty()) {
                    clientId = MqttClient.generateClientId();
                    debugInformationProvided(new DebugInformation("mqtt",
                            "no stable client id (settings store unreadable); using " + clientId));
                }
                mqttBrokerUri = mqttBroker;

                this.mqttManager = new MqttManager(mqttBroker, clientId, new PlaybackListener() {

                    @Override
                    public void onPlaybackStarted(Payload payload) {
                        runOnUiThread(() -> {
                            // A transportState of PLAYING with no track is a payload this coordinator can
                            // legitimately send, and onPlaybackStopped below already says so. Without the same
                            // test here the read throws on the UI thread, which reaches CrashReporter and
                            // System.exit(2) — so a broker message could reboot a frame, which AGENTS.md says
                            // this feature may never do.
                            if (payload == null || payload.currentTrack == null) {
                                return;
                            }
                            var duration = payload.currentTrack.duration;
                            var title = payload.currentTrack.title;
                            var artist = payload.currentTrack.artist;
                            var groupName = payload.groupName;
                            var trackId = groupName + "_" + title + "_" + artist + "_" + duration; // crude unique id

                            var titleView = (TextView) findViewById(R.id.songTitleLabel);
                            var artistView = (TextView) findViewById(R.id.songArtistLabel);
                            var deviceLabel = (TextView) findViewById(R.id.deviceLabel);
                            var bannerView = (RelativeLayout) findViewById(R.id.sonosBannerView);

                            titleView.setText(title);
                            artistView.setText(artist);
                            deviceLabel.setText(groupName);
                            bannerView.setVisibility(View.VISIBLE);
                            bannerView.setAlpha(1f);

                            // Cancel previous timer if track changed
                            if (bannerFadeRunnable != null) {
                                bannerHandler.removeCallbacks(bannerFadeRunnable);
                            }
                            currentTrackId = trackId;

                            // Parse duration string (e.g., "03:45")
                            long durationMs = 120 * 1000L;
                            if (duration != null && duration.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
                                String[] parts = duration.split(":");
                                int hours = Integer.parseInt(parts[0]);
                                int min = Integer.parseInt(parts[1]);
                                int sec = Integer.parseInt(parts[2]);
                                durationMs = (hours * 360L + min * 60L + sec) * 1000L;
                            }
                            if (durationMs > 0) {
                                bannerFadeRunnable = () -> {
                                    // Only fade if the same track is still displayed
                                    if (currentTrackId != null && currentTrackId.equals(trackId)) {
                                        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
                                        fadeOut.setDuration(1000);
                                        fadeOut.setFillAfter(true);
                                        bannerView.startAnimation(fadeOut);
                                        bannerView.setVisibility(View.GONE);
                                    }
                                };
                                bannerHandler.postDelayed(bannerFadeRunnable, durationMs);
                            }
                        });
                    }

                    @Override
                    public void onPlaybackStopped(Payload payload) {
                        runOnUiThread(() -> {
                            if (payload == null || payload.currentTrack == null) {
                                return;
                            }
                            var duration = payload.currentTrack.duration;
                            var title = payload.currentTrack.title;
                            var artist = payload.currentTrack.artist;
                            var groupName = payload.groupName;
                            var trackId = groupName + "_" + title + "_" + artist + "_" + duration; // crude unique id

                            // Cancel previous timer if track changed
                            // trackId.equals(), not the other way round: currentTrackId is null until the first
                            // PLAYING banner, and a coordinator that sends a STOP before anything has ever
                            // played threw here on the UI thread — the same System.exit(2) path the guard above
                            // closes. trackId is a concatenation, so it is never null.
                            if (bannerFadeRunnable != null && trackId.equals(currentTrackId)) {
                                bannerHandler.removeCallbacks(bannerFadeRunnable);
                                bannerHandler.post(bannerFadeRunnable);
                            }
                        });
                    }

                    // `this` for the TLS context: an ssl:// broker builds its own, and must make the
                    // same trust decision as every other connection on the frame. The Reporter is this
                    // activity too, because AppLogger is what writes files/eo1-app.log — and every event
                    // Paho delivers lands there rather than on stdout, which reaches neither /logs nor the
                    // buffer CrashSources scrapes.
                }, this, (message, severe) -> reportMqttEvent(message, severe));
            }
        }
        catch (Exception e) {
            handleException(e);
        }
    }

    /** The broker address {@link #configureMqttManager()} dialled, to tell a re-apply from a rebuild. */
    @Nullable private String mqttBrokerUri;

    private void reportMqttEvent(String message, boolean severe) {
        if (severe) {
            Log.w("MQTT", message);
        } else {
            Log.i("MQTT", message);
        }
        debugInformationProvided(new DebugInformation("mqtt", message));
    }

    /**
     * This function is run every time the timer fires.
     */
    private void runOnTimer() {
        timerTickCount++;
        // Clamp: interval is user-editable (the options UI accepts any integer), and a persisted
        // 0/negative value would otherwise reschedule in a tight loop and burn through assets.
        long delayMs = MILLIS * Math.max(1, settingsManager.getConfiguration().interval);
        Log.i(TAG, "runOnTimer: tick " + timerTickCount + " (next in " + delayMs + "ms)");
        handler.postDelayed(timerTick, delayMs);
        nextRotationAtMs = System.currentTimeMillis() + delayMs;
        logScreenAndTimerState();
        if (brightnessManager.getShouldTheScreenBeOn()) {
            applyCurrentBrightness();
            // Loop liveness only, and it must stay that way. Stamping this before the call below
            // means the clock advances whether or not anything reaches the panel, which is exactly why
            // KioskWatchdog reads lastDisplayProvenAtMs instead; wiring this line back into the ladder
            // would re-create the 19.8-hour white frame of 2026-11-08.
            lastRotationActivityMs = System.currentTimeMillis();
            mediaManager.showNextImage(this);
        } else {
            Log.i(TAG, "runOnTimer: skipping showNextImage (screen should be off / quiet hours)");
        }
        // Self-update: interval-gated check, plus auto-retry of a staged-but-uninstalled APK.
        updateManager.checkIfDue();
        updateManager.installStaged(false);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Recorded before anything acts on the key, so a report of "the keyboard stopped working" is
        // answerable from /state alone: if lastKeyAtMs never moves, this window was never offered the
        // key — which is a focus problem, not a mapping one.
        if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
            View focus = getCurrentFocus();
            focusedView = focus == null ? "none" : focus.getClass().getSimpleName();
            lastKeyEventCode = event.getKeyCode();
            lastKeyEventAtMs = System.currentTimeMillis();
            debugInformationProvided(new DebugInformation("key",
                    android.view.KeyEvent.keyCodeToString(event.getKeyCode())
                            + " with focus " + focusedView));
            if (this.eventManager != null && this.eventManager.onKeyDown(event.getKeyCode())) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        this.windowFocused = hasFocus;
        if (hasFocus) {
            View focus = getCurrentFocus();
            this.focusedView = focus == null ? "none" : focus.getClass().getSimpleName();
            // First focus arrives after the window has been laid out, which is the earliest point the
            // panel's pixel size is a fact rather than a zero. Without this the gate would stay dark
            // for the first asset even though onCreate asked for it, and a dark gate is
            // indistinguishable from a frame whose library refused to load.
            publishFrameShape();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The manifest claims `orientation` in configChanges, so a panel that is turned does not
        // recreate this activity — which means the shape MediaManager judges a clip's aspect against
        // would otherwise be the one this process booted with, forever.
        publishFrameShape();
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.activityPaused = false;

        // If this process fired an install and is still alive, the install did not take
        // effect — roll the state back to STAGED so the next tick can retry.
        updateManager.reconcileInstallOutcome();

        if (BuildConfig.DEBUG) { hideSystemUI(); }

        debugOverlay.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);

        if (webView != null && lastVisibleView == webView) {
            webView.onResume();
            // A page left mid-load by onPause gets its fall-through timer back; without it a
            // stalled load would hold the screen with nothing on it. Only one that still owns the
            // surfaces, since retirement leaves the URL standing on purpose (see retirePendingWebLoad)
            // and re-arming a superseded load would hand it the abandon it lost.
            if (webLoadOwnsSurfaces && !webLoadProducedPaint && !webUrlOnScreen.isEmpty()) {
                startWebLoadWatchdog();
            }
        }
        // Same reasoning for a video caught mid-attempt by the options dialog or a screen toggle:
        // pausing dropped its poll, and without this the photo would stay over a running clip for the
        // rest of the interval with nothing left to notice.
        resumeVideoPaintPollIfPending();

        int permissionCheckStorage = ContextCompat.checkSelfPermission(MainActivity.this, WRITE_EXTERNAL_STORAGE);
        if (permissionCheckStorage != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions( MainActivity.this, new String[]{WRITE_EXTERNAL_STORAGE}, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    0);
        }

        if (this.settingsManager.isSetupDialogIfNeeded()) {
            showConfigurationUI();
        } else {
            startCoreLoopIfNetworkReady();
        }

        this.connectionManager.registerListener(this);

        // Re-seeded every resume so a frame that was named before this process started still says
        // which one it is. Unset stays unmentioned: no line, same overlay as before the field.
        String deviceName = deviceName();
        if (!deviceName.isEmpty()) {
            debugInformationProvided(new DebugInformation(
                    SettingsManager.DEBUG_KEY_DEVICE_NAME, deviceName));
        }
        debugInformationProvided(new DebugInformation("version", BuildFacts.version()));
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key),
                this.connectionManager.isNetworkAvailable() ? "connected" : "waiting for network"));
    }

    /**
     * Start Immich slideshow, quiet hours, server check, and MQTT once the link is up.
     * Idempotent for a given resume cycle; no-ops while setup dialog is showing or offline.
     */
    private void startCoreLoopIfNetworkReady() {
        if (settingsManager.isSetupDialogIfNeeded()) {
            Log.i(TAG, "startCoreLoop: skipped (setup dialog needed)");
            return;
        }
        if (!connectionManager.isNetworkAvailable()) {
            Log.i(TAG, "startCoreLoop: network unavailable, pausing");
            pauseCoreLoopForNetwork();
            return;
        }
        if (coreLoopStarted) {
            if (!timerStopped) {
                Log.i(TAG, "startCoreLoop: timer already armed, skipping re-arm");
                return;
            }
            // Timer was cleared in onPause / pauseCoreLoopForNetwork — re-arm at a full
            // interval instead of an immediate second fetch.
            long delayMs = MILLIS * Math.max(1, settingsManager.getConfiguration().interval);
            Log.i(TAG, "startCoreLoop: re-arm timer in " + delayMs + "ms");
            handler.removeCallbacks(timerTick);
            handler.postDelayed(timerTick, delayMs);
            nextRotationAtMs = System.currentTimeMillis() + delayMs;
            timerStopped = false;
            // onPause cancels quietHoursTimer; this resume path must restore the quiet-hours
            // schedule (and the current screen state), otherwise 22:00/07:00 transitions stop
            // firing until a settings save or app restart. Idempotent after a network blip,
            // where the timer was not canceled, and self-corrects a missed transition.
            setupQuietHours();
            applyScheduledScreenState();
            return;
        }

        Log.i(TAG, "startCoreLoop: starting Immich slideshow / quiet hours / MQTT");
        coreLoopStarted = true;
        networkWaitNotified = false;
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), "connected"));

        handler.removeCallbacks(timerTick);
        handler.post(timerTick);
        nextRotationAtMs = System.currentTimeMillis();
        timerStopped = false;
        setupQuietHours();
        // Owns MQTT's lifetime by way of applyScheduledScreenState, which is the one place that decides whether
        // this frame should be showing anything at all. connectMqttIfConfigured() used to be called here
        // instead, on the cold-start branch only — which meant a resume never reached it, and the
        // reconnect this method implies stopped happening the first time the Activity was paused.
        applyScheduledScreenState();
    }

    /**
     * Stop the Immich timer while offline so we do not hammer the server before Wi‑Fi is ready.
     */
    private void pauseCoreLoopForNetwork() {
        Log.i(TAG, "pauseCoreLoop: waiting for network (wasStarted=" + coreLoopStarted + ")");
        // Keep coreLoopStarted=true so reconnect takes the re-arm path (next flip after a
        // full interval) instead of the fresh-start path, which would fire an immediate
        // showNextImage on every Wi-Fi blip.
        handler.removeCallbacks(timerTick);
        timerStopped = true;
        nextRotationAtMs = 0L;
        logScreenAndTimerState();
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), "waiting for network"));
        if (!networkWaitNotified) {
            networkWaitNotified = true;
            Toast.makeText(this, "Waiting for network…", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Whether the broker link should exist right now: MQTT follows the <em>panel</em>, not the Activity.
     *
     * <p>The distinction matters here because a quiet-hours window is not an {@code onPause} — {@link
     * #turnScreenOff()} hides views and releases the display hold without pausing anything, and it is the OS
     * putting the panel to sleep afterwards that eventually does. Gating on the lifecycle instead of on this
     * value gave the feature a lifetime nobody chose: {@code onPause} closed the session, and the resume path
     * that reopens the rotation returns before it ever reaches a connect.
     *
     * <p>Called from {@link #turnScreenOn()} and {@link #turnScreenOff()} rather than from the schedule, because
     * those two are where every panel transition converges — scheduled, toggled, power-button-reconciled and
     * brightness-adjusted alike.
     */
    private void updateMqttForScreenState() {
        if (brightnessManager == null) {
            return;
        }
        if (brightnessManager.getShouldTheScreenBeOn()) {
            // Deliberately no null test on mqttManager here: a manager retired for quiet hours or for a
            // cleared host has to be rebuilt by the code below, and returning early would leave the feature
            // off until the Activity happened to be recreated.
            mqttHeldForPanel = false;
            connectMqttIfConfigured();
        } else {
            // Reported as the reason a frame says "no client", so "quiet hours" and "never configured" are
            // not the same sentence in /state.
            mqttHeldForPanel = true;
            retireMqttManager();
        }
    }

    /**
     * Hands over the current manager: the field is cleared first, and only then is its teardown queued.
     *
     * <p>The order is the whole point. A queued {@code stop()} runs whenever the worker gets to it, so if the
     * field still named that manager, an off→on transition that lands before the worker drains would consult
     * a manager that is still unsuspended and still connected — skip the connect as unnecessary, then get
     * suspended by the stop behind it. MQTT would sit held with the panel lit until something else
     * reconciled it. Retiring the reference first means that wake sees no manager and builds a fresh one,
     * whose dial queues behind the stop rather than racing it.
     *
     * <p>{@code stop()} and not {@code disconnect()}: with automaticReconnect on, a client whose link already
     * dropped is sitting in Paho's backoff timer in the DISCONNECTED state, where {@code disconnect()} throws
     * 32101 without cancelling the timer.
     */
    private void retireMqttManager() {
        final MqttManager retiring = this.mqttManager;
        this.mqttManager = null;
        if (retiring != null) {
            mqttExec(new Runnable() {
                @Override public void run() {
                    retiring.stop();
                }
            });
        }
    }

    private volatile boolean mqttHeldForPanel;

    /**
     * One serial worker for every blocking Paho call.
     *
     * <p>Two reasons, and the second is the one that made this necessary. {@code connect()} and
     * {@code stop()} block — for up to {@code connectionTimeout} and then {@code timeToWait} — and this is
     * reached from key dispatch: {@code EventManager.onKeyDown} → {@link #adjustMinimumBrightness(String)} →
     * {@link #turnScreenOn()} → here. A broker that is merely unreachable would otherwise stall the input
     * thread past Android's ~5 s dispatch timeout and get the process ANR'd, which is precisely the signal
     * {@code KioskWatchdog} escalates on, over a now-playing banner. A single serial queue rather than a
     * thread-per-call also keeps the order: a dial queued before a quiet-hours stop still finishes before it,
     * so the panel going dark cannot be overtaken by its own connect.
     *
     * <p>Every task is wrapped against {@code Throwable}. A throw on this thread would reach the one global
     * {@code UncaughtExceptionHandler}, which calls {@code System.exit(2)} — so a background MQTT worker is
     * the last place in the app that may be allowed to do that.
     */
    private void mqttExec(Runnable job) {
        ExecutorService executor = mqttWorker;
        if (executor == null) {
            synchronized (this) {
                executor = mqttWorker;
                if (executor == null) {
                    executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "mqtt");
                            // Daemon, because a hung connect must never be the reason a dying process
                            // outlives the Activity that started it.
                            t.setDaemon(true);
                            t.setPriority(Thread.MIN_PRIORITY + 1);
                            return t;
                        }
                    });
                    mqttWorker = executor;
                }
            }
        }
        final Runnable guarded = new GuardedMqttTask(job);
        try {
            executor.execute(guarded);
        } catch (Throwable rejected) {
            // Shut down already, after onDestroy queued the final stop. Run it here rather than lose it:
            // a client left unclosed would keep reconnecting under this frame's id.
            guarded.run();
        }
    }

    /** Runs one MQTT job and makes sure nothing it throws can reach the global handler. */
    private final class GuardedMqttTask implements Runnable {
        private final Runnable delegate;

        GuardedMqttTask(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void run() {
            try {
                delegate.run();
            } catch (Throwable t) {
                // An MqttException from connect() has already been reported by MqttManager itself.
                reportMqttEvent("MQTT worker task threw: " + t, true);
            }
        }
    }

    @Nullable private volatile ExecutorService mqttWorker;

    private void connectMqttIfConfigured() {
        try {
            Configuration config = settingsManager.getConfiguration();
            String mqttUser = config.mqttUser;
            String mqttPassword = config.mqttPassword;
            if (mqttPassword == null) {
                mqttPassword = "";
            }
            // A broker the operator changed is not a client this method can retarget: the URI is fixed at
            // construction, inside Paho. Rebuild it — closing the old client first, because two live clients
            // under one id is precisely the mutual kick a stable id makes visible in the broker log.
            String wanted = mqttBrokerUriFromConfig();
            if (wanted == null) {
                // MQTT stopped being configured while this client was live. Checking for an address change
                // first would miss it — there is no address to differ from — and leave the frame dialling a
                // broker the operator removed, so unset is handled on its own terms.
                retireMqttManager();
                mqttBrokerUri = null;
                return;
            }
            // Either no client exists — because the host was cleared and this is its restoration — or the
            // operator pointed at a different broker, or the manager in hand has been stopped. The URI is
            // fixed inside Paho at construction and a closed Paho client cannot be re-opened, so none of the
            // three can be served by connecting the object already in hand: a stopped manager is replaced,
            // never revived, which is also what keeps a callback still in flight from the closed one from
            // ever reaching the client that took its place.
            if (this.mqttManager == null || this.mqttManager.isSuspended() || !wanted.equals(mqttBrokerUri)) {
                // Queued, not inline: stop() blocks on a quiesce, and this whole method is reachable from key
                // dispatch by way of turnScreenOn.
                retireMqttManager();
                configureMqttManager();
                if (this.mqttManager == null) {
                    return;
                }
            }
            // Guard rather than let Paho answer 32100. A settings save reaches here through
            // settingsChanged() -> startCoreLoop()'s cold-start branch, and did so before this line existed,
            // which threw into handleException and left the existing session alone.
            if (!this.mqttManager.isConnected()) {
                final MqttManager target = this.mqttManager;
                final String user = mqttUser;
                final String password = mqttPassword;
                mqttExec(new Runnable() {
                    @Override public void run() {
                        if (target.isSuspended()) {
                            return;   // the panel went dark while this was queued
                        }
                        try {
                            target.connect(user, password);
                        } catch (MqttException e) {
                            // A manager replaced between queueing and running is not a failure: it means the
                            // panel toggled or the config changed, and the replacement's own dial is queued
                            // behind this one. Anything else MqttManager has already reported.
                            if (e.getReasonCode() != MqttException.REASON_CODE_CLIENT_CLOSED) {
                                Log.i(TAG, "deferred MQTT connect ended early: " + e);
                            }
                        }
                    }
                });
            }
        } catch (Exception e) {
            handleException(e);
        }
    }

    /** The URI the current configuration asks for, in the same form {@link #configureMqttManager()} builds. */
    @Nullable
    private String mqttBrokerUriFromConfig() {
        // Null-safe because /state reaches this through mqttStateJson, and a collector that throws abandons
        // the section it is in — for a frame that has not finished starting, "no mqtt block" beats no report.
        if (settingsManager == null) {
            return null;
        }
        Configuration config = settingsManager.getConfiguration();
        if (config == null || config.mqttHost == null || config.mqttHost.isEmpty()) {
            return null;
        }
        String protocol = config.mqttProtocol;
        if (protocol == null || protocol.isEmpty()) {
            protocol = "tcp";
        }
        int port = config.mqttPort > 0 ? config.mqttPort : 1883;
        return String.format("%s://%s:%d", protocol, config.mqttHost, port);
    }

    @Override
    protected void onPause() {
        this.activityPaused = true;
        cancelVideoWatchdog();
        pauseVideoPaintPoll();
        cancelWebLoadWatchdog();
        if (webView != null && lastVisibleView == webView) {
            webView.onPause();
        }
        // Keep coreLoopStarted so onResume only re-arms the timer instead of
        // kicking a second immediate showNextImage (cold-start pause/resume thrash).
        handler.removeCallbacks(timerTick);
        timerStopped = true;
        nextRotationAtMs = 0L;
        this.quietHoursTimer.cancel();
        this.quietHoursTimer.purge();
        this.connectionManager.unregisterListener(this);
        // No MQTT here. This call used to close the broker session on every pause — including the pause the
        // OS delivers when a released display hold lets the panel sleep, which is what a quiet-hour onset
        // turns into — and nothing on the resume path could open it again. What owns that connection now is
        // applyScheduledScreenState, which speaks in the same terms the schedule does.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (this.kioskWatchdog != null) {
            this.kioskWatchdog.stop();
        }
        // The update manager is not stopped, stopped only un-listened-to: it is the process's, and its
        // worker outlives this Activity on purpose, because a download or an install does not pause
        // politely for a configuration change. Everything it reports while nobody is attached still
        // reaches files/eo1-app.log.
        UpdateManager.detach(this);
        if (this.brightnessManager != null) {
            // Recreation would otherwise leave the old light-sensor listener firing for an Activity
            // that no longer exists.
            this.brightnessManager.shutdown();
        }
        if (this.mqttManager != null) {
            // Same reasoning, and it became load-bearing the moment Paho started reconnecting by itself:
            // nothing in this repo ever called close(), so every recreation abandoned a live client and its
            // threads — and an abandoned client that now reconnects on its own shows up under this frame's
            // stable id and makes the broker close the successor's session. Closing is also the only way to
            // release the MemoryPersistence those threads hold.
            //
            // Queued rather than called inline: shutdown() lets already-submitted work finish, so a dial still
            // on the worker runs before this stop instead of after it. Calling closeQuietly() here directly
            // would let that queued connect land afterwards and leave a reconnecting client with no owner.
            final MqttManager dying = this.mqttManager;
            this.mqttManager = null;
            mqttExec(new Runnable() {
                @Override public void run() {
                    dying.closeQuietly();
                }
            });
        }
        // Outside the manager guard, and after anything queued above. A frame whose host had already been
        // cleared reaches onDestroy with mqttManager null, and shutting the worker down only inside that
        // test would leave the idle thread alive for the rest of the process on every recreation.
        synchronized (this) {
            if (mqttWorker != null) {
                mqttWorker.shutdown();
                mqttWorker = null;
            }
        }
        // And the same for the display: this instance's window is going away with whatever flags it
        // holds, while the instance replacing it builds a fresh PhoneWindow that carries none. Held
        // dimly and claimed nowhere is precisely the state that reads as a frame dimming itself, so
        // the outgoing instance lets go of its lock here — the new one asserts its own in onCreate.
        releaseDisplayHold();
        if (webView != null) {
            WebView view = webView;
            webView = null;
            view.stopLoading();
            view.destroy();
        }
        if (this.webServer != null) {
            this.webServer.shutdown();
        }
        if (capabilities.supportsPowerButton()) {
            unregisterReceiver(displayStateReceiver);
        }
        super.onDestroy();
    }

    @Override
    public void brightnessChanged(float targetScreenBrightness) {
        writeScreenBrightness(targetScreenBrightness);
    }

    /**
     * The only thing in the app that touches {@code screenBrightness}. It writes exactly what it is
     * handed, because the upward-only {@code Math.max} this replaced was the sole reason a BACK
     * tap survived the next rotation tick — and it stopped working the moment a recreated Activity
     * handed back fresh attributes reading {@code BRIGHTNESS_OVERRIDE_NONE}, which is when the
     * panel dropped to whatever a stale cache held.
     */
    private void writeScreenBrightness(float brightness) {
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.screenBrightness = brightness;
        getWindow().setAttributes(layoutParams);
        lastAppliedBrightness = brightness;
    }

    /**
     * Asks the brightness manager what the panel should be showing and puts that on the hardware —
     * and holds the panel up to show it, whenever the app believes the screen should be on.
     *
     * <p>The two belong together because they answer to the same belief. Written alone, a level is a
     * request the display may ignore: on a device that has drifted into its standby dim state the
     * {@code screenBrightness} override does not drive the backlight at all, which is how a frame
     * came to report {@code applied: 0.80} while showing a near-black picture. Asking twice is free,
     * so no caller has to remember to hold anything.
     */
    private void applyCurrentBrightness() {
        if (brightnessManager.getShouldTheScreenBeOn()) {
            claimDisplayHold();
        }
        writeScreenBrightness(brightnessManager.currentBrightness());
    }

    /**
     * Claims the display for as long as this process believes the screen should be on: the window flag
     * that makes the window manager keep a bright wake lock on our behalf, plus the dim lock that
     * wakes a sleeping panel on the way. Idempotent — repeat calls add no flags, no lock references
     * and no log lines — which is the entire point: a claim is a LEVEL this app must go on holding,
     * not an edge it gets to act on once.
     *
     * <p>Asserting it only from the screen-on transitions is what let a frame that restarts inside
     * on-hours run the rest of its life unheld. {@code shouldTheScreenBeOn} starts true, the
     * quiet-hours scheduler seeds its edge memory from the wall clock, so a process born in a
     * non-quiet minute sees no transition, never runs the wake path, and leaves the panel to the
     * ROM's own standby: dim — far below the user's floor, and immune to {@code screenBrightness} —
     * and then off. Every rung the watchdog had for that frame read as healthy, because rotation,
     * the main loop and the network all were.
     */
    private void claimDisplayHold() {
        final boolean wasFlagged = screenHoldFlagPresent();
        // Nothing to repair on a first assert, and nothing to repair after this process put the panel
        // down on purpose. A flag that went missing with no release in between is the report.
        final boolean expectedToBeAbsent = screenHoldAtMs == 0L || screenHoldReleased;

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        acquireDisplayWakeLock();

        screenHoldAtMs = System.currentTimeMillis();
        screenHoldReleased = false;

        if (!wasFlagged && !expectedToBeAbsent) {
            screenHoldRepairs++;
            // Said out loud, and in the log that survives: this is the frame that went dark by
            // itself, not the one that never came up. logcat holds ~13 minutes.
            String line = "display hold was NOT asserted although the screen should be on"
                    + " — re-asserted (repair #" + screenHoldRepairs + ")";
            if (appLogger != null) {
                appLogger.info(TAG, line);
            }
            debugInformationProvided(new DebugInformation("screenHold", line));
        }
    }

    /**
     * Drops the claim. The only counterpart to {@link #claimDisplayHold()}: quiet hours and the
     * screen-off paths release the panel so the device can do what its own power policy wants.
     */
    private void releaseDisplayHold() {
        Window window = this.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        screenHoldReleased = true;

        PowerManager.WakeLock lock = this.screenOffWakeLock;
        if (lock != null) {
            try {
                if (lock.isHeld()) {
                    lock.release();
                    debugInformationProvided(new DebugInformation("screenOffWakeLock", "released"));
                }
            }
            catch (Exception e) {
                handleException(e);
            }
        }
    }

    /**
     * The wake lock behind the claim, made once per process instead of once per call. Reference
     * counted by default, so an {@code acquire()} from every Activity instance leaves a held lock no
     * later instance can name — and a {@code SCREEN_DIM} lock left held is itself the dimming this
     * claim exists to outrank. Non-reference-counted plus {@code isHeld()} makes assert and release
     * strictly paired, however many times the on-path runs.
     */
    @SuppressLint("WakelockTimeout")
    private void acquireDisplayWakeLock() {
        try {
            PowerManager.WakeLock lock = this.screenOffWakeLock;
            if (lock == null) {
                PowerManager powerManager = (PowerManager) this.getSystemService(POWER_SERVICE);
                if (powerManager == null) {
                    return;
                }
                lock = powerManager.newWakeLock(
                        ACQUIRE_CAUSES_WAKEUP | SCREEN_DIM_WAKE_LOCK, "com.aphex3k.eo1:WAKE_LOCK");
                if (lock == null) {
                    return;
                }
                lock.setReferenceCounted(false);
                this.screenOffWakeLock = lock;
            }
            if (!lock.isHeld()) {
                lock.acquire();
                debugInformationProvided(new DebugInformation("screenOffWakeLock", "acquired"));
            }
        } catch (Exception e) {
            handleException(e);
        }
    }

    /**
     * Whether the window this activity shows actually carries {@code FLAG_KEEP_SCREEN_ON} right now —
     * a read of the live attributes, not a remembered intention, and safe from any thread: it is a
     * field read on the layout params, and it exists so {@code /state} can tell "we asked for 0.8"
     * apart from "we asked for 0.8 into a panel nobody was holding up".
     */
    private boolean screenHoldFlagPresent() {
        try {
            Window window = getWindow();
            if (window == null) {
                return false;
            }
            WindowManager.LayoutParams attributes = window.getAttributes();
            return attributes != null
                    && (attributes.flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void checkForUpdates() {
        this.updateManager.checkForUpdates(this);
    }

    /**
     * If the screen is on, turn it off and vice versa. Handle app logic appropriately
     */
    @Override
    public void toggleScreenOn() {
        this.brightnessManager.toggleShouldTheScreenBeOn();

        if (this.brightnessManager.getShouldTheScreenBeOn()) {
            turnScreenOn();
        }
        else {
            turnScreenOff();
        }
    }

    /**
     * Syncs app screen state with the display when they diverge. Only the physical power
     * button can cause a divergence; every in-app path (F2/web toggle, quiet hours,
     * brightness adjust) already leaves shouldTheScreenBeOn matching the panel it drives.
     */
    private void reconcileScreenState(boolean displayOn) {
        boolean appOn = this.brightnessManager.getShouldTheScreenBeOn();
        if (displayOn && !appOn) {
            Log.i(TAG, "reconcileScreenState: display woken outside app control; turning screen on");
            this.brightnessManager.setShouldTheScreenBeOn(true);
            turnScreenOn();
        }
        else if (!displayOn && appOn) {
            Log.i(TAG, "reconcileScreenState: display slept outside app control; turning screen off");
            this.brightnessManager.setShouldTheScreenBeOn(false);
            turnScreenOff();
        }
    }

    /**
     * Turn off the screen to conserve as much energy as possible without shutting down the device
     */
    private void turnScreenOff() {
        cancelVideoWatchdog();
        releaseDisplayHold();
        // Reconciled here rather than in applyScheduledScreenState, because this and turnScreenOn are the
        // chokepoint every path that changes the panel goes through — the schedule, the web and button
        // toggle, the power-button reconcile, and the brightness adjust. Gated on the scheduled path alone,
        // a frame whose quiet hours had closed the broker link would stay deaf after a Brightness+ woke it.
        updateMqttForScreenState();

        videoPlayer.setVisibility(View.INVISIBLE);
        imageView.setVisibility(View.INVISIBLE);
        // Also stops the page's JavaScript and repaints for the whole quiet window.
        hideWebView();
        // `attempt` deliberately SURVIVES this. It is an identity, not a report: the paint poll holds the
        // in-flight clip's record in videoPaintRecord and re-presents it to revealVideoSurface(), which
        // accepts a record only while it is still the attempt or the panel. Nulling the attempt to keep
        // media.attempt off a dark screen therefore stranded the reveal — the wake's first frame was
        // refused as "superseded", the poll cancelled itself, and the photograph went on covering a clip
        // that was playing, which is a stale panel of the exact shape this change set exists to remove.
        // (onPause parks the same poll and never touched the attempt; that asymmetry was the tell.)
        // What a dark window must not do is imply a transition is landing, so the report says so
        // directly instead: media.attempt carries "parked" while painting is suspended. See buildStateJson.
        // Nothing is visible now, so nothing should be reported: a dark frame in quiet hours used to answer
        // /state with onScreen:true, its clip's measurements and its routing row, because hiding the
        // surfaces was the one panel change that never went through the derivation. The surfaces keep their
        // own identities (provenPhoto, videoCompositing, webProvenRecord) so waking restores the truth.
        reprovePanel();
    }

    /**
     * Turn on the screen and gain control over screen brightness
     */
    private void turnScreenOn() {
        // The claim is asserted here and from applyCurrentBrightness, which covers the paths that
        // never reach a transition at all. Overlapping on purpose: this reveals what turnScreenOff
        // hid, and the reveal has to land on a panel that is being held up.
        claimDisplayHold();

        if (lastVisibleView != null) {
            lastVisibleView.setVisibility(View.VISIBLE);
        }
        if (webView != null && lastVisibleView == webView) {
            // hideWebView() paused the page; making it visible again would not unfreeze it.
            webView.onResume();
        }
        // Whatever was uncovered is visible again, so the panel is re-derived rather than remembered: each
        // surface kept its own identity while the screen was dark, and this is the half of turnScreenOff's
        // honesty that makes the dark interval cost nothing in reporting accuracy.
        reprovePanel();
        // A video whose first frame was still owed when quiet hours began: its surface was hidden
        // along with everything else, so the deadline resumes only once the panel can paint again.
        resumeVideoPaintPollIfPending();
        applyCurrentBrightness();
        // The resume half of the pairing in turnScreenOff: this is how a frame that closed its broker link
        // for quiet hours gets it back after a wake that never passed through applyScheduledScreenState.
        updateMqttForScreenState();
    }

    @Override
    public void showConfigurationUI() {
        this.runOnUiThread(() -> {
            Fragment existing = getSupportFragmentManager().findFragmentByTag(OPTIONS_DIALOG_TAG);
            if (existing != null) {
                if (existing.isAdded()) {
                    return;
                }
                // Restored from a recreation and never given its SettingsManager back —
                // OptionsDialogFragment has no no-arg constructor, so it is half-built. Drop it
                // rather than show a second window under the same tag.
                getSupportFragmentManager().beginTransaction()
                        .remove(existing)
                        .commitAllowingStateLoss();
            }
            OptionsDialogFragment dialog = new OptionsDialogFragment(this.settingsManager);
            dialog.show(getSupportFragmentManager(), OPTIONS_DIALOG_TAG);
        });
    }

    /**
     * Steps the brightness floor. Deliberately not gated on a capability: what is platform-specific
     * is the <em>button</em>, and that gate lives where the key is read
     * ({@link EventManager#onKeyDown(int)}). The panel write this ends up triggering is a plain
     * window attribute, available on every device the app runs on — gating it on the button is what
     * left an EO2 permanently at the default floor, with the web page's Brightness+ reporting
     * "fired" while nothing changed.
     */
    @Override
    public void adjustMinimumBrightness(String via) {
        // A tap is also "wake up" — quiet hours may have the panel dark on a level the user
        // already chose. Everything after that is the manager's: it steps, persists, and applies.
        this.brightnessManager.setShouldTheScreenBeOn(true);
        // Woken, not just believed. setShouldTheScreenBeOn no-ops when the belief already says "on",
        // which is the common case, and the case where it matters most: on a frame whose panel was
        // never claimed the tap would otherwise move a number and leave the wall exactly as dark as
        // it was — which is what a web "Brightness+" report of "fired, nothing changed" really is.
        // Revealing comes from here too: a press during quiet hours sets a level on views that
        // turnScreenOff left INVISIBLE, and only this path shows them again.
        turnScreenOn();
        if (!this.brightnessManager.raiseMinimumBrightnessStep(via) && appLogger != null) {
            // The panel moved and the store did not: this level is gone at the next boot, and that
            // is the report this code path exists to answer. logcat alone would not survive it.
            appLogger.error(TAG, "brightness level changed but was NOT persisted on "
                    + capabilities.platform() + " — it will reset to the floor on the next start");
        }
    }

    @Override
    public void showNextImage() {
        if (this.brightnessManager.getShouldTheScreenBeOn()) {
            this.mediaManager.showNextImage(this);
        }
    }

    @Override
    public void openSystemSettings() {
        //noinspection deprecation
        startActivityForResult(new Intent(android.provider.Settings.ACTION_SETTINGS), 0);
    }

    @Override
    public void openUpdateWebsite() {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aphex3k/EO1/releases"));
        startActivity(browserIntent);
    }

    @Override
    public void settingsChanged() {
        coreLoopStarted = false;
        networkWaitNotified = false;
        handler.removeCallbacks(timerTick);
        nextRotationAtMs = 0L;
        mediaManager.invalidatePoolIfStale();
        startCoreLoopIfNetworkReady();
    }

    private void applyScheduledScreenState() {
        if (settingsManager == null || brightnessManager == null) {
            return;
        }

        Configuration configuration = settingsManager.getConfiguration();
        String tzId = configuration.selectedTimeZoneId;
        Calendar localNow = QuietHours.calendarInTimeZone(tzId);
        boolean inQuietHours = QuietHours.isQuiet(quietCronExpressions, localNow);
        boolean shouldBeOn = !inQuietHours;

        Log.i(TAG, "quietHours: tz=" + QuietHours.resolveTimeZone(tzId).getID()
                + " localTime=" + String.format(Locale.US, "%02d:%02d",
                        localNow.get(Calendar.HOUR_OF_DAY), localNow.get(Calendar.MINUTE))
                + " windows=" + quietCronExpressions
                + " inQuiet=" + inQuietHours
                + " screenOn=" + shouldBeOn);

        final boolean changed = brightnessManager.getShouldTheScreenBeOn() != shouldBeOn;
        if (changed) {
            brightnessManager.setShouldTheScreenBeOn(shouldBeOn);
        }

        if (shouldBeOn) {
            // Asserted whenever the schedule speaks, not only on the edge into it. A process born
            // inside a non-quiet minute has no edge to act on — the scheduler seeds its memory of the
            // last state from the clock, and the belief already says "on" — so an edge-only wake
            // leaves it running for days on a panel it never asked to be awake. turnScreenOn is
            // idempotent, so re-running it costs a flag check and nothing else.
            turnScreenOn();
        } else if (changed) {
            // The off path stays edge-triggered on purpose: it hides the views, cancels an armed
            // video paint watchdog and freezes the kiosk page, none of which is safe to re-run every
            // time the loop restarts.
            turnScreenOff();
        }
        logScreenAndTimerState();
    }

    /** Logcat debug lines for the screen's should-be state and the rotation-timer state. */
    private void logScreenAndTimerState() {
        Log.i(TAG, "state: screen should be "
                + (brightnessManager.getShouldTheScreenBeOn() ? "ON" : "OFF"));
        if (timerStopped) {
            Log.i(TAG, "state: rotation timer not running");
        } else {
            long remainingMs = Math.max(0L, nextRotationAtMs - System.currentTimeMillis());
            Log.i(TAG, "state: next rotation in " + ((remainingMs + 999L) / 1000L) + "s");
        }
    }

    private void setupQuietHours() {

        if (this.settingsManager == null) {
            return;
        }

        try {
            quietHoursTimer.cancel();
            quietHoursTimer.purge();
        }
        catch (Exception e) {
            handleException(e);
        }

        final Configuration configuration = this.settingsManager.getConfiguration();
        final String tzId = configuration.selectedTimeZoneId;

        final List<CronExpression> expressions = new ArrayList<>();
        for (String raw : configuration.quietHoursOrEmpty()) {
            CronExpression parsed = CronExpression.parse(raw);
            if (parsed != null) {
                expressions.add(parsed);
            } else {
                debugInformationProvided(new DebugInformation("quietHours", "Ignored invalid cron expression: " + raw));
            }
        }
        this.quietCronExpressions = expressions;
        this.lastScheduledQuietState = QuietHours.isQuiet(expressions, QuietHours.calendarInTimeZone(tzId));

        if (expressions.isEmpty()) {
            return;
        }

        quietHoursTimer = new Timer(true);

        final TimeZone quietTimeZone = QuietHours.resolveTimeZone(tzId);
        final DateFormat debugDateFormatter = new SimpleDateFormat("MMM d, yyyy HH:mm a", Locale.US);
        debugDateFormatter.setTimeZone(quietTimeZone);

        // Fire at the next whole-minute boundary in wall-clock time, then every minute after.
        // Evaluating the wall clock each tick is DST-correct by construction (a fixed 24h
        // period drifts across DST transitions), and cheap: one calendar fill + a few mask
        // checks per minute. The state only changes at window boundaries, and the state
        // applied there is idempotent, so a stray duplicate tick is harmless.
        final long firstFire = (System.currentTimeMillis() / 60000L + 1L) * 60000L;
        final List<String> summary = new ArrayList<>();
        for (CronExpression expression : expressions) {
            summary.add(expression.raw());
        }
        Log.i(TAG, "Quiet hours schedule armed: " + summary + " (" + quietTimeZone.getID() + ")");

        quietHoursTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    boolean inQuiet = QuietHours.isQuiet(expressions, QuietHours.calendarInTimeZone(tzId));
                    if (inQuiet != lastScheduledQuietState) {
                        lastScheduledQuietState = inQuiet;
                        handler.post(MainActivity.this::applyScheduledScreenState);
                        String key = inQuiet ? "startQuietHours" : "endQuietHours";
                        String text = (inQuiet ? "Quiet hours started at " : "Quiet hours ended at ")
                                + debugDateFormatter.format(new Date())
                                + " (" + quietTimeZone.getID() + ")";
                        debugInformationProvided(new DebugInformation(key, text));
                    }
                }
                catch (Exception e) {
                    handleException(e);
                }
            }
        }, new Date(firstFire), 60000L);

        debugInformationProvided(new DebugInformation("quietHoursSchedule",
                "Quiet hours armed (" + summary + "), checked every minute (" + quietTimeZone.getID() + ")"));
    }


    @Override
    public void updateChecked(Boolean updateAvailable) {
        debugInformationProvided(new DebugInformation("Update Available", String.valueOf(updateAvailable)));
    }

    @Override
    public void handleException(Exception e) {

        this.runOnUiThread(() -> {
            if (e.getClass() == InvalidCredentialsException.class) {
                if (settingsManager.isSetupDialogIfNeeded()) {
                    showConfigurationUI();
                }
                Toast.makeText(MainActivity.this, "User authentication failure. Check your configuration.", Toast.LENGTH_LONG).show();
            }
            if (e.getClass() == MalformedJsonException.class) {
                Toast.makeText(MainActivity.this, "It appears there is an issue with the format of the configuration file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            if (e.getClass() == AuthenticationFailedException.class) {
                Toast.makeText(MainActivity.this, "Server failed authentication: Invalid username or password.", Toast.LENGTH_SHORT).show();
            }
            if (e.getClass() == AuthenticationUnavailableException.class) {
                Toast.makeText(MainActivity.this, "Server authentication unavailable. Check your server setup.", Toast.LENGTH_SHORT).show();
            }
            if (e.getClass() == NoMediaFoundException.class) {
                Toast.makeText(MainActivity.this, "No Media found", Toast.LENGTH_LONG).show();
            }
            if (e.getClass() == BackendUnavailableException.class) {
                String cause = e.getCause() != null ? String.valueOf(e.getCause()) : "";
                Toast.makeText(MainActivity.this,
                        "Media backend unavailable: " + (cause.isEmpty() ? e.getMessage() : cause),
                        Toast.LENGTH_LONG).show();
            }
        });
        Log.e(TAG, e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : ""), e);

        if (this.appLogger != null) {
            this.appLogger.error(TAG, e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        }
        debugInformationProvided(new DebugInformation("Last Exception", e.toString()));
    }

    /**
     * A backend is running degraded rather than broken: toast it, and let
     * {@link #debugInformationProvided} record it where it outlives logcat's ~13 minute
     * ring (the /logs page and, in debug builds, the overlay).
     */
    @Override
    public void warnUser(String message) {
        this.runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
        debugInformationProvided(new DebugInformation("compatibility", message));
    }

    public void debugInformationProvided(@NonNull DebugInformation debugInformation) {

        // The subject is what keeps a late line off the board. It is declared where the writer knows the
        // asset (the media pipeline's verdicts, the player's own callbacks) and resolved to whatever the
        // panel holds otherwise, which is the best available answer for a display path. A row about the
        // machine rather than the wall says so — by key name in DebugBoard.FRAME_SCOPED, or, when the key
        // is computed and no name could list it, through DebugInformation.ofFrame. The durable log
        // below is written unconditionally and unfiltered: it is a transcript of events, not a claim about
        // what is on the wall right now, and a verdict the board hides has to be readable somewhere.
        final String subject = DebugBoard.subject(debugInformation, currentPanelAssetKey());
        final boolean videoScoped =
                DebugBoard.videoScoped(debugInformation.getKey(), debugInformation.isVideoScoped());

        this.runOnUiThread(() -> {
            synchronized (this.debugInformation) {
                this.debugInformation.put(debugInformation.getKey(),
                        new DebugBoard.Row(debugInformation.getValue(), subject, videoScoped));
            }
            renderDebugBoard();
        });
        Log.i(TAG, debugInformation.getKey() + ": " + debugInformation.getValue());
        if (this.appLogger != null) {
            this.appLogger.info(TAG, debugInformation.getKey() + ": " + debugInformation.getValue());
        }
    }

    /**
     * Writes a line to the durable record and to logcat, without a board row.
     *
     * <p>{@code files/eo1-app.log} is promised as an unchanged transcript: every diagnostic this app emits is
     * supposed to be readable there long after the panel moved on. Making the routing sentence a <em>derived</em>
     * board row — which is what stops a clip's coded size and bitrate being printed over a photograph of the
     * same asset — also stopped it being written anywhere durable at all, since derived rows never pass
     * through a writer. Once the clip left the panel and logcat's ~13 minutes rolled over, the routing
     * decision for it was gone from {@code /logs} and from the file alike. This is the seam between "visible
     * on the board now" and "recorded forever", and it is not optional for anything the docs promise.
     */
    private void logDurable(String key, String value) {
        Log.i(TAG, key + ": " + value);
        if (this.appLogger != null) {
            this.appLogger.info(TAG, key + ": " + value);
        }
    }

    /**
     * Records why an asset did not make it onto the panel.
     *
     * <p>Frame-scoped, deliberately, and with the asset named in the text rather than as the row's subject.
     * A failure explanation is a fact about the machine, not about what is on the wall: the sentence saying
     * "this clip put no frame on the screen in 30s" is the one an operator needs precisely <em>after</em>
     * recovery has uncovered a photograph of some other asset, at which point a row attributed to the failed
     * clip would be filtered out as not-about-the-panel. Keeping it asset-attributed would make every
     * recovery swallow its own diagnosis; keeping it unattributed-but-forever would reintroduce the stale
     * line this whole change set exists to remove. Naming the asset inside a frame-scoped row is the only
     * combination that does neither.
     */
    private void noteDisplayFailure(@Nullable String assetKey, String what) {
        debugInformationProvided(new DebugInformation("displayFailure",
                (assetKey == null || assetKey.isEmpty() ? "(no asset)" : assetKey) + ": " + what, null));
    }

    /**
     * Repaints the debug overlay from the rows that may be printed right now.
     *
     * <p>Called both when a writer adds a row and when the panel changes, because what is <em>printable</em>
     * depends on the panel as much as on the rows: an asset's line has to appear the moment that asset is
     * displayed and disappear the moment it is not, and only one of those two events is a write.
     */
    private void renderDebugBoard() {
        if (!BuildConfig.DEBUG) {
            return;
        }
        runOnUiThread(() -> {
            Map<String, String> printable;
            synchronized (this.debugInformation) {
                printable = DebugBoard.visible(this.debugInformation, panel());
            }
            // Shortest first, as the board has always ordered itself. Inserting into a list rather than
            // sorting is the original behaviour and is kept: the layout is read off the wall.
            ArrayList<String> debugList = new ArrayList<>();
            for (Map.Entry<String, String> info : printable.entrySet()) {
                String text = info.getKey().trim() + ": " + info.getValue().trim() + "\n";
                int index = debugList.size();
                for (String s : debugList) {
                    if (s.length() > text.length()) {
                        index = debugList.indexOf(s);
                        break;
                    }
                }
                debugList.add(index, text);
            }
            StringBuilder debugText = new StringBuilder();
            for (String text : debugList) {
                debugText.append(text);
            }
            this.debugOverlay.setText(debugText.toString().trim());
        });
    }

    /**
     * Records what a display path is <em>putting on</em> the panel, without claiming it arrived.
     *
     * <p>The distinction is the whole point of the two fields. A display call and a visible change are not
     * the same instant: Glide keeps the previous bitmap until the new drawable lands, {@code MediaPlayer}
     * keeps the photograph visible until a frame is proven to exist ({@code revealVideoSurface}), and a web
     * page renders some time after its view is shown. Stamping the panel at the decision made {@code /state}
     * report the incoming asset while the wall still held the outgoing one — and advanced
     * {@code media.screen.generation} on a panel that had not changed, which is the exact signal that field
     * exists to give. The attempt carries the routing measurement and the file being tried, so a watchdog or
     * a diagnosis can still ask what this attempt was measured at before anything is on screen.
     */
    private void setPanelAttempt(@NonNull PanelDisplay next) {
        attempt = next;
        // Every display path goes through here, which makes it the one place a superseded web load can be
        // retired. A load that is no longer what this frame is putting up has lost its standing to change the
        // surfaces: its watchdog calls abandonWebPage(), which uncovers the retained photograph and replaces
        // the video's pending attempt with it, and that video's first-frame reveal then fails
        // mayBeRevealed() — so the photo covers a clip that is playing, which is the failure this whole
        // change set was written to make impossible, arriving from a callback about an asset nobody asked
        // for any more.
        PanelDisplay loading = webLoadRecord;
        if (loading != null && loading != next) {
            retirePendingWebLoad();
        }
        // A row written while this attempt is in flight is about it, not about the panel it has not yet
        // replaced — so the printable-subject resolution has to see the attempt too. Re-render on the way
        // in, so a verdict recorded for the incoming asset appears the moment that asset is the attempt.
        renderDebugBoard();
    }

    /**
     * Ends a page load this frame has stopped waiting for: its deadline is cancelled and its failure path is
     * closed, and nothing else.
     *
     * <p>The distinction is the whole method. A superseded load has lost the standing to <em>change</em> the
     * surfaces — no timeout may uncover a photograph over the display that replaced it, no error may abandon
     * a page nobody is waiting for. It has not lost the fact that it is the page in the browser view: the
     * WebView keeps loading and keeps painting, and often keeps covering the image view while a newer
     * photograph is still being fetched. Clearing {@code webLoadRecord} and {@code webUrlOnScreen} here (an
     * earlier revision of this method did) silenced {@code onPageFinished}, so {@code webProvenRecord} went on
     * naming the PREVIOUS page while the view was painting the new one — a stale report of the kind this
     * change set exists to eliminate, created by the fix for one. So the identities survive retirement, and
     * {@code onPageFinished} still records its page as the browser's content and promotes it while the browser
     * is genuinely the topmost visible surface, which is a true statement about the wall whatever the next
     * display is fetching.
     *
     * <p>{@link #abandonWebPage()} does clear them, because there the page really is being given up on.
     */
    private void retirePendingWebLoad() {
        cancelWebLoadWatchdog();
        webLoadOwnsSurfaces = false;
    }

    /**
     * The panel now holds {@code proven}, and both surfaces say so.
     *
     * <p>Called from {@link #noteDisplayProven()} — each display path's proof point, and nowhere else — and
     * from the recovery paths that uncover a surface whose content is already loaded, where uncovering
     * <em>is</em> the handoff. Promotion is what retires the previous asset's measurements: they live in the
     * record being replaced, and a row about a departed asset stops being printable because of who it is
     * about, not because anything deleted it.
     */
    private void panelNowProven(@NonNull PanelDisplay proven) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> panelNowProven(proven));
            return;
        }
        // A display that is already the panel has not "changed hands", however many paths arrive at that
        // conclusion. Counting a re-promotion as a handoff makes media.screen.generation lie about the one
        // thing it exists to show: a video error can uncover the saved photograph from two rungs of the same
        // descent, and the wall moved once.
        PanelState before = panelState;
        if (before == null || before.display != proven) {
            // One write, so a reader can never pair this display with a later handoff's counters. Counting
            // here rather than stamping the record keeps records copy-free — an earlier revision minted a
            // new one at adoption, and every "does this callback still own the panel?" test silently stopped
            // matching. `attempt` is likewise left alone: clearing it at adoption emptied every answer to
            // "what was this clip measured at?", which is asked precisely <em>after</em> the first frame.
            panelState = new PanelState(proven, (before == null ? 0L : before.changes) + 1L,
                    System.currentTimeMillis());
        }
        // Ownership follows the controller's <em>lifecycle</em>, not the panel's. A video taking the panel
        // reclaims the record — an interleaved promotion by another path used to leave the staleness test
        // permanently true, silently dropping every live reading of a clip that was in fact playing — but a
        // non-video promotion must leave `videoCompositing` alone: the player it identifies may be merely
        // hidden, and forgetting its identity there is what made a screen toggle during a pending preview
        // wake to a surface that is playing again and a report that says nothing is on the panel.
        // Invalidation happens in releaseVideoDisplay(), at the stop/release/reassignment itself.
        if (proven.showsVideo()) {
            videoCompositing = proven;
        }
        // A painted page becomes what the browser is showing; a pending load never does. webLoadRecord keeps
        // identifying the load in flight, for callbacks to prove against, and is untouched here.
        if (proven.type == MediaType.WEB) {
            webProvenRecord = proven;
        }
        if (proven.type != null) {
            emptyPanel = null;
        }
        renderDebugBoard();
    }

    /**
     * The panel, as the view tree says it is — which is the only authority on the question.
     *
     * <p>Seven review rounds of this change set produced the same finding over and over in a different
     * costume: a path would release or hide a surface, and the record of what is on screen would be updated
     * somewhere else, at some other time, by whichever call site remembered. {@code displayPicture} tears
     * the outgoing player down <em>before</em> loading anything; {@code displayWebsite} does the same to a
     * video and then waits for a page; a MediaPlayer display hides the browser at prepared and paints its
     * first frame much later; {@code Glide.clear} empties the image view that a moment earlier had been
     * reported as uncovered. Each was fixed individually, and each fix created the next finding, because the
     * design was "every teardown must remember to promote".
     *
     * <p>So this asks the views instead. Whichever surface is actually visible is what the panel holds, and
     * the record for it is the one that display path created; if the photo plane is what is left but its
     * drawable has been cleared, the honest answer is that nothing is on the panel. It cannot go stale,
     * because it is not stored. Callers still supply <em>measurements</em> through the records — no view
     * knows a bitrate — but they no longer decide when the panel changed.
     */
    private PanelDisplay reproveTarget() {
        // A wall that is switched off shows nothing, whatever its views are marked as. This has to be the
        // first answer rather than a consequence of turnScreenOff() hiding three surfaces, because a display
        // already in flight does not stop when the panel does: Glide's onResourceReady arrives mid-quiet-hours
        // and makes the image view VISIBLE, onVideoPrepared makes a video surface VISIBLE, and the derivation
        // reads visibility as the definition of "on the wall". Without this rule a frame in the dark reported
        // the photograph that had just loaded — media.screen naming an image beside screenOn:false, with
        // generation advancing on a panel nobody could see, and the edge-triggered off path never running
        // again to correct it. turnScreenOn() re-derives, and the surfaces kept their content, so the truth
        // arrives with the light rather than being remembered across the dark.
        if (panelIsDark()) {
            return blankPanel();
        }
        // Walked in the order the views are actually stacked, because that order is the answer to "what is
        // on the wall". In activity_main.xml both video surfaces are declared <em>before</em> imageView, so
        // a RelativeLayout draws the image on top of them — which is why revealVideoSurface() has to hide
        // the photo for a clip to be seen at all, and why a VISIBLE TsPlayer surface under a VISIBLE image
        // view is a photograph, not a video. The browser is added at indexOfChild(imageView) + 1, above the
        // image. Top to bottom, then: browser, photo, video surfaces.
        //
        // Getting this order backwards was the direct cause of reports naming a covered surface — the one
        // complaint this whole change set opened with — and the reason each check must test visibility
        // rather than "did something load here": a surface that covers the screen is what the viewer sees
        // whether or not it has finished its own work.
        WebView page = webView;
        if (page != null && page.getVisibility() == View.VISIBLE) {
            // A visible browser shows the page it last <em>painted</em>, not the URL it was pointed at — the
            // object is reused, so an A→B rotation shows A all the way through B's load. A browser with
            // nothing painted yet is a black rectangle covering everything under it, which is the honest
            // answer and not the photograph it hides.
            PanelDisplay painted = webProvenRecord;
            return painted != null ? painted : blankPanel();
        }
        // A visible image view <em>covers</em> the video surfaces below it, so this branch must not fall
        // through to them: at startup the layout supplies a visible wallpaper before any asset has loaded,
        // and pressing Brightness+ during the first TsPlayer preparation used to report the covered clip.
        // The answer comes from the same helper the recovery paths use, so a screen and an attempt can
        // never be two records of one surface. Drawing nothing is the one case that legitimately falls
        // through — an ImageView without a drawable is transparent, so what is beneath it is visible.
        if (imageView.getVisibility() == View.VISIBLE) {
            PanelDisplay drawn = imagePlaneRecord();
            if (drawn != null) {
                return drawn;
            }
        }
        // A video surface counts only once it has proven content. Its being VISIBLE is not that: an attempt
        // being prepared has a live surface with nothing drawn on it, and deriving from that would adopt an
        // unproven clip and advance the handoff count while the wall still holds the previous frame. The
        // panel's own record is preferred while it still owns the surface, so an engine switch does not
        // silently re-decide which clip is showing.
        if (videoSurfaceProven) {
            VideoPlayerController player = videoPlayer;
            View videoSurface = player == null ? null : player.getView();
            if (videoSurface != null && videoSurface.getVisibility() == View.VISIBLE) {
                PanelDisplay shownNow = panel();
                if (shownNow != null && shownNow.showsVideo() && shownNow == videoCompositing) {
                    return shownNow;
                }
                if (videoCompositing != null) {
                    return videoCompositing;
                }
            }
        }
        // Nothing on top is showing, and either the image plane is hidden or its drawable has been cleared.
        return blankPanel();
    }

    /**
     * One record for "an image is on the wall and we cannot say which asset it is" — the layout's own
     * wallpaper before the first display, and nothing else. Cached for the same reason {@link #blankPanel}
     * is: promotion is decided by reference, and a fresh record per read turns a re-derive into a handoff.
     */
    private PanelDisplay unidentifiedPhoto() {
        PanelDisplay photo = unknownPhoto;
        if (photo == null) {
            photo = PanelDisplay.image("", "", panelGeneration.incrementAndGet(),
                    System.currentTimeMillis());
            unknownPhoto = photo;
        }
        return photo;
    }

    /**
     * Ends a video display: frees the exclusive Amlogic pipeline and drops the record that controller was
     * handed.
     *
     * <p>Ownership is invalidated <em>here</em>, at the lifecycle event, and never as a side effect of some
     * other surface being promoted. Clearing it on a non-video promotion — which an earlier revision did —
     * throws away the identity of a player that is merely hidden: wake the screen after a toggle during a
     * pending preview and the surface comes back while the derivation has nothing left to name it with, and
     * reports a blank panel over a clip that is playing again.
     */
    private void releaseVideoDisplay() {
        videoPlayer.release();
        videoPlayer.setVisibility(View.INVISIBLE);
        videoCompositing = null;
        // Nothing this surface has drawn can be evidence any more. The INVISIBLE test in the derivation
        // already covers today, which is why clearing here is about keeping the flag's promise true rather
        // than about a reachable misreport: "proven" means this surface drew something since it was last
        // emptied, and a released player has emptied it.
        videoSurfaceProven = false;
    }

    /** The single record for "nothing visible", so re-deriving a blank wall is not a new handoff. */
    private PanelDisplay blankPanel() {
        PanelDisplay blank = emptyPanel;
        if (blank == null) {
            blank = PanelDisplay.nothing(panelGeneration.incrementAndGet(), System.currentTimeMillis());
            emptyPanel = blank;
        }
        return blank;
    }

    /**
     * Re-derives the panel from the view tree and adopts that. Cheap, idempotent, and safe to call after
     * anything that changes a surface's visibility — which is precisely the property the per-path calls to
     * {@link #panelNowProven} never had.
     */
    private void reprovePanel() {
        panelNowProven(reproveTarget());
    }

    /**
     * The photo the panel last proved, so uncovering it can name the right asset.
     *
     * <p>The image view keeps its bitmap across a video or page display — that is the whole design of the
     * layered surfaces — so when a later asset fails and its surface is torn away, what is uncovered is
     * <em>this</em> asset's photograph, not the one that just failed. Attributing the visible photo to the
     * failed asset would put a lie in {@code media.screen.key} and hide the failed asset's own diagnostics
     * behind the wrong subject. Null until something has actually loaded, which is what makes an empty
     * panel reportable rather than guessed at.
     */
    @Nullable
    private volatile PanelDisplay provenPhoto;

    /**
     * The record for these very bytes, when an attempt is already in flight for them — which is how a call
     * that did not just run the routing rule tells the measurement of this clip from the leftovers of
     * another.
     *
     * <p>{@link #fallbackTsPlayerToMediaPlayer} re-displays <em>the same file</em> on the other engine, so
     * what the header probe read still describes it and only the compositor changed. For any other asset
     * there is no answer, and no answer is reported as -1 rather than as the last clip's numbers — which is
     * precisely the mistake this record was built to make impossible.
     *
     * <p>All three fields are asked, in the order a caller would trust them, because the handover clears the
     * owner <em>before</em> this lookup runs ({@code selectVideoPlayer} nulls {@code videoCompositing} in the
     * statement that releases the outgoing controller), and an asset displayed as its render-silent still
     * leaves the panel naming the clip that is actually on the wall while the attempt names the preview. The
     * rule itself is {@link PanelDisplay#carryFrom} so a JVM test can hold it.
     */
    @Nullable
    private PanelDisplay carriedDisplay(String assetId, String stage) {
        return PanelDisplay.carryFrom(assetId, stage, attempt, videoCompositing, panel());
    }

    @Override
    public void displayPicture(File file, String assetId) {
        displayPicture(file, assetId, DisplayStage.ORIGINAL);
    }

    @Override
    public void displayFallbackPicture(File file, String assetId) {
        displayPicture(file, assetId, DisplayStage.FALLBACK);
    }

    private void displayPicture(File file, String assetId, DisplayStage stage) {

        if (file.getAbsolutePath().equals(lastVisibleAsset)) {
            Log.i(TAG, "displayPicture: skip same asset " + assetId);
            // Settle before leaving. A display path that decides there is nothing to do still answered a
            // display request, and whoever made that request may already have claimed an attempt for it — a
            // render-silent clip re-selected while its preview is already on the wall minted a fresh record,
            // resolved to the same cached `_fb.jpg`, and returned here with no load to call back. The attempt
            // then out. revealPhotoView() is the seam for this: it takes the covering surfaces away, names
            // the photo the view actually holds (the cached records, so nothing new is minted and no handoff
            // is counted), and states both moments at once.
            //
            // Only while the panel is lit, though, and the ordering is the reason: this guard is evaluated
            // ABOVE the screen-should-be-on check below, and revealPhotoView() makes three visibility writes.
            // Un-hiding the photo plane during quiet hours undoes what turnScreenOff() just did, for the whole
            // dark window — the schedule acts on edges only, so nothing would hide it again — for no benefit,
            // because no display path mints an attempt in the dark (displayVideo returns at that same check
            // before it gets here), so there is nothing waiting to be settled.
            if (!panelIsDark()) {
                revealPhotoView();
            }
            noteDisplayUnchanged(file.getAbsolutePath());
            return;
        }

        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            Log.i(TAG, "displayPicture: skip (screen should be off) asset=" + assetId);
            return;
        }

        lastRotationActivityMs = System.currentTimeMillis();

        this.runOnUiThread(() -> {
            cancelVideoWatchdog();
            cancelVideoPaintWatchdog();
            // No video is active while an image shows; a stale video callback (incl. one from a
            // null-assetId fallback stream) must not match activeVideoAssetId.
            activeVideoAssetId = "";
            // The attempt, not the panel: Glide leaves the previous bitmap visible until onResourceReady
            // promotes this, and hides the browser view there too. Claiming the photograph here would
            // report an image across a web→image transition while the page was still on the wall — and if
            // both image rungs failed, that claim would stand for the whole interval.
            final PanelDisplay mine = PanelDisplay.image(assetId, file.getAbsolutePath(),
                    panelGeneration.incrementAndGet(), System.currentTimeMillis());
            setPanelAttempt(mine);

            WeakReference<MainActivity> activityReference = new WeakReference<>(this);

            try {
                // Release so a previous fallback video frees the exclusive Amlogic video
                // pipeline before the next asset may run on TsPlayer.
                releaseVideoDisplay();
                // Re-derive from the views rather than deciding here what the teardown uncovered. The player
                // above has just been released and hidden, so from this line the video surface is no longer
                // what is on the wall whatever the next asset turns out to be — and the old answer, keeping
                // the outgoing record until Glide finishes, reported a clip as playing from a controller that
                // had been destroyed two statements earlier, with the staleness test still reporting it
                // readable because its owner had never been invalidated.
                reprovePanel();
                if (!MainActivity.this.isDestroyed()) {
                    Glide.with(this).clear(imageView);
                    // The image view's drawable is now gone — clearing it to release the previous asset's
                    // memory is the point of the call — so an image→image transition has nothing visible on
                    // it until the replacement loads. Naming the outgoing photograph through that window was
                    // the same mistake one layer down: it describes a bitmap that is no longer in the view.
                    reprovePanel();

                    if (MediaTypeHelper.isGifFile(file)) {
                        Glide.with(this)
                                .asGif()
                                .load(file)
                                .centerCrop()
                                .listener(new RequestListener<GifDrawable>() {
                                    @Override
                                    public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model, @NonNull Target<GifDrawable> target, boolean isFirstResource) {
                                        assetFallback(assetId, activityReference, false, file, stage);
                                        return false;
                                    }

                                    @Override
                                    public boolean onResourceReady(@NonNull GifDrawable resource, @NonNull Object model, Target<GifDrawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                                        resource.setLoopCount(GifDrawable.LOOP_FOREVER);
                                        hideWebView();
                                        imageView.setVisibility(View.VISIBLE);
                                        lastVisibleView = imageView;
                                        lastVisibleAsset = file.getAbsolutePath();
                                        noteDisplayProven(mine);
                                        return false;
                                    }
                                })
                                .into(imageView);
                    } else {
                        Glide.with(this)
                                .load(file)
                                .centerCrop()
                                .listener(new RequestListener<Drawable>() {
                                    @Override
                                    public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                                        assetFallback(assetId, activityReference, false, file, stage);
                                        return false;
                                    }

                                    @Override
                                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                                        hideWebView();
                                        imageView.setVisibility(View.VISIBLE);
                                        lastVisibleView = imageView;
                                        lastVisibleAsset = file.getAbsolutePath();
                                        noteDisplayProven(mine);
                                        return false;
                                    }
                                })
                                .dontAnimate()
                                .into(imageView);
                    }
                }
            }
            catch (Exception e) {
                handleException(e);
                assetFallback(assetId, activityReference, false, file, stage);
            }
        });
    }

    // ------------------------------------------------------------- web-page assets

    /** How a media type is named in the {@code /state} payload. */
    /**
     * The word {@code /state} uses for an asset type — one mapping, shared with the video block's
     * {@code showing} field, because two mappers is where a report starts disagreeing with itself.
     */
    private static String mediaTypeName(@Nullable MediaType type) {
        return type == null ? "unknown" : VideoStateJson.mediaTypeName(type);
    }

    /**
     * Displays a configured web page fullscreen in the chrome-less browser view. Unlike the two
     * file-based display methods, whether this succeeded is only known later: a page that fails to
     * load or render is reported back through {@link MediaManager#webDisplayFailed} so the cycle
     * advances to a photo. The page is re-fetched on every call — that is the point of showing it.
     */
    @Override
    public void displayWebsite(String url, String assetId) {
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "displayWebsite: no page address for asset " + assetId);
            return;
        }
        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            Log.i(TAG, "displayWebsite: skip (screen should be off) asset=" + assetId);
            return;
        }

        lastRotationActivityMs = System.currentTimeMillis();

        this.runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }
            cancelVideoWatchdog();
            cancelVideoPaintWatchdog();
            // No video is active while a page shows; a stale video callback must not match.
            activeVideoAssetId = "";
            // Asset handoff: free the exclusive Amlogic video pipeline before the browser view.
            releaseVideoDisplay();
            // That teardown emptied the wall of the one surface that was showing, and the page that replaces
            // it has not been created or loaded yet — so until the browser paints, the photo plane is what a
            // viewer is looking at while the panel record still named a clip and its released controller was
            // still considered readable. A slow page made this the common case, not an edge one.
            reprovePanel();

            if (!ensureWebView()) {
                // No browser view exists to cover anything, so the surface is whatever photo was already
                // underneath — and the player two lines above has just been released. Stamping that here
                // is what keeps this branch from being the one web path that leaves the record naming a
                // clip the panel no longer holds; abandonWebPage already does exactly this for the other
                // way a page can fail.
                revealPhotoView();
                mediaManager.webDisplayFailed(MainActivity.this, assetId);
                return;
            }

            webLoadProducedPaint = false;
            // The record exists <em>before</em> the surface becomes visible. A panel re-derived from the
            // view tree while the browser was showing but its record still named the previous page would
            // adopt that page as what is on screen, so the ownership has to be in place the moment the
            // surface is.
            // The attempt: the browser view is on top, but a page that has not painted is not a page.
            // Promotion waits for the first proof the web path has, which is the same hook every other
            // display uses. A page that never renders is retired by abandonWebPage, which proves the photo
            // it uncovered.
            final PanelDisplay mine = PanelDisplay.web(assetId, url,
                    panelGeneration.incrementAndGet(), System.currentTimeMillis());
            setPanelAttempt(mine);
            // AFTER the attempt is set, because setting it retires the load that was in flight, and retirement
            // is what takes the surfaces away from a page nobody is waiting for. Raising this page's claim
            // before that call would have the retirement pull it straight back down, and a fresh load would
            // be unable to give up on itself if it never rendered. The two identity assignments could sit on
            // either side — retirement no longer touches them — but they belong with the claim they go with.
            activeWebAssetId = assetId;
            webUrlOnScreen = url;
            webLoadOwnsSurfaces = true;
            // The browser client is one object reused across every page, so a callback cannot capture its
            // attempt — it has to be told which load it belongs to, and a callback for a load this frame has
            // since abandoned must not be allowed to adopt the panel for whatever is loading now.
            webLoadRecord = mine;
            // The image stays loaded (and visible) underneath, so a page that fails to render
            // reveals the last photo instead of a black panel.
            showWebView();
            lastVisibleView = webView;
            lastVisibleAsset = url;
            // The page is now the topmost surface, whatever it has painted yet, so it is what the panel
            // holds: re-deriving here is what keeps a photo record from surviving a page that covers it.
            reprovePanel();

            try {
                webView.loadUrl(url);
            } catch (Exception e) {
                Log.w(TAG, "displayWebsite: could not start loading " + url, e);
                abandonWebPage();
                return;
            }
            startWebLoadWatchdog();
        });
    }

    /**
     * Creates the browser view on first use, inserted just above {@link #imageView} so a page is
     * never covered by the wallpaper while staying underneath the debug overlay and progress bar.
     * Deliberately not in the layout file: a frame with no web backend must never pay for a WebView
     * (it drags in the ROM's browser provider on an ~800MB device).
     *
     * @return false when this ROM has no usable WebView provider; remembered, so the failure does
     *         not repeat on every tick
     */
    private boolean ensureWebView() {
        if (webView != null) {
            return true;
        }
        if (webViewUnavailable) {
            return false;
        }
        try {
            WebView view = new WebView(this);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            settings.setTextZoom(100);
            // Fit the page to the 1920x1080 panel instead of a 980px viewport with scrollbars.
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setGeolocationEnabled(false);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(false);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setSaveFormData(false);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setDefaultTextEncodingName("utf-8");

            view.setBackgroundColor(Color.BLACK);
            view.setHorizontalScrollBarEnabled(false);
            view.setVerticalScrollBarEnabled(false);
            view.setOverScrollMode(View.OVER_SCROLL_NEVER);
            // Keys belong to EventManager; a focused WebView would eat them.
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            view.setVisibility(View.INVISIBLE);
            view.setWebViewClient(new KioskWebViewClient());
            view.setWebChromeClient(new KioskWebChromeClient());
            view.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                           String mimetype, long contentLength) {
                    Log.i(TAG, "web: ignored download request for " + url);
                }
            });

            RelativeLayout root = (RelativeLayout) imageView.getParent();
            root.addView(view, root.indexOfChild(imageView) + 1, new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
            webView = view;
            Log.i(TAG, "ensureWebView: kiosk browser view ready");
            return true;
        } catch (Throwable t) {
            // Must not escape: the default handler restarts the process, which on every tick would
            // look like a crash loop caused by the photo pipeline.
            webViewUnavailable = true;
            Log.e(TAG, "ensureWebView: no usable WebView provider on this device; web assets will be skipped", t);
            debugInformationProvided(new DebugInformation("displayFailure", "WebView unavailable for every web asset: " + t, null));
            return false;
        }
    }

    /** Keeps the page inside the kiosk and reports a page that never rendered. */
    private final class KioskWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
            // Stay inside the kiosk: at this API level any other scheme is handed to an external
            // activity (market:, tel:, intent:, mailto:), which would eject the frame from home.
            return !(lower.startsWith("http://") || lower.startsWith("https://"));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!Objects.equals(url, webUrlOnScreen)) {
                return;
            }
            // Three further tests, because "this URL finished" is not the same claim as "this page is on the
            // wall". The browser may have been covered since — hideWebView() ends its claim on the screen but
            // leaves the URL standing, so a photograph that replaced a still-loading page used to be
            // overwritten by that page's completion a few seconds later, describing a hidden browser as what
            // is displayed. And the load in flight may no longer be this one, since the client object is
            // reused across pages.
            PanelDisplay loading = webLoadRecord;
            if (loading == null || !Objects.equals(loading.pathOrUrl, url)) {
                Log.i(TAG, "onPageFinished: " + url + " is not the load in flight; proving nothing");
                return;
            }
            // Two separate claims, and they used to be one. "This page's content is now in the view" is true
            // whether or not anyone can see the view — it is what a later showWebView() will expose — so it is
            // recorded unconditionally. "This page is on the panel" is only true while the browser is the
            // topmost visible surface, and promoting a hidden one would overwrite the photograph that
            // replaced it.
            webProvenRecord = loading;
            // The load itself succeeded, seen or not: a page that rendered while its browser was covered has
            // still met the watchdog's deadline, and letting the timeout fire afterwards would abandon a
            // perfectly good page over a visibility accident.
            webLoadProducedPaint = true;
            cancelWebLoadWatchdog();
            if (view.getVisibility() != View.VISIBLE) {
                Log.i(TAG, "onPageFinished: " + url + " rendered while the browser is hidden; retained only");
                return;
            }
            // Proves <em>this page</em>, not "the current attempt": a page that finishes loading while a
            // photograph is being fetched would otherwise adopt the photograph on the strength of a callback
            // about something else.
            noteDisplayProven(loading);
            Log.i(TAG, "web: rendered " + url);
            debugInformationProvided(new DebugInformation("web", "rendered " + url, activeWebAssetId));
        }

        @Override
        @SuppressWarnings("deprecation") // the non-deprecated overload is API 23+
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (!webLoadOwnsSurfaces) {
                // A load another display superseded keeps its identity (so its completion can still say what
                // the browser painted) but has lost the standing to give up on a page nobody is waiting for.
                // Without this test its error uncovers the retained photograph over the video that replaced it.
                Log.w(TAG, "web: " + failingUrl + " failed (" + errorCode + ") after being superseded; ignored");
                return;
            }
            if (webLoadProducedPaint || !Objects.equals(failingUrl, webUrlOnScreen)) {
                Log.w(TAG, "web: sub-resource failure in " + failingUrl + " (" + errorCode
                        + " " + description + ") — page stays");
                return;
            }
            Log.w(TAG, "web: " + failingUrl + " failed (" + errorCode + "): " + description);
            noteDisplayFailure(activeWebAssetId, "page failed (" + errorCode + "): " + description + " for " + failingUrl);
            abandonWebPage();
        }
    }

    /** An unattended frame must never wait on a script dialog nobody can dismiss. */
    private final class KioskWebChromeClient extends WebChromeClient {

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            result.cancel();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  JsPromptResult result) {
            result.cancel();
            return true;
        }
    }

    /**
     * Gives up on the page that was on screen: the browser view goes away, whatever image is still
     * loaded underneath reappears, and the rotation moves on to the next asset.
     */
    private void abandonWebPage() {
        cancelWebLoadWatchdog();
        String failedAssetId = activeWebAssetId;
        // Given up on, not merely superseded: unlike retirement this does drop the load's identities, apart
        // from webProvenRecord — the page it painted is still what the view will show when it is next up.
        webLoadOwnsSurfaces = false;
        webLoadRecord = null;
        webUrlOnScreen = "";
        activeWebAssetId = "";
        hideWebView();
        // Named with the page that failed, not blank: the asset on the panel has not changed, only the
        // surface covering it — and nothing sweeps the board at all any more. DebugBoard.visible keeps frame
        // rows plus rows whose subject is the asset the panel holds, so stamping this as a NEW asset would
        // have printed the "web: failed …" / "web: timeout …" line's subject as the page that had not failed,
        // and the sentence explaining the blank panel an operator is standing in front of would be filtered
        // out for being about the wrong thing.
        revealPhotoView();
        // Cleared, not set to the image: the screen content changed behind the display methods'
        // same-asset check, so the next tick must be allowed to re-show this very photo.
        lastVisibleAsset = "";
        // A page this frame could not render never reaches assetFallback — it has no bytes and no
        // second engine — so it counts here, or the failure total would read calm on a frame whose
        // every web asset is dead.
        displayFailedCount++;
        if (mediaManager != null) {
            mediaManager.webDisplayFailed(this, failedAssetId);
        }
    }

    /** Shows the browser view and lets its page run. */
    private void showWebView() {
        if (webView == null) {
            return;
        }
        webView.setVisibility(View.VISIBLE);
        webView.onResume();
    }

    /**
     * Hides the browser view and stops its page from consuming the CPU.
     *
     * <p>{@code onPause()}/{@code onResume()} rather than {@code pauseTimers()}/{@code
     * resumeTimers()}: those two are static on API 19 and instance methods from API 29 on, so
     * against this compileSdk they would link to a virtual call that does not exist on the frame.
     */
    private void hideWebView() {
        if (webView == null) {
            return;
        }
        webView.onPause();
        webView.setVisibility(View.INVISIBLE);
        // Deliberately does NOT clear webProvenRecord. Hiding and pausing a WebView leaves its rendered page
        // in the view — that is what onPause means here — so showing it again later exposes that page, not a
        // blank rectangle. Dropping the identity here made a web→photo→web rotation report "nothing on the
        // panel" for the whole of the second page's load, on a wall that was displaying the first one.
        // Nothing covered-by-this-surface risk is introduced: reproveTarget() consults this field only when
        // the browser is the topmost <em>visible</em> surface, which is the check that keeps a hidden page
        // from outranking a photograph.
    }

    private void startWebLoadWatchdog() {
        cancelWebLoadWatchdog();
        webLoadWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                webLoadWatchdogRunnable = null;
                if (webUrlOnScreen.isEmpty() || webLoadProducedPaint) {
                    return;
                }
                Log.w(TAG, "web: " + webUrlOnScreen + " showed nothing within "
                        + WEB_LOAD_TIMEOUT_MS / 1000L + "s; advancing to the next asset");
                noteDisplayFailure(activeWebAssetId, "load timed out for " + webUrlOnScreen);
                abandonWebPage();
            }
        };
        handler.postDelayed(webLoadWatchdogRunnable, WEB_LOAD_TIMEOUT_MS);
    }

    private void cancelWebLoadWatchdog() {
        if (webLoadWatchdogRunnable != null) {
            handler.removeCallbacks(webLoadWatchdogRunnable);
            webLoadWatchdogRunnable = null;
        }
    }

    /**
     * If the image produces an error of any kind during playback attempt, try to switch to the
     * thumbnail. If that fails as well, load the next asset instead
     * @param assetId the asset in question
     * @param activityReference weak reference to the calling activity
     * @param isVideo true if it is a video
     * @param file file to load
     */
    private void restoreImageViewAfterVideoFailure() {
        cancelVideoWatchdog();
        cancelVideoPaintWatchdog();
        releaseVideoDisplay();
        hideWebView();
        // The failed clip is off the panel from here, whatever the next rung does with it — but the
        // <em>asset</em> has not changed, only the surface covering it. Naming it that way is what lets
        // the line explaining the failure survive: a ladder that dead-ends, as every local upload does
        // because an upload has no degraded source, must not leave a video named as what is displayed.
        revealPhotoView();
    }

    /**
     * The asset an undeclared diagnostic is about: the display in flight if one is in flight, since a
     * writer running during an attempt is nearly always talking about that attempt and not about the older
     * content still on the wall. Falls back to the proven panel, and to {@code ""} before either exists.
     */
    private String currentPanelAssetKey() {
        PanelDisplay pending = attempt;
        if (pending != null && !pending.assetKey.isEmpty()) {
            return pending.assetKey;
        }
        PanelDisplay shown = panel();
        return shown == null ? "" : shown.assetKey;
    }

    /**
     * Which rung of the display ladder a display call belongs to. A failure moves strictly
     * down it — never up, never twice on the same rung — so one bad asset cannot spin the
     * rotation loop: {@link #ORIGINAL} → {@link #ORIGINAL_REPLAY} → {@link #FALLBACK} → flagged.
     */
    private enum DisplayStage {
        /** The asset's original bytes, first attempt. */
        ORIGINAL,
        /** The same original bytes again on the other player engine; after this, the fallback. */
        ORIGINAL_REPLAY,
        /** The backend's degraded source: a preview thumbnail or a transcoded stream. */
        FALLBACK
    }

    private void assetFallback(String assetId, WeakReference<MainActivity> activityReference,
                               boolean isVideo, File file, DisplayStage stage) {
        assetFallback(assetId, activityReference, isVideo, file, stage, false);
    }

    /**
     * @param silentDescent true when the rung above was left because it put <b>nothing</b> on the panel
     *                      rather than because it refused the file. Only the paint watchdog can say so,
     *                      and the dead end at the bottom of the ladder means two different things
     *                      depending on it — see {@code MediaManager#displayThumbnailAsset}.
     */
    private void assetFallback(String assetId, WeakReference<MainActivity> activityReference,
                               boolean isVideo, File file, DisplayStage stage,
                               boolean silentDescent) {
        // assetId may be null (a display that came from no pool asset); Objects.equals keeps
        // this guard null-safe (a bare assetId.equals() would NPE and kill the app).
        if (isVideo && !Objects.equals(assetId, activeVideoAssetId)) {
            return;
        }
        // Counted here because this is the one funnel every display failure passes through on its way
        // down the ladder; a photo Glide refused, a page that never painted and a clip that decoded to
        // nothing all arrive at this line. Counted, not judged: a failure that reaches the last rung
        // becomes an incompatibility verdict somewhere else.
        displayFailedCount++;
        playbackFallback(assetId, activityReference, isVideo, file, stage, silentDescent);
    }

    /**
     * Records that content was proven on the panel. Stamped at each display path's proof point and
     * nowhere else — see {@link #lastDisplayProvenAtMs} for why the rotation clock cannot say this.
     *
     * <p>It is also where a display's <em>proof</em> moves the panel record, and it is the only thing that may
     * stamp the clock, because this is the moment the wall actually changed. It is not the only mover of the
     * record: {@link #reproveTarget()} asks the views what is visible and {@code panelNowProven} adopts that,
     * from {@code reprovePanel()} and from {@code stepAwayFrom()}. The two answer different questions and both
     * are honest — this one says "a display just landed", that one says "this is what the surfaces show right
     * now, whatever landed" — and the second exists because a list of paths that must remember to promote was
     * wrong three times over. Glide's {@code onResourceReady},
     * the browser's first painted page, and {@code revealVideoSurface} — which for an engine with no paint
     * signal runs at prepared, and for the platform player only once a frame is proven to exist — are the
     * four handoffs, and each of them calls here. Promoting the attempt rather than the decision is what
     * keeps {@code /state} from reporting a photograph that has not loaded and a clip that has painted
     * nothing, and keeps {@code media.screen.generation} meaning "the panel changed".
     *
     * <p>The callback passes <em>the record it belongs to</em> rather than promoting whatever attempt
     * happens to be current, and that distinction is load-bearing: a page that finishes loading while a
     * photograph is being fetched would otherwise adopt the photograph and claim it had reached the panel
     * on the strength of a callback about something else entirely. Each path proves its own content, and
     * the wall is then described by whichever proof landed last — which is what the wall actually is.
     */
    private void noteDisplayProven(@Nullable PanelDisplay proven) {
        if (proven == null) {
            return;
        }
        lastDisplayProvenAtMs = System.currentTimeMillis();
        displayProvenCount++;
        // The durable "what has this frame been showing" line. Its three per-type predecessors
        // (displayPictures / displayVideo / displayWebsite) were folded into the board's derived row during
        // this change set, and a derived row never reaches AppLogger — so on a release build, where the
        // board itself is compiled out, the log stopped naming any displayed file at all. That is the exact
        // hole logDurable() was introduced to close for the routing line, left open here: after logcat's
        // ~13 minutes roll, "what has this frame shown" was unanswerable, which is the question the durable
        // transcript exists to answer.
        logDurable("on screen", proven.describe());
        if (proven.type == MediaType.IMAGE) {
            // Remembered so a later recovery can name the photograph it is uncovering rather than the asset
            // whose failure made it uncover it.
            provenPhoto = proven;
            if (!proven.assetKey.isEmpty()) {
                // The wallpaper placeholder is superseded by a real image, and must not come back after it.
                unknownPhoto = null;
            }
        }
        if (panelIsDark()) {
            // Everything above still happened: the content is proven to its surface, provenPhoto remembers
            // which photograph that is, and the durable transcript records the load as an event. What must
            // not happen is adopting it as the panel, because a switched-off wall is showing nothing, and
            // {@code /state} naming an image that only becomes visible at the end of quiet hours is the same
            // mislabelled panel this change set exists to prevent. Nothing is lost by waiting: turnScreenOn()
            // re-derives from these surfaces, which kept their content, so the wake reports the truth without
            // any path having to remember that a load finished in the dark.
            return;
        }
        panelNowProven(proven);
    }

    /**
     * The rotation landed on the asset already on the panel, so there was nothing new to prove.
     *
     * <p>This is the one non-proof that may refresh the wedge clock, and it is what keeps a
     * legitimately one-asset library (a single uploaded photo, a single looping clip) off the ladder:
     * re-decoding it every interval to satisfy a watchdog would be the per-duration recreate this
     * codebase already rejects for video.
     *
     * <p><b>The claim, however, belongs to {@code lastVisibleAsset}, and that field is retained.</b> A
     * photograph Glide refused, a clip whose player this path released, and a web page that never
     * painted can each leave the name pointing at content that is no longer on the wall — so renewing
     * on the name alone lets a frame whose every display fails keep its wedge clock warm forever,
     * which is the failure this clock exists to detect. It therefore asks the view tree:
     * {@link #reproveTarget()} is the only authority on what is visible, and it cannot go stale because
     * it is not stored. The clock moves only while the panel genuinely still holds this content.
     *
     * <p>That question subsumes the guard this method used to carry. A process that has never proven a
     * display has nothing on the wall to match — the 2026-11-08 shape, HOME relaunched and never
     * reaching first draw — so it keeps aging and keeps climbing.
     */
    private void noteDisplayUnchanged(String pathOrUrl) {
        if (pathOrUrl != null && pathOrUrl.equals(reproveTarget().pathOrUrl)) {
            lastDisplayProvenAtMs = System.currentTimeMillis();
        }
    }

    /**
     * Moves one asset one rung down the display ladder. Nothing is flagged here: an asset is
     * only incompatible with this device once its {@link DisplayStage#FALLBACK} display failed
     * too, which is the rung below.
     */
    private void playbackFallback(String assetId, WeakReference<MainActivity> activityReference,
                                  boolean isVideo, File file, DisplayStage stage,
                                  boolean silentDescent) {
        // restoreImageViewAfterVideoFailure is called at each transition that stops showing
        // video, never before a rung that immediately starts another video.
        if (assetId == null) {
            Log.i(TAG, "playbackFallback: no asset for " + (file != null ? file.getName() : "?")
                    + "; deferring rotation to next tick");
            if (!isVideo) {
                mediaManager.removeFromCache(file);
            }
            return;
        }
        if (stage == DisplayStage.FALLBACK) {
            // Both rungs failed. Flag it for the operator — who can regenerate the asset's
            // thumbnails in Immich or drop it from the frame's albums — and let the next tick
            // rotate on. Drop the fallback artifact so a fix on the server is picked up at
            // once instead of being served from a poisoned cache.
            Log.w(TAG, "playbackFallback: " + assetId + " failed with its fallback source too;"
                    + " flagging it as incompatible with this device");
            if (isVideo) {
                restoreImageViewAfterVideoFailure();
            } else {
                // The image counterpart, and it matters more here than on the video path: the line below
                // unlinks the file this asset's record points at, so without a restatement the panel
                // report names a path that no longer exists on disk — permanently, for an upload whose
                // backend has no degraded source to fall back to.
                revealPhotoView();
            }
            mediaManager.tagAssetAsIncompatible(assetId);
            mediaManager.removeFromCache(file);
            return;
        }
        // The middle three terms are one predicate with a javadoc; what is local here is the ladder
        // plumbing — that this is the ORIGINAL rung, and that the bytes have to still be on disk.
        if (isVideo && stage == DisplayStage.ORIGINAL && file != null
                && VideoRoutingRule.offersSecondEngineAttempt(preferTsPlayer,
                        lastRoutingAllowedTsPlayer, tsAttemptsForActiveAsset, MAX_ENGINE_ATTEMPTS)
                && mediaManager.isUsableLocalVideo(file)) {
            // The original's bytes are already on disk (routing is content-deterministic, so a
            // re-fetch through /video/playback would be the same bytes): replay them on the
            // other engine rather than re-downloading. ORIGINAL_REPLAY is the rung after this,
            // so a second failure here cannot loop back into another replay.
            Log.i(TAG, "playbackFallback: reusing local original for " + assetId
                    + " on TsPlayer (attempt " + (tsAttemptsForActiveAsset + 1) + " of "
                    + MAX_ENGINE_ATTEMPTS + ")");
            lastVisibleAsset = "";
            displayVideo(file, assetId, DisplayStage.ORIGINAL_REPLAY);
            return;
        }
        if (isVideo) {
            restoreImageViewAfterVideoFailure();
        } else {
            // Same reason as on the terminal rung: the original's bytes are dropped below, and if the
            // degraded source turns out not to exist — every local upload — the display call never runs,
            // leaving the record pointing at the file this call just deleted.
            revealPhotoView();
        }
        MainActivity activity = activityReference.get();
        if (activity != null) {
            mediaManager.displayThumbnailAsset(activity, assetId, isVideo, silentDescent);
        }
        // Local uploads may point at the persistent original in filesDir/uploaded;
        // removeFromCache's file-based guards (cache-dir match + owned-name check) make
        // this a no-op for those files.
        mediaManager.removeFromCache(file);
    }

    private void cancelVideoWatchdog() {
        if (videoWatchdogRunnable != null) {
            handler.removeCallbacks(videoWatchdogRunnable);
            videoWatchdogRunnable = null;
        }
        videoWatchdogAssetId = "";
        videoWatchdogRecord = null;
        videoWatchdogFile = null;
        videoWatchdogActivityReference = null;
        videoWatchdogLastPosition = -1;
        videoWatchdogLastProgressMs = 0L;
        videoWatchdogRecoverAttempted = false;
        videoRateAnchorMs = 0L;
        videoRateAnchorPosition = -1;
        videoWatchdogPrevQueryStartElapsedMs = 0L;
        // No watchdog, no measurement: TsPlayer skips this arm entirely, so leaving the previous
        // MediaPlayer asset's rate standing would attribute it to a clip that was never watched.
        videoPlaybackRateX100 = -1;
        videoPlaybackRateMinX100 = -1;
        videoPlaybackRateMaxX100 = -1;
        videoPlaybackRateSamples = 0;
    }

    private void startVideoWatchdog(String assetId, File file,
                                    WeakReference<MainActivity> activityReference,
                                    DisplayStage stage) {
        cancelVideoWatchdog();
        // A display with no asset behind it cannot be matched by the same-asset guard, so
        // there is nothing for the watchdog to attribute a stall to.
        if (assetId == null) {
            debugInformationProvided(new DebugInformation("video", "watchdog skipped (no asset id)"));
            return;
        }
        // TsPlayer getCurrentTime/duration are unreliable; position stalls false-trigger
        // MediaPlayer fallback and break looping. TsVideoView owns health via status poll.
        if (videoPlayer == tsPlayerController) {
            debugInformationProvided(new DebugInformation("video", "watchdog skipped (TsPlayer)"));
            return;
        }
        // The clip this player was handed — the same authority the panel derivation uses — falling back to
        // the attempt only if ownership was already cleared by an interleaved promotion.
        PanelDisplay playing = videoCompositing != null ? videoCompositing : attempt;
        videoWatchdogRecord = playing != null && playing.showsVideo() && assetId.equals(playing.assetKey)
                ? playing : null;
        videoWatchdogAssetId = assetId;
        videoWatchdogFile = file;
        videoWatchdogActivityReference = activityReference;
        videoWatchdogStage = stage;
        videoWatchdogLastPosition = -1;
        videoWatchdogLastProgressMs = System.currentTimeMillis();
        // Stamped here rather than left at zero so the first poll's interval is a poll and not the device's
        // uptime. Nothing can fire on that poll anyway — there is no previous reading — but an interval that
        // means "since boot" is the kind of value a later edit would have to remember is inert.
        videoWatchdogPrevQueryStartElapsedMs = android.os.SystemClock.elapsedRealtime();
        videoWatchdogRecoverAttempted = false;
        scheduleVideoWatchdogCheck();
    }

    private void scheduleVideoWatchdogCheck() {
        if (videoWatchdogRunnable != null) {
            handler.removeCallbacks(videoWatchdogRunnable);
        }
        videoWatchdogRunnable = this::runVideoWatchdogCheck;
        handler.postDelayed(videoWatchdogRunnable, VIDEO_WATCHDOG_POLL_MS);
    }

    private void runVideoWatchdogCheck() {
        if (videoWatchdogRunnable == null || videoWatchdogAssetId.isEmpty()) {
            return;
        }

        final String assetId = videoWatchdogAssetId;
        final File file = videoWatchdogFile;
        final WeakReference<MainActivity> activityReference = videoWatchdogActivityReference;
        // Read before anything below cancels the watchdog: the ladder rung belongs to the
        // display that is being watched, not to whatever starts after it.
        final DisplayStage stage = videoWatchdogStage;

        if (!assetId.equals(activeVideoAssetId)
                || !brightnessManager.getShouldTheScreenBeOn()
                || videoPlayer.getVisibility() != View.VISIBLE) {
            cancelVideoWatchdog();
            return;
        }

        long queryStartElapsedMs = android.os.SystemClock.elapsedRealtime();
        int position = videoPlayer.getCurrentPosition();
        long now = System.currentTimeMillis();
        long queryEndElapsedMs = android.os.SystemClock.elapsedRealtime();
        measurePlaybackRate(position, now);

        // Both questions below are about the previous poll, so they have to be answered before the
        // bookkeeping replaces those readings.
        int previousPositionMs = videoWatchdogLastPosition;
        // An UPPER bound on the media time between the two position values: start of the previous query to
        // end of this one, not end to end, and on a monotonic clock. VideoLoopSeamRule reads it as "a
        // completed lap could not have happened in that interval", so every error in the SHORTENING direction
        // reports a healthy wrap as a rewind and seeks away a lap that had already restarted itself — and
        // there are two ways to shorten it: a query's own duration, and a backward correction to a wall clock
        // these frames never sync. The wall `now` above stays for the rate window and the stall rung, and
        // videoWatchdogPrevQueryStartElapsedMs says exactly how a step reaches each of them.
        long sincePreviousMs = queryEndElapsedMs - videoWatchdogPrevQueryStartElapsedMs;
        videoWatchdogPrevQueryStartElapsedMs = queryStartElapsedMs;

        int reportedMs = videoPlayer.getDuration();
        // The file's own length, from the record this watchdog was armed for and nothing else. Reading the
        // attempt would be wrong in both directions: a clip being watched may not have reached the wall yet,
        // and a render-silent asset chosen while this clip is still playing replaces the attempt with an
        // image record — which reads back as "no duration known" (-1) and lets a creeping position past the
        // player's shortened 23,000 ms trigger a restart before the file's remaining 23,621 ms have played.
        PanelDisplay watched = videoWatchdogRecord;
        long fileMs = watched != null && watched.clip != null ? watched.clip.fileDurationMs : -1L;
        // The seam decision, its thresholds and the measured position series that shaped them live in
        // VideoLoopSeamRule — which is also the only place they can be tested.
        VideoLoopSeamRule.Decision seam = VideoLoopSeamRule.evaluate(
                position, previousPositionMs, sincePreviousMs, reportedMs, fileMs);

        // Any movement at all re-arms the stall clock, so the 8 s threshold below can only ever be
        // reached by a position that is exactly equal across polls. That is why the seam rule has to
        // notice a tail that creeps or steps backwards: for those there is no backstop behind it.
        if (position != previousPositionMs) {
            videoWatchdogLastPosition = position;
            videoWatchdogLastProgressMs = now;
            videoWatchdogRecoverAttempted = false;
        }

        // The end of the cycle, announced here because nothing else announces it: this is the one line
        // that says the app, not the player, decided to wrap the lap. The gap pair is spacing since the
        // data source was set — read FrameGapWindow before interpreting it. On the first run that carried
        // it, no interval anywhere exceeded 345 ms, and a lap whose position was bit-identical between
        // polls still reported a 241 ms maximum: the counter sees buffers reaching a draw pass, not
        // changes of picture.
        if (seam.restarts()) {
            // Read once and decode twice — two getter calls would be two samples of a word that surface
            // destruction can clear in between. Print the sentinel as a word: `gap=-1/-1` in an operator's
            // grep looks like a measurement, while -1 means "no record" — which is every TsPlayer lap and
            // any clip under two frames. Matches the durable line's `maxFrameGap=none`.
            long gapPair = videoPlayer.getGapSnapshot();
            int lastGapMs = FrameGapWindow.latestOf(gapPair);
            int maxGapMs = FrameGapWindow.maxOf(gapPair);
            debugInformationProvided(new DebugInformation("video",
                    "loop restart pos=" + position + " end=" + seam.boundaryMs
                            + " (" + seam.reasonWord()
                            + " player=" + reportedMs + " file=" + fileMs + ")"
                            + " rate=" + describePlaybackRate()
                            + " gap=" + (lastGapMs < 0 ? "none" : Integer.toString(lastGapMs))
                            + "/" + (maxGapMs < 0 ? "none" : Integer.toString(maxGapMs))));
            noteMediaManualLoopRestart();
            try {
                videoPlayer.seekTo(0);
                videoPlayer.play();
            } catch (Exception e) {
                handleException(e);
            }
            videoWatchdogLastPosition = -1;
            videoWatchdogLastProgressMs = now;
            videoWatchdogRecoverAttempted = false;
            scheduleVideoWatchdogCheck();
            return;
        }

        // Reached only by a reading that is exactly equal to the last one: anything else returned
        // above, and anything the seam rule calls a tail was already restarted there.
        if (position != previousPositionMs) {
            scheduleVideoWatchdogCheck();
            return;
        }

        if (now - videoWatchdogLastProgressMs < VIDEO_STALL_THRESHOLD_MS) {
            scheduleVideoWatchdogCheck();
            return;
        }

        if (!videoWatchdogRecoverAttempted) {
            videoWatchdogRecoverAttempted = true;
            int duration = videoPlayer.getDuration();
            int seekTarget = position;
            if (duration > 0 && position >= duration - 1000) {
                seekTarget = 0;
            }
            debugInformationProvided(new DebugInformation("video",
                    "stall recover pos=" + position + " seek=" + seekTarget + " duration=" + duration
                            + " rate=" + describePlaybackRate()));
            try {
                videoPlayer.seekTo(seekTarget);
                videoPlayer.play();
            } catch (Exception e) {
                handleException(e);
            }
            videoWatchdogLastProgressMs = System.currentTimeMillis();
            scheduleVideoWatchdogCheck();
            return;
        }

        debugInformationProvided(new DebugInformation("video",
                "stall fallback pos=" + position + " duration=" + videoPlayer.getDuration()));
        cancelVideoWatchdog();
        if (fallbackTsPlayerToMediaPlayer(assetId, file, activityReference, stage)) {
            return;
        }
        restoreImageViewAfterVideoFailure();
        assetFallback(assetId, activityReference, true, file, stage);
    }

    /**
     * Ratio of media time advanced to wall time across one window, x100.
     *
     * <p>This is the only signal that separates "smooth but slow" from "fine". Everything else in
     * the playback path — {@code onError}, {@code onInfo}, the stall check two lines above — needs
     * playback to <em>stop</em> to notice anything, and a decoder running at 0.6x never stops: it
     * advances, just not as fast as the clock. Re-anchors whenever position jumps backwards, which
     * is a native loop rather than a rate.
     *
     * @param position player position just read, in ms
     * @param now      wall clock that reading was taken on, in ms
     */
    private void measurePlaybackRate(int position, long now) {
        if (videoRateAnchorMs == 0L || position < videoRateAnchorPosition || now < videoRateAnchorMs) {
            videoRateAnchorMs = now;
            videoRateAnchorPosition = position;
            return;
        }
        long elapsed = now - videoRateAnchorMs;
        if (elapsed < VIDEO_RATE_WINDOW_MS) {
            return;
        }
        int rateX100 = (int) Math.min(Integer.MAX_VALUE,
                (position - videoRateAnchorPosition) * 100L / elapsed);
        videoPlaybackRateX100 = rateX100;
        // A single reading cannot tell a pipeline that is uniformly late from one that keeps up
        // and hitches, and those want different fixes; the first measurements of the slow clip
        // misled exactly because only the latest window was kept.
        if (videoPlaybackRateSamples == 0 || rateX100 < videoPlaybackRateMinX100) {
            videoPlaybackRateMinX100 = rateX100;
        }
        if (videoPlaybackRateSamples == 0 || rateX100 > videoPlaybackRateMaxX100) {
            videoPlaybackRateMaxX100 = rateX100;
        }
        videoPlaybackRateSamples++;
        videoRateAnchorMs = now;
        videoRateAnchorPosition = position;
    }

    /** Position of the player actually in use, or -1 when it cannot answer. */
    private int activePositionMs() {
        try {
            return videoPlayer != null ? videoPlayer.getCurrentPosition() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Duration the player actually in use believes the clip has, or -1 when it cannot answer. */
    private int activeDurationMs() {
        try {
            return videoPlayer != null ? videoPlayer.getDuration() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** The last measured playback rate as a multiple of realtime, or "n/a" before one completes. */
    private String describePlaybackRate() {
        return videoPlaybackRateX100 < 0 ? "n/a"
                : String.format(Locale.ROOT, "%.2fx", videoPlaybackRateX100 / 100.0);
    }

    // ------------------------------------------------------------------
    // KioskWatchdog.Host — what "the frame is still displaying" means, and how to restore it.
    // All of these run on the watchdog's timer thread unless noted.
    // ------------------------------------------------------------------

    @Override
    public long nowMs() {
        return System.currentTimeMillis();
    }

    @Override
    public boolean isPaused() {
        return activityPaused || settingsManager.isSetupDialogIfNeeded();
    }

    @Override
    public boolean screenShouldBeOn() {
        return brightnessManager.getShouldTheScreenBeOn();
    }

    @Override
    public boolean screenHoldClaimed() {
        // The live window flag, not the belief above — a watchdog that asked the belief could never
        // see the frame it is here to catch. Reads attributes cross-thread on purpose: this is a
        // diagnostic of a window the main thread may be busy not-serving.
        return screenHoldFlagPresent();
    }

    @Override
    public void reassertScreenHold() {
        // The window belongs to the UI thread. The claim re-reads the flags and counts a repair only
        // when they were genuinely missing, so a queued repair that lands after an ordinary assert
        // costs nothing and says nothing.
        runOnUiThread(() -> {
            if (brightnessManager != null && brightnessManager.getShouldTheScreenBeOn()) {
                claimDisplayHold();
            }
        });
    }

    @Override
    public long intervalMs() {
        return MILLIS * Math.max(1, settingsManager.getConfiguration().interval);
    }

    /**
     * The clock the kiosk ladder escalates on. Until 2026-11-08 this returned
     * {@link #lastRotationActivityMs}, which {@link #runOnTimer} stamps before it asks for the next
     * asset — a clock that feeds itself, so {@code stale} could never pass one rotation interval and
     * no rung above {@code LEVEL_NONE} was reachable on a frame that kept ticking and never drew.
     */
    @Override
    public long lastDisplayProvenAtMs() {
        return KioskWatchdog.escalationClockMs(lastDisplayProvenAtMs, displayClockAnchorMs);
    }

    @Override
    public long downloadInFlightMs() {
        long last = downloadProgressAtMs;
        long started = downloadStartedAtMs;
        if (last == 0L || started == 0L || System.currentTimeMillis() - last > DOWNLOAD_PROGRESS_SILENCE_MS) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - started);
    }

    @Override
    public boolean networkAvailable() {
        return connectionManager.isNetworkAvailable();
    }

    @Override
    public long offlineMs() {
        long since = offlineSinceMs;
        return since == 0L ? 0L : Math.max(0L, System.currentTimeMillis() - since);
    }

    /**
     * Nothing in the rotation pool and no network being waited for, which KioskWatchdog holds its
     * ladder on — an empty library is not a wedge, and a rung that restarts the app only re-seeds the
     * wedge clock the ladder reads.
     *
     * <p>{@code poolKeyCount()} rather than anything reading {@code MediaManager.pool}: that list
     * belongs to the rotation thread, and this is called on the watchdog's {@code Timer}, the one
     * thread that has to keep working when everything it watches is stuck. The network half is
     * {@link #offlineMs()} for the same reason — it is a field the connection callbacks already
     * maintain, where {@code networkAvailable()} would be a binder call into a framework that may be
     * the wedged thing. A frame that has never received a connection callback therefore reads as
     * "not offline", which is the safe direction: it only ever withholds escalation for the seconds
     * until that callback lands, against a ladder whose first threshold is twenty minutes. The third
     * argument is {@link MediaManager#catalogPassCompleted()}, which is what separates "the library is
     * empty" from "the library has not been asked yet" — the rule itself is
     * {@link KioskWatchdog#outOfMaterial}, pure, so the conjunction has a JVM test rather than living
     * only in this call site's argument list.
     */
    @Override
    public boolean nothingToDisplay() {
        return KioskWatchdog.outOfMaterial(mediaManager.poolKeyCount() == 0,
                mediaManager.catalogPassCompleted(), offlineMs());
    }

    /**
     * Number of acknowledgements so far. A value unchanged across a whole tick interval is the
     * only evidence that counts as a dead main thread — a late acknowledgement is still proof the
     * queue drained, and this hardware legitimately needs minutes of main-thread work to start.
     */
    @Override
    public long mainLoopProgressToken() {
        return mainLoopAcks;
    }

    @Override
    public void postProbe() {
        handler.post(mainLoopProbeAck);
    }

    @Override
    public boolean autoRebootEnabled() {
        return settingsManager.getConfiguration().autoReboot;
    }

    @Override
    public void forceRotation() {
        runOnUiThread(() -> {
            Log.i(TAG, "watchdog: forcing a rotation");
            showNextImage();
        });
    }

    @Override
    public void recoverPlayback() {
        runOnUiThread(this::redrawCurrentAsset);
    }

    /**
     * Forces the full player teardown that an asset handoff would have done — the documented
     * recovery for a frozen Amlogic video plane — and then rotates. Clearing {@code lastVisibleAsset}
     * first is what makes it work: without it, landing back on the same asset would skip straight
     * past the recreate. Must run on the UI thread.
     */
    private void redrawCurrentAsset() {
        try {
            Log.i(TAG, "watchdog: forcing player teardown before the next display");
            // Deliberately touches neither clock: a recovery attempt is not a display. If this
            // rotation really lands, the proof point in displayPicture/displayVideo (or the still it
            // falls back to) refreshes the wedge clock; if it does not, nothing moves it and the ladder
            // keeps climbing to the network and reboot rungs. Stamping either clock here would be the
            // self-feeding bug again, this time from inside the ladder's own rung.
            lastVisibleAsset = "";
            cancelVideoWatchdog();
            releaseVideoDisplay();
            // The browser view sits above the image, so claiming the image is on screen only works
            // if the page is taken away first.
            hideWebView();
            // Revealing the photo is a change to the panel, so it is stated as one: without this the
            // record went on naming the clip whose plane this rung just tore down, and the /state probe
            // kept calling into the controller that was released two lines above — on the one path a
            // wedged frame is most likely to be diagnosed from, because this is what KioskWatchdog
            // reaches for, and the report it collects prints the same stale record.
            revealPhotoView();
            mediaManager.showNextImage(this);
        } catch (Exception e) {
            handleException(e);
        }
    }

    @Override
    public void repairNetwork() {
        runAdbdTask("network-repair", "network repair rung", new AdbdTask() {
            @Override
            public List<DeviceRepair.Step> run(AdbdInstallClient client) throws InterruptedException {
                return DeviceRepair.repairWifi(client::exec, new DeviceRepair.Sleeper() {
                    @Override
                    public void sleep(long ms) throws InterruptedException {
                        Thread.sleep(ms);
                    }
                });
            }
        });
    }

    @Override
    public void rebootDevice(String reason) {
        runAdbdTask("reboot", reason, new AdbdTask() {
            @Override
            public List<DeviceRepair.Step> run(AdbdInstallClient client) {
                // The last instant at which a file can honestly say "this frame is about to reboot
                // itself". Not in the watchdog: adbd still gets a say. Awaited, with a deadline — the
                // reboot proceeds whether or not the report landed.
                DiagnosticsReport.Result report =
                        captureDiagnostics(DiagnosticsReport.TRIGGER_REBOOT, reason, true);
                List<DeviceRepair.Step> steps = DeviceRepair.reboot(client::exec);
                // Such a file can only ever claim the request, because a reboot that lands kills this
                // thread before it could annotate itself. So record the outcome that IS still
                // observable from here: adbd refusing the command. Left unrecorded, the report keeps
                // asserting a reboot that the next genuine boot would be credited with completing.
                if (report != null && report.ok && !steps.isEmpty()) {
                    DeviceRepair.Step step = steps.get(0);
                    if (!step.ok()) {
                        DiagnosticsReport.recordDispatchOutcome(
                                DiagnosticsReport.dirFor(MainActivity.this), report.name,
                                step.outcome + (step.detail == null || step.detail.isEmpty()
                                        ? "" : " (" + step.detail + ")"));
                    }
                }
                return steps;
            }
        });
    }

    /** A repair sequence, executed against a live adbd client on a worker thread. */
    private interface AdbdTask {
        List<DeviceRepair.Step> run(AdbdInstallClient client) throws InterruptedException;
    }

    /**
     * Runs an adbd repair off the watchdog thread: adbd round-trips take seconds, and a timer task
     * that blocks cannot escalate. Only ever reached with {@link DeviceRepair}'s fixed commands.
     */
    private void runAdbdTask(String label, String reason, AdbdTask task) {
        if (repairRunning) {
            // Through the file log, not just logcat: a reboot rung that never reached adbd is exactly
            // the silence a diagnostics report would otherwise have to explain.
            logWatchdogEvent(label + " skipped — another repair is in flight", true);
            return;
        }
        repairRunning = true;
        final String name = label;
        final String why = reason;
        new Thread(new Runnable() {
            @Override
            public void run() {
                AdbdInstallClient.Result armed = null;
                try {
                    // The ladder has reached this rung, so it may use adbd — but only for as long as
                    // this one action runs: start it now (if the operator allowed that at all), and
                    // the finally below closes it again. Between rungs nothing is listening.
                    armed = AdbdInstallClient.ensureTcpAdbd(
                            settingsManager != null && settingsManager.isLoopbackAdbEnabled());
                    if (armed.outcome != AdbdInstallClient.Outcome.SUCCESS
                            && armed.outcome != AdbdInstallClient.Outcome.ARMED) {
                        logWatchdogEvent(name + " skipped: adbd unavailable (" + armed.detail + ")", true);
                        if ("reboot".equals(name)) {
                            // The EO1 case, and the common one: the ladder decided to reboot, the
                            // permission was granted, and the ROM's adbd is simply not there. Nothing
                            // reboots and nothing else would ever have written a report.
                            captureDiagnostics(DiagnosticsReport.TRIGGER_EXHAUSTED,
                                    why + " — adbd unavailable (" + armed.detail + ")", false);
                        }
                        return;
                    }
                    List<DeviceRepair.Step> steps = task.run(new AdbdInstallClient());
                    logWatchdogEvent(name + ": " + DeviceRepair.describe(steps), false);
                } catch (Throwable t) {
                    // A repair thread must never reach the global handler (System.exit(2)).
                    logWatchdogEvent(name + " failed: " + t, true);
                } finally {
                    if (armed != null && armed.outcome == AdbdInstallClient.Outcome.ARMED) {
                        AdbdInstallClient.teardownTcpAdbd();
                    }
                    repairRunning = false;
                }
            }
        }, "eo1-" + name).start();
    }

    /**
     * Best-effort, once per process, from {@code onCreate}: make sure the ROM's two OTA pollers cannot
     * wake this frame, and report which of the two answers is the case (see {@link
     * DeviceRepair#VENDOR_UPDATER_COMMANDS} and {@link VendorPollers}).
     *
     * <p>It asks the framework first and adbd second, in that order, for one reason: what a {@code pm}
     * command reports is whether the transport answered, not whether the poller is silent. Loopback
     * adbd accepts a connection and then never reads a byte while a host {@code adb} client is
     * attached to the frame, so a bench run used to log {@code unreachable
     * (SocketTimeoutException)} on a device that had been quiet for days, and no amount of reading
     * that line told anyone which of the two it was. So: a frame whose pollers the framework already
     * has excluded from intent resolution opens no socket at all — no package force-stop, no timeout,
     * a verdict instead. A frame that is genuinely armed gets one attempt through adbd, and whatever
     * adbd said is then re-read through {@link VendorPollers}, because the re-read is the answer.
     *
     * <p>Deliberately not part of the core loop — adbd is loopback, so Wi-Fi has nothing to do with
     * it, and a frame that boots into a dead network is precisely the one whose poller still fires
     * every 15 minutes. Kept out of the watchdog ladder too: this is not a repair and must never
     * block the caller. It only ever {@code probe()}s, never {@code ensureTcpAdbd()}s — silencing a
     * poller nobody asked about must not open a root port on the LAN. A frame whose ROM has no such
     * package says so once in {@code /logs} and moves on. The attempt is claimed before the thread
     * starts, so an unreachable adbd costs one log line, not a retry loop; the next launch tries
     * again.
     */
    private void silenceVendorUpdater() {
        if (vendorUpdaterAttempted) {
            return;
        }
        vendorUpdaterAttempted = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VendorPollers.Reader reader = VendorPollers.readerFor(MainActivity.this);
                    List<VendorPollers.Verdict> before = VendorPollers.audit(reader);
                    if (VendorPollers.allQuiet(before)) {
                        String quiet = "silenced, verified without adbd: "
                                + VendorPollers.summarize(before);
                        Log.i(TAG, "vendor updater: " + quiet);
                        debugInformationProvided(new DebugInformation("vendorUpdater", quiet));
                        return;
                    }
                    String attempted;
                    if (!AdbdInstallClient.probe()) {
                        // Silencing the vendor pollers is not an action anyone asked for, so unlike a
                        // reached watchdog rung it never opens a root port on the LAN — it uses one
                        // that is already there. On an EO1 that makes this a logged no-op.
                        attempted = "adbd is not listening, nothing written";
                    } else {
                        AdbdInstallClient client = new AdbdInstallClient();
                        List<DeviceRepair.Step> steps =
                                DeviceRepair.silenceVendorUpdater(client::exec);
                        attempted = DeviceRepair.describe(steps);
                    }
                    List<VendorPollers.Verdict> after = VendorPollers.audit(reader);
                    String fix = VendorPollers.needsHuman(after);
                    String outcome = "after " + attempted + " -> " + VendorPollers.summarize(after)
                            + (fix.isEmpty() ? "" : " | " + fix);
                    Log.i(TAG, "vendor updater: " + outcome);
                    debugInformationProvided(new DebugInformation("vendorUpdater", outcome));
                } catch (Throwable t) {
                    // A background attempt must never reach the global handler (System.exit(2)).
                    Log.w(TAG, "vendor updater: " + t);
                }
            }
        }, "eo1-vendor-updater").start();
    }

    /**
     * Restarts our own process. The relaunch alarm is scheduled on a worker thread rather than
     * inline: {@code AlarmManager.set} is a binder call, and if the framework is the part that is
     * hung, blocking the watchdog thread here would cost us every remaining rung (including
     * reboot). The kill is what actually unsticks a wedged UI thread.
     */
    @Override
    public void selfRestart() {
        // Counted before anything else, because the process doing this is about to stop existing:
        // a report after a rung-4 restart would otherwise look like one after a wedge.
        selfRestartCount++;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    if (mgr != null && pendingIntent != null) {
                        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 15000L, pendingIntent);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "watchdog: could not schedule relaunch: " + t);
                }
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable ignored) {
                    // Nothing left to do from inside a process that cannot kill itself.
                }
            }
        }, "eo1-self-restart").start();
    }

    /**
     * One watchdog event. {@code debugInformationProvided} is the single write path into
     * {@code AppLogger} (it also drives the on-device debug overlay), so the file log gets exactly
     * one line per event — writing here as well duplicates every line.
     */
    @Override
    public void logWatchdogEvent(String message, boolean severe) {
        if (severe) {
            Log.w(TAG, "watchdog: " + message);
        } else {
            Log.i(TAG, "watchdog: " + message);
        }
        debugInformationProvided(new DebugInformation("watchdog", message));
    }

    // ------------------------------------------------------ diagnostics reports

    /**
     * Set while a capture thread is alive. Static because the activity is re-created and a capture
     * outlives the instance that started it; two reports written at once would interleave their
     * collectors and produce one file that describes neither moment.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean DIAGNOSTICS_CAPTURING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long lastDiagnosticsCaptureAtMs = 0L;

    /** The watchdog reached the reboot rung and nothing rebooted. Document that anyway. */
    @Override
    public void captureDiagnostics(String reason) {
        captureDiagnostics(DiagnosticsReport.TRIGGER_EXHAUSTED, reason, false);
    }

    /**
     * Writes one diagnostics report. Never on the calling thread for the collecting part: the caller
     * is either the watchdog's single {@code Timer} thread — whose job is to keep escalating, so it
     * must not be made to wait on a capture — or {@code eo1-reboot}, which has a device to reboot.
     *
     * <p>When {@code await} is set the caller joins for at most {@link
     * DiagnosticsReport#CAPTURE_DEADLINE_MS} and then proceeds regardless. That is the whole contract:
     * a report that cannot be written must never become the reason a frame does not recover, and the
     * absence is logged so an empty {@code /reports} page reads as "the capture hung", not as "there
     * was nothing to say".
     *
     * @return the outcome, or {@code null} when nothing was captured (already in flight, or too soon
     *         after the last manual capture)
     */
    private DiagnosticsReport.Result captureDiagnostics(final String trigger, final String reason,
                                                        boolean await) {
        long now = android.os.SystemClock.elapsedRealtime();
        boolean spacingEnforced = DiagnosticsReport.TRIGGER_MANUAL.equals(trigger);
        if (spacingEnforced && lastDiagnosticsCaptureAtMs != 0L
                && now - lastDiagnosticsCaptureAtMs < DiagnosticsReport.CAPTURE_SPACING_MS) {
            logWatchdogEvent("diagnostics capture skipped — one ran "
                    + ((now - lastDiagnosticsCaptureAtMs) / 1000L) + "s ago", false);
            return null;
        }
        if (!DIAGNOSTICS_CAPTURING.compareAndSet(false, true)) {
            logWatchdogEvent("diagnostics capture skipped — another is in flight", false);
            return null;
        }
        lastDiagnosticsCaptureAtMs = now;
        final DiagnosticsReport.Content content = diagnosticsContent(trigger, reason);
        final File dir = DiagnosticsReport.dirFor(this);
        final DiagnosticsReport.Result[] holder = new DiagnosticsReport.Result[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = DiagnosticsReport.capture(dir, content);
                    if (!holder[0].ok) {
                        logWatchdogEvent("diagnostics report failed: " + holder[0].message, true);
                    }
                } catch (Throwable t) {
                    // A capture thread must never reach the global handler (System.exit(2)).
                    Log.w(TAG, "diagnostics capture: " + t);
                } finally {
                    DIAGNOSTICS_CAPTURING.set(false);
                }
            }
        }, "eo1-diag-capture");
        worker.setDaemon(true);
        worker.start();
        if (!await) {
            return null;
        }
        try {
            worker.join(DiagnosticsReport.CAPTURE_DEADLINE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            logWatchdogEvent("diagnostics capture did not complete within "
                    + DiagnosticsReport.CAPTURE_DEADLINE_MS + "ms", true);
            return null;
        }
        return holder[0];
    }

    /**
     * Assembles one report's inputs. The section order is the design: counters that only this
     * process holds, then this process's own stacks, then the log, and only last the document that
     * goes asking the framework things — so a framework that is the thing being diagnosed can cost
     * the view of itself and never the evidence that it was asked.
     */
    private DiagnosticsReport.Content diagnosticsContent(String trigger, String reason) {
        DiagnosticsReport.Content c = new DiagnosticsReport.Content();
        c.trigger = trigger;
        c.reason = reason;
        c.triggerThread = Thread.currentThread().getName();
        c.epochMillis = System.currentTimeMillis();
        c.bootEpochMillis = PROCESS_START_MS - android.os.SystemClock.elapsedRealtime();
        c.versionCode = BuildConfig.VERSION_CODE;
        c.logFile = appLogger != null ? appLogger.logFile() : new File(getFilesDir(), "eo1-app.log");
        c.rotatedLogFile = appLogger != null ? appLogger.rotatedFile() : null;
        c.header.putAll(CrashReporter.deviceFacts(getFilesDir()));
        c.header.put("Git-Source", BuildFacts.source());
        c.header.put("Build-Date", BuildFacts.buildStamp());
        String name = deviceName();
        if (!name.isEmpty()) {
            c.header.put("Frame-Name", name);
        }
        c.header.put("Capture-Deadline-Ms", Long.toString(DiagnosticsReport.CAPTURE_DEADLINE_MS));
        c.header.put("Auto-Reboot-At-Decision", Boolean.toString(autoRebootEnabled()));
        c.header.put("Android-Uptime", DeviceTelemetry.humanizeUptime(
                android.os.SystemClock.elapsedRealtime()));
        c.liveness = new DiagnosticsReport.Collector() {
            @Override
            public String collect() {
                return diagnosticsLiveness();
            }
        };
        c.threadDump = new DiagnosticsReport.Collector() {
            @Override
            public String collect() {
                return diagnosticsThreadDump();
            }
        };
        c.appLog = new DiagnosticsReport.Collector() {
            @Override
            public String collect() {
                return DiagnosticsReport.tailLog(c.logFile, c.rotatedLogFile,
                        DiagnosticsReport.APP_LOG_LINES);
            }
        };
        c.state = new DiagnosticsReport.Collector() {
            @Override
            public String collect() {
                return diagnosticsState();
            }
        };
        return c;
    }

    /**
     * Section 1: everything this process knows that no framework call can withhold. Pure field reads
     * and one directory listing — which is why it has no time budget and cannot need one.
     */
    private String diagnosticsLiveness() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("process started   : ")
                .append(DiagnosticsReport.localTime(PROCESS_START_MS))
                .append(" (activity #").append(sActivityCreateCount)
                .append(", self-restarts ").append(selfRestartCount).append(")\n");
        sb.append("display proven    : ")
                .append(lastDisplayProvenAtMs == 0L
                        ? "never since this Activity started — the ladder ages from that instant"
                        : DiagnosticsReport.localTime(lastDisplayProvenAtMs)
                                + " (" + ((now - lastDisplayProvenAtMs) / 1000L) + "s ago)")
                .append("  count=").append(displayProvenCount)
                .append("  [the wedge clock: KioskWatchdog escalates on this]\n");
        sb.append("display failures  : ").append(displayFailedCount).append('\n');
        sb.append("rotation clock    : ")
                .append(lastRotationActivityMs == 0L ? "never"
                        : DiagnosticsReport.localTime(lastRotationActivityMs)
                                + " (" + ((now - lastRotationActivityMs) / 1000L) + "s ago)")
                .append("  [loop liveness only — refreshed by every timer tick, never by a display "
                        + "reaching the panel; NOT what the ladder reads]\n");
        sb.append("rotation ticks    : ").append(timerTickCount)
                .append("  next at ").append(nextRotationAtMs == 0L ? "unscheduled"
                        : DiagnosticsReport.localTime(nextRotationAtMs)).append('\n');
        sb.append("main thread       : acked ")
                .append((now - mainLoopAckAtMs) / 1000L).append("s ago\n");
        KioskWatchdog w = kioskWatchdog;
        if (w != null) {
            sb.append("watchdog          : level=").append(w.actedLevel())
                    .append(" action=").append(w.lastAction())
                    .append(" ticks=").append(w.ticks())
                    .append(" probeMisses=").append(w.probeMisses())
                    .append(" repairAttempts=").append(w.repairAttempts()).append('\n');
            sb.append("                    last action ")
                    .append(w.lastActionAtMs() == 0L ? "never"
                            : (now - w.lastActionAtMs()) / 60_000L + "m ago").append('\n');
            sb.append("                    stale=").append(w.staleMsNow() / 1000L).append("s")
                    .append(" limit=").append(KioskWatchdog.staleLimitMs(intervalMs()) / 1000L)
                    .append("s\n");
            sb.append("rungs this wedge  : ").append(w.rungHistory()).append('\n');
            Boolean up = w.cachedNetworkUp();
            sb.append("network (cached)  : ")
                    .append(up == null ? "never observed by the ladder"
                            : (up.booleanValue() ? "up" : "down") + " as of "
                                    + ((now - w.cachedNetworkAtMs()) / 1000L) + "s ago")
                    .append('\n');
            sb.append("screen/paused     : screen=")
                    .append(w.cachedScreenOn() ? "should be on" : "should be off")
                    .append(" paused=").append(w.cachedPaused() ? "yes (dialog in front)" : "no")
                    .append('\n');
        } else {
            sb.append("watchdog          : not running\n");
        }
        sb.append("offline for         : ").append(offlineMs() / 1000L).append("s\n");
        sb.append("download in flight  : ").append(downloadInFlightMs() / 1000L).append("s\n");
        sb.append("window              : focused=").append(windowFocused)
                .append(" view=").append(focusedView)
                .append(" lastKey=").append(lastKeyEventCode).append('\n');
        // This line states the frame's default engine, once per launch, and is true whatever is on the
        // panel — which is why it says nothing about the clip. The clip is the next line's business.
        sb.append("video engine        : ").append(preferTsPlayer ? "TsPlayer" : "MediaPlayer")
                // The panel the aspect rule compares every clip against, in the record that survives a
                // reboot. /state carries it too, but /state is gone by the time anyone asks about a
                // frame that has since been moved, power-cycled or re-hung — and an "unmeasured" here
                // is the only evidence there will ever be that the rule was dark rather than wrong.
                .append(" panel=").append(lastFrameShape == null ? "unmeasured" : lastFrameShape)
                .append('\n');
        // Read once, for the same reason buildStateJson does: this collector runs on a diagnostics
        // worker, and half of one asset and half of the next is not a report about either.
        PanelDisplay shown = panel();
        sb.append("on screen           : ")
                .append(shown == null ? "nothing yet" : shown.describe()).append('\n');
        if (shown != null && shown.showsVideo()) {
            // Paint evidence belongs to the engine that is compositing, and only while one is. These
            // used to be written for every launch, so a frame that had not shown a video in a week
            // still reported the last clip's player as painted — in the record people quote.
            sb.append("active video        : path=").append(shown.pathOrUrl)
                    .append(" stage=").append(shown.stage)
                    .append(" engine=").append(shown.engine);
            // Same discipline as StatePlayerProbe, and for the same reason: this collector runs on a
            // diagnostics worker, so a rotation between the reads and the append would print this clip's path
            // beside the next attempt's frame stamp — in the report that outlives the reboot it describes.
            // Ownership is therefore checked <em>after</em> the reads, and everything collected is discarded
            // together if it changed; checking only beforehand would leave the last read free to describe a
            // display that had already been replaced mid-report.
            VideoPlayerController compositing = videoPlayer;
            boolean ownedBefore = panel() == shown
                    && (!shown.showsVideo() || videoCompositing == shown);
            // Derived from the engine in the record, as /state does, for two reasons. A live read can answer
            // for a different controller than the one being reported, and `videoPaintProduced` is
            // deliberately initialised to "no signal" for a TsPlayer display so a silent failure is still
            // noticed — printing it unguarded asserts a composited frame for exactly the failure the latch
            // exists to catch, in the report an operator quotes after a power-cycle.
            boolean paintSignal = !VideoEngineReport.TS_PLAYER.equals(shown.engine);
            long lastFrameAtMs = paintSignal && compositing != null ? compositing.getLastFrameAtMs() : 0L;
            boolean painted = paintSignal && videoPaintProduced;
            // Worst frame spacing this engine recorded for the clip in hand. Read under exactly the same
            // discipline as the stamp above, on the same controller, so the two cannot describe different
            // displays. It says whether buffers kept arriving; it cannot say whether the picture changed,
            // and a low value must not be read as "the clip was fine" — see FrameGapWindow.
            int maxFrameGapMs = paintSignal && compositing != null
                    ? FrameGapWindow.maxOf(compositing.getGapSnapshot())
                    : FrameGapWindow.NO_GAP;
            if (ownedBefore && panel() == shown
                    && (!shown.showsVideo() || videoCompositing == shown)) {
                sb.append(" paintSignal=").append(paintSignal)
                        .append(" painted=").append(painted)
                        .append(" lastFrame=")
                        .append(lastFrameAtMs == 0L ? "never"
                                : ((now - lastFrameAtMs) / 1000L) + "s ago")
                        .append(" maxFrameGap=")
                        .append(maxFrameGapMs < 0 ? "none" : maxFrameGapMs + "ms");
            } else {
                sb.append(" player=changed-mid-report");
            }
            sb.append('\n');
        }
        // One line for the broker link, in the record that outlives logcat. "connected=false" and "connected
        // but no message for hours" are different diagnoses, and before this class reported into AppLogger
        // neither was recoverable at all — the events went to stdout, into a buffer CrashSources never reads.
        // Read once. mqttManager is volatile and retireMqttManager() clears it from another thread, so the
        // null test and the call must not be two reads of the field: between them the manager can go away and
        // describe() throws NPE on the diagnostics worker, where DiagnosticsReport replaces the section with
        // a note that something failed — losing the whole liveness block, not just this line.
        MqttManager liveManager = mqttManager;
        sb.append("mqtt                : ")
                .append(liveManager == null
                        ? (mqttHeldForPanel ? "held (panel off)" : "no client")
                        : liveManager.describe())
                .append('\n');
        if (mediaManager != null) {
            MediaAsset current = mediaManager.getCurrentAsset();
            sb.append("pool                : keys=").append(mediaManager.poolKeyCount())
                    .append(" remainingThisPass=").append(mediaManager.rotationRemainingInPass())
                    .append(" rebuildFailures=").append(mediaManager.rebuildFailureCount())
                    .append('\n');
            sb.append("acquire failures    : run=").append(mediaManager.acquireFailureRun())
                    .append(" total=").append(mediaManager.acquireFailureCount())
                    .append(" last=")
                    .append(mediaManager.lastAcquireFailureAtMs() == 0L ? "never"
                            : DiagnosticsReport.localTime(mediaManager.lastAcquireFailureAtMs()))
                    .append(" webSkipped=").append(mediaManager.webFallbackDepth()).append('\n');
            sb.append("verdicts (process)  : ").append(mediaManager.verdictCounts()).append('\n');
            sb.append("current asset       : ");
            if (current == null) {
                sb.append("none\n");
            } else {
                sb.append(current.key()).append(' ').append(mediaTypeName(current.type))
                        .append(" backend=").append(current.backendId)
                        .append(' ').append(current.width).append('x').append(current.height)
                        .append(" size=").append(current.sizeBytes == null ? "?" : current.sizeBytes)
                        .append(" dur=").append(current.durationMs).append("ms")
                        .append(" file=").append(current.originalFileName).append('\n');
            }
            sb.append("backends            :\n");
            for (Map.Entry<String, String> e : mediaManager.backendFetchRecords().entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        sb.append("cache dir           : ")
                .append(getCacheDir() == null ? "?" : getCacheDir().getAbsolutePath()).append('\n');
        return sb.toString();
    }

    /**
     * Section 2: this process's own stacks. The one part of a report that turns "the main thread ran
     * nothing for two ticks" into a place in the code, and it needs no framework to answer.
     */
    private static String diagnosticsThreadDump() {
        StringBuilder sb = new StringBuilder();
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        List<Thread> threads = new ArrayList<>(traces.keySet());
        Collections.sort(threads, new Comparator<Thread>() {
            @Override
            public int compare(Thread a, Thread b) {
                String an = a.getName() == null ? "" : a.getName();
                String bn = b.getName() == null ? "" : b.getName();
                return an.compareTo(bn);
            }
        });
        for (Thread t : threads) {
            StackTraceElement[] frames = traces.get(t);
            sb.append('"').append(t.getName()).append('"')
                    .append(t.isDaemon() ? " daemon" : "")
                    .append(t.isAlive() ? "" : " (dead)")
                    .append(" prio=").append(t.getPriority()).append('\n');
            if (frames == null) {
                sb.append("\t(no stack: the thread was not in one)\n");
                continue;
            }
            int shown = Math.min(frames.length, DiagnosticsReport.THREAD_DUMP_MAX_FRAMES);
            for (int i = 0; i < shown; i++) {
                sb.append("\tat ").append(frames[i]).append('\n');
            }
            if (frames.length > shown) {
                sb.append("\t... ").append(frames.length - shown).append(" more\n");
            }
        }
        return sb.toString();
    }

    /**
     * Section 4: the {@code /state} document, unchanged. Pretty-printed for a human reading a
     * {@code .txt} in a browser; on anything unexpected the compact form is written instead, because
     * an unparseable answer is still the answer.
     */
    private String diagnosticsState() {
        String json = buildStateJson();
        try {
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(com.google.gson.JsonParser.parseString(json)) + "\n";
        } catch (Throwable t) {
            return json + "\n";
        }
    }

    /** The watchdog's view of itself, for {@code /state}. */
    /**
     * The {@code mqtt} section of {@code /state}. It exists because everything this feature can report about
     * itself used to be a {@code config.mqttHost} echo — what was <em>set</em>, never what was <em>dialled</em>
     * or <em>live</em> — and the failure it ships to answer is exactly the one that distinction hides: a
     * client that is connected and deaf, which a host name cannot tell apart from a session holding.
     *
     * <p>No credential appears here, and the URI is host and port only.
     */
    /**
     * The page URL a {@code web} backend entry holds, when that entry's page is still the one this
     * asset record was built from, for {@code media.current}.
     *
     * <p>{@link com.aphex3k.media.web.WebsiteBackend} gives its asset an id that is a deterministic
     * UUID of the page URL, so the pairing is not assumed here — it is recomputed. Matching on the
     * backend id alone would repeat, through a different door, the defect this key used to have: a
     * configuration edit that repoints {@code web-1} from page A to page B leaves {@code currentAsset}
     * naming A until some later selection succeeds, and an id-only lookup would print B beside A's key.
     * Null means this record's page is not recoverable from the current configuration, which the caller
     * reports by omitting the key.
     */
    @Nullable
    private String configuredWebUrl(String backendId, String assetId) {
        if (settingsManager == null || backendId == null || assetId == null) {
            return null;
        }
        for (ConfigurationBackendEntry entry : settingsManager.getConfiguration().backendsOrEmpty()) {
            if (entry.isWeb() && backendId.equals(entry.id) && entry.host != null) {
                String page = WebsiteBackend.pageForAssetId(entry.host, assetId);
                if (page != null) {
                    return page;
                }
            }
        }
        return null;
    }

    private JsonObject mqttStateJson() {
        JsonObject o = new JsonObject();
        MqttManager manager = this.mqttManager;
        o.addProperty("configured", manager != null || mqttBrokerUriFromConfig() != null);
        o.addProperty("heldForPanel", mqttHeldForPanel);
        if (manager == null) {
            o.addProperty("note", mqttHeldForPanel
                    ? "held for quiet hours" : "no client for this configuration");
            return o;
        }
        for (Map.Entry<String, JsonElement> entry : manager.stateJson().entrySet()) {
            o.add(entry.getKey(), entry.getValue());
        }
        return o;
    }

    private JsonObject watchdogStateJson() {
        JsonObject o = new JsonObject();
        long stale = kioskWatchdog != null ? kioskWatchdog.staleMsNow() : 0L;
        o.addProperty("staleMs", stale);
        o.addProperty("staleLimitMs", KioskWatchdog.staleLimitMs(intervalMs()));
        o.addProperty("level", kioskWatchdog != null ? kioskWatchdog.actedLevel() : KioskWatchdog.LEVEL_NONE);
        o.addProperty("lastAction", kioskWatchdog != null ? kioskWatchdog.lastAction() : "none");
        o.addProperty("lastActionAtMs", kioskWatchdog != null ? kioskWatchdog.lastActionAtMs() : 0L);
        o.addProperty("repairAttempts", kioskWatchdog != null ? kioskWatchdog.repairAttempts() : 0);
        o.addProperty("ticks", kioskWatchdog != null ? kioskWatchdog.ticks() : 0);
        o.addProperty("paused", isPaused());
        // "Alive" is the watchdog's own verdict: the main thread advanced at the last check. A
        // large mainLoopStallMs alongside it means slow startup, not a wedge.
        // Absent while there is no watchdog to ask, rather than false: `false` is a claim that the main
        // loop is not advancing, and in that window nothing has probed it yet.
        if (kioskWatchdog != null) {
            o.addProperty("mainLoopAlive", kioskWatchdog.probeMisses() == 0);
        }
        o.addProperty("mainLoopStallMs", Math.max(0L, System.currentTimeMillis() - mainLoopAckAtMs));
        o.addProperty("probeMisses", kioskWatchdog != null ? kioskWatchdog.probeMisses() : 0);
        o.addProperty("downloadInFlightMs", downloadInFlightMs());
        o.addProperty("offlineMs", offlineMs());
        o.addProperty("autoReboot", autoRebootEnabled());
        // Why a stale frame can be sitting on a low rung on purpose: nothing in the pool, so every
        // rung above the two cheap retries would only restart an app that comes back equally empty.
        o.addProperty("nothingToDisplay", nothingToDisplay());
        return o;
    }

    private String describeMediaInfo(int what, int extra) {
        switch (what) {
            case MediaPlayer.MEDIA_INFO_BUFFERING_START:
                return "buffering start extra=" + extra;
            case MediaPlayer.MEDIA_INFO_BUFFERING_END:
                return "buffering end extra=" + extra;
            case MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                return "rendering start extra=" + extra;
            default:
                return "what=" + what + " extra=" + extra;
        }
    }

    @Override
    public void displayVideo(File file, String assetId) {
        displayVideo(file, assetId, DisplayStage.ORIGINAL);
    }

    @Override
    public void displayFallbackVideo(File file, String assetId) {
        displayVideo(file, assetId, DisplayStage.FALLBACK);
    }

    private void displayVideo(File file, String assetId, DisplayStage stage) {

        if (file.getAbsolutePath().equals(lastVisibleAsset)) {
            Log.i(TAG, "displayVideo: skip same asset " + assetId);
            // Gated on the view tree, not on the retained name: this is the path where the claim is
            // weakest, because a failed clip releases its player without touching lastVisibleAsset, and
            // a clip that painted nothing must not renew the clock the ladder is climbing on.
            noteDisplayUnchanged(file.getAbsolutePath());
            return;
        }

        if (!this.brightnessManager.getShouldTheScreenBeOn()) {
            Log.i(TAG, "displayVideo: skip (screen should be off) asset=" + assetId);
            return;
        }

        // A video this device has already rendered nothing from, at every rung, is not worth another
        // black interval: show the still the server can make instead. Judged before the player is
        // touched, so the clip is not even decoded on the way. The stamp is loop liveness; the still
        // itself proves on the panel through displayPicture's Glide callback, and if it never gets
        // there the wedge clock keeps aging, which is the correct thing to report.
        if (stage == DisplayStage.ORIGINAL && mediaManager != null
                && mediaManager.isRenderSilent(assetId)) {
            lastRotationActivityMs = System.currentTimeMillis();
            Log.i(TAG, "displayVideo: " + assetId + " renders nothing on this device; showing its still");
            // The attempt only. Nothing has been taken off the wall yet — the preview is fetched
            // asynchronously and the previous surface stays up until it lands — so the panel must keep
            // saying what is actually visible; the preview's own display call proves the switch when it
            // happens. What the clip <em>cannot</em> be is the answer to "what is playing", and the
            // attempt being a still already says that. Setting it is also how a superseded web load is
            // retired — see webLoadOwnsSurfaces — and like every other display path this one arrives on the
            // main looper, because MediaManager posts its display calls rather than making them inline.
            setPanelAttempt(PanelDisplay.image(assetId, "",
                    panelGeneration.incrementAndGet(), System.currentTimeMillis()));
            debugInformationProvided(new DebugInformation("displayVideo", "render-silent still "
                    + assetId));
            mediaManager.showRenderSilentStill(this, assetId);
            return;
        }

        lastRotationActivityMs = System.currentTimeMillis();

        WeakReference<MainActivity> activityReference = new WeakReference<>(this);
        activeVideoAssetId = assetId;

        this.runOnUiThread(() -> {
            try {
                if (stage == DisplayStage.ORIGINAL) {
                    // The one per-asset edge in this ladder: ORIGINAL is entered once when an asset is
                    // displayed, while ORIGINAL_REPLAY and FALLBACK are re-entries into the same
                    // attempt series. Engine memory therefore resets here and nowhere else — reset it
                    // at a re-entry and the replay rung forgets it already burned an attempt, which is
                    // the bug this counter exists to stop. Kept inside the posted block with everything else
                    // that touches it. (This line used to justify that placement by saying displayVideo is
                    // reached from the rotation thread. It is not: MediaManager posts every display call
                    // through a UiPoster that is runOnUiThread in production, so the method enters on the main
                    // looper whichever thread decided to rotate. The grouping below is still the reason to
                    // keep them here — but it is a sequencing argument, and a false thread claim in a comment
                    // is how a correct-looking race fix gets written.)
                    tsAttemptsForActiveAsset = 0;
                    mediaManualLoopRestarts = 0;
                    mediaManualLoopCycleMs = -1;
                    mediaManualLoopAtMs = -1;
                }
                // Re-read the wall before either decision below. Doing it here rather than only at the
                // lifecycle callbacks is what makes it impossible for the engine choice and the source
                // gate to be judging two different panels — and on the UI thread, because a View's
                // bounds belong to the thread that laid them out.
                publishFrameShape();
                cancelVideoWatchdog();
                VideoRoutingDecision routing = routingAllowsTsPlayer(file);
                selectVideoPlayer(routing.allowTsPlayer);
                int videoDurationMs = mediaManager != null ? mediaManager.getVideoDurationMs(assetId) : -1;
                startVideoOnController(videoPlayer, file, assetId, videoDurationMs,
                        activityReference, true, stage, routing);
            } catch (Exception e) {
                restoreImageViewAfterVideoFailure();
                assetFallback(assetId, activityReference, true, file, stage);
                handleException(e);
            }
        });
    }

    /**
     * Chooses the engine for a file already in hand, and reports the choice. The rule itself is
     * {@link VideoRoutingRule#allowsTsPlayer}; what lives here is everything that needs an
     * {@code Activity} — reading the header, falling back to catalog dimensions, and saying the
     * outcome where an operator can read it afterwards.
     *
     * <p>The aspect clause is shared with {@link MediaCompatibility#incompatibleVideoUpscale}, which
     * asks the same question of the same numbers <em>before</em> the download, because a clip this
     * path hands to MediaPlayer is composited at a magnification this hardware does not keep up
     * with — so on a two-engine frame such a clip should never arrive here as an original at all.
     *
     * <p>Returns what it measured instead of storing it. Every field this method wrote was written only
     * on a video display and cleared by nobody, so {@code /state} reported the last clip's codec, size,
     * rate and rotation over whatever the panel had moved on to. The measurement now travels to
     * {@link #setPanelAttempt} as one value and is read from nowhere else.
     */
    private VideoRoutingDecision routingAllowsTsPlayer(File file) {
        VideoHeaderProbe.Probe probe = VideoHeaderProbe.probe(file);
        int rotation = probe.rotationDeg;
        int width = probe.codedWidth;
        int height = probe.codedHeight;
        String codec = probe.codec;
        MediaAsset asset = mediaManager != null ? mediaManager.getCurrentAsset() : null;
        if (asset != null) {
            if (width <= 0) {
                width = asset.width;
            }
            if (height <= 0) {
                height = asset.height;
            }
        }
        MediaCompatibility.PanelShape shape = currentPanelShape();
        boolean allowTs = VideoRoutingRule.allowsTsPlayer(rotation, width, height, shape);
        boolean forceFallback = !allowTs;
        // The panel's own shape, not a property of the file: published from the layout listener for
        // every frame, and deliberately left out of the clip record so a frame that has never played a
        // video can still say whether the aspect rule is lit.
        lastFrameShape = shape;
        lastFrameOrientation = VideoRoutingRule.orientationWord(shape);
        // Record the verdict where it is made. ORIGINAL_REPLAY asks this same field whether TsPlayer
        // was ever a live option for these bytes, and a second derivation of that answer somewhere
        // else is how the rung ends up replaying the engine that just failed.
        lastRoutingAllowedTsPlayer = allowTs;
        // Said with preferTsPlayer in the sentence, not from the routing outcome alone: this method
        // decides which engine a clip is suitable for, and on a frame whose native library never
        // loaded the answer it hands to selectVideoPlayer is discarded. Naming TsPlayer here while
        // MediaPlayer composites the clip is what made an EO1's log read like an EO2's.
        String engineWord = VideoEngineReport.engineWord(forceFallback, preferTsPlayer);
        String panelWord = shape == null ? "unmeasured" : shape.toString();
        Log.i(TAG, "displayVideo routing: codec=" + codec + " rotation=" + rotation
                + " coded=" + width + "x" + height
                + " panel=" + panelWord + " frame=" + lastFrameOrientation
                + " player=" + engineWord
                + " " + describeStream(probe));
        return new VideoRoutingDecision(allowTs, engineWord,
                new PanelDisplay.ClipFacts(rotation, width, height, codec,
                        probe.stats.fpsMilli, probe.stats.avgFrameBytes, probe.stats.peakFrameBytes,
                        probe.stats.bitRateBps, probe.stats.durationMs),
                // The measurement only. The compositor is appended when the sentence is rendered, from the
                // record that carries it, so an engine handover later in the attempt cannot leave the board
                // saying "-> TsPlayer" beside engine=MediaPlayer.
                "codec=" + codec + " rotation=" + rotation + " dims=" + width + "x" + height
                        + " panel=" + panelWord + " frame=" + lastFrameOrientation
                        + "; " + describeStream(probe));
    }

    /**
     * The panel's own shape, in live layout pixels, read one way, in one place.
     *
     * <p>{@code MediaManager} has no {@code Context} and {@code MediaCompatibility} has no Android at
     * all — so this is the only point where the wall enters either one's decisions, which is why the
     * aspect rule and the crop gate cannot end up with two conventions for what the panel looks like.
     *
     * <p>The read is the root view, deliberately: the video views are {@code GONE} whenever their
     * engine is idle, and a {@code GONE} view is never measured, so a video view would report 0 on
     * exactly the frames whose engine this rule exists to choose.
     *
     * @return the shape, or {@code null} before the layout has settled, which every caller reads as
     *         "cannot judge" and answers by keeping the clip's default engine and its original
     */
    @Nullable
    private MediaCompatibility.PanelShape currentPanelShape() {
        View root = rootView;
        if (root == null) {
            return null;
        }
        return MediaCompatibility.PanelShape.of(root.getWidth(), root.getHeight());
    }

    /**
     * Hands {@link #currentPanelShape()} to the media pipeline's crop gate, and records it for
     * {@code /state} even when no video has been displayed to record it — an operator checking whether
     * the gate is deciding against the right wall needs the value the gate actually holds, not the one
     * the last {@code displayVideo} happened to see.
     *
     * <p>Driven by a layout-change listener on the root view, which is the only source guaranteed to
     * hold the current size, and called directly from {@code onCreate},
     * {@code onConfigurationChanged} and {@code onWindowFocusChanged} so a value exists before the
     * first asset rather than only after the first resize. Those callbacks are convenience, not
     * correctness: {@code onConfigurationChanged} runs <em>before</em> the view tree is re-measured, so
     * it can read the pre-rotation bounds — the listener publishes again once they are real — and a
     * panel turned by the ROM post-processor need not produce another focus callback at all.
     * {@code onCreate} on its own would publish {@code null} and leave the gate dark for the very first
     * asset, which is precisely what the comment above its call site says this method exists to prevent.
     *
     * <p>Also called at the top of {@code displayVideo}'s posted block, so the engine decision and the
     * source decision cannot be judging two different panels. That call is belt-and-braces, not the
     * mechanism that keeps the value fresh: {@code displayVideo} returns early for an unchanged path,
     * so a pool holding a single video would never reach it again after the frame was moved.
     */
    private void publishFrameShape() {
        MediaCompatibility.PanelShape shape = currentPanelShape();
        lastFrameShape = shape;
        lastFrameOrientation = VideoRoutingRule.orientationWord(shape);
        if (mediaManager != null) {
            mediaManager.publishPanel(shape, preferTsPlayer);
        }
    }

    /**
     * Stamps one <em>app-issued</em> restart on the platform player: either {@code onCompletion}
     * arriving and {@code onVideoEnd} calling {@code play()}, or the position watchdog noticing a
     * position parked past the duration the player reports — the case that never raises a callback at
     * all. Both paths land here, so one count is one restart this process actually caused.
     *
     * <p>It does not count laps, and adding a wraparound observer to make it do so would be the wrong
     * fix. {@code setLooping(true)} loops inside the platform player without any callback, so the
     * ordinary case is genuinely invisible from here and the interesting cases — a seam that needed
     * rescuing, or one that fired twice — are exactly the ones that DO come through this method. Its
     * value is diagnostic, not statistical: {@code manualLoopRestarts} climbing while
     * {@code manualLoopCycleMs} stays well under the clip's duration is the seam double-firing, which
     * is the failure this change makes likely by moving most of an EO2's library onto this engine.
     *
     * <p>Before this existed the operator's only loop numbers came from {@code tsVideoView}, so a
     * MediaPlayer clip reported an engine that was not running.
     */
    private void noteMediaManualLoopRestart() {
        long now = System.currentTimeMillis();
        if (mediaManualLoopAtMs > 0) {
            mediaManualLoopCycleMs = now - mediaManualLoopAtMs;
        }
        mediaManualLoopAtMs = now;
        mediaManualLoopRestarts++;
    }

    /** Sample-table facts in one line, each part omitted when the file did not give it. */
    private static String describeStream(VideoHeaderProbe.Probe probe) {
        VideoHeaderProbe.StreamStats s = probe.stats;
        // Named out in full rather than abbreviated: megabytes per second is the other reading a
        // reader would take from "MB/s", and the two differ by three orders of magnitude here.
        long macroblocksPerSecond = MediaCompatibility.videoMacroblocksPerSecond(
                probe.codedWidth, probe.codedHeight, s.fpsMilli);
        if (s.fpsMilli <= 0 && s.avgFrameBytes <= 0 && s.bitRateBps <= 0
                && macroblocksPerSecond < 0) {
            return "stream unknown";
        }
        StringBuilder sb = new StringBuilder("stream");
        if (s.fpsMilli > 0) {
            sb.append(String.format(Locale.ROOT, " %.3ffps", s.fpsMilli / 1000.0));
        }
        if (s.avgFrameBytes > 0) {
            sb.append(' ').append(s.avgFrameBytes).append("B/frame");
        }
        if (s.peakFrameBytes > 0) {
            sb.append(" peak ").append(s.peakFrameBytes).append('B');
        }
        if (s.bitRateBps > 0) {
            sb.append(String.format(Locale.ROOT, " %.1f Mbps", s.bitRateBps / 1e6));
        }
        if (macroblocksPerSecond >= 0) {
            sb.append(' ').append(macroblocksPerSecond).append(" macroblocks/s");
        }
        return sb.toString();
    }

    /**
     * Prefer TsPlayer when enabled and the native library loaded; otherwise MediaPlayer.
     *
     * @param allowTsPlayer false forces MediaPlayer (used after TsPlayer failure for the same file)
     */
    private void selectVideoPlayer(boolean allowTsPlayer) {
        boolean useTs = allowTsPlayer && preferTsPlayer;
        VideoPlayerController next = useTs ? tsPlayerController : mediaPlayerController;
        if (videoPlayer != null && videoPlayer != next) {
            // Release, don't just stop: a stopped platform MediaPlayer stays prepared and keeps
            // the exclusive Amlogic video pipeline, which would starve the incoming TsPlayer.
            videoPlayer.release();
            videoPlayer.setVisibility(View.GONE);
            // Ownership ends with the release, wherever a release happens. This site was missed when the
            // other five were consolidated, and the gap is exactly what the ownership test exists to catch:
            // across the span until the new controller is handed the file, panel() and videoCompositing both
            // still name the outgoing clip while the player answering /state is a different, freshly chosen
            // one — publishing the old clip's path beside the new player's -1 duration.
            videoCompositing = null;
            videoSurfaceProven = false;
        }
        videoPlayer = next;
        if (useTs) {
            mediaPlayerVideoView.setVisibility(View.GONE);
            // VISIBLE required for SurfaceView to create its surface (under ImageView).
            tsVideoView.setVisibility(View.VISIBLE);
        } else {
            tsVideoView.setVisibility(View.GONE);
            mediaPlayerVideoView.setVisibility(View.INVISIBLE);
        }
        // No debug line here. The engine is a property of the display in hand, so it travels with it:
        // {@link #startVideoOnController} adopts a {@link PanelDisplay} carrying this answer, the board
        // prints it in its "on screen" row, and {@code /state} reports it as {@code video.engine}. A
        // separate key written from here landed <em>before</em> that handoff swept the board, so on a new
        // asset it announced an engine and then vanished — one more line whose freshness was a property
        // of its writer rather than of the panel.
    }

    /**
     * After TsPlayer stalls or errors, hand the same file to MediaPlayer once. Swapping engine
     * is not swapping rung: the retry stays on {@code stage}, so its failure moves one rung
     * down the ladder like any other failure of that display.
     *
     * @return true if MediaPlayer playback was started
     */
    private boolean fallbackTsPlayerToMediaPlayer(String assetId, File file,
                                                  WeakReference<MainActivity> activityReference,
                                                  DisplayStage stage) {
        if (!preferTsPlayer || videoPlayer != tsPlayerController) {
            return false;
        }
        if (!Objects.equals(assetId, activeVideoAssetId)) {
            return false;
        }
        tsFallbackCount++;
        Log.i(TAG, "TsPlayer → MediaPlayer fallback #" + tsFallbackCount);
        debugInformationProvided(new DebugInformation("video", "TsPlayer → MediaPlayer fallback #" + tsFallbackCount, assetId));
        try {
            selectVideoPlayer(false);
            int videoDurationMs = mediaManager != null ? mediaManager.getVideoDurationMs(assetId) : -1;
            // No fresh routing pass: these are the same bytes the record in hand was measured from,
            // only handed to the other compositor.
            startVideoOnController(videoPlayer, file, assetId, videoDurationMs,
                    activityReference, false, stage, null);
            return true;
        } catch (Exception e) {
            handleException(e);
            return false;
        }
    }

    /**
     * Hands one file to one controller, and makes it the thing the panel is said to hold.
     *
     * @param routing what the routing rule measured for these bytes, or {@code null} from
     *                {@link #fallbackTsPlayerToMediaPlayer}, which re-displays the same file on the
     *                other engine — in which case the measurement already in the record in hand still
     *                describes it, and anything else would be a previous clip's number
     */
    private void startVideoOnController(VideoPlayerController controller, File file, String assetId,
                                        int durationHintMs,
                                        WeakReference<MainActivity> activityReference,
                                        boolean allowTsFallback, DisplayStage stage,
                                        @Nullable VideoRoutingDecision routing) {
        controller.stop();
        PanelDisplay carried = routing == null ? carriedDisplay(assetId, stage.name()) : null;
        PanelDisplay.ClipFacts clip = routing != null ? routing.clip
                : (carried != null ? carried.clip : PanelDisplay.ClipFacts.UNKNOWN);
        String routingFacts = routing != null ? routing.routingFacts
                : (carried != null ? carried.routingFacts : "");
        // The attempt. Where the engine can prove a frame, the photograph stays visible until it does, so
        // promoting here would report `onScreen: true` for a clip that has painted nothing — and the
        // panel's generation would advance on a wall that had not changed. Promotion is
        // revealVideoSurface(), which runs at prepared on TsPlayer (no per-frame proof exists there, and
        // the photo is stepped aside up front by design) and at the first composited frame on the platform
        // player. The routing facts stay reachable meanwhile as media.attempt.
        final PanelDisplay mine = PanelDisplay.video(assetId, file.getAbsolutePath(),
                controller == tsPlayerController
                        ? VideoEngineReport.TS_PLAYER : VideoEngineReport.MEDIA_PLAYER,
                stage.name(), clip, routingFacts,
                panelGeneration.incrementAndGet(), System.currentTimeMillis());
        setPanelAttempt(mine);
        // Which record this controller was handed. Telemetry is only reportable while that answer still
        // matches, which is a stronger check than "is this still the panel": on a video-to-video change the
        // outgoing clip <em>is</em> still the panel while the controller already belongs to the incoming
        // one, and reading its position then would pair one clip's path with another attempt's numbers.
        videoCompositing = mine;
        // Nothing on this surface has been proven yet, whatever the view's visibility says.
        videoSurfaceProven = false;
        // The durable copy of the routing decision. The board row is derived from the record and therefore
        // disappears with the clip; this line is what makes the decision readable a week later, and it is
        // emitted here rather than in routingAllowsTsPlayer so an engine handover re-logs the same
        // measurement under the compositor that actually took the file.
        logDurable("videoRouting", mine.pathOrUrl + " " + mine.routingSentence());
        // TsPlayer SurfaceView must be VISIBLE to get a surface; MediaPlayer TextureView can stay
        // invisible until prepared (ImageView covers both until then).
        if (controller == tsPlayerController) {
            // Counted here rather than at each caller because this is the one place an attempt on an
            // engine actually begins — which includes the mid-attempt swap in
            // fallbackTsPlayerToMediaPlayer, whose caller would otherwise have to remember to
            // bookkeep a decision it did not make.
            tsAttemptsForActiveAsset++;
            controller.setVisibility(View.VISIBLE);
        } else {
            controller.setVisibility(View.INVISIBLE);
        }
        // Both engines, and both for the same reason: `controller.stop()` above has ended what was on this
        // surface — for TsPlayer that is a full deletePlayer(), so the wall is a VISIBLE plane with no
        // decoder behind it, which is the one thing this class's own derivation calls a black plane rather
        // than a clip. Leaving the panel record untouched here made the answer depend on which thread
        // happened to re-derive first, and a TsPlayer→TsPlayer rotation went on reporting the outgoing
        // clip's path, measurements and routing row across the whole recreate window even though that
        // player no longer existed to produce them. On a TsPlayer→MediaPlayer handover the wall is blank
        // from the line above. Deriving now says what the surfaces show; the later revealVideoSurface()
        // replaces it with the incoming clip when there is one.
        reprovePanel();
        controller.setListener(new VideoPlayerListener() {
            @Override
            public void onVideoPrepared() {
                if (!Objects.equals(assetId, activeVideoAssetId)) {
                    return;
                }
                try {
                    if (!MainActivity.this.isDestroyed()) {
                        hideWebView();
                        // Hiding the page is a change to the wall, and on this engine nothing else will
                        // happen for a while: the video surface stays INVISIBLE until a frame is proven, so
                        // a web→video transition left both reports naming a browser view that had just been
                        // taken away. Re-deriving from the views says what is actually visible in the gap —
                        // the photograph underneath, or an empty panel — and the later reveal replaces it.
                        if (controller.supportsPaintSignal()) {
                            reprovePanel();
                        }
                        // "Prepared" is a promise about the file, not about the screen. Where the engine
                        // can prove a frame arrived, the photo stays put until it does, so an attempt
                        // that decodes to nothing leaves the picture that was already there instead of
                        // an empty black root. TsPlayer offers no such proof, so it keeps hiding the
                        // photo up front — its surface sits below the ImageView either way.
                        if (!controller.supportsPaintSignal()) {
                            revealVideoSurface(controller.getView(), mine);
                        }
                        controller.setVisibility(View.VISIBLE);
                        lastVisibleAsset = file.getAbsolutePath();
                        controller.setLooping(true);
                        controller.setFocusable(false);
                        controller.setVolume(0, 0);
                        controller.play();
                        debugInformationProvided(new DebugInformation("video",
                                "prepared duration=" + controller.getDuration()
                                    + " player=" + (controller == tsPlayerController
                                    ? "TsPlayer" : "MediaPlayer")));
                        startVideoWatchdog(assetId, file, activityReference, stage);
                    }
                } catch (Exception e) {
                    if (allowTsFallback && fallbackTsPlayerToMediaPlayer(assetId, file, activityReference, stage)) {
                        return;
                    }
                    restoreImageViewAfterVideoFailure();
                    assetFallback(assetId, activityReference, true, file, stage);
                    handleException(e);
                }
            }

            @Override
            public void onVideoEnd() {
                if (!Objects.equals(assetId, activeVideoAssetId)) {
                    return;
                }
                int position = controller.getCurrentPosition();
                int duration = controller.getDuration();
                debugInformationProvided(new DebugInformation("video",
                        "end pos=" + position + " duration=" + duration));
                if (!brightnessManager.getShouldTheScreenBeOn()) {
                    return;
                }
                // TsVideoView recreates itself for looping; calling play() here double-tears-down.
                if (controller == tsPlayerController && tsVideoView.isRecreatingForLoop()) {
                    debugInformationProvided(new DebugInformation("video", "loop recreate in progress"));
                    return;
                }
                debugInformationProvided(new DebugInformation("video", "restart after end"));
                if (controller != tsPlayerController) {
                    noteMediaManualLoopRestart();
                }
                controller.play();
                videoWatchdogLastPosition = -1;
                videoWatchdogLastProgressMs = System.currentTimeMillis();
                videoWatchdogRecoverAttempted = false;
            }

            @Override
            public boolean onError(int what, int extra) {
                if (!Objects.equals(assetId, activeVideoAssetId)) {
                    return false;
                }
                cancelVideoWatchdog();
                noteDisplayFailure(assetId, "player error what=" + what + " extra=" + extra + " on " + file.getName());
                if (allowTsFallback && fallbackTsPlayerToMediaPlayer(assetId, file, activityReference, stage)) {
                    return true;
                }
                restoreImageViewAfterVideoFailure();
                assetFallback(assetId, activityReference, true, file, stage);
                return false;
            }

            @Override
            public boolean onInfo(int what, int extra) {
                if (!Objects.equals(assetId, activeVideoAssetId)) {
                    return false;
                }
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START
                        || what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                        || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    debugInformationProvided(new DebugInformation("video", describeMediaInfo(what, extra)));
                }
                return false;
            }
        });
        controller.setDurationHint(durationHintMs);
        // The rotation this attempt was routed with, read off the record just adopted rather than off a
        // field left over from whichever clip was measured last.
        controller.setDisplayRotation(clip.rotationDeg);
        controller.setDataSource(file.getPath());
        controller.setLooping(true);
        // Armed here, before any callback: an attempt whose prepareAsync() never returns and never
        // raises is exactly the case the prepared-armed position watchdog cannot see.
        startVideoPaintWatchdog(assetId, file, activityReference, controller, stage, mine);
    }

    /**
     * Steps the photo aside for the surface that is now showing something, and records which view is on
     * screen so {@code turnScreenOn} and the failure paths uncover the right one.
     *
     * <p>The ImageView is hidden, never cleared. {@code Glide.clear()} empties the drawable, and every
     * recovery path in this class reveals the photo by setting it VISIBLE — so clearing here meant that
     * after the first video of a process, "show the image underneath" showed an empty view over a black
     * root. The next image asset clears and reloads it in {@code displayPicture} regardless.
     */
    private boolean revealVideoSurface(View videoView, @Nullable PanelDisplay record) {
        // Step aside for the photo plane only while this attempt still owns the panel's future. A record
        // that is neither the display in flight nor the one already proven belongs to an attempt something
        // else has superseded — and hiding the photograph on its behalf would leave the wall showing a
        // surface no report describes, which is the failure this method existed to prevent.
        if (!PanelDisplay.mayBeRevealed(record, attempt, panel())) {
            Log.i(TAG, "revealVideoSurface: superseded attempt, leaving the panel as reported");
            return false;
        }
        // Taking the other surfaces away is part of uncovering this one, exactly as it is for
        // revealPhotoView(): the browser sits ABOVE the image and both video surfaces, so a clip "revealed"
        // under a page that is still visible is not on the panel at all. That is not a hypothetical pairing —
        // onVideoPrepared hides the page without touching lastVisibleView, which is what turnScreenOn()
        // restores, so a wake from a dark interval put the page back over a clip that had taken it away
        // before the screen went out, and the first frame then hid only the photograph. hideWebView() is
        // idempotent, so a path that already hid it loses nothing.
        hideWebView();
        imageView.setVisibility(View.INVISIBLE);
        lastVisibleView = videoView;
        videoPaintRevealed = true;
        videoSurfaceProven = true;
        noteDisplayProven(record);
        return true;
    }

    private void startVideoPaintWatchdog(String assetId, File file,
                                         WeakReference<MainActivity> activityReference,
                                         VideoPlayerController controller, DisplayStage stage,
                                         PanelDisplay record) {
        cancelVideoPaintWatchdog();
        if (assetId == null) {
            return;
        }
        videoPaintAssetId = assetId;
        videoPaintFile = file;
        videoPaintActivityReference = activityReference;
        videoPaintStage = stage;
        videoPaintRecord = record;
        videoPaintStartMs = System.currentTimeMillis();
        // An engine with no per-frame proof cannot fail this check, and must not be watched by it:
        // getLastFrameAtMs() is a constant 0 there, so arming the poll would spend 12 seconds and then
        // descend the ladder on every TsPlayer display. The photo was already stepped aside at
        // prepared, which is the whole of what this check can say about that engine.
        videoPaintProduced = !controller.supportsPaintSignal();
        videoPaintRevealed = videoPaintProduced;
        if (!videoPaintProduced) {
            scheduleVideoPaintCheck();
        }
    }

    private void cancelVideoPaintWatchdog() {
        if (videoPaintRunnable != null) {
            handler.removeCallbacks(videoPaintRunnable);
            videoPaintRunnable = null;
        }
        videoPaintAssetId = "";
        videoPaintFile = null;
        videoPaintActivityReference = null;
        videoPaintRecord = null;
        videoPaintAwaitingResume = false;
    }

    /**
     * Whether the panel is not ours to paint: quiet hours or the screen toggle put it in the dark, or the
     * Activity is paused behind a dialog.
     *
     * <p>One predicate for the two questions that must never disagree — the paint poll parks its deadline on
     * this, and {@code /state} marks an attempt {@code parked} with it. Written twice they drift, and the
     * drift is what makes a frame sitting out its quiet hours look like a frame that has stopped displaying.
     * A manager that is not built yet counts as lit: nothing is displaying in that instant anyway, and
     * "parked" would claim a suspension this frame has not decided on.
     */
    private boolean paintingSuspended() {
        BrightnessManager manager = brightnessManager;
        return activityPaused || panelIsDark();
    }

    /**
     * The display is switched off — quiet hours, the operator's toggle, or the power button.
     *
     * <p>Not the same question as {@link #paintingSuspended()}, and the difference is a wall rather than a
     * nuance: behind the options dialog the panel is still lit and still showing the frame's content, so a
     * photograph that finishes loading there IS on the wall and must be reported as such. Only darkness
     * makes a visible view show nothing.
     */
    private boolean panelIsDark() {
        BrightnessManager manager = brightnessManager;
        return manager != null && !manager.getShouldTheScreenBeOn();
    }

    /**
     * Stops polling without dropping the obligation. A display the operator switched away — options
     * dialog, quiet hours, the screen toggle — cannot composite frames while it is away, so it must
     * neither spend the deadline nor be forgotten by it.
     */
    private void pauseVideoPaintPoll() {
        if (videoPaintRunnable == null || videoPaintAssetId.isEmpty()) {
            return;
        }
        handler.removeCallbacks(videoPaintRunnable);
        videoPaintRunnable = null;
        videoPaintAwaitingResume = !videoPaintProduced;
        videoPaintPausedMs = System.currentTimeMillis();
    }

    /** Puts an unpainted attempt back on the clock, with the parked time deducted from its budget. */
    private void resumeVideoPaintPollIfPending() {
        if (!videoPaintAwaitingResume) {
            return;
        }
        videoPaintAwaitingResume = false;
        if (videoPaintAssetId.isEmpty() || !videoPaintAssetId.equals(activeVideoAssetId)) {
            return;
        }
        videoPaintStartMs += System.currentTimeMillis() - videoPaintPausedMs;
        // turnScreenOn restores only {@code lastVisibleView}, and an attempt that has not painted is
        // still holding the photo — so the surface would come back INVISIBLE, compose nothing, and the
        // deadline would spend itself on our own visibility bookkeeping rather than on the clip.
        videoPlayer.setVisibility(View.VISIBLE);
        scheduleVideoPaintCheck();
    }

    private void scheduleVideoPaintCheck() {
        if (videoPaintRunnable != null) {
            handler.removeCallbacks(videoPaintRunnable);
        }
        videoPaintRunnable = this::runVideoPaintCheck;
        handler.postDelayed(videoPaintRunnable, VIDEO_PAINT_POLL_MS);
    }

    /**
     * One look at whether the current display attempt has produced a frame: reveal the video when it
     * has, descend one rung of the ladder when it has not often enough to be believed.
     */
    private void runVideoPaintCheck() {
        if (videoPaintRunnable == null || videoPaintAssetId.isEmpty()) {
            return;
        }
        final String assetId = videoPaintAssetId;
        final File file = videoPaintFile;
        final WeakReference<MainActivity> activityReference = videoPaintActivityReference;
        final DisplayStage stage = videoPaintStage;
        if (!assetId.equals(activeVideoAssetId)) {
            cancelVideoPaintWatchdog();
            return;
        }
        if (paintingSuspended()) {
            // Not a verdict — the panel is simply not ours to paint right now.
            pauseVideoPaintPoll();
            return;
        }
        long frameAtMs = videoPlayer.getLastFrameAtMs();
        if (frameAtMs > 0L) {
            if (!videoPaintProduced) {
                videoPaintProduced = true;
                Log.i(TAG, "video: first frame after " + (System.currentTimeMillis() - videoPaintStartMs)
                        + "ms asset=" + assetId);
                debugInformationProvided(new DebugInformation("video", "painted after "
                        + (System.currentTimeMillis() - videoPaintStartMs) + "ms"));
            }
            if (!videoPaintRevealed && !revealVideoSurface(videoPlayer.getView(), videoPaintRecord)) {
                // Refused, which means another display owns the wall and this obligation is finished with.
                // Dropping it is right; dropping it silently is not, because the other path's promotion is
                // its own business and this poll is the one place that knows a frame reached the surface just
                // now. Deriving once here keeps the reports describing the wall rather than whichever
                // call site last remembered to update them.
                reprovePanel();
            }
            cancelVideoPaintWatchdog();
            return;
        }
        long waitedMs = System.currentTimeMillis() - videoPaintStartMs;
        if (waitedMs < VIDEO_PAINT_TIMEOUT_MS) {
            scheduleVideoPaintCheck();
            return;
        }
        Log.w(TAG, "video: " + (file != null ? file.getName() : assetId) + " produced no frame within "
                + VIDEO_PAINT_TIMEOUT_MS / 1000L + "s (stage=" + stage + ")");
        // activePositionMs, not the player directly: this check fires precisely when a player never
        // reached a usable state, and getCurrentPosition() throws in exactly that state — an exception
        // here would reach the global handler and restart the process over a diagnostic line.
        noteDisplayFailure(assetId, "no frame within " + VIDEO_PAINT_TIMEOUT_MS / 1000L
                + "s stage=" + stage + " pos=" + activePositionMs()
                + " on " + (file != null ? file.getName() : "?"));
        cancelVideoPaintWatchdog();
        if (activityReference == null || activityReference.get() == null) {
            return;
        }
        // Uncover the photo plane first, whatever the verdict ends up being: this is the one failure
        // mode where nothing has put a pixel on the panel, so leaving the choice to the rung below could
        // mean a black screen for the rest of the interval even if the next source never arrives.
        restoreImageViewAfterVideoFailure();
        if (stage == DisplayStage.FALLBACK) {
            // The ladder has run out on a clip that was never *refused* — every rung accepted it and
            // rendered nothing. That is not a verdict on the file, so it is not tagged: the asset is
            // remembered as render-silent for this process and shown as its still preview instead.
            MainActivity target = activityReference.get();
            if (target != null && mediaManager != null) {
                mediaManager.noteRenderSilent(assetId);
                mediaManager.showRenderSilentStill(target, assetId);
            }
            return;
        }
        // Earlier rungs descend exactly as an error would, so a clip that silently paints nothing
        // spends the same engine and degraded-source attempts as one that refuses to decode loudly —
        // but it is this call that says so, and the difference decides whether the file gets tagged.
        assetFallback(assetId, activityReference, true, file, stage, true);
    }

    /**
     * Puts the photo plane back on screen and records it as what is showing — the one place that pairing
     * is written down, so no recovery path can uncover a view while leaving {@code lastVisibleView}
     * pointing at a video surface nobody can see.
     *
     * <p>It also restates the panel, and takes the panel's identity from the photo rather than from the
     * caller. Every recovery rung that uncovers the photo is a change to what the wall shows, and four of
     * them were written before this method existed — so each had to remember to say so, and three of them
     * forgot, which left a torn-down clip named as what is on screen. Having each caller name the asset
     * instead was the next mistake: the asset in hand is the one that just <em>failed</em>, while the bitmap
     * the view is uncovering belongs to whatever last loaded successfully. So the identity comes from
     * {@link #provenPhoto}, and an image view holding no drawable reports an empty panel rather than an
     * unnamed photograph.
     */
    private void revealPhotoView() {
        // Taking the other surfaces away is part of uncovering this one. The browser view sits above the
        // image in the layout, so a photo "revealed" without hiding the page is not on the panel at all —
        // and an image rung that fails after a displayed page reached exactly that state, reporting
        // image/none for a whole interval while the page stayed visible. hideWebView() is idempotent, so
        // callers that already did it lose nothing.
        hideWebView();
        imageView.setVisibility(View.VISIBLE);
        lastVisibleView = imageView;
        PanelDisplay target = photoPlaneNow();
        setPanelAttempt(target);
        reprovePanel();
    }

    /**
     * What the photo plane actually holds: the photograph that last <em>loaded</em>, never the asset whose
     * failure made this run — a photo A under a failed video B used to be reported as "screen: B", which
     * mislabelled the visible asset and hid A's own diagnostics behind the wrong subject. An image view with
     * no drawable is an empty panel, not an unnamed photograph.
     */
    private PanelDisplay photoPlaneNow() {
        PanelDisplay drawn = imagePlaneRecord();
        return drawn != null ? drawn : blankPanel();
    }

    /**
     * What the image plane is drawing, or null when it is drawing nothing at all — the single source both
     * the recovery paths and the panel derivation consult, because the two used to disagree.
     *
     * <p>{@link #revealPhotoView()} took the recovery target from one rule and {@link #reproveTarget()}
     * proved the same visible surface by another: with no photograph ever loaded, the layout's wallpaper is
     * what the screen actually shows, so the proof adopted an <em>unidentified image</em> while the attempt
     * was left holding a <em>blank</em> record. Two different records for one screen means
     * {@code media.attempt} never clears after recovery — {@code type: none} beside a visible wallpaper,
     * forever. An image view with no drawable draws nothing, which is why that case is null (look beneath)
     * rather than blank.
     */
    @Nullable
    private PanelDisplay imagePlaneRecord() {
        if (imageView.getDrawable() == null) {
            return null;
        }
        PanelDisplay loaded = provenPhoto;
        return loaded != null ? loaded : unidentifiedPhoto();
    }

    @Override
    public void connected() {
        offlineSinceMs = 0L;
        Log.i(TAG, "ConnectionManager: connected");
        debugInformationProvided(new DebugInformation(getString(R.string.connection_status_key), "connected"));
        startCoreLoopIfNetworkReady();
    }

    @Override
    public void disconnected() {
        if (offlineSinceMs == 0L) {
            offlineSinceMs = System.currentTimeMillis();
        }
        Log.i(TAG, "ConnectionManager: disconnected");
        pauseCoreLoopForNetwork();
    }

    @Override
    public void clientProgressUpdates(long bytesRead, long contentLength, boolean done, @Nullable HttpUrl url) {

        // An undeclared length divides by -1 (a negative percent) or by 0 (Infinity, which rounds to
        // Long.MAX_VALUE), and either poisons the throttle below for the rest of the download.
        long percent = contentLength > 0L ? Math.round((100.0 * bytesRead) / contentLength) : 0L;

        // Nothing about the clocks happens here. They are written on the UI thread, in
        // applyDownloadBar, because this method runs on whichever thread read the bytes: a prefetch
        // can pass MediaManager's gate, stop between here and its own UI post, and resume after the
        // close that follows the counted download it was riding on — stamping a transfer that has
        // already ended, which downloadInFlightMs() then reports and the next download inherits.
        // A finish clears nothing either; the end of the window belongs to downloadCycleClosed.

        // OkHttp invokes this on every read() of the response body, so logging every
        // event floods logcat with repeated "download: 9%" lines. Log the content-length
        // announcement once per download, a progress line only for a percent value not
        // logged yet, and at most one progress line per DOWNLOAD_PROGRESS_LOG_INTERVAL_MS.
        synchronized (downloadProgressLogLock) {
            long nowMs = System.currentTimeMillis();
            if (done || percent < lastLoggedDownloadPercent) {
                // A completed download, or a percent below the last logged value (a new
                // download after one that failed without a done event) resets the throttle.
                if (done) {
                    Log.i(TAG, "download: completed");
                }
                lastLoggedDownloadPercent = -1L;
                lastDownloadProgressLogAtMs = 0L;
            }
            if (!done) {
                if (percent == 0L && lastLoggedDownloadPercent < 0L) {
                    if (contentLength == -1) {
                        Log.i(TAG, "download: content-length unknown");
                    } else {
                        Log.i(TAG, "download: content-length " + contentLength);
                    }
                    lastLoggedDownloadPercent = 0L;
                    lastDownloadProgressLogAtMs = nowMs;
                } else if (contentLength != -1
                        && percent != lastLoggedDownloadPercent
                        && nowMs - lastDownloadProgressLogAtMs >= DOWNLOAD_PROGRESS_LOG_INTERVAL_MS) {
                    Log.d(TAG, "download: " + percent + "%");
                    lastLoggedDownloadPercent = percent;
                    lastDownloadProgressLogAtMs = nowMs;
                }
            }
        }

        // MediaManager only forwards progress events while a remote download is in
        // flight (catalog fetches and local playback never reach this callback).
        this.runOnUiThread(() -> applyDownloadBar(bytesRead, contentLength, done));
    }

    @Override
    public void downloadCycleClosed() {
        // Everything a close does happens on the UI thread, behind the same live count a show is
        // decided by. The count is raised before a download's first read and retired in a finally, so
        // a second thread can open download B in the gap between A's decrement and this running;
        // hiding B's bar or zeroing B's clocks from here would blank a transfer that is genuinely
        // running and re-anchor its age to now. Standing aside strands nothing, because the close
        // that finds the count still at zero is whichever download ends last.
        runOnUiThread(() -> {
            if (downloadBarAction(DownloadBarSignal.CYCLE_CLOSED, 0, downloadCountedInFlight())
                    == DownloadBarAction.UNCHANGED) {
                return;
            }
            downloadStartedAtMs = 0L;
            downloadProgressAtMs = 0L;
            synchronized (downloadProgressLogLock) {
                lastLoggedDownloadPercent = -1L;
                lastDownloadProgressLogAtMs = 0L;
            }
            hideDownloadBar();
        });
    }

    /** Puts the bar into the state one progress event describes. UI thread. */
    private void applyDownloadBar(long bytesRead, long contentLength, boolean done) {
        int permille = downloadBarPermille(bytesRead, contentLength);
        DownloadBarSignal signal = done ? DownloadBarSignal.TRANSFER_FINISHED
                                        : DownloadBarSignal.TRANSFER_PROGRESS;
        boolean inFlight = downloadCountedInFlight();
        if (downloadBytesCounted(signal, inFlight)) {
            long nowMs = System.currentTimeMillis();
            if (downloadStartedAtMs == 0L) {
                downloadStartedAtMs = nowMs;
            }
            downloadProgressAtMs = nowMs;
        }
        switch (downloadBarAction(signal, permille, inFlight)) {
            case SHOW:
                progressIndicator.setMax(DOWNLOAD_BAR_MAX);
                progressIndicator.setProgressCompat(permille, true);
                progressIndicator.setVisibility(View.VISIBLE);
                break;
            case HIDE:
                hideDownloadBar();
                break;
            default:
                break;
        }
    }

    /** Whether a counted download exists at this instant. UI thread. */
    private boolean downloadCountedInFlight() {
        MediaManager manager = mediaManager;
        return manager != null && manager.isDownloadInFlight();
    }

    /** The three things the bar can be asked about. */
    enum DownloadBarSignal {
        /** A read delivered bytes; {@code permille} says how far along they got. */
        TRANSFER_PROGRESS,
        /** A read reached the end of its body. */
        TRANSFER_FINISHED,
        /** No counted download is running — the edge MediaManager retires the count on. */
        CYCLE_CLOSED
    }

    /** What the bar should do about one signal. */
    enum DownloadBarAction { SHOW, HIDE, UNCHANGED }

    /**
     * The download bar's whole rule, as a table rather than as an ordering.
     *
     * <p>Two facts decide it. Whether a counted download exists <em>now</em>, asked at the moment of
     * drawing, is what makes an event's arrival order irrelevant instead of contested. And whether the
     * event carries a fraction: an event with none has nothing to draw, and leaving the previous
     * transfer's fill standing is the lesser truth — a bar frozen at someone else's 60% while this one
     * streams is exactly what an operator reports as a stalled indicator.
     *
     * <p>A finish or a close that finds a download counted says nothing about it, so it changes
     * nothing: that transfer's own events will speak, and whichever close lands with the count back at
     * zero is the one that ends the window.
     */
    static DownloadBarAction downloadBarAction(DownloadBarSignal signal, int permille,
                                               boolean inFlight) {
        if (!inFlight) {
            return DownloadBarAction.HIDE;
        }
        if (signal == DownloadBarSignal.TRANSFER_PROGRESS) {
            return permille >= 0 ? DownloadBarAction.SHOW : DownloadBarAction.HIDE;
        }
        return DownloadBarAction.UNCHANGED;
    }

    /**
     * Whether an event is evidence that bytes are moving <em>now</em>, which is what the in-flight
     * clocks record. Same two facts as the bar's rule and the same reason: asked on the UI thread, so
     * an event that arrives after the close that zeroed the clocks cannot re-anchor them to a transfer
     * that has ended. Bytes from a counted download count even when its length is undeclared, and so
     * draw no bar while still proving the transfer alive — which is why this is not the cell above.
     */
    static boolean downloadBytesCounted(DownloadBarSignal signal, boolean inFlight) {
        return inFlight && signal == DownloadBarSignal.TRANSFER_PROGRESS;
    }

    /**
     * The only place the bar is hidden. It decides nothing: whether a hide is warranted belongs to
     * {@link #downloadBarAction}, so that a transfer with no fraction to show can clear the screen
     * while a counted download is running, and a finish or close that belongs to some other transfer
     * cannot. An earlier revision put the count test in here, and that is how a chunked download
     * inherited the previous file's frozen fill.
     *
     * <p>Emptied before it is hidden so a later transfer starts from nothing rather than animating
     * down from wherever the last one stopped.
     */
    private void hideDownloadBar() {
        progressIndicator.setProgressCompat(0, false);
        progressIndicator.setVisibility(View.INVISIBLE);
    }

    /**
     * The bar's fill for one progress event, out of {@link #DOWNLOAD_BAR_MAX}, or -1 when the
     * response declared no length.
     *
     * <p>A fixed max rather than the byte count for two reasons: a {@code Content-Length} over 2 GiB
     * does not fit the int the view takes, and an unknown length arrives as -1, which is not a max at
     * all — it is the case that left a bar on screen that could never move. Hiding for -1 says less
     * than animating an indeterminate bar would, and this panel has no spare frames to animate with.
     */
    static int downloadBarPermille(long bytesRead, long contentLength) {
        if (contentLength <= 0L) {
            return -1;
        }
        long permille = (Math.max(0L, bytesRead) * DOWNLOAD_BAR_MAX) / contentLength;
        return (int) Math.min(permille, (long) DOWNLOAD_BAR_MAX);
    }

    // ------------------------------------------------------------------
    // WebController — always-on LAN web server (/state, /logs, /files, /control).
    // All of these are called from the web server's worker threads.
    // ------------------------------------------------------------------

    @Override
    public String localIp() {
        try {
            WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        return DeviceTelemetry.ipToString(ip);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "localIp: " + e);
        }
        return "unknown";
    }

    @Override
    public String platform() {
        // "EO1" was the fallback, and it no longer can be: that name now says something about the
        // silicon ("no vendor player"), so answering it before detection has run would put a hardware
        // claim on a page for a frame the app has not identified. "unknown" also matches what the
        // neighbouring fields say when the answer is not there yet.
        return capabilities != null ? capabilities.platform() : "unknown";
    }

    @Override
    public String appBuildSummary() {
        return BuildFacts.summary(this);
    }

    @Override
    public String deviceName() {
        return settingsManager != null ? settingsManager.deviceName() : "";
    }

    /**
     * Called from a web server worker thread; the manager owns which generation is current.
     */
    @Override
    public int forgetMedia(String backendId, String assetKey) {
        MediaManager manager = mediaManager;
        if (manager == null) {
            return 0;
        }
        int forgotten = manager.forgetAssets(backendId, assetKey);
        if (forgotten > 0) {
            stepAwayFrom(backendId, assetKey);
        }
        return forgotten;
    }

    /**
     * Takes a removed photo off the panel. Freeing its bytes and recording the exclusion is not enough:
     * if that photo is what is on screen now, and it was the only asset, the pool rebuilds empty, a
     * "no media" toast appears, and the removed photo stays on the wall indefinitely — a removal that is
     * invisible from the sofa is not a removal.
     */
    private void stepAwayFrom(final String backendId, @Nullable final String assetKey) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                MediaAsset current = mediaManager != null ? mediaManager.getCurrentAsset() : null;
                boolean displayed = current != null
                        && (assetKey == null ? backendId.equals(current.backendId)
                                : assetKey.equals(current.key()));
                if (displayed) {
                    // Cleared rather than left: every other recovery path reveals the photo by making the
                    // view visible again, so an emptied view here is undone by the next display.
                    lastVisibleAsset = "";
                    try {
                        Glide.with(MainActivity.this).clear(imageView);
                    } catch (Exception ignored) {
                        // A destroyed activity cannot clear a view; the next display replaces it.
                    }
                    imageView.setImageDrawable(null);
                    // The photo this view was holding is gone, so no later recovery may name it as what the
                    // panel holds. Forgetting this is how a removed asset comes back as "what is on screen"
                    // long after the operator asked for it to be forgotten.
                    provenPhoto = null;
                    // The wall is empty from here, so say that. Naming no asset would leave the removed
                    // one as what is on screen — permanently, because if this was the only asset in the
                    // pool, every later tick fails the same way and no display method ever runs again.
                    // It is also the one state that must not be written as an image with an empty path:
                    // a cleared panel claiming a photograph is the same false present tense as the bug this
                    // record was built to remove. But "blank" is only the answer when the photo plane was
                    // what the viewer was looking at — forgetting a photograph that was loading over a
                    // displayed web page leaves the page on screen, so the remaining panel is derived from
                    // the views rather than asserted here.
                    //
                    // And the attempt is restated with it, not left behind. panelNowProven deliberately does
                    // not touch attempt, which is right for a display and wrong here: this is the one recovery
                    // path that cannot go through revealPhotoView() (it has to empty the drawable, and
                    // uncovering it would put the removed photo back), so nothing else would say "nothing is
                    // being put up any more". Left unstated, the field that answers "what is in flight" named
                    // an asset whose bytes were unlinked under it — media.attempt printed that phantom
                    // forever whenever no later display runs, which is this method's own stated case — and
                    // currentPanelAssetKey() kept attributing every undeclared diagnostic to the deleted
                    // asset, where DebugBoard.visible then filtered those rows out as not-about-the-panel.
                    PanelDisplay remaining = reproveTarget();
                    setPanelAttempt(remaining);
                    panelNowProven(remaining);
                }
                if (mediaManager != null) {
                    mediaManager.showNextImage(MainActivity.this);
                }
            }
        });
    }

    /**
     * Called from the web server's worker threads, so it may not throw: an exception escaping here would
     * reach the process-wide handler that exits the app.
     */
    @Override
    public String serviceTermsNotice() {
        try {
            Configuration c = settingsManager == null ? null : settingsManager.getConfiguration();
            return Configuration.requiredServiceNotices(c);
        } catch (Exception e) {
            return "";
        }
    }

    /** Called from the web server's worker threads; {@code SettingsManager} does the refresh. */
    @Override
    public String setDeviceName(String requested) {
        return settingsManager != null ? settingsManager.setDeviceName(requested) : "";
    }

    @Override
    public String buildStateJson() {
        JsonObject o = new JsonObject();

        JsonObject device = new JsonObject();
        device.addProperty("manufacturer", Build.MANUFACTURER);
        device.addProperty("model", Build.MODEL);
        device.addProperty("buildHardware", Build.HARDWARE);
        device.addProperty("buildBoard", Build.BOARD);
        device.addProperty("boardPlatform", tsBoardPlatform);
        device.addProperty("cpu", VideoSilicon.cpuModelLine());
        // Identity, not capability: which family this board belongs to is the first thing a video
        // report needs and the last thing a model string can tell you, so all three published names
        // are printed rather than one inferred (docs/RESEARCH.md §1.3).
        device.addProperty("android", Build.VERSION.RELEASE);
        device.addProperty("platform", capabilities.platform());
        // Omitted, not empty, when the frame was never named — a reader then falls back to
        // platform and address, which is all there ever was.
        String deviceName = deviceName();
        if (!deviceName.isEmpty()) {
            device.addProperty("name", deviceName);
        }
        o.add("device", device);

        JsonObject caps = new JsonObject();
        caps.addProperty("lightSensor", capabilities.supportsLightSensor());
        caps.addProperty("screenBrightness", capabilities.supportsScreenBrightness());
        caps.addProperty("brightnessButton", capabilities.supportsBrightnessButton());
        caps.addProperty("powerButton", capabilities.supportsPowerButton());
        caps.addProperty("screenToggleKeyCode", capabilities.screenToggleKeyCode());
        o.add("capabilities", caps);

        JsonObject app = new JsonObject();
        app.addProperty("version", BuildFacts.version());
        app.addProperty("versionName", BuildFacts.versionName());
        app.addProperty("buildNumber", BuildFacts.buildNumber());
        app.addProperty("variant", BuildFacts.variant());
        app.addProperty("buildDate", BuildFacts.buildStamp());
        app.addProperty("gitSha", BuildFacts.gitSha());
        app.addProperty("gitDirty", "true".equals(BuildFacts.gitDirty()));
        app.addProperty("debug", BuildConfig.DEBUG);
        app.addProperty("tsPlayer", preferTsPlayer);
        app.addProperty("webPort", webServer != null ? webServer.getBoundPort() : -1);
        app.addProperty("trustedNetwork", settingsManager != null && settingsManager.isTrustedNetwork());
        app.addProperty("createCount", sActivityCreateCount);
        app.addProperty("processStartMs", PROCESS_START_MS);
        BuildFacts.Signer signer = BuildFacts.signing(this);
        JsonObject signerJson = new JsonObject();
        signerJson.addProperty("signed", signer.signed);
        signerJson.addProperty("fingerprint", signer.fingerprintHex);
        signerJson.addProperty("subject", signer.subject);
        signerJson.addProperty("serial", signer.serial);
        signerJson.addProperty("validFrom", signer.validFrom);
        signerJson.addProperty("validTo", signer.validTo);
        signerJson.addProperty("validity", signer.validityStatus);
        signerJson.addProperty("algorithm", signer.algorithm);
        signerJson.addProperty("keyVerdict", signer.keyVerdict);
        app.add("signer", signerJson);
        o.add("app", app);

        // The whole backlight story in one place: what the user set, what the sensor last saw, and
        // what is actually on the panel. A "it dimmed by itself" report is answerable from this.
        // `supported` is the panel's ability, not a control's existence — whether this device has a
        // button that would ever write the level is `capabilities.brightnessButton`.
        // `step` is what this process is acting on; `persistedStep` is what is on disk right now, and
        // is null when the store has nothing stored ("absent" in `storeState`) or would not answer
        // ("unreadable" there) — the two cases the old always-integer read made indistinguishable
        // from a level of 0. So `persistedStep: 0` now means an operator did put this frame on the
        // floor by wrapping past the top step; a frame nobody ever adjusted reads null with
        // `stepSource: "default"`. `lastKnownGood` is what an unreadable store is answered with,
        // never the floor, and `lastChange` says who moved the level last.
        final int persistedStep = settingsManager.readMinimumBrightnessStep();
        final int cachedStep = settingsManager.lastKnownGoodBrightnessStep();
        JsonObject brightness = new JsonObject();
        brightness.addProperty("supported", capabilities.supportsScreenBrightness());
        brightness.addProperty("step", brightnessManager.getMinimumBrightnessStep());
        brightness.add("persistedStep", persistedStep >= 0
                ? (JsonElement) new JsonPrimitive(persistedStep) : JsonNull.INSTANCE);
        brightness.add("lastKnownGoodStep", cachedStep >= 0
                ? (JsonElement) new JsonPrimitive(cachedStep) : JsonNull.INSTANCE);
        brightness.addProperty("storeState", settingsManager.minimumBrightnessStoreState());
        brightness.addProperty("stepSource", brightnessManager.stepSource());
        brightness.addProperty("authoritative", brightnessManager.isStepAuthoritative());
        brightness.addProperty("floor", brightnessManager.minimumBrightness());
        brightness.addProperty("applied", lastAppliedBrightness);
        brightness.addProperty("lastLightValue", brightnessManager.lastLightValue());
        brightness.addProperty("screenOn", brightnessManager.getShouldTheScreenBeOn());

        // The display hold, READ rather than remembered. `applied` above and `screenOn` here are both
        // intent — what this process asked the window for — and on the frame that started inside a
        // non-quiet minute both were true of a panel that had gone dark without being asked to stay
        // up. `keepScreenOn` is the live window flag and `wakeLockHeld` the lock behind it, so
        // "applied 0.8, keepScreenOn false" is this whole report in two fields.
        JsonObject hold = new JsonObject();
        hold.addProperty("keepScreenOn", screenHoldFlagPresent());
        PowerManager.WakeLock displayLock = screenOffWakeLock;
        hold.addProperty("wakeLockHeld", displayLock != null && displayLock.isHeld());
        hold.addProperty("assertedAtMs", screenHoldAtMs);
        hold.addProperty("repairs", screenHoldRepairs);
        brightness.add("hold", hold);

        JsonObject lastChange = new JsonObject();
        lastChange.addProperty("from", brightnessManager.changedFromStep());
        lastChange.addProperty("to", brightnessManager.changedToStep());
        lastChange.addProperty("via", brightnessManager.changedVia());
        lastChange.addProperty("atMs", brightnessManager.changedAtMs());
        lastChange.addProperty("durable", brightnessManager.changedDurably());
        brightness.add("lastChange", lastChange);

        o.add("brightness", brightness);

        o.add("config", ConfigStateJson.configStateJson(settingsManager.getConfiguration()));

        JsonObject network = new JsonObject();
        network.addProperty("available", connectionManager.isNetworkAvailable());
        network.add("wifi", DeviceTelemetry.wifi(this));
        o.add("network", network);

        // One snapshot of the panel — its content, its handoff count and its timestamp, which travel in one
        // object precisely so a read cannot take them from two different moments. Everything below that
        // describes the display reads `shown` or `panelSnapshot`, never the field again.
        PanelState panelSnapshot = panelState;
        PanelDisplay shown = panelSnapshot == null ? null : panelSnapshot.display;

        JsonObject media = new JsonObject();
        media.addProperty("rotationAssets", mediaManager.rotationRemainingInPass());
        media.addProperty("screenOn", brightnessManager.getShouldTheScreenBeOn());
        // Two clocks, two different claims, and the names say which is which: the first is the wedge
        // clock the ladder escalates on, the second only proves the timer is turning over. A reader
        // comparing them is reading the diagnosis — proven old while ticked fresh means a loop that is
        // alive and displaying nothing, which is the 2026-11-08 failure.
        media.addProperty("lastDisplayProvenAtMs", lastDisplayProvenAtMs());
        media.addProperty("lastRotationTickAtMs", lastRotationActivityMs);
        MediaAsset currentAsset = mediaManager.getCurrentAsset();
        if (currentAsset != null) {
            JsonObject current = new JsonObject();
            current.addProperty("key", currentAsset.key());
            current.addProperty("type", mediaTypeName(currentAsset.type));
            current.addProperty("backendId", currentAsset.backendId);
            if (currentAsset.type == MediaType.WEB) {
                // The address of what rotation **selected**, which is what this object means, not the
                // address of what the wall is showing. `webUrlOnScreen` — the obvious source — is written
                // on the UI thread by displayWebsite, survives a page being superseded, and is blanked by
                // abandonWebPage, so it printed one page for a selection of another, and "" for a page
                // still selected. A web asset's id is a UUID of its URL, so the selection carries no
                // address of its own to print; the entry that produced it does. The wall's own address is
                // honest where it is documented: screen.pathOrUrl and attempt.pathOrUrl.
                String selectedPage = configuredWebUrl(currentAsset.backendId, currentAsset.id);
                if (selectedPage != null) {
                    current.addProperty("url", selectedPage);
                }
            }
            if (currentAsset.originalFileName != null) {
                current.addProperty("originalFileName", currentAsset.originalFileName);
            }
            current.addProperty("width", currentAsset.width);
            current.addProperty("height", currentAsset.height);
            current.addProperty("durationMs", currentAsset.durationMs);
            if (currentAsset.sizeBytes != null) {
                current.addProperty("sizeBytes", currentAsset.sizeBytes);
            }
            current.addProperty("local", currentAsset.localPath != null);
            media.add("current", current);
        }
        // What the panel holds, kept separate from what rotation selected rather than collapsed into it.
        // The two differ by one posted UI runnable in normal operation, and a `current` that keeps
        // advancing while `screen.generation` stops moving is the shape of a frame that has stopped
        // painting — which one field naming both facts can never show.
        JsonObject screen = new JsonObject();
        if (shown == null) {
            screen.addProperty("type", "none");
        } else {
            // VideoStateJson's mapper, not this file's: the record's null type means "nothing on the
            // panel", and media.screen and video.showing must not spell the same state two ways.
            screen.addProperty("type", VideoStateJson.mediaTypeName(shown.type));
            if (!shown.assetKey.isEmpty()) {
                screen.addProperty("key", shown.assetKey);
            }
            if (!shown.pathOrUrl.isEmpty()) {
                screen.addProperty("pathOrUrl", shown.pathOrUrl);
            }
            if (!shown.engine.isEmpty()) {
                screen.addProperty("engine", shown.engine);
            }
            if (!shown.stage.isEmpty()) {
                screen.addProperty("stage", shown.stage);
            }
            // Handoffs, not intentions: the record's own number counts when the display was decided on,
            // which for an asynchronously loaded image or a preparing clip is not when it reached the wall.
            screen.addProperty("generation", panelSnapshot.changes);
            screen.addProperty("sinceMs", panelSnapshot.changedAtMs);
            screen.addProperty("attemptGeneration", shown.generation);
        }
        media.add("screen", screen);
        o.add("media", media);

        JsonObject input = new JsonObject();
        input.addProperty("windowFocused", windowFocused);
        input.addProperty("activityPaused", activityPaused);
        input.addProperty("focusedView", focusedView);
        input.addProperty("lastKeyCode", lastKeyEventCode);
        input.addProperty("lastKeyAtMs", lastKeyEventAtMs);
        o.add("input", input);

        o.add("update", updateManager.stateJson());
        o.add("mqtt", mqttStateJson());
        o.add("watchdog", watchdogStateJson());
        o.add("crash", crashBlockJson());
        o.add("trust", trustBlockJson());
        o.add("diagnostics", diagnosticsBlockJson());

        o.add("telemetry", DeviceTelemetry.snapshot(this));

        // Reported on every build, not just debug. This is the block that answers "what is on the screen,
        // and why is it black", and a release frame that cannot say which engine it chose, which rung of
        // the ladder it is on, or whether a single frame ever composited leaves an operator staring at a
        // black panel with no evidence to act on. The TsPlayer-internal counters stay debug-only.
        //
        // `shown` is the snapshot taken above, shared with the media block. The builder itself lives in
        // VideoStateJson so the rule "describe only what the panel holds" is testable at all.
        VideoStateJson.Frame frameFacts = new VideoStateJson.Frame();
        frameFacts.engine = videoPlayer == tsPlayerController
                ? VideoEngineReport.TS_PLAYER : VideoEngineReport.MEDIA_PLAYER;
        frameFacts.preferTsPlayer = preferTsPlayer;
        frameFacts.tsSupported = tsAllowed;
        frameFacts.tsLibraryLoaded = tsLibraryLoaded;
        frameFacts.tsLoadError = tsLoadError;
        frameFacts.tsSilicon = tsSilicon;
        frameFacts.modelProfile = platform();
        frameFacts.tsModelAllows = tsModelAllows;
        frameFacts.tsGateAxis = tsGateAxis;
        frameFacts.buildFlag = BuildConfig.USE_TSPLAYER;
        frameFacts.panelShape = lastFrameShape == null ? null : lastFrameShape.toString();
        frameFacts.frameOrientation = lastFrameOrientation;
        frameFacts.tsFallbacks = tsFallbackCount;
        frameFacts.playbackRateX100 = videoPlaybackRateX100;
        frameFacts.playbackRateMinX100 = videoPlaybackRateMinX100;
        frameFacts.playbackRateMaxX100 = videoPlaybackRateMaxX100;
        frameFacts.playbackRateSamples = videoPlaybackRateSamples;
        frameFacts.debugBuild = BuildConfig.DEBUG;
        o.add("video", VideoStateJson.build(shown, frameFacts, new StatePlayerProbe(shown)));

        // What a display path is putting on the panel right now, present only while that differs from what
        // is provably up. Two facts, because they are two different moments: Glide has the new file loading
        // and the old bitmap still showing, the platform player has the clip prepared and the photograph
        // still covering it. Seeing both is what distinguishes "trying" from "stuck" from "lying".
        //
        // `parked` is the third of those, and the reason the attempt is not simply hidden while the panel is
        // dark. A frame in quiet hours with an attempt that was interrupted mid-prepare is showing a display
        // that will not land for hours, which looks exactly like a display that never lands — but the two
        // need different answers, and hiding the attempt entirely (which an earlier revision of
        // turnScreenOff did, by nulling it) destroyed the identity the paint poll needs to finish the reveal
        // on waking. So the attempt stays, and says whether the panel is even allowed to paint.
        PanelDisplay pending = attempt;
        if (pending != null && pending != shown) {
            JsonObject attemptJson = new JsonObject();
            attemptJson.addProperty("type", VideoStateJson.mediaTypeName(pending.type));
            if (!pending.assetKey.isEmpty()) {
                attemptJson.addProperty("key", pending.assetKey);
            }
            if (!pending.pathOrUrl.isEmpty()) {
                attemptJson.addProperty("pathOrUrl", pending.pathOrUrl);
            }
            if (!pending.engine.isEmpty()) {
                attemptJson.addProperty("engine", pending.engine);
            }
            if (!pending.stage.isEmpty()) {
                attemptJson.addProperty("stage", pending.stage);
            }
            attemptJson.addProperty("generation", pending.generation);
            // The same predicate that parks the paint poll (runVideoPaintCheck), so the report says the
            // panel is not allowed to paint exactly when the code watching it decided to stop trying.
            attemptJson.addProperty("parked", paintingSuspended());
            media.add("attempt", attemptJson);
        }

        if (BuildConfig.DEBUG) {
            // The same filtered view the panel renders, so /state can never print a row about an asset the
            // wall no longer holds while the overlay correctly omits it. One filter, two surfaces.
            // Filtered by the <em>snapshot</em> every other panel-scoped field in this response was built
            // from, not by re-reading the field: a handoff landing during the update/network/telemetry reads
            // above would otherwise emit a document whose video.showing describes the outgoing clip while
            // debug["on screen"] describes the incoming photograph — one response, two panels.
            Map<String, String> printable;
            synchronized (debugInformation) {
                printable = DebugBoard.visible(debugInformation, shown);
            }
            if (!printable.isEmpty()) {
                JsonObject debug = new JsonObject();
                for (Map.Entry<String, String> entry : printable.entrySet()) {
                    debug.addProperty(entry.getKey(), entry.getValue());
                }
                o.add("debug", debug);
            }
        }

        return o.toString();
    }

    /**
     * The live player reads {@code /state}'s video block is allowed to make, and only for a clip the
     * panel actually holds.
     *
     * <p>Behind an interface so {@link VideoStateJson} can be tested without a player, and so the
     * question "did the report touch the engine?" has one answer in the code rather than one per field.
     * Calling these while a photograph is on the screen meant asking a released player for its position
     * from the web server's thread, at whatever moment the UI thread was tearing it down.
     */
    private final class StatePlayerProbe implements VideoStateJson.PlayerProbe {

        /** The record this document was built for. Anything else and the live reads below are meaningless. */
        private final PanelDisplay subject;

        StatePlayerProbe(PanelDisplay subject) {
            this.subject = subject;
        }

        @Override
        public boolean stale() {
            if (panel() != subject) {
                return true;
            }
            // The panel check alone is not enough for a clip. On a video-to-video change the outgoing clip
            // is <em>still the panel</em> — the incoming one has not proven a frame yet — while the
            // controller has already been handed the new file, so reading position now would attach the
            // incoming attempt's numbers to the outgoing clip's path. The controller's own ownership is the
            // only test that sees this.
            return subject.showsVideo() && videoCompositing != subject;
        }

        @Override
        public boolean painted() {
            return videoPaintProduced;
        }

        @Override
        public long lastFrameAtMs() {
            VideoPlayerController active = videoPlayer;
            return active == null ? 0L : active.getLastFrameAtMs();
        }

        @Override
        public long gapSnapshot() {
            // One controller reference, one load: reading the two gap getters would be two samples of a word
            // that surface destruction can clear in between, and the reset is invisible to stale().
            VideoPlayerController active = videoPlayer;
            return active == null ? FrameGapWindow.emptySnapshot() : active.getGapSnapshot();
        }

        @Override
        public int durationMs() {
            return activeDurationMs();
        }

        @Override
        public int positionMs() {
            return activePositionMs();
        }

        @Override
        public VideoStateJson.NativeCounters nativeCounters() {
            if (tsVideoView == null) {
                return null;
            }
            VideoStateJson.NativeCounters counters = new VideoStateJson.NativeCounters();
            counters.errors = tsVideoView.getVideoErrorCount();
            counters.playerCreated = tsVideoView.isPlayerCreated();
            counters.surfaceReady = tsVideoView.isSurfaceReady();
            counters.recreatingForLoop = tsVideoView.isRecreatingForLoop();
            counters.createPlayerMs = tsVideoView.getLastCreatePlayerMs();
            counters.setSurfaceMs = tsVideoView.getLastSetSurfaceMs();
            counters.startMs = tsVideoView.getLastStartMs();
            counters.restartMethod = tsVideoView.getLastRestartMethod();
            return counters;
        }

        @Override
        public VideoStateJson.ManualCounters manualCounters() {
            VideoStateJson.ManualCounters counters = new VideoStateJson.ManualCounters();
            counters.manualLoopRestarts = mediaManualLoopRestarts;
            counters.manualLoopCycleMs = mediaManualLoopCycleMs;
            return counters;
        }

        @Override
        public boolean renderSilent(String assetKey) {
            return mediaManager != null && mediaManager.isRenderSilent(assetKey);
        }
    }

    @Override
    public String getLogTail(int lines) {
        return appLogger != null ? appLogger.tailJson(lines) : "[]";
    }

    @Override
    public String getFileLogTail(int lines) {
        return appLogger != null ? appLogger.tailFile(lines) : "";
    }

    @Override
    public File uploadedDir() {
        return UploadedMedia.dirFor(this);
    }

    @Override
    public UploadedMedia.FileInfo[] listUploadedFiles() {
        return UploadedMedia.list(UploadedMedia.dirFor(this));
    }

    @Override
    public File uploadedFile(String name) {
        return UploadedMedia.resolve(UploadedMedia.dirFor(this), name);
    }

    @Override
    public boolean deleteUploadedFile(String name) {
        return UploadedMedia.delete(UploadedMedia.dirFor(this), name);
    }

    @Override
    public File crashDir() {
        return CrashStore.dirFor(this);
    }

    @Override
    public CrashStore.Info[] listCrashDumps() {
        return CrashStore.list(CrashStore.dirFor(this));
    }

    @Override
    public File crashDumpFile(String name) {
        return CrashStore.resolve(CrashStore.dirFor(this), name);
    }

    @Override
    public boolean deleteCrashDump(String name) {
        return CrashStore.delete(CrashStore.dirFor(this), name);
    }

    @Override
    public File reportDir() {
        return DiagnosticsReport.dirFor(this);
    }

    @Override
    public DiagnosticsReport.Info[] listReports() {
        // readHeaders, unlike the store's own name-only callers: this feeds a page a person reads, and
        // Reason — the one line saying why the frame wrote the file — lives in the header. Same choice
        // CrashStore.list makes for /crashes. Bounded at 40 files x 1 KB, and only on this route:
        // /state's summary and pruning keep reading names alone.
        return DiagnosticsReport.list(DiagnosticsReport.dirFor(this), true);
    }

    @Override
    public File reportFile(String name) {
        return DiagnosticsReport.resolve(DiagnosticsReport.dirFor(this), name);
    }

    @Override
    public boolean deleteReport(String name) {
        return DiagnosticsReport.delete(DiagnosticsReport.dirFor(this), name);
    }

    /**
     * Writes one report on request from the LAN page. Runs the collection on the calling web worker
     * through a capture thread it joins, and not through {@code runOnUiThread}: the thread this exists
     * to diagnose may be the UI thread, and a capture that posts to it would wait on the hang it came
     * to record.
     */
    @Override
    public DiagnosticsReport.Result captureReport() {
        return captureDiagnostics(DiagnosticsReport.TRIGGER_MANUAL, "requested over the LAN", true);
    }

    /**
     * The {@code /state} {@code crash} block. Wrapped because a throw anywhere inside
     * {@link #buildStateJson} would otherwise 500 the whole document — the same reason
     * {@code WebServer} wraps every out-of-route controller call in a degraded default.
     */
    private JsonObject crashBlockJson() {
        JsonObject o = new JsonObject();
        try {
            o.add("store", CrashStore.statsJson(CrashStore.dirFor(this)));
            JsonObject sources = new JsonObject();
            CrashSources.Availability a = CrashSources.last();
            sources.addProperty("logcat", a.logcat);
            sources.addProperty("dropbox", a.dropbox);
            sources.addProperty("anr", a.anr);
            sources.addProperty("copies", a.copies);
            sources.addProperty("readLogs", hasReadLogsPermission() ? "granted" : "denied");
            // Only when there is something to say. "unavailable" is five diagnoses wearing one word,
            // and this is the ungated read an operator has on a frame that is not producing logcat.
            if (a.logcatReason != null) {
                sources.addProperty("reason", a.logcatReason);
            }
            o.add("sources", sources);
        } catch (Throwable t) {
            // Report whatever was assembled before the failure; never fail /state over this.
        }
        return o;
    }

    /**
     * The {@code /state} {@code trust} block: what this frame's certificate stores hold, what the app
     * chose to trust on top of them, and what time the frame thinks it is.
     *
     * <p>This is the read-only half of the root-certificate feature, and it is the half that earns the
     * rest. "A frame cannot reach Immich" has four causes that look identical from the display — a
     * missing anchor, a cipher suite with nothing in common, a certificate outside its own window, and
     * a wrong clock — and three of them cannot be told apart from this machine. The census answers all
     * four at once: anchor counts per store, the shipped roots this device lacks, and {@code nowMs}
     * against the workstation reading the page.
     *
     * <p>{@code mode} is the field to read first. {@code merged} means the bundle is in force for this
     * app's connections; {@code platform-only} means it is not, and {@code detail} says whether that
     * was the operator's choice or a refusal. Note what neither value claims: an app-local trust
     * decision covers the Immich client and the update download, and cannot reach the kiosk
     * {@code WebView}, whose engine validates inside Chromium. See docs/TRUST.md.
     */
    /** How many of the shipped anchors a store lacks by name before {@code /state} stops listing them. */
    private static final int MISSING_ANCHORS_SHOWN = 12;

    private JsonObject trustBlockJson() {
        JsonObject o = new JsonObject();
        try {
            MergedTrust.Result trust = MergedTrust.last();
            o.addProperty("mode", trust.mode);
            o.addProperty("detail", trust.detail);
            o.addProperty("enabled", settingsManager != null && settingsManager.isTrustBundleEnabled());
            o.addProperty("bundleSize", RootBundle.size());
            o.addProperty("platformAnchors", trust.platformAnchors);
            // Only in MODE_MERGED is `mergedAnchors` the set this frame actually trusts. In
            // platform-only mode the value is either -1 (never counted, bundle off, enumeration
            // failed) or the size of a merged set the narrowing guard enumerated and then explicitly
            // REFUSED to install — so one key carried three meanings, and the middle one reported more
            // anchors in force than the frame has. `platformAnchors` above is that mode's honest number,
            // which makes this key's absence a statement rather than a loss.
            if (!trust.isPlatformOnly()) {
                o.addProperty("trustedAnchors", trust.mergedAnchors);
            }
            // The frame's own opinion about what time it is. Short-lived certificates make this a
            // first-class diagnostic: nothing in this app sets the clock, and a frame powered off for a
            // week can trust every anchor in the world and still reject all of them.
            o.addProperty("nowMs", System.currentTimeMillis());

            // Which of the four gates can be closed at all from this device. An ECDSA-certified host
            // with no ECDSA suite here fails before its certificate is ever read, so no certificate
            // work can help it — and that is the difference between "the fix failed" and "the fix
            // does not apply to this server".
            String[] suites = MergedTrust.platformCipherSuites();
            boolean ecdsa = false;
            boolean gcm = false;
            for (int i = 0; i < suites.length; i++) {
                if (suites[i].indexOf("ECDSA") >= 0) {
                    ecdsa = true;
                }
                if (suites[i].indexOf("GCM") >= 0 || suites[i].indexOf("CCM") >= 0) {
                    gcm = true;
                }
            }
            JsonObject gates = new JsonObject();
            gates.addProperty("suiteCount", suites.length);
            gates.addProperty("anyEcdsa", ecdsa);
            gates.addProperty("anyAead", gcm);
            JsonArray names = new JsonArray();
            for (int i = 0; i < suites.length; i++) {
                names.add(suites[i]);
            }
            gates.add("supported", names);
            o.add("gates", gates);

            Date now = new Date();
            JsonArray stores = new JsonArray();
            stores.add(storeJson(TrustCensus.SYSTEM_STORE,
                    TrustCensus.censusDirectory(new File(TrustCensus.SYSTEM_STORE), now)));
            stores.add(storeJson(TrustCensus.USER_ADDED_STORE,
                    TrustCensus.censusDirectory(new File(TrustCensus.USER_ADDED_STORE), now)));
            o.add("stores", stores);
        } catch (Throwable t) {
            // Report whatever was assembled before the failure; /state must not 500 over a diagnosis.
        }
        return o;
    }

    /** One store's census, shaped the same whether or not the app was allowed to look inside it. */
    private static JsonObject storeJson(String path, TrustCensus.Store store) {
        JsonObject o = new JsonObject();
        o.addProperty("path", path);
        o.addProperty("readable", store.isKnown());
        o.addProperty("total", store.total);
        o.addProperty("expired", store.expired);
        o.addProperty("notYetValid", store.notYetValid);
        o.addProperty("unparseable", store.unparseable);
        // Bounded on purpose. Against a ROM store from 2013 a whole-distribution bundle is "missing" from
        // almost every frame, so the untruncated list is ~110 strings per store — tens of KB of near-zero
        // information inside a diagnostics section that budgets 128 KB for /state as a whole. The count is
        // the number anyone acts on; the names are a sample to confirm it.
        List<String> missingAnchors = TrustCensus.missingFromBundle(store);
        o.addProperty("missingBundleCount", missingAnchors.size());
        JsonArray missing = new JsonArray();
        for (int i = 0; i < missingAnchors.size() && i < MISSING_ANCHORS_SHOWN; i++) {
            missing.add(missingAnchors.get(i));
        }
        o.add("missingBundleAnchors", missing);
        return o;
    }

    /**
     * The {@code /state} {@code diagnostics} block: how much evidence the frame has been writing about
     * itself, and whether the newest of it came from a reboot. Names only, so this costs a directory
     * listing rather than a read per report — the same reason {@code CrashStore.stats} exists.
     *
     * <p>It is in {@code /state} rather than only on the reports page because the question it answers
     * ("did the frame already document the thing you are about to report?") is asked while someone is
     * still reading {@code /state}.
     */
    private JsonObject diagnosticsBlockJson() {
        JsonObject o = new JsonObject();
        try {
            o.add("store", DiagnosticsReport.statsJson(DiagnosticsReport.dirFor(this)));
        } catch (Throwable t) {
            // As with the crash block: a summary that cannot be read is a missing key, not a 500.
        }
        return o;
    }

    /** Whether this install was granted the signature-level READ_LOGS (false on a sideload). */
    private boolean hasReadLogsPermission() {
        try {
            return checkPermission("android.permission.READ_LOGS",
                    android.os.Process.myPid(), android.os.Process.myUid())
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public File apkUploadDir() {
        return updateManager != null ? updateManager.updatesDir() : null;
    }

    /**
     * Verifies an uploaded APK the same way as the self-update download path and stages it
     * for install. Runs on the web server's worker thread; never auto-installs.
     */
    @Override
    public ApkUploadResult uploadApk(File apk) {
        if (updateManager == null || apk == null) {
            return ApkUploadResult.reject("", 0, false, "update-manager-unavailable");
        }
        return updateManager.stageUploadedApk(apk);
    }

    /**
     * Mirrors a hardware key press from the web UI. Every action is posted to the UI thread
     * because most of them touch views or start activities — except {@code keyevent}, which
     * is injected from the calling web worker thread: {@code Instrumentation#sendKeyDownUpSync}
     * blocks on the UI thread's looper and would deadlock if posted to that thread.
     *
     * <p>{@code keyevent} is a debug-build-only admin action: it injects arbitrary keycodes,
     * so a release build refuses it (the web server also requires POST for it).
     *
     * <p>{@code show} is the other debug-only action, and the only one that is not a key press: it
     * pins one rotation asset by ID as the next tick's display target, through
     * {@link MediaManager#queueAssetDisplay} rather than by adding a second way to render an asset.
     */
    @Override
    public boolean control(String action, Map<String, String> params) {
        if (action == null) {
            return false;
        }
        if ("keyevent".equals(action)) {
            if (!BuildConfig.DEBUG) {
                return false;
            }
            String rawCode = params != null ? params.get("code") : null;
            int keyCode;
            try {
                keyCode = Integer.parseInt(rawCode);
            } catch (NumberFormatException e) {
                return false;
            }
            if (keyCode < 0 || keyCode > 0xFFFF) {
                return false;
            }
            return sendKeyCode(keyCode);
        }
        if ("watchdog".equals(action)) {
            // Debug builds only: rungs escalate over hours on real hardware, and the whole point of
            // the ladder is that nobody has to be there when it fires. level 1..5 forces one.
            if (!BuildConfig.DEBUG || kioskWatchdog == null) {
                return false;
            }
            String rawLevel = params != null ? params.get("level") : null;
            int level;
            try {
                level = Integer.parseInt(rawLevel);
            } catch (NumberFormatException e) {
                level = KioskWatchdog.LEVEL_FORCE_ROTATION;
            }
            if (level < KioskWatchdog.LEVEL_FORCE_ROTATION || level > KioskWatchdog.LEVEL_REBOOT) {
                return false;
            }
            // Called from the web thread on purpose: every rung dispatches to whichever thread owns
            // the work, so forcing the self-restart rung still works when the UI thread is the
            // thing that is stuck.
            kioskWatchdog.forceAction(level);
            return true;
        }
        final Runnable task;
        switch (action) {
            case "next":
                task = this::showNextImage;
                break;
            case "show": {
                // Debug-only: "the clip that misbehaves is #600 of 1000", which ?action=next cannot
                // promise to reach. Checked and parked from this thread, which may read the pool's
                // key set but never its records; the rotation worker picks the record up.
                if (!BuildConfig.DEBUG || mediaManager == null) {
                    return false;
                }
                final String assetKey = params != null ? params.get("asset") : null;
                if (!mediaManager.queueAssetDisplay(assetKey)) {
                    return false;
                }
                task = () -> {
                    if (!brightnessManager.getShouldTheScreenBeOn()) {
                        // Standing down for quiet hours means no tick will consume the pin. Dropping
                        // it here keeps a request made while the panel was dark from surfacing
                        // unprompted the moment it wakes.
                        mediaManager.cancelAssetDisplay(assetKey);
                    }
                    showNextImage();
                };
                break;
            }
            case "screen":
                task = this::toggleScreenOn;
                break;
            case "brightness":
                task = () -> adjustMinimumBrightness("web");
                break;
            case "config":
                task = this::showConfigurationUI;
                break;
            case "settings":
                task = this::openSystemSettings;
                break;
            case "update-site":
                task = this::openUpdateWebsite;
                break;
            case "check-updates":
                task = this::checkForUpdates;
                break;
            case "install-staged":
                task = () -> {
                    turnScreenOn();
                    updateManager.installStaged(true);
                };
                break;
            case "update-reset":
                task = updateManager::resetUpdate;
                break;
            default:
                return false;
        }
        runOnUiThread(task);
        return true;
    }

    @Override
    public boolean trustedNetwork() {
        return settingsManager != null && settingsManager.isTrustedNetwork();
    }

    /**
     * The trusted-network flag lives in the app's default SharedPreferences (not in
     * {@code configuration.json}). The stripped compile-time android.jar lacks
     * {@code Context.getDefaultSharedPreferences()}, so use the PreferenceManager entry point.
     */
    @Override
    @SuppressWarnings("deprecation")
    public SharedPreferences getDefaultSharedPreferences() {
        return android.preference.PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override
    public String trustedNetworkToken() {
        return settingsManager != null ? settingsManager.trustedNetworkToken() : "";
    }

    @Override
    public String exportConfigurationJson() {
        return settingsManager != null ? settingsManager.exportConfigurationJson() : "{}";
    }

    /**
     * Imports a full device configuration from the web UI. On success the time zone is
     * re-applied and the core loop restarted, mirroring the on-device Save flow.
     */
    @Override
    public String importConfigurationJson(String json) {
        if (settingsManager == null) {
            return "settings manager unavailable";
        }
        final String error = settingsManager.importConfiguration(json);
        if (error == null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    settingsManager.updateTimeZone(MainActivity.this);
                    settingsChanged();
                }
            });
        }
        return error;
    }

    /**
     * Injects a key press into the activity (web-admin keycode probe; ADB is not
     * available on the frame). Runs on the web server's worker thread —
     * {@code sendKeyDownUpSync()} posts to the UI thread's looper and would deadlock
     * if called from that thread itself.
     */
    @Override
    public boolean sendKeyCode(int keyCode) {
        try {
            Log.i(TAG, "web keyevent: injecting keyCode=" + keyCode);
            new Instrumentation().sendKeyDownUpSync(keyCode);
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "web keyevent: injection failed for keyCode=" + keyCode, e);
            return false;
        }
    }

    private void hideSystemUI() {

        Window window = this.getWindow();
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        window.getDecorView().setSystemUiVisibility(
        // Do not let system steal touches for showing the navigation bar
        View.SYSTEM_UI_FLAG_IMMERSIVE
                // Hide the nav bar and status bar
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                // Keep the app content behind the bars even if user swipes them up
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        // make navbar translucent - do this already in hideSystemUI() so that the bar
        // is translucent if user swipes it up
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
    }
}
