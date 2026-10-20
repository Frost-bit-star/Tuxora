package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Supplier of the bootstrap zip stream. */
    interface ZipSupplier {
        java.io.InputStream get() throws Exception;
    }

    /** Progress sink for the bootstrap install; called from a background thread. */
    interface InstallProgress {
        void onProgress(int percent, String message);
    }

    /** Performs bootstrap setup if necessary, showing the installer's own progress screen. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        setupBootstrapIfNeeded(activity, null, whenDone);
    }

    /**
     * Performs bootstrap setup if necessary. When {@code installProgress} is {@code null} the
     * installer shows its own setup screen; otherwise the caller owns the UI and receives
     * extraction progress so the bar does not sit still through the long bootstrap extract.
     */
    static void setupBootstrapIfNeeded(final Activity activity, final InstallProgress installProgress,
                                       final Runnable whenDone) {
        if (!validateEnvironment(activity)) return;

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        runInstaller(activity, null, TermuxInstaller::openEmbeddedBootstrap, installProgress, whenDone);
    }

    /**
     * Unconditionally (re)installs the bootstrap from the given zip file. Used by the slim
     * build, which downloads the desktop bootstrap on first launch instead of bundling it.
     */
    static void installBootstrapFromFile(final Activity activity, final File zipFile, final Runnable whenDone) {
        installBootstrapFromFile(activity, zipFile, null, whenDone);
    }

    /**
     * Same as {@link #installBootstrapFromFile(Activity, File, Runnable)} but reports
     * extraction progress and lets the caller own the UI (no dialog is created here).
     */
    static void installBootstrapFromFile(final Activity activity, final File zipFile,
                                         final InstallProgress progress, final Runnable whenDone) {
        if (!validateEnvironment(activity)) return;
        runInstaller(activity, zipFile, null, progress, whenDone);
    }

    /** Validates the environment, returning {@code false} after showing an error if unusable. */
    private static boolean validateEnvironment(final Activity activity) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return false;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return false;
        }

        return true;
    }

    private static void runInstaller(final Activity activity, final File zipFile, final ZipSupplier zipSupplier,
                                     final InstallProgress installProgress, final Runnable whenDone) {
        // When a progress sink is provided the caller owns the UI; otherwise show our own
        // screen and drive it through an internal sink so the bar still advances.
        final android.app.Dialog progress = (installProgress == null) ? TuxoraSetup.showSetupScreen(activity) : null;
        final InstallProgress sink = (installProgress != null) ? installProgress
            : (progress != null ? new TuxoraSetup.SetupProgress(activity, progress) : null);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");
                    if (sink != null) sink.onProgress(55, "Installing packages\u2026");

                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    if (zipFile != null) {
                        extractParallel(zipFile, symlinks, sink);
                    } else {
                        extractSequential(zipSupplier.get(), symlinks, sink);
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");
                    if (sink != null) sink.onProgress(94, "Finishing setup\u2026");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    if (sink != null) sink.onProgress(95, "Installing desktop files\u2026");
                    activity.runOnUiThread(whenDone);

                } catch (final Throwable e) {
                    // Catch Throwable (not just Exception): a low-heap device can throw
                    // OutOfMemoryError while streaming the bootstrap, and swallowing it
                    // left the setup dialog dismissed with no error and no retry.
                    SetupService.stop(activity);
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    if (progress != null) {
                        activity.runOnUiThread(() -> {
                            try {
                                progress.dismiss();
                            } catch (RuntimeException e) {
                                // Activity already dismissed - ignore.
                            }
                        });
                    }
                }
            }
        }.start();
    }

    /** Sequential extraction for the bundled (stream) bootstrap of the full build. */
    private static void extractSequential(final InputStream zipStream, final List<Pair<String, String>> symlinks,
                                          final InstallProgress installProgress) throws Exception {
        final byte[] buffer = new byte[262144];
        Error error;
        int extracted = 0;
        try (ZipInputStream zipInput = new ZipInputStream(zipStream)) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInput.getNextEntry()) != null) {
                if (zipEntry.getName().equals("SYMLINKS.txt")) {
                    BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                    String line;
                    while ((line = symlinksReader.readLine()) != null) {
                        String[] parts = line.split("←");
                        if (parts.length != 2)
                            throw new RuntimeException("Malformed symlink line: " + line);
                        String oldPath = parts[0];
                        String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                        symlinks.add(Pair.create(oldPath, newPath));

                        error = ensureDirectoryExists(new File(newPath).getParentFile());
                        if (error != null) {
                            throw new RuntimeException(error.getMessage());
                        }
                    }
                } else {
                    String zipEntryName = zipEntry.getName();
                    File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                    boolean isDirectory = zipEntry.isDirectory();

                    error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                    if (error != null) {
                        throw new RuntimeException(error.getMessage());
                    }

                    if (!isDirectory) {
                        try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                            int readBytes;
                            while ((readBytes = zipInput.read(buffer)) != -1)
                                outStream.write(buffer, 0, readBytes);
                        }
                        applyExtractPermissions(targetFile, zipEntryName);
                        // The bundled bootstrap holds ~110k entries; without periodic
                        // updates the progress bar sat at 55% for the whole extraction
                        // and users thought the setup had frozen.
                        if (installProgress != null && (++extracted % 250 == 0)) {
                            installProgress.onProgress(Math.min(93, 55 + extracted / 1200),
                                "Installing packages (" + extracted + ")");
                        }
                    }
                }
            }
        }
    }

    /**
     * Parallel extraction for file-backed bootstraps: random-access {@link java.util.zip.ZipFile}
     * with a small worker pool, which is several times faster than single-threaded streaming
     * on flash storage. Reports progress through {@code installProgress} (55-93%).
     */
    private static void extractParallel(final File zipFile, final List<Pair<String, String>> symlinks,
                                        final InstallProgress installProgress) throws Exception {
        final int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            final List<ZipEntry> files = new ArrayList<>(110000);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("SYMLINKS.txt")) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(zip.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String[] parts = line.split("←");
                            if (parts.length != 2)
                                throw new RuntimeException("Malformed symlink line: " + line);
                            String oldPath = parts[0];
                            String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                            symlinks.add(Pair.create(oldPath, newPath));
                            Error error = ensureDirectoryExists(new File(newPath).getParentFile());
                            if (error != null) throw new RuntimeException(error.getMessage());
                        }
                    }
                } else if (entry.isDirectory() || entry.getName().endsWith("/")) {
                    Error error = ensureDirectoryExists(new File(TERMUX_STAGING_PREFIX_DIR_PATH, entry.getName()));
                    if (error != null) throw new RuntimeException(error.getMessage());
                } else {
                    files.add(entry);
                }
            }

            final int total = files.size();
            final AtomicInteger done = new AtomicInteger();
            final AtomicReference<Exception> firstError = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (final ZipEntry entry : files) {
                pool.submit(() -> {
                    if (firstError.get() != null) return;
                    try {
                        File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, entry.getName());
                        File parent = targetFile.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
                            throw new RuntimeException("Could not create directory " + parent);
                        try (InputStream in = zip.getInputStream(entry);
                             FileOutputStream outStream = new FileOutputStream(targetFile)) {
                            byte[] buffer = new byte[262144];
                            int readBytes;
                            while ((readBytes = in.read(buffer)) != -1)
                                outStream.write(buffer, 0, readBytes);
                        }
                        applyExtractPermissions(targetFile, entry.getName());
                        int completed = done.incrementAndGet();
                        if (installProgress != null && (completed % 250 == 0 || completed == total))
                            installProgress.onProgress(55 + (int) (38d * completed / total),
                                "Installing packages (" + completed + "/" + total + ")");
                    } catch (Exception e) {
                        firstError.compareAndSet(null, e);
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(90, TimeUnit.MINUTES))
                throw new RuntimeException("Bootstrap extraction timed out");
            if (firstError.get() != null) throw firstError.get();
        }
    }

    /** Sets the mode of an extracted entry: read-only dex (Android 14+ W^X), executables, the rest. */
    private static void applyExtractPermissions(File targetFile, String zipEntryName) throws android.system.ErrnoException {
        if (zipEntryName.endsWith(".apk") || zipEntryName.endsWith(".dex") ||
            zipEntryName.endsWith(".jar")) {
            // Android 14+ refuses to load writable dex files
            // ("Attempt to load writable dex file"), so strip the write bit.
            //noinspection OctalInteger
            Os.chmod(targetFile.getAbsolutePath(), 0500);
        } else if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
            zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods") ||
            zipEntryName.startsWith("lib/xfce4/xfconf/xfconfd") || zipEntryName.endsWith("/xfconfd") ||
            zipEntryName.contains("/xfce4/xfconf/")) {
            //noinspection OctalInteger
            Os.chmod(targetFile.getAbsolutePath(), 0700);
        }
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    /**
     * Opens a stream over the bootstrap zip embedded in {@code libtermux-bootstrap.so}.
     *
     * <p>The native side hands back a direct {@link java.nio.ByteBuffer} that wraps the
     * zip bytes in place (they live in the mmap'd {@code .rodata} of the shared library),
     * so nothing is copied onto the Java heap. The previous implementation returned the
     * whole zip as a {@code byte[]}, which needs ~518 MB of contiguous heap for the
     * self-contained build and threw {@link OutOfMemoryError} on devices whose
     * {@code dalvik.vm.heapgrowthlimit} is 256-512 MB — the desktop never finished
     * installing.
     */
    static InputStream openEmbeddedBootstrap() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        final java.nio.ByteBuffer buf = getZip();
        if (buf == null) throw new IllegalStateException("getZip() returned null (NewDirectByteBuffer failed)");
        return new InputStream() {
            @Override
            public int read() {
                return buf.hasRemaining() ? buf.get() & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (!buf.hasRemaining()) return -1;
                len = Math.min(len, buf.remaining());
                buf.get(b, off, len);
                return len;
            }
        };
    }

    /** Native side of {@link #openEmbeddedBootstrap()}: a direct buffer over the embedded zip. */
    public static native java.nio.ByteBuffer getZip();

}
