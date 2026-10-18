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
openssl-tool,git,zsh,pulseaudio,termux-api"

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
