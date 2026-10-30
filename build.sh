#!/usr/bin/env bash
# Build the APK on a Debian/Ubuntu machine (or Proxmox CT) without Android Studio.
set -e
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
$SUDO apt-get update
$SUDO apt-get install -y openjdk-17-jdk-headless unzip wget

export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ ! -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
  wget -q -O /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" "platform-tools"

if [ ! -d "$HOME/gradle-8.7" ]; then
  wget -q -O /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
  unzip -q -o /tmp/gradle.zip -d "$HOME"
fi

cd "$(dirname "$0")"
echo "sdk.dir=$ANDROID_HOME" > local.properties
"$HOME/gradle-8.7/bin/gradle" assembleDebug --no-daemon
echo
echo "APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
