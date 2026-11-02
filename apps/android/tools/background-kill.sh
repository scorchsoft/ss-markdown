#!/usr/bin/env bash
# The rotation matrix's `am kill` column (docs/robustness.md Part 3):
# leave the app (Home), have its process killed in the background, come
# back. BackgroundKillTest#leaveAndDie sets the text, selection and scroll,
# goes to the background and dies by SIGKILL; BackgroundKillTest#comeBack
# relaunches and checks every character, the selection, the top line, the
# view mode, and that Undo and Redo work as before the kill (ADR 0016).
#
# Needs a booted device or emulator with the debug app and its test APK
# installed (CI installs them first, for the kill loop).
set -euo pipefail

app=com.scorchsoft.ssmarkdown
runner="$app.test/$app.LeakCheckRunner"
test_class="$app.BackgroundKillTest"

run() {
  adb shell am instrument -w -r -e class "$test_class#$1" -e backgroundKill "$2" "$runner" 2>&1 || true
}

adb logcat -c
out="$(run leaveAndDie leave)"
# The step must die (no "OK"), and must have reached its death.
if grep -qE 'FAILURES!!!|AssertionError|OK \(1 test\)|INSTRUMENTATION_FAILED|OK \(0 tests\)' <<<"$out" ||
  ! adb logcat -d -s BackgroundKill:I | grep -q 'dying'; then
  echo "$out"
  echo "::error::the background-kill step did not reach its kill"
  exit 1
fi

out="$(run comeBack return)"
if ! grep -q 'OK (1 test)' <<<"$out"; then
  echo "$out"
  echo "::error::coming back after a background kill lost something"
  exit 1
fi
adb logcat -d -s BackgroundKill:I | grep -E 'dying|came back'
echo "Background kill: killed in the background, came back with every character, the selection, the top line, the view mode, Undo and Redo."
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "### Background kill (am kill): text, selection, top line, view mode, Undo and Redo came back" >> "$GITHUB_STEP_SUMMARY"
fi
