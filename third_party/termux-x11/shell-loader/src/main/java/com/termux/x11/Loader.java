package com.termux.x11;
import com.termux.x11.shell_loader.BuildConfig;

public class Loader {
    /**
     * Command-line entry point.
     * It is pretty simple.
     * 1. Check if application is installed.
     * 2. Check if target apk's signature matches stored hash to prevent running code of potentially replaced malicious apk.
     * 3. Load target apk code with `PathClassLoader` and start target's main function.
     * <p>
     * This way we can make this loader version-agnostic and keep it secure. All application logic is located in target apk.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        String cls = System.getenv("TERMUX_X11_LOADER_OVERRIDE_CMDENTRYPOINT_CLASS");
        cls = cls != null ? cls : BuildConfig.CLASS_ID;
        try {
            android.content.pm.PackageInfo targetInfo = getTargetPackageInfo();
            if (targetInfo == null) {
                System.err.println(BuildConfig.packageNotInstalledErrorText);
                return;
            }
            if (!isSignatureValid(targetInfo)) {
                System.err.println(BuildConfig.packageSignatureMismatchErrorText);
                return;
            }

            android.util.Log.i(BuildConfig.logTag, "loading " + targetInfo.applicationInfo.sourceDir + "::" + cls + "::main of " + targetInfo.packageName + " application (commit " + BuildConfig.COMMIT + ")");
            Class<?> targetClass = Class.forName(cls, true,
                    new dalvik.system.PathClassLoader(targetInfo.applicationInfo.sourceDir, null, ClassLoader.getSystemClassLoader()));
            targetClass.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            e.getCause().printStackTrace(System.err);
        } catch (Throwable e) {
            android.util.Log.e(BuildConfig.logTag, "Loader error", e);
            e.printStackTrace(System.err);
        }
    }

    /**
     * Resolves the host app's package info.
     *
     * <p>{@code GET_SIGNATURES} is deprecated and returns no certificates on Android 13+
     * (API 33+), so on those versions we ask for {@code GET_SIGNING_CERTIFICATES} instead.
     * The pre-33 code path passes the legacy {@code int} flags overload, which the framework
     * rejects on newer releases.
     */
    private static android.content.pm.PackageInfo getTargetPackageInfo() {
        try {
            android.content.pm.IPackageManager pm = android.app.ActivityThread.getPackageManager();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return pm.getPackageInfo(BuildConfig.APPLICATION_ID,
                        (long) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES, 0);
            }
            return pm.getPackageInfo(BuildConfig.APPLICATION_ID,
                    android.content.pm.PackageManager.GET_SIGNATURES, 0);
        } catch (Throwable e) {
            android.util.Log.e(BuildConfig.logTag, "getPackageInfo(" + BuildConfig.APPLICATION_ID + ") failed", e);
            return null;
        }
    }

    /** True when the target app is signed with the certificate this loader was built against. */
    private static boolean isSignatureValid(android.content.pm.PackageInfo info) {
        try {
            android.content.pm.Signature[] signatures = (android.os.Build.VERSION.SDK_INT >= 28 && info.signingInfo != null)
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signatures;
            return signatures != null && signatures.length == 1
                    && BuildConfig.SIGNATURE == signatures[0].hashCode();
        } catch (Throwable e) {
            android.util.Log.e(BuildConfig.logTag, "signature check failed", e);
            return false;
        }
    }
}
