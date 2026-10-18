package com.termux.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure-Java implementations of the traditional {@code crypt(3)} password hashes that
 * Termux's {@code passwd} (shadow-utils) writes into {@code $PREFIX/etc/shadow}:
 * {@code $1$} (MD5), {@code $5$} (SHA-256) and {@code $6$} (SHA-512).
 *
 * <p>Both algorithms follow the glibc reference implementations
 * ({@code crypt/md5-crypt.c}, {@code crypt/sha256-crypt.c}, {@code crypt/sha512-crypt.c})
 * and are verified offline against {@code openssl passwd -1/-5/-6}. Kept dependency-free
 * so it builds without NDK; yescrypt/bcrypt hashes fall back to {@code python3}'s
 * {@code crypt} module in {@link TermuxPassword}.
 */
final class Crypt {

    private static final String ITOA64 =
        "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private Crypt() {}

    static String md5Crypt(String password, String salt) {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] saltBytes = salt.getBytes(StandardCharsets.US_ASCII);
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(pw);
            md.update("$1$".getBytes(StandardCharsets.US_ASCII));
            md.update(saltBytes);

            byte[] alt = MessageDigest.getInstance("MD5").digest(concat(pw, saltBytes, pw));
            for (int i = pw.length; i > 0; i -= 16) md.update(alt, 0, i > 16 ? 16 : i);
            for (int i = pw.length; i != 0; i >>= 1) {
                if ((i & 1) != 0) md.update(new byte[]{0});
                else md.update(pw, 0, 1);
            }
            byte[] d = md.digest();
            for (int i = 0; i < 1000; i++) {
                MessageDigest m2 = MessageDigest.getInstance("MD5");
                if ((i & 1) != 0) m2.update(pw); else m2.update(d, 0, 16);
                if (i % 3 != 0) m2.update(saltBytes);
                if (i % 7 != 0) m2.update(pw);
                if ((i & 1) != 0) m2.update(d, 0, 16); else m2.update(pw);
                d = m2.digest();
            }
            StringBuilder sb = new StringBuilder("$1$").append(salt).append('$');
            to64(sb, ((long) (d[0] & 0xff) << 16) | ((long) (d[6] & 0xff) << 8) | (d[12] & 0xff), 4);
            to64(sb, ((long) (d[1] & 0xff) << 16) | ((long) (d[7] & 0xff) << 8) | (d[13] & 0xff), 4);
            to64(sb, ((long) (d[2] & 0xff) << 16) | ((long) (d[8] & 0xff) << 8) | (d[14] & 0xff), 4);
            to64(sb, ((long) (d[3] & 0xff) << 16) | ((long) (d[9] & 0xff) << 8) | (d[15] & 0xff), 4);
            to64(sb, ((long) (d[4] & 0xff) << 16) | ((long) (d[10] & 0xff) << 8) | (d[5] & 0xff), 4);
            to64(sb, d[11] & 0xff, 2);
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ulrich Drepper's SHA-crypt; {@code explicitRounds} keeps the {@code rounds=N$} part in the output. */
    static String shaCrypt(String password, String salt, long rounds, boolean explicitRounds, boolean isSha512) {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] saltBytes = salt.getBytes(StandardCharsets.US_ASCII);
        int size = isSha512 ? 64 : 32;
        String algo = isSha512 ? "SHA-512" : "SHA-256";
        try {
            // alt = H(key + salt + key)
            MessageDigest altMd = MessageDigest.getInstance(algo);
            altMd.update(pw);
            altMd.update(saltBytes);
            altMd.update(pw);
            byte[] alt = altMd.digest();

            // main digest: H(key + salt) then alternate-sum chunks then binary-representation loop
            MessageDigest md = MessageDigest.getInstance(algo);
            md.update(pw);
            md.update(saltBytes);
            int cnt = pw.length;
            while (cnt > size) { md.update(alt); cnt -= size; }
            md.update(alt, 0, cnt);
            cnt = pw.length;
            while (cnt > 0) {
                if ((cnt & 1) != 0) md.update(alt);
                else md.update(pw);
                cnt >>= 1;
            }
            byte[] a = md.digest();

            // p_bytes = H(key repeated key_len times), tiled to key_len bytes
            MessageDigest pMd = MessageDigest.getInstance(algo);
            for (int i = 0; i < pw.length; i++) pMd.update(pw);
            byte[] pDigest = pMd.digest();
            byte[] pBytes = new byte[pw.length];
            for (int i = 0; i < pw.length; i++) pBytes[i] = pDigest[i % size];

            // s_bytes = H(salt repeated (16 + a[0]) times), tiled to salt_len bytes
            MessageDigest sMd = MessageDigest.getInstance(algo);
            for (int i = 0; i < 16 + (a[0] & 0xff); i++) sMd.update(saltBytes);
            byte[] sDigest = sMd.digest();
            byte[] sBytes = new byte[saltBytes.length];
            for (int i = 0; i < saltBytes.length; i++) sBytes[i] = sDigest[i % size];

            for (int round = 0; round < rounds; round++) {
                MessageDigest m2 = MessageDigest.getInstance(algo);
                if ((round & 1) != 0) m2.update(pBytes); else m2.update(a);
                if (round % 3 != 0) m2.update(sBytes);
                if (round % 7 != 0) m2.update(pBytes);
                if ((round & 1) != 0) m2.update(a); else m2.update(pBytes);
                a = m2.digest();
            }

            StringBuilder sb = new StringBuilder(isSha512 ? "$6$" : "$5$");
            if (explicitRounds) sb.append("rounds=").append(rounds).append('$');
            sb.append(salt).append('$');
            if (isSha512) {
                int[][] perm = {{0,21,42},{22,43,1},{44,2,23},{3,24,45},{25,46,4},{47,5,26},{6,27,48},
                    {28,49,7},{50,8,29},{9,30,51},{31,52,10},{53,11,32},{12,33,54},{34,55,13},{56,14,35},
                    {15,36,57},{37,58,16},{59,17,38},{18,39,60},{40,61,19},{62,20,41}};
                for (int[] p : perm)
                    to64(sb, ((long) (a[p[0]] & 0xff) << 16) | ((long) (a[p[1]] & 0xff) << 8) | (a[p[2]] & 0xff), 4);
                to64(sb, a[63] & 0xff, 2);
            } else {
                int[][] perm = {{0,10,20},{21,1,11},{12,22,2},{3,13,23},{24,4,14},{15,25,5},{6,16,26},
                    {27,7,17},{18,28,8},{9,19,29}};
                for (int[] p : perm)
                    to64(sb, ((long) (a[p[0]] & 0xff) << 16) | ((long) (a[p[1]] & 0xff) << 8) | (a[p[2]] & 0xff), 4);
                to64(sb, ((long) (a[31] & 0xff) << 8) | (a[30] & 0xff), 3);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when {@link #verify} can check this hash natively (MD5/SHA-256/SHA-512 crypt). */
    static boolean isSupported(String hash) {
        return hash != null && (hash.startsWith("$1$") || hash.startsWith("$5$") || hash.startsWith("$6$"));
    }

    /** Verifies {@code password} against a full shadow hash string ({@code $1$}/{@code $5$}/{@code $6$}). */
    static boolean verify(String password, String hash) {
        if (password == null || hash == null) return false;
        if (hash.startsWith("$1$")) {
            int s = 3, e = hash.indexOf('$', s);
            if (e < 0) return false;
            String salt = hash.substring(s, e);
            return constantTimeEquals(hash, md5Crypt(password, salt));
        }
        if (hash.startsWith("$5$") || hash.startsWith("$6$")) {
            boolean isSha512 = hash.startsWith("$6$");
            int p = 3;
            long rounds = 5000;
            boolean explicitRounds = false;
            if (hash.startsWith("rounds=", p)) {
                p += 7;
                int e = hash.indexOf('$', p);
                if (e < 0) return false;
                try { rounds = Long.parseLong(hash.substring(p, e)); } catch (NumberFormatException nfe) { return false; }
                rounds = Math.max(1000, Math.min(999999999, rounds));
                explicitRounds = true;
                p = e + 1;
            }
            int e = hash.indexOf('$', p);
            if (e < 0) return false;
            String salt = hash.substring(p, Math.min(e, p + 16));
            return constantTimeEquals(hash, shaCrypt(password, salt, rounds, explicitRounds, isSha512));
        }
        return false;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    private static void to64(StringBuilder sb, long v, int n) {
        for (int i = 0; i < n; i++) { sb.append(ITOA64.charAt((int) (v & 0x3f))); v >>= 6; }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
