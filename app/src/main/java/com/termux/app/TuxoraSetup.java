package com.termux.app;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs the bundled Tuxora desktop files into {@code $HOME} on first boot.
 *
 * <p>The original desktop tree is shipped compressed as {@code assets/tuxora-home.zip}
 * (regular files/dirs) together with {@code assets/tuxora-home-symlinks.txt}
 * ({@code <target>←<path>} lines), because Android asset packaging cannot store
 * symbolic links. Both are produced by {@code scripts/make-tuxora-home.py}.
 *
 * <p>Idempotent: a marker file guards the (one time) extraction.
 */
public final class TuxoraSetup {

    private static final String LOG_TAG = "TuxoraSetup";
    private static final String MARKER_NAME = ".tuxora-desktop-v1";
    private static final String HOME_ZIP = "tuxora-home.zip";
    private static final String HOME_SYMLINKS = "tuxora-home-symlinks.txt";
    private static final char SEP = '\u2190'; // ←

    private TuxoraSetup() {}

    /** Absolute {@code $HOME} for this process. */
    public static File homeDir(Context context) {
        return new File(context.getFilesDir(), "home");
    }

    /** Idempotently installs the bundled desktop files into {@code $HOME}. */
    public static void setupIfNeeded(Context context) {
        File marker = new File(context.getFilesDir(), MARKER_NAME);
        if (marker.exists()) return;

        File home = homeDir(context);
        try {
            Log.i(LOG_TAG, "Extracting bundled desktop files into " + home.getAbsolutePath());
            extractZip(context, home);
            createSymlinks(context, home);
            //noinspection ResultOfMethodCallIgnored
            new File(context.getFilesDir(), MARKER_NAME).createNewFile();
            Log.i(LOG_TAG, "Bundled desktop files installed");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to install bundled desktop files", e);
        }
    }

    private static void extractZip(Context context, File home) throws Exception {
        byte[] buffer = new byte[16384];
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open(HOME_ZIP))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(home, entry.getName());
                if (entry.isDirectory() || entry.getName().endsWith("/")) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                if (isExecutable(entry.getName())) chmod(target, 0700);
            }
        }
    }

    private static void createSymlinks(Context context, File home) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(HOME_SYMLINKS), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int sep = line.indexOf(SEP);
                if (sep <= 0) continue;
                String target = line.substring(0, sep);
                String path = line.substring(sep + 1);
                if (path.isEmpty() || path.startsWith("/") || path.contains("..")) continue;
                File link = new File(home, path);
                File parent = link.getParentFile();
                if (parent != null) parent.mkdirs();
                try {
                    if (Os.lstat(link.getAbsolutePath()) != null) link.delete();
                } catch (ErrnoException ignored) {
                    // Does not exist.
                }
                try {
                    Os.symlink(target, link.getAbsolutePath());
                } catch (ErrnoException e) {
                    Log.w(LOG_TAG, "symlink failed: " + path + " -> " + target, e);
                }
            }
        }
    }

    private static boolean isExecutable(String name) {
        return name.startsWith(".local/bin/")
            || name.contains("/.local/bin/")
            || name.endsWith(".sh");
    }

    private static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException e) {
            Log.w(LOG_TAG, "chmod failed: " + file.getAbsolutePath(), e);
        }
    }
}
