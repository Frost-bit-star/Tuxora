#!/usr/bin/env bash
##
## Build self-contained Tuxora bootstrap archives.
##
## The archives contain the Termux base system *plus* the whole Tuxora desktop
## stack (openbox, termux-x11, polybar, rofi, thunar, xfce4, geany, netsurf,
## audio, ...), pulled from the termux-main and termux-x11 repositories. The
## app extracts one of these on first run, so a user who installs the release
## APK gets the desktop with no `pkg install` and no network access.
##
## Usage:
##   scripts/build-tuxora-bootstraps.sh [aarch64,arm,i686,x86_64]
##
## Output: app/src/main/cpp/bootstrap-<arch>.zip
##
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$REPO_ROOT/app/src/main/cpp"

ARCHS="${1:-aarch64,arm,i686,x86_64}"
PORT="${TUXORA_REPO_PORT:-8765}"
WORK="${TUXORA_BOOTSTRAP_WORK:-/tmp/tuxora-bootstrap}"
# Pinned termux-packages revision so bootstraps are reproducible.
TERMUX_PACKAGES_REF="${TERMUX_PACKAGES_REF:-master}"

# Packages installed by the original Tuxora setup.sh, minus the things that are
# only needed for the VNC workflow (replaced by termux-x11 here). These are
# pulled in addition to the standard bootstrap package set.
TUXORA_EXTRA_PACKAGES="x11-repo,termux-x11-nightly,dbus,openbox,polybar,rofi,feh,\
thunar,geany,gtk2,gtk3,imagemagick,xfce4-settings,xfce4-terminal,exo,netsurf,\
ranger,vim,nano,htop,ncmpcpp,mpc,mpd,mutt,elinks,mpv-x,neofetch,calcurse,bmon,\
calc,bc,fsmon,l3afpad,xarchiver,xcompmgr,xmlstarlet,xorg-font-util,xorg-xrdb,\
fontconfig-utils,desktop-file-utils,startup-notification,xbitmaps,jq,wget,\
openssl-tool,git,zsh,pulseaudio,termux-api,\
audacious,audacious-plugins,papirus-icon-theme"

mkdir -p "$WORK"

echo "[*] Cloning termux-packages ($TERMUX_PACKAGES_REF)..."
if [ ! -d "$WORK/termux-packages/.git" ]; then
    git clone --depth 1 --branch "$TERMUX_PACKAGES_REF" \
        https://github.com/termux/termux-packages.git "$WORK/termux-packages"
fi

echo "[*] Starting merged main+x11 repository proxy on port $PORT..."
python3 "$REPO_ROOT/scripts/tuxora-bootstrap-repo.py" --port "$PORT" &
REPO_PID=$!
trap 'kill $REPO_PID 2>/dev/null || true' EXIT
# Wait for the server to accept connections.
for _ in $(seq 1 50); do
    if curl -fsS "http://127.0.0.1:$PORT/dists/stable/main/binary-all/Packages" >/dev/null 2>&1 \
        || curl -fsS "http://127.0.0.1:$PORT/dists/stable/main/binary-aarch64/Packages" >/dev/null 2>&1; then
        break
    fi
    sleep 0.2
done

