#!/usr/bin/env bash
# Downloads a portable, user-local Android build toolchain (no sudo, no global install).
# Usage: android/scripts/setup-toolchain.sh [TOOLCHAIN_DIR]
# Default TOOLCHAIN_DIR: <repo>/.toolchain  (ignored by git). Peak footprint ~3 GB.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TC="${1:-$ROOT/.toolchain}"
JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
GRADLE_VERSION="8.14.3"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
SDK_PACKAGES=("platforms;android-36" "build-tools;35.0.0" "platform-tools")

avail_gib=$(df -BG --output=avail "$(dirname "$TC")" | tail -1 | tr -dc '0-9')
if [ "$avail_gib" -lt 30 ]; then
  echo "Refusing: only ${avail_gib} GiB free; need >= 30 GiB (20 GiB reserve + overhead)." >&2
  exit 1
fi

mkdir -p "$TC"
fetch() { # url dest
  if [ ! -s "$2" ]; then wget -q --tries=3 -O "$2.part" "$1" && mv "$2.part" "$2"; fi
}

if [ ! -x "$TC/jdk/bin/java" ]; then
  mkdir -p "$TC/dl/jdk"
  fetch "$JDK_URL" "$TC/dl/jdk/jdk17.tar.gz"
  rm -rf "$TC/jdk.tmp" && mkdir -p "$TC/jdk.tmp"
  tar -xzf "$TC/dl/jdk/jdk17.tar.gz" -C "$TC/jdk.tmp" --strip-components=1
  mv "$TC/jdk.tmp" "$TC/jdk"
fi

if [ ! -x "$TC/gradle/bin/gradle" ]; then
  mkdir -p "$TC/dl/gradle"
  fetch "$GRADLE_URL" "$TC/dl/gradle/gradle.zip"
  rm -rf "$TC/gradle.tmp" && mkdir -p "$TC/gradle.tmp"
  unzip -q "$TC/dl/gradle/gradle.zip" -d "$TC/gradle.tmp"
  mv "$TC/gradle.tmp/gradle-${GRADLE_VERSION}" "$TC/gradle" && rm -rf "$TC/gradle.tmp"
fi

export JAVA_HOME="$TC/jdk"
export ANDROID_HOME="$TC/android-sdk"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$TC/dl/cmdline" "$ANDROID_HOME/cmdline-tools"
  fetch "$CMDLINE_URL" "$TC/dl/cmdline/cmdline-tools.zip"
  rm -rf "$TC/cmdline.tmp" && mkdir -p "$TC/cmdline.tmp"
  unzip -q "$TC/dl/cmdline/cmdline-tools.zip" -d "$TC/cmdline.tmp"
  mv "$TC/cmdline.tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" && rm -rf "$TC/cmdline.tmp"
fi

# `yes` exits with SIGPIPE once sdkmanager stops reading; that is expected.
(set +o pipefail; yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null)
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" "${SDK_PACKAGES[@]}"

cat > "$TC/env.sh" <<ENV
export JAVA_HOME="$TC/jdk"
export ANDROID_HOME="$TC/android-sdk"
export GRADLE_USER_HOME="$TC/gradle-home"
export PATH="$TC/jdk/bin:$TC/gradle/bin:$TC/android-sdk/platform-tools:\$PATH"
ENV
echo "Toolchain ready. Run: source $TC/env.sh"
du -sh "$TC"
