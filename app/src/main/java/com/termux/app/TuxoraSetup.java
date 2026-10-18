package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.termux.BuildConfig;
import com.termux.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Prepares the Tuxora desktop before it is started.
 *
 * <p>The full build installs the bundled bootstrap and the original desktop tree shipped
 * as {@code assets/tuxora-home.zip} (regular files/dirs) plus
 * {@code assets/tuxora-home-symlinks.txt} ({@code <target>←<path>} lines), because Android
 * asset packaging cannot store symbolic links.
 *
 * <p>The slim build does not bundle any of that: it downloads {@code bootstrap-<arch>.zip}
 * and the two {@code tuxora-home.*} files from the GitHub release matching the app version
 * on first launch, then installs them the same way.
 *
 * <p>Idempotent: a marker file guards the (one time) setup.
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

    /**
     * Prepares the desktop and invokes {@code onReady} on the UI thread once it can be started.
     * For the full build this installs the bundled bootstrap and dotfiles; for the slim build
     * it downloads them once, showing progress and offering a retry on failure.
     */
    public static void prepare(final Activity activity, final Runnable onReady) {
        final File marker = new File(activity.getFilesDir(), MARKER_NAME);
        if (marker.exists()) {
            activity.runOnUiThread(onReady);
            return;
        }

        if (BuildConfig.TUXORA_SLIM) {
            prepareSlim(activity, marker, onReady);
        } else {
            TermuxInstaller.setupBootstrapIfNeeded(activity, () -> new Thread(() -> {
                try {
                    File home = homeDir(activity);
                    try (InputStream zip = activity.getAssets().open(HOME_ZIP)) {
                        extractZip(zip, home);
                    }
                    try (InputStream symlinks = activity.getAssets().open(HOME_SYMLINKS)) {
                        createSymlinks(symlinks, home);
                    }
                    mark(marker);
                    Log.i(LOG_TAG, "Bundled desktop files installed");
                    activity.runOnUiThread(onReady);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Failed to install bundled desktop files", e);
                }
            }).start());
        }
    }

    private static void prepareSlim(final Activity activity, final File marker, final Runnable onReady) {
        final ProgressDialog progress = ProgressDialog.show(activity, null,
            activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread(() -> {
            try {
                String arch = termuxArch();
                String base = BuildConfig.TUXORA_RELEASE_BASE_URL + "/v" + BuildConfig.VERSION_NAME;
                File bootstrapZip = new File(activity.getCacheDir(), "bootstrap-" + arch + ".zip");
                File homeZip = new File(activity.getCacheDir(), HOME_ZIP);
                File symlinks = new File(activity.getCacheDir(), HOME_SYMLINKS);

                download(base + "/bootstrap-" + arch + ".zip", bootstrapZip);
                download(base + "/" + HOME_ZIP, homeZip);
                download(base + "/" + HOME_SYMLINKS, symlinks);

                activity.runOnUiThread(() -> {
                    dismiss(progress);
                    TermuxInstaller.installBootstrapFromFile(activity, bootstrapZip, () -> new Thread(() -> {
                        try {
                            File home = homeDir(activity);
                            try (InputStream zip = new FileInputStream(homeZip)) {
                                extractZip(zip, home);
                            }
                            try (InputStream symlinkStream = new FileInputStream(symlinks)) {
                                createSymlinks(symlinkStream, home);
                            }
                            mark(marker);
                            Log.i(LOG_TAG, "Downloaded desktop files installed");
                            activity.runOnUiThread(onReady);
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "Failed to install downloaded desktop files", e);
                        }
                    }).start());
                });
            } catch (Exception e) {
                dismiss(progress);
                Log.e(LOG_TAG, "Failed to download desktop files", e);
                showDownloadError(activity, marker, onReady, e);
            }
        }).start();
    }

    private static void showDownloadError(final Activity activity, final File marker, final Runnable onReady, Exception e) {
        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                    .setTitle("Desktop download failed")
                    .setMessage("Tuxora couldn't download the desktop files. Connect to the internet and try again.\n\n" + e.getMessage())
                    .setPositiveButton("Retry", (dialog, which) -> {
                        dialog.dismiss();
                        prepare(activity, onReady);
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .show();
            } catch (Exception ignored) {
                // Activity already gone.
            }
        });
    }

    private static void download(String urlString, File out) throws Exception {
        Log.i(LOG_TAG, "Downloading " + urlString);
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + code + " for " + urlString);
            }
            byte[] buffer = new byte[65536];
            try (InputStream in = connection.getInputStream();
                 FileOutputStream outStream = new FileOutputStream(out)) {
                int read;
                while ((read = in.read(buffer)) != -1) outStream.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
        Log.i(LOG_TAG, "Downloaded " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }

    private static void extractZip(InputStream zipStream, File home) throws Exception {
        byte[] buffer = new byte[16384];
        try (ZipInputStream zip = new ZipInputStream(zipStream)) {
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

    private static void createSymlinks(InputStream symlinksStream, File home) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(symlinksStream, StandardCharsets.UTF_8))) {
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

    private static void mark(File marker) {
        try {
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to write desktop marker", e);
        }
    }

    private static void dismiss(ProgressDialog progress) {
        try {
            progress.dismiss();
        } catch (RuntimeException e) {
            // Activity already dismissed - ignore.
        }
    }

    /** Maps the primary Android ABI to the termux-packages architecture name. */
    private static String termuxArch() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        switch (abi) {
            case "armeabi-v7a":
            case "armeabi":
                return "arm";
            case "x86":
                return "i686";
            case "x86_64":
                return "x86_64";
            case "arm64-v8a":
            default:
                return "aarch64";
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
