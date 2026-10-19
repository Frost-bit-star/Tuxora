package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.termux.BuildConfig;
import com.termux.R;

import java.io.BufferedReader;
import java.io.File;
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
 * <p>The slim build bundles the same dotfiles as the full build (the desktop UI is inbuilt);
 * only the dependency bootstrap {@code bootstrap-<arch>.zip} is downloaded from the GitHub
 * release matching the app version on first launch, then installed the same way.
 *
 * <p>Idempotent: a marker file guards the (one time) setup.
 */
public final class TuxoraSetup {

    private static final String LOG_TAG = "TuxoraSetup";
    private static final String MARKER_NAME = ".tuxora-desktop-v1";
    private static final String HOME_ZIP = "tuxora-home.zip";
    /** Minimum size for a bootstrap file to be trusted as complete when pre-staged. */
    private static final long MIN_STAGED_BYTES = 1024 * 1024;
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
     * the dotfiles are bundled too and only the dependency bootstrap is downloaded once,
     * showing progress and offering a retry on failure.
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
        final Dialog screen = showSetupScreen(activity);
        SetupService.start(activity);
        final SetupProgress ui = new SetupProgress(activity, screen);
        new Thread(() -> {
            try {
                String arch = termuxArch();
                String base = BuildConfig.TUXORA_RELEASE_BASE_URL + "/v" + BuildConfig.VERSION_NAME;

                // Slim = app + dotfiles in the APK; only the dependency stack is needed.
                File bootstrapZip = resolveBootstrap(activity, arch, base + "/bootstrap-" + arch + ".zip", ui);

                activity.runOnUiThread(() ->
                    TermuxInstaller.installBootstrapFromFile(activity, bootstrapZip, ui, () -> new Thread(() -> {
                        try {
                            File home = homeDir(activity);
                            try (InputStream zip = activity.getAssets().open(HOME_ZIP)) {
                                extractZip(zip, home);
                            }
                            try (InputStream symlinkStream = activity.getAssets().open(HOME_SYMLINKS)) {
                                createSymlinks(symlinkStream, home);
                            }
                            mark(marker);
                            Log.i(LOG_TAG, "Bundled desktop files installed");
                            SetupService.notifyProgress(100, "Done");
                            SetupService.stop(activity);
                            dismiss(screen);
                            activity.runOnUiThread(onReady);
                        } catch (Exception e) {
                            SetupService.stop(activity);
                            dismiss(screen);
                            Log.e(LOG_TAG, "Failed to install bundled desktop files", e);
                        }
                    }).start()));
            } catch (Exception e) {
                SetupService.stop(activity);
                dismiss(screen);
                Log.e(LOG_TAG, "Failed to download desktop dependencies", e);
                showDownloadError(activity, marker, onReady, e);
            }
        }).start();
    }

    /** Bridges installer/download progress to the setup screen and the setup notification. */
    static final class SetupProgress implements TermuxInstaller.InstallProgress {
        private final Activity activity;
        private final Dialog screen;

        SetupProgress(Activity activity, Dialog screen) {
            this.activity = activity;
            this.screen = screen;
        }

        @Override
        public void onProgress(final int percent, final String message) {
            SetupService.notifyProgress(percent, message);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    View content = screen.getWindow() != null ? screen.getWindow().getDecorView() : null;
                    if (content == null) return;
                    TextView messageView = content.findViewById(R.id.setup_message);
                    if (messageView != null) messageView.setText(message);
                    android.widget.ProgressBar bar = content.findViewById(R.id.setup_progress);
                    if (bar != null) bar.setProgress(percent);
                } catch (RuntimeException ignored) {
                    // Screen already gone.
                }
            });
        }
    }

    private static void showDownloadError(final Activity activity, final File marker, final Runnable onReady, Exception e) {
        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                    .setTitle("Desktop download failed")
                    .setMessage("Tuxora couldn't download the desktop dependencies. Connect to the internet and try again.\n\n" + e.getMessage())
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

    /**
     * Returns a bootstrap zip without hitting the network when possible: app cache first,
     * then a file pre-staged into public Downloads (e.g. pushed over adb), else downloads
     * it to the cache. Falls through to the normal download if the staged file is missing
     * or unreadable (no storage permission), so staging can never make things worse.
     */
    private static File resolveBootstrap(final Activity activity, final String arch, final String url,
                                         final TermuxInstaller.InstallProgress progress) throws Exception {
        File cache = new File(activity.getCacheDir(), "bootstrap-" + arch + ".zip");
        if (cache.exists() && cache.length() > MIN_STAGED_BYTES) {
            Log.i(LOG_TAG, "Using cached bootstrap (" + cache.length() + " bytes): " + cache.getAbsolutePath());
            if (progress != null) progress.onProgress(55, "Installing packages\u2026");
            return cache;
        }
        try {
            File staged = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "bootstrap-" + arch + ".zip");
            if (staged.exists() && staged.isFile() && staged.length() > MIN_STAGED_BYTES) {
                Log.i(LOG_TAG, "Using pre-staged bootstrap from Downloads (" + staged.length() + " bytes)");
                if (progress != null) progress.onProgress(55, "Installing packages\u2026");
                return staged;
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "No pre-staged bootstrap available: " + e);
        }
        download(url, cache, progress);
        return cache;
    }

    private static void download(String urlString, File out, TermuxInstaller.InstallProgress progress) throws Exception {
        if (out.exists() && out.length() > 1024 * 1024) {
            Log.i(LOG_TAG, "Using pre-staged bootstrap (" + out.length() + " bytes): " + out.getAbsolutePath());
            return;
        }
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
            long total = connection.getContentLengthLong();
            long done = 0;
            long lastReport = 0;
            byte[] buffer = new byte[262144];
            try (InputStream in = connection.getInputStream();
                 FileOutputStream outStream = new FileOutputStream(out)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    outStream.write(buffer, 0, read);
                    done += read;
                    if (progress != null && total > 0 && done - lastReport >= 4 * 1024 * 1024) {
                        lastReport = done;
                        int percent = (int) (50L * done / total);
                        progress.onProgress(percent, "Downloading packages\u2026 " + (done / (1024 * 1024)) +
                            " / " + (total / (1024 * 1024)) + " MB");
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
        if (progress != null) progress.onProgress(55, "Installing packages\u2026");
        Log.i(LOG_TAG, "Downloaded " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }

    private static void extractZip(InputStream zipStream, File home) throws Exception {
        byte[] buffer = new byte[262144];
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

    private static void dismiss(Dialog progress) {
        try {
            progress.dismiss();
        } catch (RuntimeException e) {
            // Activity already dismissed - ignore.
        }
    }

    /**
     * Full-screen first-launch setup screen: the pangolin splash GIF with the
     * "installing necessary packages" message underneath. Covers download and
     * install; the caller dismisses it right before the desktop boot continues.
     */
    public static Dialog showSetupScreen(final Activity activity) {
        try {
            Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            View content = activity.getLayoutInflater().inflate(R.layout.dialog_setup, null);
            try {
                ((pl.droidsonroids.gif.GifImageView) content.findViewById(R.id.setup_splash))
                    .setImageDrawable(new pl.droidsonroids.gif.GifDrawable(activity.getAssets(), "tuxora_splash.gif"));
            } catch (Exception e) {
                Log.w(LOG_TAG, "Splash GIF failed to load, continuing without it", e);
            }
            ((TextView) content.findViewById(R.id.setup_message))
                .setText(R.string.bootstrap_installer_body);
            dialog.setContentView(content);
            dialog.setCancelable(false);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.show();
            return dialog;
        } catch (RuntimeException e) {
            // Activity already dismissed - fall back to no screen rather than crash.
            Log.w(LOG_TAG, "Could not show setup screen", e);
            Dialog fallback = new Dialog(activity);
            fallback.setTitle("");
            return fallback;
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
