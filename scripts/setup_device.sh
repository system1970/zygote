#!/usr/bin/env bash
# ZYGOTE device setup — restore everything after ANY app wipe, fast.
# Models live in shared storage (/sdcard/zygote-models) so they survive
# uninstalls; this script only stages them through /data/local/tmp (shell can
# read /sdcard; the app UID cannot) and copies into app storage. No network
# transfer, seconds not minutes.
#
# Usage:  bash setup_device.sh [apk_path]
set -e
cd "$(dirname "$0")/.."
ADB="android-sdk/platform-tools/adb.exe"
APK="${1:-orkestrate-app/app/build/outputs/apk/debug/app-debug.apk}"
PKG="com.example.llama.aichat"
MODELS_SRC="/sdcard/zygote-models"
STAGE="/data/local/tmp/zygote-stage"

echo "==> adb devices"
"$ADB" devices

echo "==> installing APK: $APK"
"$ADB" install -r -t "$APK"

echo "==> staging models via /data/local/tmp (shell can read /sdcard)"
"$ADB" shell "rm -rf $STAGE && mkdir -p $STAGE && cp $MODELS_SRC/*.gguf $STAGE/ && chmod 644 $STAGE/*.gguf && ls -la $STAGE/"

echo "==> copying models into app storage"
"$ADB" shell "run-as $PKG sh -c 'mkdir -p files/models && cp $STAGE/*.gguf files/models/ && ls -la files/models/'"

echo "==> enabling phone-control accessibility service"
"$ADB" shell "settings put secure enabled_accessibility_services $PKG/com.example.llama.PhoneControlService"
"$ADB" shell "settings put secure accessibility_enabled 1"

echo "==> cleanup stage"
"$ADB" shell "rm -rf $STAGE"

echo "==> DONE. Device ready."
