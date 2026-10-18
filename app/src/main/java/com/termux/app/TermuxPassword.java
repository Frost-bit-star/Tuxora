package com.termux.app;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decides whether a login password is set on this Termux install and verifies one.
 *
 * <p>Two stores are consulted:
 * <ul>
 *   <li>{@code $HOME/.termux_authinfo} — written by Termux's {@code passwd} utility
 *       (libtermux-auth): raw 20-byte PBKDF2-HMAC-SHA1 digest, salt {@code "Termux!"},
 *       65536 iterations. No file means no password (fresh install).</li>
 *   <li>{@code $PREFIX/etc/shadow} — classic {@code crypt(3)} hashes ({@code $1$},
 *       {@code $5$}, {@code $6$}, yescrypt via python3) when that file exists.</li>
 * </ul>
 */
final class TermuxPassword {

    private static final String LOG_TAG = "TuxoraPassword";

    enum Result { OK, WRONG }

    private static final byte[] PBKDF2_SALT = "Termux!".getBytes(StandardCharsets.US_ASCII);
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int PBKDF2_KEY_BYTES = 20; // SHA_DIGEST_LENGTH

    private TermuxPassword() {}

    private static File filesDir(Context ctx) {
        return new File(ctx.getApplicationInfo().dataDir, "files");
    }

    private static File prefix(Context ctx) {
        return new File(filesDir(ctx), "usr");
    }

    private static File authInfoFile(Context ctx) {
        return new File(filesDir(ctx), "home/.termux_authinfo");
    }

    /** Local user: passwd entry matching this app's uid, else a derived {@code u0_aXXX}. */
    static String resolveUsername(Context ctx) {
        int uid = android.os.Process.myUid();
        File passwd = new File(prefix(ctx), "etc/passwd");
        try (BufferedReader r = new BufferedReader(new FileReader(passwd))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split(":", -1);
                if (f.length > 2) {
                    try {
                        if (Integer.parseInt(f[2]) == uid && !f[0].isEmpty()) return f[0];
                    } catch (NumberFormatException ignored) { }
                }
            }
        } catch (IOException e) {
            Log.d(LOG_TAG, "No readable passwd: " + e.getMessage());
        }
        if (uid >= 10000 && uid < 20000) return "u0_a" + (uid - 10000);
        String prop = System.getProperty("user.name");
        return (prop == null || prop.isEmpty()) ? "user" : prop;
    }

    /** Raw hash field from {@code $PREFIX/etc/shadow} for this app's user, or null. */
    private static String shadowHash(Context ctx) {
        String user = resolveUsername(ctx);
        File shadow = new File(prefix(ctx), "etc/shadow");
        try (BufferedReader r = new BufferedReader(new FileReader(shadow))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split(":", -1);
                if (f.length > 1 && f[0].equals(user)) return f[1];
            }
        } catch (IOException e) {
            Log.d(LOG_TAG, "No readable shadow: " + e.getMessage());
        }
        return null;
    }

    private static boolean isLocked(String h) {
        return h == null || h.isEmpty() || h.equals("!") || h.equals("!!")
            || h.equals("*") || h.startsWith("*");
    }

    /** True when {@code passwd} has been run (authinfo present) or shadow holds a real hash. */
    static boolean hasPassword(Context ctx) {
        File ai = authInfoFile(ctx);
        if (ai.isFile() && ai.length() >= PBKDF2_KEY_BYTES) return true;
        return !isLocked(shadowHash(ctx));
    }

    static Result verify(Context ctx, String password) {
        String hash = shadowHash(ctx);
        if (!isLocked(hash)) {
            if (Crypt.verify(password, hash)) return Result.OK;
            if (Crypt.isSupported(hash)) return Result.WRONG;
            Boolean py = verifyWithPython(hash, password);
            if (py != null) return py ? Result.OK : Result.WRONG;
            return Result.WRONG;
        }
        File ai = authInfoFile(ctx);
        byte[] stored = readFile(ai, PBKDF2_KEY_BYTES);
        if (stored == null) return Result.WRONG;
        byte[] computed = pbkdf2Sha1(password.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(stored, computed) ? Result.OK : Result.WRONG;
    }

    /** Matches libtermux-auth: PBKDF2-HMAC-SHA1(pwd, "Termux!", 65536) truncated to 20 bytes. */
    private static byte[] pbkdf2Sha1(byte[] password) throws IllegalStateException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            // An empty key is invalid for SecretKeySpec but valid for HMAC (zero-padded).
            byte[] key = password.length == 0 ? new byte[64] : password;
            mac.init(new SecretKeySpec(key, "HmacSHA1"));

            mac.update(PBKDF2_SALT);
            mac.update(new byte[]{0, 0, 0, 1});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < PBKDF2_ITERATIONS; i++) {
                u = mac.doFinal(u);
                for (int b = 0; b < t.length; b++) t[b] ^= u[b];
            }
            return t;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    private static byte[] readFile(File f, int want) {
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[want];
            int off = 0;
            while (off < want) {
                int n = in.read(buf, off, want - off);
                if (n < 0) break;
                off += n;
            }
            return off == want ? buf : null;
        } catch (IOException e) {
            Log.d(LOG_TAG, "Cannot read " + f + ": " + e.getMessage());
            return null;
        }
    }

    /** @return TRUE/FALSE for a decisive result, or null when python/crypt is unavailable. */
    private static Boolean verifyWithPython(String hash, String password) {
        String[] cmd = {
            "python3", "-c",
            "import crypt,sys; h=crypt.crypt(sys.argv[2],sys.argv[1]);"
                + " sys.exit(0 if h==sys.argv[1] else 1)",
            hash, password
        };
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            int code = p.waitFor();
            if (code == 0) return Boolean.TRUE;
            if (code == 1) return Boolean.FALSE;
            return null;
        } catch (Exception e) {
            Log.d(LOG_TAG, "python3 crypt fallback unavailable: " + e.getMessage());
            return null;
        }
    }
}
