#!/usr/bin/env bash
set -euo pipefail
# Isolated debug build: no repository/backend/signing secrets are injected.
adb shell svc wifi disable
adb shell svc data disable
test_status=0
./gradlew :app:connectedStableDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.cloudstream3.SettingsMigrationSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.adiOfflineUi=true \
  --no-configuration-cache --stacktrace || test_status=$?
mkdir -p migration-ui
adb pull /sdcard/Android/data/com.adixtream.app.debug/files/migration-ui/. migration-ui/ || true
exit "$test_status"
