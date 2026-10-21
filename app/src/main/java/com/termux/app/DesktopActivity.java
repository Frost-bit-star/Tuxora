package com.termux.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.BuildConfig;
import com.termux.R;

import pl.droidsonroids.gif.GifDrawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher activity for Tuxora Desktop Mode.
 *
 * <p>Opening the app shows the bundled pangolin splash GIF, cross-fades into a native
 * Ubuntu-style login, then boots the landscape X11 desktop. On a fresh Termux install
 * (no password set via {@code passwd}) the login offers a one-tap "Sign up" straight to
 * the desktop; once a password exists it must be verified against {@code $PREFIX/etc/shadow}.
 *
 * <p>The X server is a separate {@code app_process} started by the {@code termux-x11} script
 * (loader.apk -> {@code CmdEntryPoint} -> native {@code libXlorie.so}). When it is ready it
 * broadcasts {@code ACTION_START}; the renderer either connects immediately or pulls the
 * pending connection from {@code LorieApp.pendingConnections} in its {@code onCreate}.
 */
public class DesktopActivity extends Activity {

    private static final String LOG_TAG = "TuxoraDesktop";
    private static final int REQ_PICK_AVATAR = 4100;
    private static final long SPLASH_MS = 2600;

    /** Held for the lifetime of the app process so the X session is not suspended. */
    private static PowerManager.WakeLock sWakeLock;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();

    private View splashImage;
    private View loginContent;
    private ImageView avatarImage;
    private TextView displayName;
    private View passwordRow;
    private EditText passwordInput;
    private ImageButton loginButton;
    private ImageButton togglePassword;
    private View signupColumn;
    private Button signupButton;
    private TextView errorText;
    private TextView clockText;

