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

public class ConnectionManager {

    private final Set<WeakReference<ConnectionManagerListener>> listeners = new HashSet<>();
    private final ConnectivityManager connectivityManager;
    private final Handler pollingHandler = new Handler();
    @Nullable
    private Boolean lastConnectionStatus = null;

    protected ConnectionManager (Activity activity) {
        connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
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
        pollingHandler.removeCallbacks(this::runOnTimer);
        pollingHandler.post(this::runOnTimer);
    }

    /**
     * Stop polling.
     */
    private void stopPolling() {
        pollingHandler.removeCallbacks(this::runOnTimer);
    }

    /**
     * If the connection status changed, let every listener know
     */
    private void runOnTimer() {
        final boolean newStatus = isNetworkAvailable();
        if (lastConnectionStatus != null && lastConnectionStatus != newStatus) {
            for (WeakReference<ConnectionManagerListener> listenerReference: listeners) {
                ConnectionManagerListener listener = listenerReference.get();
                if (listener != null) {
                    if (newStatus) {
                        listener.connected();
                    }
                    else {
                        listener.disconnected();
                    }
                }
            }
        }
        lastConnectionStatus = newStatus;
        pollingHandler.postDelayed(this::runOnTimer, 2 * 1000);
    }

    protected boolean isNetworkAvailable() {
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }
}
