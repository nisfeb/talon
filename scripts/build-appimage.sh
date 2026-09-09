#!/usr/bin/env bash
#
# Build a portable Linux AppImage for Talon Desktop.
#
# Wraps the jpackage app-image (from
# `:composeApp:createReleaseDistributable`) into a single
# self-contained `.AppImage` runnable on most Linux distros. The
# bundled JRE means testers don't need Java installed.
#
# Output: dist/Talon-x86_64.AppImage
#
# Requirements: a JDK with full jmods (we use the one Gradle uses if
# it's a full JDK; otherwise pass JAVA_HOME explicitly). FUSE is
# needed at *runtime* on the tester's machine — most desktop distros
# already have it. AppImages can also be extracted with
# `--appimage-extract` if FUSE isn't available.
#
# Usage: scripts/build-appimage.sh [--skip-build] [--profile NAME]
#
# --profile NAME bakes TALON_PROFILE=NAME into the launcher, so the
# AppImage keeps its own data directory (~/.config/talon-NAME), its own
# sessions, database and single-instance lock, and titles its window
# "Talon (NAME)". It can then run beside the everyday Talon. The
# output is dist/Talon-NAME-x86_64.AppImage.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT"

SKIP_BUILD=0
PROFILE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build) SKIP_BUILD=1; shift ;;
        --profile) PROFILE="${2:?--profile needs a name}"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [[ -n "$PROFILE" && ! "$PROFILE" =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo "--profile must be letters, digits, - or _" >&2; exit 2
fi
APP_TITLE="Talon"
if [[ -n "$PROFILE" ]]; then APP_TITLE="Talon ($PROFILE)"; fi

DIST_SRC="composeApp/build/compose/binaries/main-release/app/Talon"
ICON_SRC="composeApp/src/desktopMain/resources/icon.png"
TOOLS_DIR="$ROOT/build/tools"
APPIMAGETOOL="$TOOLS_DIR/appimagetool-x86_64.AppImage"
WORK_DIR="$ROOT/build/appimage"
OUT_DIR="$ROOT/dist"
APPDIR="$WORK_DIR/Talon.AppDir"

# 1. Build the jpackage distributable unless asked to skip.
if [[ "$SKIP_BUILD" -eq 0 ]]; then
    echo "==> Building Talon distributable"
    # Prefer a JDK that ships full jmods (system openjdk-headless on
    # some distros only ships ~8 jmods, missing java.sql / java.desktop
    # which jlink needs). Override by exporting JAVA_HOME.
    if [[ -z "${JAVA_HOME:-}" ]]; then
        for cand in /home/sneagan/jdk-install/jdk-17.0.12+7 /usr/lib/jvm/temurin-17 /usr/lib/jvm/java-17-openjdk; do
            if [[ -d "$cand/jmods" && -e "$cand/jmods/java.sql.jmod" ]]; then
                export JAVA_HOME="$cand"
                break
            fi
        done
    fi
    : "${JAVA_HOME:?JAVA_HOME must point to a JDK with full jmods (java.sql, java.desktop)}"
    # `slimReleaseDistributable` depends on `createReleaseDistributable`
    # and runs after — so a single Gradle call gives us a slim host-
    # native-only distributable. The Gradle slim is host-OS-aware
    # (linux-x64 here) and shared by every package* task; the
    # post-cp slim block below stays as a belt-and-suspenders
    # idempotent fallback for `--skip-build` runs against an
    # unslimmed dist.
    PATH="$JAVA_HOME/bin:$PATH" "$ROOT/gradlew" :composeApp:slimReleaseDistributable
fi

if [[ ! -d "$DIST_SRC" ]]; then
    echo "ERROR: distributable not found at $DIST_SRC" >&2
    exit 1
fi

# 2. Fetch appimagetool if we don't have it cached.
mkdir -p "$TOOLS_DIR"
if [[ ! -x "$APPIMAGETOOL" ]]; then
    echo "==> Fetching appimagetool"
    curl -fL --output "$APPIMAGETOOL" \
        "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
    chmod +x "$APPIMAGETOOL"
fi

# 3. Stage the AppDir.
echo "==> Staging AppDir at $APPDIR"
rm -rf "$APPDIR"
mkdir -p "$APPDIR"

# Copy the entire jpackage app dir contents into AppDir root. The
# native launcher at `bin/Talon` already knows how to find its
# bundled JRE under `lib/runtime/` via relative path, so we don't
# have to rewrite anything.
cp -r "$DIST_SRC/." "$APPDIR/"

# Slim cross-platform native libs out of bundled JARs. The Linux
# AppImage doesn't need Windows DLLs, macOS dylibs (especially their
# .dSYM debug bundles), or ARM-Linux binaries. DJL's ONNX runtime +
# HuggingFace tokenizers ship all platforms in single fat JARs;
# stripping the non-target paths takes the AppImage from ~260 MB
# down to ~120 MB without changing runtime behavior (the runtime
# detects platform and loads the matching subdir; absent subdirs
# are never accessed on the host they don't apply to).
slim_jar() {
    local jar="$1"; shift
    if [[ ! -f "$jar" ]]; then return; fi
    local before
    before=$(stat -c%s "$jar")
    # zip -d takes globs; suppress "nothing to do" warnings on JARs
    # that don't have one of the patterns (e.g. older ONNX releases
    # without aarch64 builds).
    for pattern in "$@"; do
        zip --quiet -d "$jar" "$pattern" >/dev/null 2>&1 || true
    done
    local after
    after=$(stat -c%s "$jar")
    if [[ "$after" -lt "$before" ]]; then
        printf '  slim %-55s %s → %s\n' "$(basename "$jar")" \
            "$(numfmt --to=iec --suffix=B "$before")" \
            "$(numfmt --to=iec --suffix=B "$after")"
    fi
}

echo "==> Slimming non-Linux-x64 native libs"
for jar in "$APPDIR"/lib/app/onnxruntime-*.jar; do
    slim_jar "$jar" \
        'ai/onnxruntime/native/win-x64/*' \
        'ai/onnxruntime/native/osx-x64/*' \
        'ai/onnxruntime/native/osx-aarch64/*' \
        'ai/onnxruntime/native/linux-aarch64/*'
done
for jar in "$APPDIR"/lib/app/tokenizers-*.jar; do
    slim_jar "$jar" \
        'native/lib/win-x86_64/*' \
        'native/lib/osx-aarch64/*' \
        'native/lib/osx-x86_64/*' \
        'native/lib/linux-aarch64/*'
done

# Icon at AppDir root, named to match Icon= in the .desktop file.
cp "$ICON_SRC" "$APPDIR/talon.png"

# .desktop file — AppImage spec needs exactly one at the root.
cat > "$APPDIR/talon.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$APP_TITLE
GenericName=Urbit Chat Client
Exec=Talon
Icon=talon
Categories=Network;InstantMessaging;
Comment=Native chat client for Urbit
Terminal=false
StartupWMClass=Talon
EOF

# AppRun: AppImage's entrypoint. Mirrors what the bin/Talon launcher
# would do if invoked directly — set HERE, exec the launcher.
cat > "$APPDIR/AppRun" <<EOF
#!/usr/bin/env bash
HERE="\$(dirname "\$(readlink -f "\${0}")")"
${PROFILE:+export TALON_PROFILE="$PROFILE"}
exec "\${HERE}/bin/Talon" "\$@"
EOF
chmod +x "$APPDIR/AppRun"

# 4. Build the AppImage.
mkdir -p "$OUT_DIR"
OUT_FILE="$OUT_DIR/Talon${PROFILE:+-$PROFILE}-x86_64.AppImage"
echo "==> Packaging into $OUT_FILE"
# ARCH must be set for appimagetool 13+. Disable FUSE for the tool
# itself (--appimage-extract-and-run) so this works inside CI/sandbox
# environments without FUSE on the host.
# Write beside the target and rename over it: a copy of the previous
# build that is still running keeps its (now unlinked) file, where
# writing in place would pull the classes out from under it.
ARCH=x86_64 "$APPIMAGETOOL" --appimage-extract-and-run "$APPDIR" "$OUT_FILE.new"
mv -f "$OUT_FILE.new" "$OUT_FILE"

ls -lh "$OUT_FILE"
echo
echo "Built $OUT_FILE"
echo "Testers run it with: chmod +x $(basename "$OUT_FILE") && ./$(basename "$OUT_FILE")"
