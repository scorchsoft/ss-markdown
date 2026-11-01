#!/usr/bin/env bash
# Installs the Android SDK pieces this project needs into $ANDROID_HOME
# (default /root/android-sdk). Idempotent: re-running when everything is
# present is a no-op that takes under a second.
#
# Why this exists: Claude Code's cloud containers ship Gradle and a JDK but
# no Android SDK, and every session starts from a fresh container. Run it
# once per session, before the first `gradle -p apps/android ...`.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;36.0.0"
# AGP 8.13 also resolves its own default build-tools (35.0.0) on first use.
BUILD_TOOLS_AGP="build-tools;35.0.0"
# Pinned rather than "latest" so two sessions get the same bytes.
CMDLINE_TOOLS_ZIP="commandlinetools-linux-13114758_latest.zip"

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [ -d "$ANDROID_HOME/platforms/android-36" ] && [ -d "$ANDROID_HOME/build-tools/36.0.0" ] && [ -d "$ANDROID_HOME/build-tools/35.0.0" ] \
   && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
  echo "Android SDK already present at $ANDROID_HOME"
  exit 0
fi

mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  echo "Downloading command-line tools..."
  curl -sSL --retry 4 --retry-delay 2 -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# sdkmanager is a Java program; JAVA_TOOL_OPTIONS (proxy + truststore in the
# cloud container) is picked up automatically.
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --install "platform-tools" "$PLATFORM" "$BUILD_TOOLS" "$BUILD_TOOLS_AGP" >/dev/null
echo "Android SDK ready at $ANDROID_HOME"
