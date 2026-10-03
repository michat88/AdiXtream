#!/usr/bin/env bash
set -euo pipefail
# Isolated debug build: no repository/backend/signing secrets are injected.
# Google APIs test AVD only. Block all outbound IP, including emulator Ethernet;
# disabling Wi-Fi/mobile data alone does not guarantee an offline emulator.
# Restarting adbd can briefly close the transport even after the AVD boots.
root_ready=false
for attempt in 1 2 3; do
  if ! timeout 30s adb wait-for-device; then continue; fi
  timeout 15s adb root || true
  if ! timeout 30s adb wait-for-device; then continue; fi
  if [ "$(adb shell id -u | tr -d '\r')" = "0" ]; then
    root_ready=true
    break
  fi
  sleep 2
done
$root_ready || { echo "Rooted test AVD unavailable; offline smoke not run"; exit 1; }
adb shell iptables -w 10 -I OUTPUT -j REJECT
adb shell ip6tables -w 10 -I OUTPUT -j REJECT
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
