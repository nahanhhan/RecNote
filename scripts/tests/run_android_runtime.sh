#!/usr/bin/env bash
# Run one isolated emulator suite and collect only synthetic output that survives test-app cleanup.
set -uo pipefail
runtime_result=0
./gradlew :app:connectedDebugAndroidTest -PisolatedTest -PtestAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.class=io.github.nahanhhan.lecturerecording.PdfExportTest,io.github.nahanhhan.lecturerecording.NoteRecoveryTest,io.github.nahanhhan.lecturerecording.NativePhotoAndModelTest,io.github.nahanhhan.lecturerecording.WebDavSettingsTest,io.github.nahanhhan.lecturerecording.DatabaseMigrationTest --console=plain || runtime_result=$?
mkdir -p runtime-artifacts
adb pull /sdcard/Download/RecNote-CI-Verification/ runtime-artifacts/verification || runtime_result=1
exit "$runtime_result"
