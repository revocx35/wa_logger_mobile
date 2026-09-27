#!/usr/bin/env bash
# Headless Android emulator for scripts/e2e.py. Needs /dev/kvm (see CLAUDE.md).
#   scripts/emulator.sh setup   -> install emulator + API 37 image, create the AVD (once; AVD=name to choose it)
#   scripts/emulator.sh start   -> boot headless and wait until Android is ready
#   scripts/emulator.sh stop    -> shut it down
# The AVD defaults to "npm_test", shared with the NPM Mobile project on this host (disk is too tight for two).
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
IMAGE="system-images;android-37.0;google_apis;x86_64"
AVD="${AVD:-npm_test}"
ADB="$SDK/platform-tools/adb"
LOG="${TMPDIR:-/tmp}/emulator-$AVD.log"

running() { ps -eo comm= | grep -q '^qemu-system-x86'; }

case "${1:-start}" in
  setup)
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" emulator "$IMAGE" >/dev/null
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_6 --force >/dev/null
    cfg="$HOME/.android/avd/$AVD.avd/config.ini"
    sed -i '/^hw.ramSize=/d; /^hw.cpu.ncore=/d; /^disk.dataPartition.size=/d' "$cfg"
    printf 'hw.ramSize=3072\nhw.cpu.ncore=2\ndisk.dataPartition.size=3G\n' >> "$cfg"
    echo "AVD $AVD ready"
    ;;
  start)
    [ -r /dev/kvm ] || { echo "/dev/kvm missing: pass it into the container first" >&2; exit 1; }
    if ! running; then
      nohup "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
        -gpu swiftshader_indirect >"$LOG" 2>&1 &
    fi
    "$ADB" wait-for-device
    for _ in $(seq 1 120); do
      [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { echo "emulator ready ($("$ADB" shell getprop ro.build.version.release | tr -d '\r'))"; exit 0; }
      sleep 3
    done
    echo "emulator did not finish booting; see $LOG" >&2
    exit 1
    ;;
  stop)
    "$ADB" emu kill >/dev/null 2>&1 || true
    echo stopped
    ;;
  *)
    echo "usage: $0 [setup|start|stop]" >&2
    exit 2
    ;;
esac
