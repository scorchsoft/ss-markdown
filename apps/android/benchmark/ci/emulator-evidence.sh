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
pkg=com.scorchsoft.ssmarkdown
# The runner is 2 vCPU and 8 GB, and the emulator's guest RAM is real host
# memory. Gradle here only rebuilds one APK (the workflow built the rest
# before the emulator started), so it gets a small heap and no separate
# Kotlin daemon.
gradle_args=(--no-daemon --console=plain -p apps/android
  "-Dorg.gradle.jvmargs=-Xmx${EMULATOR_GRADLE_HEAP:-2g} -Dfile.encoding=UTF-8"
  -Pkotlin.compiler.execution.strategy=in-process)

# The host's memory and swap every 30 s for the whole run, and its biggest
# processes now: the first thing to read when a stage is slow.
vmstat -t -S M 30 > "$out/host-vmstat.txt" 2>&1 &
vmstat_pid=$!
trap 'kill $vmstat_pid 2> /dev/null' EXIT
{ free -m; ps -eo pid,rss,vsz,pcpu,comm --sort=-rss | head -12; } > "$out/host-start.txt" 2>&1
cat "$out/host-start.txt"

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
#
# The whole script also runs to a deadline (EVIDENCE_BUDGET_MIN, set by the
# workflow below the step's timeout-minutes), so a stall can never run the
# job into its own limit and lose the logs and artifacts with it: each
# attempt gets the smaller of STAGE_LIMIT_MIN and the time left.
STAGE_LIMIT_MIN=${STAGE_LIMIT_MIN:-20}
deadline=$(($(date +%s) + ${EVIDENCE_BUDGET_MIN:-60} * 60))
# Minutes left before the deadline, less what the monkey and evidence need.
minutes_left() { echo $(((deadline - $(date +%s)) / 60 - 6)); }

# What the device was doing when a stage overran, saved before the restart
# wipes it: the screen and its view tree, the activity and window stacks,
# every thread and its CPU, the whole logcat, and thread dumps of the app,
# the test runner, System UI and system_server (SIGQUIT writes them to
# /data/anr). Every command has its own limit: a wedged device can hang adb.
capture_stall() {
  local dir="$out/stall-$1" t="timeout 60s" p
  mkdir -p "$dir"
  $t adb exec-out screencap -p > "$dir/screen.png" 2> /dev/null || true
  $t adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 || true
  $t adb pull /sdcard/window.xml "$dir/window.xml" > /dev/null 2>&1 || true
  $t adb shell dumpsys activity > "$dir/dumpsys-activity.txt" 2>&1 || true
  $t adb shell dumpsys window > "$dir/dumpsys-window.txt" 2>&1 || true
  $t adb shell dumpsys gfxinfo > "$dir/dumpsys-gfxinfo.txt" 2>&1 || true
  $t adb shell dumpsys meminfo > "$dir/dumpsys-meminfo.txt" 2>&1 || true
  $t adb shell ps -A -T -o PID,TID,PPID,S,PCY,WCHAN,TIME,CMD > "$dir/ps.txt" 2>&1 || true
  $t adb shell top -b -n 1 -H -m 40 > "$dir/top.txt" 2>&1 || true
  $t adb shell cat /proc/loadavg /proc/pressure/cpu /proc/pressure/memory /proc/pressure/io > "$dir/pressure.txt" 2>&1 || true
  for p in "$pkg" "$pkg.benchmark" com.android.systemui system_server; do
    $t adb shell "pid=\$(pidof $p) && kill -3 \$pid" > /dev/null 2>&1 || true
  done
  sleep 5
  $t adb logcat -d -v threadtime > "$dir/logcat.txt" 2>&1 || true
  $t adb pull /data/anr "$dir/anr" > /dev/null 2>&1 || true
  $t adb shell ls -laR /sdcard/Android/media > "$dir/device-media.txt" 2>&1 || true
  adb_throughput > "$dir/adb-throughput.txt" 2>&1
  # The host's side: memory, swap, the OOM killer, and what Gradle's JVMs
  # (the test runner among them) are waiting on.
  {
    free -m
    vmstat -S M 1 5
    ps -eo pid,rss,vsz,pcpu,etime,args --sort=-rss | head -15 | cut -c1-200
    sudo dmesg 2> /dev/null | grep -iE "out of memory|oom|killed process" | tail -20
    ps -eo pid,ppid,stat,etime,args | grep -E "[j]ava|[a]db|[g]radle" | cut -c1-300
    $t adb devices -l
    ss -tnp 2> /dev/null | grep -E "5037|5554|5555|8554" | head -40
  } > "$dir/host.txt" 2>&1
  # Taken while the stage is still alive: Gradle, its test runner (UTP) and
  # the Kotlin daemon, and the UTP logs, which say what it last heard.
  for p in $(pgrep java 2> /dev/null); do
    $t "${JAVA_HOME:+$JAVA_HOME/bin/}jstack" -l "$p" > "$dir/host-jstack-$p.txt" 2>&1 || true
  done
  find apps/android -path '*androidTest-results*' -name '*.log' -newer "$out/host-start.txt" -not -path "$out/*" \
    -exec cp --parents {} "$dir/" \; 2> /dev/null || true
  echo "stall evidence saved to $dir: $(ls "$dir" | tr '\n' ' ')"
}

