package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Log;

/**
 * Thin wrapper around the platform {@link BiometricPrompt} (API 28+) used to optionally
 * unlock the Tuxora login with a fingerprint/face instead of typing the shadow password.
 *
 * <p>The biometric is only an alternative <em>gate</em>: like the password login it just
 * decides whether to boot the desktop, it does not decrypt anything. It is offered only
 * when a password already exists (so there is always a fallback) and the device actually
 * has a biometric enrolled. Uses the framework API so {@link DesktopActivity} can stay a
 * plain {@link Activity} (no AndroidX FragmentActivity requirement).
 */
final class BiometricGate {

    private static final String LOG_TAG = "TuxoraBiometric";

    private BiometricGate() {}

    /** True if this device can authenticate with an enrolled biometric right now. */
    static boolean isAvailable(Context ctx) {
        if (Build.VERSION.SDK_INT < 29) return false; // BiometricManager added in API 29
        BiometricManager bm = ctx.getSystemService(BiometricManager.class);
        if (bm == null) return false;
        int result = (Build.VERSION.SDK_INT >= 30)
            ? bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            : bm.canAuthenticate();
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Shows the system biometric prompt. {@code onSuccess} runs on a successful auth,
     * {@code onFallback} when the user cancels, taps "Use password", or any error occurs,
     * so the caller always ends up either booted or back on the password field.
     */
    static void prompt(Activity activity, Runnable onSuccess, Runnable onFallback) {
        if (Build.VERSION.SDK_INT < 28) {
            onFallback.run();
            return;
        }
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                .setTitle("Unlock Tuxora")
                .setSubtitle("Use your fingerprint to sign in")
                .setNegativeButton("Use password", activity.getMainExecutor(),
                    (dialog, which) -> onFallback.run())
                .build();
            prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        onSuccess.run();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        // Cancel / "Use password" / lockout / hardware error: fall back.
                        onFallback.run();
                    }
                    // onAuthenticationFailed() = one bad read; the prompt stays up, so ignore.
                });
        } catch (Exception e) {
            Log.w(LOG_TAG, "Biometric prompt failed, falling back to password", e);
            onFallback.run();
        }
    }
}
