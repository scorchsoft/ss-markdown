#!/usr/bin/env bash
# Runs one benchmark stage on the connected device WITHOUT Gradle's test
# runner (UTP), and copies its results to where Gradle would have put them.
# From the repository root:
#
#   instrument.sh <variant> <enabledRules> <host-output-dir> [-e class ...]
#
# Why: on CI's 2-vCPU runners the device finishes a stage and Gradle never
# hears about it (apps/android/CLAUDE.md, "Emulator evidence"). Here the
# instrumentation runs detached on the device, writing its raw (-r) output
# to a file, and the host only makes short adb calls: install, poll the
# file, pull the results. No long-lived adb stream to lose.
#
# Exit status: 0 when the run finished with no failed test, 1 otherwise.
set -u
variant=$1 rules=$2 dest=$3
shift 3
test_pkg=com.scorchsoft.ssmarkdown.benchmark
runner=androidx.test.runner.AndroidJUnitRunner
media=/sdcard/Android/media/$test_pkg/additional_test_output
raw=/data/local/tmp/instrument-$rules.txt
apks=apps/android
t="timeout 180s"

app_apk=$(ls $apks/app/build/outputs/apk/"$variant"/*.apk 2> /dev/null | head -1)
test_apk=$(ls $apks/benchmark/build/outputs/apk/"$variant"/*.apk 2> /dev/null | head -1)
if [ -z "$app_apk" ] || [ -z "$test_apk" ]; then
  echo "::error::no $variant APKs (app: '$app_apk', benchmark: '$test_apk'); build them first"
  exit 1
fi
echo "app: $app_apk; tests: $test_apk"
$t adb install -r -t "$app_apk" || exit 1
$t adb install -r -t "$test_apk" || exit 1
# A fresh output folder, made as the shell user as Gradle's runner makes it:
# made by root (the script runs adb as root), the test cannot write in it
# and fails with "Unable to select a directory for writing files".
adb shell rm -rf "$media" "$raw"
adb shell "su shell mkdir -p $media" || adb shell "mkdir -p $media"
adb shell ls -ld "$media"
mkdir -p "$(dirname "$dest")"

# Detached on the device: the run survives the host's adb connection.
adb shell "nohup am instrument -w -r \
  -e androidx.benchmark.enabledRules $rules \
  -e androidx.benchmark.suppressErrors EMULATOR \
  -e androidx.benchmark.targetPackageName com.scorchsoft.ssmarkdown \
  -e additionalTestOutputDir $media \
  $* $test_pkg/$runner > $raw 2>&1 < /dev/null &"

# The caller's time limit stops this loop; the run ends with INSTRUMENTATION_CODE.
seen=0
while :; do
  sleep 15
  $t adb shell cat "$raw" > "$dest.raw.tmp" 2> /dev/null || continue
  mv "$dest.raw.tmp" "$dest.raw.txt"
  done_now=$(grep -c "^INSTRUMENTATION_STATUS_CODE: " "$dest.raw.txt")
  if [ "$done_now" != "$seen" ]; then
    seen=$done_now
    grep -E "^INSTRUMENTATION_STATUS: (test|class)=" "$dest.raw.txt" | tail -2 | tr '\n' ' '
    echo "($seen status reports)"
  fi
  grep -qE "^INSTRUMENTATION_CODE:|^INSTRUMENTATION_FAILED|Process crashed" "$dest.raw.txt" && break
done

mkdir -p "$dest"
$t adb pull "$media/." "$dest/" > /dev/null || echo "::warning::could not pull $media"

# Status codes: 0 ok, 1 start, -1 error, -2 failure, -3 ignored, -4 assumption failure (skipped).
python3 - "$dest.raw.txt" << 'EOF'
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
results = []
for block in text.split("INSTRUMENTATION_STATUS_CODE: ")[1:]:
    code = int(block.split()[0])
    if code == 1:
        continue
    results.append(code)
names = re.findall(r"INSTRUMENTATION_STATUS: test=(\S+)", text)
passed = results.count(0)
skipped = results.count(-3) + results.count(-4)
failed = results.count(-1) + results.count(-2)
print(f"instrumentation: {passed} passed, {skipped} skipped, {failed} failed")
for m in re.finditer(r"INSTRUMENTATION_STATUS: stack=(.*?)(?=INSTRUMENTATION_STATUS)", text, re.S):
    print(m.group(1)[:1500])
finished = re.search(r"^INSTRUMENTATION_CODE: -1", text, re.M) is not None
if failed or not finished or "Process crashed" in text:
    print("::error::the instrumentation run failed or did not finish")
    sys.exit(1)
EOF
