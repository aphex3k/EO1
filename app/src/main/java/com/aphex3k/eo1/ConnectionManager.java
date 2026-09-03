package com.aphex3k.eo1;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Polls ConnectivityManager and notifies listeners when the link goes up or down.
 */
public class ConnectionManager {

    static final long POLL_INTERVAL_MS = 2_000L;

    private final Set<WeakReference<ConnectionManagerListener>> listeners = new HashSet<>();
    @Nullable
    private final ConnectivityManager connectivityManager;
    @Nullable
    private final Handler pollingHandler;
    @Nullable
    private Boolean lastConnectionStatus = null;

    protected ConnectionManager(Activity activity) {
        this((ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE), new Handler());
    }

    /**
     * Test / injectable constructor. Pass a null Handler to drive updates via {@link #evaluateConnectionStatus()}.
     */
    protected ConnectionManager(@Nullable ConnectivityManager connectivityManager,
                                @Nullable Handler pollingHandler) {
        this.connectivityManager = connectivityManager;
        this.pollingHandler = pollingHandler;
    }

    /**
     * register a new listener for connection related callbacks
     * @param newListener the new listener
     */
    public void registerListener(ConnectionManagerListener newListener) {
        boolean existing = false;
        for (WeakReference<ConnectionManagerListener> weakReference: listeners) {
            if (weakReference.get() == newListener) {
                existing = true;
                break;
            }
        }
        if (!existing) {
            this.listeners.add(new WeakReference<>(newListener));
        }

        boolean available = isNetworkAvailable();
        lastConnectionStatus = available;
        notifyListener(newListener, available);

        checkPolling();
    }

    /**
     * Remove an existing listener.
     * @param existingListener this listener will not be called anymore.
     */
    public void unregisterListener(ConnectionManagerListener existingListener) {
        for (WeakReference<ConnectionManagerListener> weakReference: listeners) {
            if (weakReference.get() == null) {
                listeners.remove(weakReference);
                break;
            }
        }
        for (WeakReference<ConnectionManagerListener> weakReference: listeners) {
            if (weakReference.get() == existingListener) {
                listeners.remove(weakReference);
                break;
            }
        }

        checkPolling();
    }

    /**
     * Check if we should start or stop polling. No need to poll if nobody is listening anyways...
     */
    private void checkPolling() {
        if (this.listeners.isEmpty()) {
            stopPolling();
        }
        else {
            startPolling();
        }
    }

    /**
     * Start polling if not already polling.
     */
    private void startPolling() {
        if (pollingHandler == null) {
            return;
        }
        pollingHandler.removeCallbacks(this::runOnTimer);
        pollingHandler.post(this::runOnTimer);
    }

    /**
     * Stop polling.
     */
    private void stopPolling() {
        if (pollingHandler == null) {
            return;
        }
        pollingHandler.removeCallbacks(this::runOnTimer);
    }

    /**
     * If the connection status changed, let every listener know
     */
    private void runOnTimer() {
        evaluateConnectionStatus();
        if (pollingHandler != null) {
            pollingHandler.postDelayed(this::runOnTimer, POLL_INTERVAL_MS);
        }
    }

    /**
     * Re-check connectivity and notify listeners on change. Package-visible for unit tests.
     */
    void evaluateConnectionStatus() {
        final boolean newStatus = isNetworkAvailable();
        if (lastConnectionStatus != null && lastConnectionStatus != newStatus) {
            for (WeakReference<ConnectionManagerListener> listenerReference: listeners) {
                ConnectionManagerListener listener = listenerReference.get();
                if (listener != null) {
                    notifyListener(listener, newStatus);
                }
            }
        }
        lastConnectionStatus = newStatus;
    }

    private static void notifyListener(ConnectionManagerListener listener, boolean connected) {
        if (connected) {
            listener.connected();
        } else {
            listener.disconnected();
        }
    }

    protected boolean isNetworkAvailable() {
        if (connectivityManager == null) {
            return false;
        }
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }
}
