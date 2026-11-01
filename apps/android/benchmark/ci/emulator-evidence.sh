#!/usr/bin/env bash
# Runs on CI's API 34 emulator (the `perf` job in .github/workflows/android.yml),
# from the repository root. Three pieces of evidence, each into build/emulator-evidence/:
#
#   1. the app's Baseline Profile, generated (app/src/main/generated/baselineProfiles/);
#   2. the Macrobenchmarks, measured against a benchmark build carrying that profile;
#   3. a seeded monkey run of the debuggable build, with its logcat for StrictMode.
#
# Each step's failure is recorded and the next still runs, so one bad step
# never hides the others; the script exits non-zero if any failed.
set -u
out=apps/android/build/emulator-evidence
mkdir -p "$out"
status=0
gradle_args=(--no-daemon --console=plain -p apps/android)
pkg=com.scorchsoft.ssmarkdown

adb shell settings put global package_verifier_enable 0 || true

echo "::group::Baseline Profile"
if ! gradle "${gradle_args[@]}" :app:generateBaselineProfile \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile; then
  echo "::error::Baseline Profile generation failed"
  status=1
fi
cp -r apps/android/app/src/main/generated/baselineProfiles "$out/" 2>/dev/null || true
echo "::endgroup::"

echo "::group::Macrobenchmarks"
if ! gradle "${gradle_args[@]}" :benchmark:connectedBenchmarkReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark; then
  echo "::error::Macrobenchmarks failed"
  status=1
fi
adb uninstall "$pkg" > /dev/null 2>&1 || true
echo "::endgroup::"

echo "::group::Monkey (seeded) with StrictMode"
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
# Seeded, so a failure replays. No system keys (Home, power, calls) and few
# app switches: the monkey's job is the app's own screens.
adb shell monkey -p "$pkg" -s 20261101 --throttle 60 \
    --pct-syskeys 0 --pct-appswitch 2 --pct-anyevent 0 \
    --ignore-security-exceptions -v 2000 > "$out/monkey.txt" 2>&1
monkey_exit=$?
adb logcat -d -v threadtime > "$out/logcat.txt"
tail -n 20 "$out/monkey.txt"
if [ $monkey_exit -ne 0 ] || grep -qE "// CRASH|// NOT RESPONDING|Monkey aborted" "$out/monkey.txt"; then
  echo "::error::monkey found a crash or ANR (exit $monkey_exit); see monkey.txt"
  status=1
fi
# Only this app's crashes and ANRs: system apps on the image may log their own.
if grep -A2 "FATAL EXCEPTION" "$out/logcat.txt" | grep -q "Process: $pkg" || grep -q "ANR in $pkg" "$out/logcat.txt"; then
  grep -E -A25 "FATAL EXCEPTION|ANR in $pkg" "$out/logcat.txt" | head -80
  echo "::error::the app crashed or stopped responding during the monkey run"
  status=1
fi
echo "::endgroup::"

exit $status