run_stage() {
  local name=$1
  shift
  local slug attempt rc limit
  slug=$(echo "$name" | tr 'A-Z ' 'a-z-')
  for attempt in 1 2; do
    limit=$(minutes_left)
    [ "$limit" -gt "$STAGE_LIMIT_MIN" ] && limit=$STAGE_LIMIT_MIN
    if [ "$limit" -lt 3 ]; then
      echo "::error::$name not run (attempt $attempt): the job's time budget is spent"
      return 1
    fi
    # In its own process group, so an overrun is captured while Gradle is
    # still alive and waiting, then the whole group is stopped.
    setsid "$@" &
    local stage_pid=$! stop_at=$(($(date +%s) + limit * 60))
    while kill -0 $stage_pid 2> /dev/null && [ "$(date +%s)" -lt $stop_at ]; do sleep 5; done
    if ! kill -0 $stage_pid 2> /dev/null; then
      wait $stage_pid
      return $?
    fi
    echo "::warning::$name overran ${limit}m on attempt $attempt (emulator stall); evidence in stall-$slug-$attempt, restarting the framework"
    capture_stall "$slug-$attempt"
    kill -TERM -- -$stage_pid 2> /dev/null
    sleep 30
    kill -KILL -- -$stage_pid 2> /dev/null
    wait $stage_pid 2> /dev/null
    adb shell am force-stop "$pkg" > /dev/null 2>&1 || true
    timeout 5m bash -c "$(declare -f restart_framework); restart_framework" ||
      echo "::warning::the framework did not come back within 5 minutes"
  done
  echo "::error::$name overran its limit twice"
  return 1
}

# adb's own throughput, both ways: the test runner pulls every trace and
# profile through it, so a slow link stalls a stage that the device finished.
adb_throughput() {
  local s e
  timeout 60s adb shell dd if=/dev/urandom of=/data/local/tmp/adb-probe bs=1048576 count=32 2> /dev/null
  s=$(date +%s%N)
  timeout 300s adb pull /data/local/tmp/adb-probe "$out/adb-probe" > /dev/null 2>&1
  e=$(date +%s%N)
  echo "adb pull 32 MiB: $(((e - s) / 1000000)) ms"
  s=$(date +%s%N)
  timeout 300s adb push "$out/adb-probe" /data/local/tmp/adb-probe2 > /dev/null 2>&1
  e=$(date +%s%N)
  echo "adb push 32 MiB: $(((e - s) / 1000000)) ms"
  rm -f "$out/adb-probe"
  adb shell rm -f /data/local/tmp/adb-probe /data/local/tmp/adb-probe2
}
adb_throughput | tee "$out/adb-throughput.txt"
echo "heap: growthlimit=$(adb shell getprop dalvik.vm.heapgrowthlimit) heapsize=$(adb shell getprop dalvik.vm.heapsize) ram=$(adb shell cat /proc/meminfo | head -1)"