BUILD_DIR="$WORK/out"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Skip architectures whose bootstrap already exists (e.g. restored from the CI
# cache), so only missing ABI(s) are regenerated.
TODO=""
for arch in $(echo "$ARCHS" | tr ',' ' '); do
    if [ -s "$BUILD_DIR/bootstrap-$arch.zip" ]; then
        echo "[*] Reusing cached bootstrap-$arch.zip"
    else
        TODO="${TODO:+$TODO,}$arch"
    fi
done

if [ -n "$TODO" ]; then
    echo "[*] Generating bootstraps for: $TODO"
    "$WORK/termux-packages/scripts/generate-bootstraps.sh" \
        --architectures "$TODO" \
        --repository "http://127.0.0.1:$PORT" \
        --add "$TUXORA_EXTRA_PACKAGES"
else
    echo "[*] All bootstraps already present, skipping generation."
fi

# Replace the stock Termux login banner with the Tuxora one, so a fresh install
# greets the user with Tuxora branding in both Termux sessions and the desktop
# terminal (via the ~/.bashrc shipped in the dotfiles archive).
MOTD_DIR="$WORK/motd"
mkdir -p "$MOTD_DIR/etc"
cp -f "$REPO_ROOT/scripts/tuxora-motd.txt" "$MOTD_DIR/etc/motd"
for arch in $(echo "$ARCHS" | tr ',' ' '); do
    if [ -f "$BUILD_DIR/bootstrap-$arch.zip" ]; then
        (cd "$MOTD_DIR" && zip -q "$BUILD_DIR/bootstrap-$arch.zip" etc/motd)
        echo "[*] Injected Tuxora motd into bootstrap-$arch.zip"
    fi
done

# Patch termux-x11 inside each bootstrap.
#
# The stock termux-x11-nightly package ships:
#   * A Loader that reads the APK signing certificate via the deprecated
#     PackageManager.GET_SIGNATURES call, which throws on Android 13+ (SDK 33),
#     so the X server aborts with "Termux:X11 application is not found".
#     third_party/termux-x11/shell-loader is patched to use
#     GET_SIGNING_CERTIFICATES on SDK>=33 and SigningInfo on SDK>=28.
#   * A launcher script hardcoded to the com.termux.x11 package name, which never
#     matches this fork (com.termux) so the running server is invisible to the
#     renderer. We point it at our own package and set a stable --nice-name.
# Build the patched loader APK once, then inject it and rewrite the script in
# every bootstrap archive.
LOADER_REPO="$REPO_ROOT/third_party/termux-x11"
LOADER_APK=""
if [ -d "$LOADER_REPO" ]; then
    echo "[*] Building patched termux-x11 shell-loader..."
    (cd "$LOADER_REPO" && ./gradlew --no-daemon :shell-loader:assembleDebug -x lint >/dev/null 2>&1) || true
    for cand in "$LOADER_REPO/shell-loader/build/outputs/apk/debug"/*.apk; do
        [ -f "$cand" ] && LOADER_APK="$cand" && break
    done
fi

if [ -n "$LOADER_APK" ] && [ -f "$LOADER_APK" ]; then
    for arch in $(echo "$ARCHS" | tr ',' ' '); do
        zip_file="$BUILD_DIR/bootstrap-$arch.zip"
        [ -f "$zip_file" ] || continue
        tmp_dir="$(mktemp -d)"
        mkdir -p "$tmp_dir/libexec/termux-x11" "$tmp_dir/bin"
        cp -f "$LOADER_APK" "$tmp_dir/libexec/termux-x11/loader.apk"

        # Rewrite the launcher: correct target package and CLASSPATH.
        if unzip -p "$zip_file" bin/termux-x11 > "$tmp_dir/bin/termux-x11" 2>/dev/null; then
            python3 - "$tmp_dir/bin/termux-x11" <<'PYEOF'
import sys, re
p = sys.argv[1]
s = open(p).read()
s = re.sub(
    r'export CLASSPATH=.*?\n',
    'export CLASSPATH=/data/data/com.termux/files/usr/libexec/termux-x11/loader.apk\n',
    s)
s = s.replace(
    'exec /system/bin/app_process -Xnoimage-dex2oat / --nice-name="termux-x11 com.termux.x11 $*" com.termux.x11.Loader "$@"',
    'exec /system/bin/app_process -Xnoimage-dex2oat / --nice-name="termux-x11 com.termux $*" com.termux.x11.Loader "$@"')
s = s.replace('com.termux.x11.Loader', 'com.termux.x11.Loader')
open(p, 'w').write(s)
PYEOF
            chmod 755 "$tmp_dir/bin/termux-x11"
        fi
        (cd "$tmp_dir" && \
            zip -q -X "$zip_file" libexec/termux-x11/loader.apk bin/termux-x11)
        rm -rf "$tmp_dir"
        echo "[*] Injected patched termux-x11 loader + launcher into bootstrap-$arch.zip"
    done
else
    echo "[!] Patched shell-loader APK not found; bootstrap keeps the stock termux-x11" >&2
fi

# The audacious-plugins package ships a PipeWire output plugin. Tuxora runs
# PulseAudio (Android AAudio via OpenSL), and there is no PipeWire server, so
# audacious picks the PipeWire plugin by default and fails with
# "PipeWireOutput: unable to connect context". Rename the plugin inside each
# bootstrap so audacious falls back to its PulseAudio output.
for arch in $(echo "$ARCHS" | tr ',' ' '); do
    zip_file="$BUILD_DIR/bootstrap-$arch.zip"
    [ -f "$zip_file" ] || continue
    pipewire_entry="$(unzip -Z1 "$zip_file" 2>/dev/null \
        | grep -m1 'lib/audacious/Output/pipewire\.so$' || true)"
    if [ -n "$pipewire_entry" ]; then
        tmp_dir="$(mktemp -d)"
        (cd "$tmp_dir" && unzip -q -o "$zip_file" "$pipewire_entry" \
            && mv "$pipewire_entry" "$pipewire_entry.disabled" \
            && zip -q -d "$zip_file" "$pipewire_entry" >/dev/null 2>&1 || true \
            && zip -q "$zip_file" "$pipewire_entry.disabled")
        rm -rf "$tmp_dir"
        echo "[*] Disabled PipeWire output plugin in bootstrap-$arch.zip"
    fi
done

echo "[*] Copying archives to $OUT_DIR ..."
mkdir -p "$OUT_DIR"
for arch in $(echo "$ARCHS" | tr ',' ' '); do
    if [ -f "$BUILD_DIR/bootstrap-$arch.zip" ]; then
        cp -f "$BUILD_DIR/bootstrap-$arch.zip" "$OUT_DIR/bootstrap-$arch.zip"
        echo "    bootstrap-$arch.zip  ($(du -h "$OUT_DIR/bootstrap-$arch.zip" | cut -f1))"
    else
        echo "[!] Missing bootstrap-$arch.zip" >&2
        exit 1
    fi
done

echo "[*] Done."
