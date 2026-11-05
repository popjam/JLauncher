#!/bin/bash
# Sets up an Android emulator on a Mac for T3 Code's Device panel: the Android command-line tools,
# the emulator, an Android 14 image (arm64 on Apple Silicon) and a Pixel 7 called JLauncher_Pixel.
# Needs Homebrew. Safe to run again.
set -euo pipefail

SDK="$HOME/Library/Android/sdk"
ARCH=$([ "$(uname -m)" = arm64 ] && echo arm64-v8a || echo x86_64)
IMAGE="system-images;android-34;google_apis;$ARCH"

command -v brew >/dev/null || { echo "Install Homebrew first: https://brew.sh"; exit 1; }
# Java 17+: Android Studio's own if it's installed, else any installed, else Homebrew's (no password needed)
STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -x "$STUDIO_JDK/bin/java" ]; then
    export JAVA_HOME="$STUDIO_JDK"
elif /usr/libexec/java_home -v 17+ >/dev/null 2>&1; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17+)
else
    brew install openjdk@17
    export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
fi
# T3 Code's Device panel installs its helpers with pnpm
command -v pnpm >/dev/null || brew install pnpm
# ...and streams the emulator's screen with ffmpeg (Homebrew's has libx264)
command -v ffmpeg >/dev/null || brew install ffmpeg

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/ct.zip" https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
    unzip -q "$tmp/ct.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
fi
BIN="$SDK/cmdline-tools/latest/bin"

yes | "$BIN/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
"$BIN/sdkmanager" --sdk_root="$SDK" platform-tools emulator "platforms;android-34" "$IMAGE"
"$BIN/avdmanager" list avd -c | grep -qx JLauncher_Pixel ||
    echo no | "$BIN/avdmanager" create avd -n JLauncher_Pixel -k "$IMAGE" -d pixel_7

# So T3 Code (and new terminals) find the SDK
for rc in "$HOME/.zprofile" "$HOME/.zshrc"; do
    grep -q 'ANDROID_HOME=' "$rc" 2>/dev/null ||
        printf '\nexport ANDROID_HOME="%s"\nexport ANDROID_SDK_ROOT="$ANDROID_HOME"\nexport PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"\n' "$SDK" >> "$rc"
done
launchctl setenv ANDROID_HOME "$SDK"
launchctl setenv ANDROID_SDK_ROOT "$SDK"

echo "Done: JLauncher_Pixel ($ARCH). Restart T3 Code so its Device panel finds the SDK."
