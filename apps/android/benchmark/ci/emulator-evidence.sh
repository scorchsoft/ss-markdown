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
# A system process (System UI, launcher) that stops responding on the
# shared CI emulator shows an ANR dialog over the app, and every benchmark
# then fails with "System UI isn't responding" on screen. Those dialogs
# concern the image, not this app; ours are still caught by the monkey's
# logcat check below.
adb shell settings put global hide_error_dialogs 1 || true
# A Pixel 6a's per-app heap (dalvik.vm.heapgrowthlimit=256m on 6 GB Pixels),
# the phone the budgets name. The API 34 AVD ships 192m, under which the
# admission rules (robustness Part 2a) rightly decline a 20 MB file. The
# property is read when the runtime starts, so restart it.
adb root > /dev/null && adb wait-for-device
adb shell setprop dalvik.vm.heapgrowthlimit 256m

# Restarts the Android framework (not the emulator) and waits until it has
# booted: applies the heap property above, and clears a wedged system_server.
restart_framework() {
  adb shell stop && adb shell start
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
  sleep 10
}
restart_framework
# One emulator stage under a time limit, retried once. The API 34 image
# sometimes wedges inside system_server on `am start -W` (a coldStart
# iteration sat for about an hour with our process never forked, until the
# job's 90-minute limit killed it), so a stage that overruns is stopped, the
# framework restarted, and the stage run again. A healthy stage takes about
# 7 minutes. A retry is reported as a warning, never hidden.
STAGE_LIMIT=${STAGE_LIMIT:-20m}
run_stage() {
  local name=$1; shift
  local attempt rc
  for attempt in 1 2; do
    timeout --kill-after=60s "$STAGE_LIMIT" "$@"
    rc=$?
    if [ $rc -ne 124 ] && [ $rc -ne 137 ]; then return $rc; fi
    echo "::warning::$name overran $STAGE_LIMIT on attempt $attempt (emulator stall); restarting the framework"
    adb shell am force-stop "$pkg" > /dev/null 2>&1 || true
    restart_framework
  done
  echo "::error::$name overran $STAGE_LIMIT twice"
  return 1
}

echo "heap: growthlimit=$(adb shell getprop dalvik.vm.heapgrowthlimit) heapsize=$(adb shell getprop dalvik.vm.heapsize) ram=$(adb shell cat /proc/meminfo | head -1)"

echo "::group::Baseline Profile"
if ! run_stage "Baseline Profile" gradle "${gradle_args[@]}" :app:generateBaselineProfile \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile; then
  echo "::error::Baseline Profile generation failed"
  status=1
fi
cp -r apps/android/app/src/main/generated/baselineProfiles "$out/" 2>/dev/null || true
echo "::endgroup::"

echo "::group::Macrobenchmarks"
if ! run_stage "Macrobenchmarks" gradle "${gradle_args[@]}" :benchmark:connectedBenchmarkReleaseAndroidTest \
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
