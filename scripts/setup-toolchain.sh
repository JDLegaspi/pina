#!/bin/sh
set -eu

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-mac-13114758_latest.zip"

if ! command -v rustup >/dev/null 2>&1 && [ ! -x "$HOME/.cargo/bin/rustup" ]; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --no-modify-path
fi
"$HOME/.cargo/bin/rustup" toolchain install stable --profile minimal
"$HOME/.cargo/bin/rustup" target add aarch64-linux-android

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp=$(mktemp -d)
  curl -sSfL "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" -o "$tmp/tools.zip"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  'platforms;android-37.0' 'build-tools;36.0.0' 'ndk;30.0.16248370' 'platform-tools'
