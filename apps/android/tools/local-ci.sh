#!/usr/bin/env bash
# Runs CI's Checks and Preview bundle jobs on this machine, in parallel, so a
# change can be proved before it is pushed. The emulator jobs need KVM and
# stay in CI. Usage, from the repo root:
#
#   apps/android/tools/local-ci.sh            # both jobs
#   apps/android/tools/local-ci.sh --kotlin   # Checks only
#   apps/android/tools/local-ci.sh --web      # Preview bundle only
#
# Extra Gradle arguments (an init script, --max-workers) go in GRADLE_ARGS.
# Logs land in build/local-ci/; the exit code is non-zero if either job fails.
set -uo pipefail

root="$(git rev-parse --show-toplevel)"
logs="$root/apps/android/build/local-ci"
mkdir -p "$logs"
run_kotlin=true
run_web=true
case "${1:-}" in
  --kotlin) run_web=false ;;
  --web) run_kotlin=false ;;
  "") ;;
  *) echo "usage: $0 [--kotlin|--web]" >&2; exit 2 ;;
esac

pids=()
names=()
if $run_kotlin; then
  # shellcheck disable=SC2086 # GRADLE_ARGS is a list of words on purpose.
  (cd "$root" && gradle -p apps/android ${GRADLE_ARGS:-} check :app:assembleDebug) \
    > "$logs/checks.log" 2>&1 &
  pids+=($!); names+=("Checks (gradle check :app:assembleDebug)")
fi
if $run_web; then
  (
    set -e
    cd "$root/apps/android/feature/preview/web"
    npm ci --no-audit --no-fund
    npm run build
    if ! git -C "$root" diff --quiet -- apps/android/feature/preview/src/main/assets/preview; then
      echo "assets/preview is stale: commit the rebuilt bundle with its source"
      exit 1
    fi
    npm test
  ) > "$logs/preview.log" 2>&1 &
  pids+=($!); names+=("Preview bundle (build, freshness, npm test)")
fi

status=0
for i in "${!pids[@]}"; do
  if wait "${pids[$i]}"; then
    echo "PASS  ${names[$i]}"
  else
    echo "FAIL  ${names[$i]}  (see ${logs}/$( [[ ${names[$i]} == Checks* ]] && echo checks || echo preview ).log)"
    status=1
  fi
done
exit $status
