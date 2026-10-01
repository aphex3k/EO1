package com.aphex3k.eo1;

import java.io.File;

/**
 * The app-facing surface the {@link WebServer} uses to reach state, logs, uploads, and controls.
 *
 * <p>{@code MainActivity} implements this. Keeping the server behind an interface means the
 * server is unit-testable with a fake controller and never holds a hard reference to an Activity
 * beyond what the implementor chooses.
 */
public interface WebController {

    /** The device's LAN IP address (dotted-quad) for display in the UI. */
    String localIp();

    /** Builds the full {@code /state} JSON document. */
    String buildStateJson();

    /** Returns the recent in-memory log events as a JSON array (up to {@code lines}). */
    String getLogTail(int lines);

    /** Returns the recent on-disk log lines as plain text (up to {@code lines}). */
    String getFileLogTail(int lines);

    /** The persistent upload directory. */
    File uploadedDir();

    /** Lists the uploaded files, newest first. */
    UploadedMedia.FileInfo[] listUploadedFiles();

    /**
     * Resolves an uploaded file by name.
     *
     * @return the file, or {@code null} if missing/unsafe.
     */
    File uploadedFile(String name);

    /** Deletes an uploaded file by name. */
    boolean deleteUploadedFile(String name);

    /**
     * Fires a device control action (mirrors a hardware key press).
     *
     * @param action one of {@code next}, {@code screen}, {@code brightness}, {@code config},
     *               {@code settings}, {@code update-site}, {@code check-updates},
     *               {@code install-staged} (installs a staged self-update, screen on),
     *               {@code update-reset} (drops staged update files and state).
     * @return {@code true} if a known action was dispatched.
     */
    boolean control(String action);
}
