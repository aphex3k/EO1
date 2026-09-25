package com.aphex3k.eo1;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Dedicated, persistent storage for media uploaded via the web server.
 *
 * <p>Files live under {@code getFilesDir()/uploaded} — an internal, app-private directory that is
 * never scanned by {@link MediaCacheManager} eviction and is not cleared by "clear cache". The
 * original uploads stay here permanently; only the on-demand transcode/convert outputs (written to
 * the cache dir) are ever evicted.
 *
 * <p>All of the path logic here is written against a plain {@link File baseDir} (not a
 * {@link Context}) so it is unit-testable on the JVM. {@link #dirFor(Context)} is the only
 * Context-bound entry point.
 */
public final class UploadedMedia {

    /** Directory name under {@code getFilesDir()} that holds persistent uploads. */
    public static final String DIR_NAME = "uploaded";

    /** Maximum accepted length of a single file name. */
    private static final int MAX_NAME_LEN = 255;

    private UploadedMedia() {
    }

    /**
     * Returns the persistent upload directory, creating it if necessary.
     */
    public static File dirFor(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            // Not fatal: listFile checks will simply return an empty set.
        }
        return dir;
    }

    /**
     * Lists the uploaded files, newest first.
     */
    public static FileInfo[] list(File baseDir) {
        List<FileInfo> out = new ArrayList<>();
        File[] files = baseDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    out.add(new FileInfo(f.getName(), f.length(), f.lastModified()));
                }
            }
        }
        // Collections.sort (not List.sort) — the latter is a Java 8 default method that is absent
        // on API 19 (no coreLibraryDesugaring), so it throws NoSuchMethodError at runtime.
        Collections.sort(out, new Comparator<FileInfo>() {
            @Override
            public int compare(FileInfo a, FileInfo b) {
                return Long.compare(b.lastModified, a.lastModified);
            }
        });
        return out.toArray(new FileInfo[0]);
    }

    /**
     * Resolves a file name to a safe, existing file inside {@code baseDir}.
     *
     * @return the file, or {@code null} if the name is unsafe or the file does not exist.
     */
    public static File resolve(File baseDir, String name) {
        if (baseDir == null || !isSafeName(name)) {
            return null;
        }
        File f = new File(baseDir, name);
        if (!f.isFile()) {
            return null;
        }
        try {
            String base = baseDir.getCanonicalPath();
            String path = f.getCanonicalPath();
            // Ensure the resolved file is strictly inside baseDir (no traversal).
            if (!path.startsWith(base + File.separator) && !path.equals(base)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return f;
    }

    /**
     * Deletes an uploaded file by name.
     *
     * @return {@code true} if a file was deleted.
     */
    public static boolean delete(File baseDir, String name) {
        File f = resolve(baseDir, name);
        if (f == null) {
            return false;
        }
        return f.delete();
    }

    /**
     * Recursively deletes leftover {@code incoming_*} temp directories from uploads whose process
     * died mid-way (up to one 512 MB file each). Safe to call at startup; it only touches
     * directories, never the persistent uploads.
     *
     * @return number of top-level directories removed.
     */
    public static int cleanStaleIncomingDirs(File baseDir) {
        File[] entries = baseDir.listFiles();
        if (entries == null) {
            return 0;
        }
        int removed = 0;
        for (File entry : entries) {
            if (entry.isDirectory() && entry.getName().startsWith("incoming_")) {
                if (deleteRecursively(entry)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        return file.delete();
    }

    /**
     * Picks a non-colliding name for an incoming upload. If {@code candidate} already exists,
     * appends {@code _1}, {@code _2}, ... until a free name is found.
     */
    public static String uniqueName(File baseDir, String candidate) {
        if (new File(baseDir, candidate).exists()) {
            int dot = candidate.lastIndexOf('.');
            String stem = dot > 0 ? candidate.substring(0, dot) : candidate;
            String ext = dot > 0 ? candidate.substring(dot) : "";
            for (int i = 1; ; i++) {
                String attempt = stem + "_" + i + ext;
                if (!new File(baseDir, attempt).exists()) {
                    return attempt;
                }
            }
        }
        return candidate;
    }

    /**
     * Sanitizes an arbitrary incoming file name into a safe single-path-component name.
     * Strips directory parts and replaces disallowed characters with {@code _}.
     */
    public static String safeFileName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "upload";
        }
        // Keep only the last path component (handles both / and \ separators).
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String name = slash >= 0 ? raw.substring(slash + 1) : raw;
        if (name.isEmpty()) {
            name = "upload";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == ' ') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        name = sb.toString();
        // Collapse runs of dots/underscores and trim leading dots so we never produce "." / "..".
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            name = "upload";
        }
        if (name.length() > MAX_NAME_LEN) {
            name = name.substring(0, MAX_NAME_LEN);
        }
        return name;
    }

    /**
     * Validates a name for use with {@link #resolve}/{@link #delete}. Rejects path traversal and
     * directory references.
     */
    public static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LEN) {
            return false;
        }
        if (name.equals(".") || name.equals("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == 0) {
                return false;
            }
        }
        // Reject any ".." sequence to be safe against traversal.
        if (name.contains("..")) {
            return false;
        }
        return true;
    }

    /**
     * A snapshot of an uploaded file.
     */
    public static final class FileInfo {
        public final String name;
        public final long size;
        public final long lastModified;

        public FileInfo(String name, long size, long lastModified) {
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
        }
    }
}