# EMULATOR_RUNNER=adb (the default) runs both stages with instrument.sh,
# which needs only the APKs the workflow built; =gradle uses Gradle's
# connected tasks and its test runner, as a developer would on a phone.
runner=${EMULATOR_RUNNER:-adb}
results=apps/android/benchmark/build/outputs/connected_android_test_additional_output
profiles=apps/android/app/src/main/generated/baselineProfiles

echo "::group::Baseline Profile ($runner)"
if [ "$runner" = adb ]; then
  rm -rf "$out/profile-output"
  if run_stage "Baseline Profile" bash apps/android/benchmark/ci/instrument.sh nonMinifiedRelease \
      BaselineProfile "$out/profile-output" -e class "$pkg.benchmark.BaselineProfileGenerator"; then
    # What the baselineprofile plugin's merge would write for one generator:
    # the rule's un-timestamped outputs, sorted and de-duplicated.
    bp=$(find "$out/profile-output" -name '*-baseline-prof.txt' | head -1)
    sp=$(find "$out/profile-output" -name '*-startup-prof.txt' | head -1)
    if [ -n "$bp" ]; then
      mkdir -p "$profiles"
      sort -u "$bp" > "$profiles/baseline-prof.txt"
      [ -n "$sp" ] && sort -u "$sp" > "$profiles/startup-prof.txt"
      echo "profile: $(wc -l < "$profiles/baseline-prof.txt") rules"
    else
      echo "::error::the generator passed but wrote no baseline-prof.txt"
      status=1
    fi
  else
    echo "::error::Baseline Profile generation failed"
    status=1
  fi
elif ! run_stage "Baseline Profile" gradle "${gradle_args[@]}" :app:generateBaselineProfile \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile; then
  echo "::error::Baseline Profile generation failed"
  status=1
fi
cp -r "$profiles" "$out/" 2>/dev/null || true
echo "::endgroup::"

echo "::group::Macrobenchmarks ($runner)"
if [ "$runner" = adb ]; then
  rm -rf "$results"
  # Measure the app as it ships: rebuilt with the profile just generated, as
  # Gradle's connected task would (it depends on :app:assembleBenchmarkRelease).
  if [ -s "$profiles/baseline-prof.txt" ]; then
    timeout 15m gradle "${gradle_args[@]}" :app:assembleBenchmarkRelease ||
      echo "::warning::could not rebuild benchmarkRelease with the new profile; measuring the build without it"
  fi
  if ! run_stage "Macrobenchmarks" bash apps/android/benchmark/ci/instrument.sh benchmarkRelease \
      Macrobenchmark "$results/emulator" -e notClass "$pkg.benchmark.BaselineProfileGenerator"; then
    echo "::error::Macrobenchmarks failed"
    status=1
  fi
elif ! run_stage "Macrobenchmarks" gradle "${gradle_args[@]}" :benchmark:connectedBenchmarkReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark; then
  echo "::error::Macrobenchmarks failed"
  status=1
fi
adb uninstall "$pkg" > /dev/null 2>&1 || true
echo "::endgroup::"

echo "::group::Monkey (seeded) with StrictMode"
timeout 3m adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
# Seeded, so a failure replays. No system keys (Home, power, calls) and few
# app switches: the monkey's job is the app's own screens. About 3 minutes;
# the limit only keeps a wedged device inside the job's budget.
timeout 6m adb shell monkey -p "$pkg" -s 20261101 --throttle 60 \
    --pct-syskeys 0 --pct-appswitch 2 --pct-anyevent 0 \
    --ignore-security-exceptions -v 2000 > "$out/monkey.txt" 2>&1
monkey_exit=$?
timeout 60s adb logcat -d -v threadtime > "$out/logcat.txt"
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
