#!/usr/bin/env bash
set -euo pipefail
# Isolated debug build: no repository/backend/signing secrets are injected.
# Google APIs test AVD only. Block all outbound IP, including emulator Ethernet;
# disabling Wi-Fi/mobile data alone does not guarantee an offline emulator.
adb root
adb wait-for-device
adb shell iptables -I OUTPUT -j REJECT
adb shell ip6tables -I OUTPUT -j REJECT
adb shell svc wifi disable
adb shell svc data disable
test_status=0
./gradlew :app:connectedStableDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.cloudstream3.SettingsMigrationSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.adiOfflineUi=true \
  --no-configuration-cache --stacktrace || test_status=$?
mkdir -p migration-ui
adb pull /sdcard/Download/AdiXtream-migration-ui/. migration-ui/ || true
exit "$test_status"