    private boolean passwordVisible;
    private boolean booted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_login);

        splashImage = findViewById(R.id.splash_image);
        loginContent = findViewById(R.id.login_content);
        avatarImage = findViewById(R.id.avatar_image);
        displayName = findViewById(R.id.display_name);
        passwordRow = findViewById(R.id.password_row);
        passwordInput = findViewById(R.id.password_input);
        loginButton = findViewById(R.id.login_button);
        togglePassword = findViewById(R.id.toggle_password);
        signupColumn = findViewById(R.id.signup_column);
        signupButton = findViewById(R.id.signup_button);
        errorText = findViewById(R.id.error_text);
        clockText = findViewById(R.id.clock_text);

        loadAvatar();
        setupClock();
        setupPasswordToggle();
        setupLogin();

        // Show splash, then cross-fade to the login form.
        try {
            ((pl.droidsonroids.gif.GifImageView) splashImage)
                .setImageDrawable(new GifDrawable(getAssets(), "tuxora_splash.gif"));
        } catch (Exception e) {
            Log.w(LOG_TAG, "Splash GIF failed to load, continuing without it", e);
        }
        main.postDelayed(this::revealLogin, SPLASH_MS);
    }

    /** True once the desktop boot has been kicked off; the login form is then skipped. */
    private boolean autoBooting;

    // ------------------------------------------------------------------ clock

    private void setupClock() {
        Runnable tick = new Runnable() {
            @Override public void run() {
                Date now = new Date();
                String d = new SimpleDateFormat("MMM d", Locale.getDefault()).format(now);
                String t = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now);
                clockText.setText(d + "  " + t);
                main.postDelayed(this, 30000);
            }
        };
        tick.run();
    }

    // ------------------------------------------------------------------ splash

    private void revealLogin() {
        authExecutor.execute(() -> {
            String user = TermuxPassword.resolveUsername(this);
            boolean hasPw = TermuxPassword.hasPassword(this);
            main.post(() -> {
                applyAuthMode(user, hasPw);
                crossFadeSplashToLogin();
            });
        });
    }

    private void applyAuthMode(String user, boolean hasPassword) {
        displayName.setText(user);
        if (hasPassword) {
            passwordRow.setVisibility(View.VISIBLE);
            signupColumn.setVisibility(View.GONE);
        } else {
            passwordRow.setVisibility(View.GONE);
            signupColumn.setVisibility(View.VISIBLE);
        }
    }

    private void crossFadeSplashToLogin() {
        loginContent.setVisibility(View.VISIBLE);
        splashImage.animate().alpha(0f).setDuration(450)
            .setInterpolator(new AccelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    splashImage.setVisibility(View.GONE);
                }
            }).start();
        loginContent.animate().alpha(1f).setDuration(500)
            .setInterpolator(new DecelerateInterpolator()).start();
    }

    // ------------------------------------------------------------------ password

    private void setupPasswordToggle() {
        togglePassword.setOnClickListener(v -> {
            passwordVisible = !passwordVisible;
            int type = passwordVisible
                ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
            passwordInput.setInputType(type);
            passwordInput.setSelection(passwordInput.getText().length());
            togglePassword.setImageResource(passwordVisible ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        });
    }

    private void setupLogin() {
        loginButton.setOnClickListener(v -> attemptLogin());
        passwordInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { errorText.setVisibility(View.GONE); }
        });
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            attemptLogin();
            return true;
        });
        signupButton.setOnClickListener(v -> bootDesktop());
        avatarImage.setOnClickListener(v -> pickAvatar());
    }

    private void attemptLogin() {
        String pw = passwordInput.getText().toString();
        if (pw.isEmpty()) {
            showError("Enter your password");
            return;
        }
        errorText.setVisibility(View.GONE);
        loginButton.setEnabled(false);
        signupButton.setEnabled(false);
        authExecutor.execute(() -> {
            TermuxPassword.Result r = TermuxPassword.verify(this, pw);
            main.post(() -> {
                loginButton.setEnabled(true);
                signupButton.setEnabled(true);
                switch (r) {
                    case OK:
                        bootDesktop();
                        break;
                    case WRONG:
                    default:
                        showError("Sorry, password authentication failed. Try again.");
                        shake(loginContent);
                        break;
                }
            });
        });
    }

    private void showError(String msg) {
        errorText.setText(msg);
        errorText.setVisibility(View.VISIBLE);
    }

    private void shake(View v) {
        v.animate().translationXBy(-14f).setDuration(45)
            .withEndAction(() -> v.animate().translationXBy(28f).setDuration(60)
                .withEndAction(() -> v.animate().translationXBy(-18f).setDuration(50)
                    .withEndAction(() -> v.animate().translationXBy(0f).setDuration(40).start())
                    .start()).start()).start();
    }

    // ------------------------------------------------------------------ avatar

    private void loadAvatar() {
        circularClip();
        File f = avatarFile();
        if (f.exists()) {
            Bitmap bm = decodeSampled(f, 256);
            if (bm != null) { avatarImage.setImageBitmap(bm); return; }
        }
        avatarImage.setImageResource(R.mipmap.ic_launcher);
    }

    private void circularClip() {
        avatarImage.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatarImage.setClipToOutline(true);
    }

    private File avatarFile() {
        return new File(getFilesDir(), "login-avatar.png");
    }

    private void pickAvatar() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK_AVATAR);
        } catch (Exception e) {
            Toast.makeText(this, "No image picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_AVATAR || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        authExecutor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Bitmap src = decodeStream(in, 512);
                if (src == null) return;
                try (FileOutputStream out = new FileOutputStream(avatarFile())) {
                    src.compress(Bitmap.CompressFormat.PNG, 90, out);
                }
                main.post(() -> avatarImage.setImageBitmap(src));
            } catch (Exception e) {
                Log.e(LOG_TAG, "Failed to save avatar", e);
            }
        });
    }

    // ------------------------------------------------------------------ boot

    private void bootDesktop() {
        if (booted) return;
        autoBooting = true;
        // The slim build can install from a bootstrap pre-staged into public
        // Downloads (pushed over adb), but reading it needs the storage
        // permission on Android 10+. Without it we still boot — setup then
        // falls back to the normal network download.
        // Only the slim build reads a pre-staged bootstrap from public Downloads, which
        // needs READ_EXTERNAL_STORAGE. The full build ships everything in the APK and must
        // NOT request it: on Android 11+ the permission is effectively auto-denied, so the
        // request callback may never fire and boot would hang forever on the splash.
        if (BuildConfig.TUXORA_SLIM && Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        continueBoot();
    }

    private static final int REQ_STORAGE = 41;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            // Boot either way: granted uses the pre-staged zip, denied downloads it.
            continueBoot();
        }
    }

    private void continueBoot() {
        if (booted) return;
        booted = true;
        acquireWakeLock();

        // Install the bundled Termux prefix (which already contains the whole
        // desktop stack) and the bundled Tuxora $HOME files before starting X,
        // so a fresh install boots straight into the configured desktop.
        TuxoraSetup.prepare(this, () -> {
            // Best-effort: ~/storage/{shared,downloads,music,...} links into the
            // shared storage, so the desktop file manager can browse the phone.
            TermuxInstaller.setupStorageSymlinks(this);
            launchDesktopRenderer();
            startXServer();
            finish();
        });
    }

    private void acquireWakeLock() {
        if (sWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tuxora:desktop");
            sWakeLock.setReferenceCounted(false);
            sWakeLock.acquire();
        }
    }

    private void launchDesktopRenderer() {
        try {
            // Pin the X display to landscape before the renderer starts: polybar
            // parks itself off-screen if it launches against a portrait screen.
            getSharedPreferences("com.termux_preferences", MODE_PRIVATE)
                .edit().putString("forceOrientation", "landscape").apply();
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(getPackageName(), "com.termux.x11.MainActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to launch the desktop renderer", e);
        }
    }

    private void startXServer() {
        String pkg = getPackageName();
        String prefix = "/data/data/" + pkg + "/files/usr";
        String home = "/data/data/" + pkg + "/files/home";

        // Write a boot script and execute it. Doing the pgrep/setsid inline via
        // `sh -c` fails because pgrep -f matches the shell's own cmdline (which
        // contains the pattern string), so it always thinks X is already running.
        // A script file's cmdline is just "sh <path>", which never matches.
        String script =
            "#!/system/bin/sh\n" +
            "export PREFIX=" + prefix + "\n" +
            "export HOME=" + home + "\n" +
            "export TMPDIR=$PREFIX/tmp\n" +
            "export PATH=$HOME/.local/bin:$PREFIX/bin:/system/bin\n" +
            "export LD_LIBRARY_PATH=$PREFIX/lib\n" +
            "export LANG=en_US.UTF-8\n" +
            "export SHELL=$PREFIX/bin/bash\n" +
            "export TERM=xterm-256color\n" +
            "export DISPLAY=:0\n" +
            "export ANDROID_ROOT=/system\n" +
            "export ANDROID_DATA=/data\n" +
            "export EXTERNAL_STORAGE=/sdcard\n" +
            "export XDG_DATA_HOME=$HOME/.local/share\n" +
            "export XDG_DATA_DIRS=$PREFIX/share:$PREFIX/share/xdg:/system/share\n" +
            "export XDG_CONFIG_DIRS=$PREFIX/etc/xdg\n" +
            "cd $HOME\n" +
            "mkdir -p \"$HOME/.mpd/playlists\" \"$HOME/Music\"\n" +
            "# Belt-and-suspenders: the desktop helper scripts (rofi launcher/menu, polybar\n" +
            "# modules) must be executable or the menu button and mpd/status scripts fail\n" +
            "# with EACCES. Extraction normally sets this, but re-assert it here.\n" +
            "chmod 700 \"$HOME/.config/rofi/bin\"/* \"$HOME/.local/bin\"/* 2>/dev/null\n" +
            "chmod 700 \"$HOME/.config/polybar\"/*/scripts/* \"$HOME/.config/polybar\"/*/launch.sh 2>/dev/null\n" +
            "[ -d \"$HOME/.cache/fontconfig\" ] || { mkdir -p \"$HOME/.cache/fontconfig\"; \"$PREFIX/bin/fc-cache\" -f >/dev/null 2>&1 || true; }\n" +
            "[ -f \"$PREFIX/var/lib/dbus/machine-id\" ] || { mkdir -p \"$PREFIX/var/lib/dbus\"; \"$PREFIX/bin/dbus-uuidgen\" --ensure >/dev/null 2>&1 || true; }\n" +
            "if [ -f \"$HOME/.tuxora-motd\" ] && ! grep -q 'Tuxora' \"$PREFIX/etc/motd\" 2>/dev/null; then cp \"$HOME/.tuxora-motd\" \"$PREFIX/etc/motd\"; fi\n" +
            "ln -sfn /sdcard $HOME/sdcard\n" +
            "# GTK resolves icon themes through the XDG data dirs; themes shipped in\n" +
            "# the legacy ~/.icons path are invisible, so expose them under\n" +
            "# ~/.local/share/icons (which is $XDG_DATA_HOME/icons).\n" +
            "if [ -d \"$HOME/.icons\" ]; then\n" +
            "  mkdir -p \"$HOME/.local/share/icons\"\n" +
            "  for t in \"$HOME/.icons\"/*; do\n" +
            "    [ -e \"$t\" ] || continue\n" +
            "    ln -sfn \"$t\" \"$HOME/.local/share/icons/$(basename \"$t\")\"\n" +
            "  done\n" +
            "fi\n" +
            "# The bundled packages ship no generated caches, so build them once. Without\n" +
            "# share/mime/mime.cache Thunar cannot resolve a file's MIME type and shows no\n" +
            "# file icons (folder icons still work because inode/directory is built in), and\n" +
            "# without share/glib-2.0/schemas/gschemas.compiled GTK aborts with \"No GSettings\n" +
            "# schema could be found\", so icon/settings theming (rofi launcher icons) fails.\n" +
            "if [ ! -f \"$PREFIX/share/mime/mime.cache\" ] && [ -x \"$PREFIX/bin/update-mime-database\" ]; then\n" +
            "  \"$PREFIX/bin/update-mime-database\" \"$PREFIX/share/mime\" >/dev/null 2>&1 || true\n" +
            "fi\n" +
            "if [ ! -f \"$PREFIX/share/glib-2.0/schemas/gschemas.compiled\" ] && [ -x \"$PREFIX/bin/glib-compile-schemas\" ]; then\n" +
            "  \"$PREFIX/bin/glib-compile-schemas\" \"$PREFIX/share/glib-2.0/schemas\" >/dev/null 2>&1 || true\n" +
            "fi\n" +
            "# The icon themes ship an icon-theme.cache generated on the build machine. A\n" +
            "# cache whose embedded directory hashes do not match the extracted tree makes\n" +
            "# GTK3 trust the cache, miss every lookup and render blank icons (rofi launcher,\n" +
            "# Thunar), instead of falling back to scanning. Rebuild it unconditionally.\n" +
            "if [ -x \"$PREFIX/bin/gtk-update-icon-cache\" ]; then\n" +
            "  for t in \"$HOME/.local/share/icons\"/* \"$PREFIX/share/icons\"/*; do\n" +
            "    [ -f \"$t/index.theme\" ] || continue\n" +
            "    ( cd \"$t\" && \"$PREFIX/bin/gtk-update-icon-cache\" -q -t -f . >/dev/null 2>&1 ) || true\n" +
            "  done\n" +
            "fi\n" +
            "{ echo \"== tuxora diag $(date) ==\";\n" +
            "  echo \"mime.cache: $( [ -f \"$PREFIX/share/mime/mime.cache\" ] && echo yes || echo no )\";\n" +
            "  echo \"gschemas.compiled: $( [ -f \"$PREFIX/share/glib-2.0/schemas/gschemas.compiled\" ] && echo yes || echo no )\";\n" +
            "  echo \"PREFIX/share/icons:\"; ls \"$PREFIX/share/icons\" 2>&1;\n" +
            "  echo \"Papirus index: $( [ -f \"$PREFIX/share/icons/Papirus/index.theme\" ] && echo yes || echo no )\";\n" +
            "  echo \"HOME/icons/Hybrid apps/48 count: $(ls \"$HOME/.local/share/icons/Hybrid/apps/48\" 2>/dev/null | wc -l)\";\n" +
            "  echo \"-- ls ~/.icons/Hybrid/apps/48 (first 8):\"; ls \"$HOME/.icons/Hybrid/apps/48\" 2>&1 | head -8;\n" +
            "  echo \"-- mime query:\"; printf 'hello' > \"$TMPDIR/t.txt\"; \"$PREFIX/bin/xdg-mime\" query filetype \"$TMPDIR/t.txt\" 2>&1;\n" +
            "  echo \"-- launcher script:\"; cat \"$HOME/.config/rofi/bin/launcher\" 2>&1 | head -40;\n" +
            "  echo \"-- gtk settings:\"; cat \"$HOME/.config/gtk-3.0/settings.ini\" 2>&1;\n" +
            "} >/sdcard/desktop-diag.log 2>&1\n" +
            "if [ -f $HOME/.config/rofi/bin/launcher ] && grep -q 'STYLE=\"launcher\"' $HOME/.config/rofi/bin/launcher; then\n" +
            "  sed -i 's/DIR=\"tuxora\"/DIR=\"launchers\"/; s/STYLE=\"launcher\"/STYLE=\"Appdrawer\"/' $HOME/.config/rofi/bin/launcher\n" +
            "fi\n" +
            "# The Appdrawer style shipped with icon-theme Papirus, but the Papirus index\n" +
            "# inherits 'breeze,hicolor' and breeze is not installed, so rofi's icon lookup\n" +
            "# stops before hicolor and every launcher entry renders without an icon. The\n" +
            "# bundled Hybrid theme is self-contained and is what the shipped GTK settings\n" +
            "# already select. Force every launcher rasi to Hybrid so icons resolve.\n" +
            "for f in $HOME/.config/rofi/launchers/*.rasi $HOME/.config/rofi/tuxora/launcher.rasi $HOME/.config/rofi/*.rasi; do\n" +
            "  [ -f \"$f\" ] || continue\n" +
            "  sed -i 's/icon-theme:[[:space:]]*\"[^\"]*\"/icon-theme:                     \"Hybrid\"/g' \"$f\"\n" +
            "done\n" +
            "# Also patch any rofi config that might set icon-theme\n" +
            "find $HOME/.config/rofi -name '*.rasi' -o -name 'config*' 2>/dev/null | xargs grep -l 'icon-theme' 2>/dev/null | xargs -I{} sed -i 's/icon-theme:[[:space:]]*\"[^\"]*\"/icon-theme:                     \"Hybrid\"/g' {} 2>/dev/null || true\n" +
            "echo '---APPDRAWER ICON---' >> /sdcard/desktop-diag.log 2>&1\n" +
            "grep icon-theme $HOME/.config/rofi/launchers/Appdrawer.rasi >> /sdcard/desktop-diag.log 2>&1\n" +
            "if pgrep -f 'termux-x11 " + pkg + "' >/dev/null 2>&1; then exit 0; fi\n" +
            "setsid $PREFIX/bin/termux-x11 :0 -ac " +
            "-xstartup \"dbus-launch --exit-with-session openbox-session\" " +
            "</dev/null >/sdcard/desktop-x.log 2>&1 &\n";

        try {
            File dir = new File(home, ".tuxora");
            dir.mkdirs();
            File f = new File(dir, "boot.sh");
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(script);
            w.close();
            f.setExecutable(true, false);
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", f.getAbsolutePath()});
            Log.i(LOG_TAG, "startXServer launched script " + f.getAbsolutePath() + ", alive=" + p.isAlive());
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to start the X server", e);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onDestroy() {
        super.onDestroy();
        authExecutor.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ image utils

    private static Bitmap decodeStream(InputStream in, int max) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(in, null, opts);
        opts.inSampleSize = calcSampleSize(opts.outWidth, opts.outHeight, max);
        opts.inJustDecodeBounds = false;
        try (InputStream in2 = new java.io.BufferedInputStream(in)) {
            return BitmapFactory.decodeStream(in2, null, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeSampled(File f, int max) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        opts.inSampleSize = calcSampleSize(opts.outWidth, opts.outHeight, max);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    private static int calcSampleSize(int w, int h, int max) {
        int size = 1;
        while (w / 2 >= max || h / 2 >= max) { w /= 2; h /= 2; size *= 2; }
        return size;
    }
}
