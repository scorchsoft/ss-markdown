#!/usr/bin/env bash
# The kill loop (docs/robustness.md Part 3): type into the real app, kill
# its process with SIGKILL, relaunch, check the recovered text. Each
# iteration is one instrumentation run of KillLoopTest#step, which checks
# what the previous iteration left, types, records what it expects, and
# kills itself; KillLoopTest#verifyLast checks the final one.
#
# Needs a booted device or emulator with the debug app and its test APK
# installed (CI installs them first). Usage: kill-loop.sh [iterations]
set -euo pipefail

iterations="${1:-50}"
app=com.scorchsoft.ssmarkdown
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
test_class="$app.KillLoopTest"

adb shell pm clear "$app" > /dev/null
adb logcat -c

run() {
  adb shell am instrument -w -r -e class "$test_class#$1" "${@:2}" "$runner" 2>&1 || true
}

for i in $(seq 1 "$iterations"); do
  out="$(run step -e killLoop 1 -e killLoopIteration "$i")"
  # The step must die (no "OK"), and must not fail its check of the previous iteration first.
  if grep -qE 'FAILURES!!!|AssertionError|OK \(1 test\)' <<<"$out"; then
    echo "$out"
    echo "::error::kill loop iteration $i failed"
    exit 1
  fi
done

out="$(run verifyLast -e killLoopVerify 1)"
if ! grep -q 'OK (1 test)' <<<"$out"; then
  echo "$out"
  echo "::error::the last kill loop iteration did not recover"
  exit 1
fi

adb logcat -d -s KillLoop:I | grep -E 'iteration' | tail -n "$((iterations + 1))"
lost=$(adb logcat -d -s KillLoop:I | grep -oE 'lost [0-9]+ ms' | awk '{ if ($2 > m) m = $2 } END { print m + 0 }')
echo "Kill loop: $iterations kills, all recovered; max loss ${lost} ms of typing."
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "### Kill loop: $iterations kills on the emulator, all recovered, max loss ${lost} ms" >> "$GITHUB_STEP_SUMMARY"
fi
