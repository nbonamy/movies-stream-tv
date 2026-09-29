#!/usr/bin/env bash
set -euo pipefail
proof_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$proof_dir/../.." && pwd)
serial=${1:?Usage: run.sh EMULATOR_SERIAL movie|series}
kind=${2:?Usage: run.sh EMULATOR_SERIAL movie|series}
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Use an explicitly selected emulator.' >&2; exit 2; }
[[ "$kind" == movie || "$kind" == series ]] || { echo 'Choose movie or series.' >&2; exit 2; }
adb_bin=${ADB:-${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}}
adb_bin=${adb_bin:-adb}
package=fr.bonamy.movies.cinejoyproof
"$repo_dir/gradlew" -p "$proof_dir" :app:assembleDebug
"$adb_bin" -s "$serial" install -r "$proof_dir/app/build/outputs/apk/debug/app-debug.apk"
"$adb_bin" -s "$serial" shell am force-stop "$package"
"$adb_bin" -s "$serial" shell am start -W -n "$package/fr.bonamy.movies.core.ProofActivity" --es kind "$kind"
mkdir -p "$proof_dir/build/evidence"
result="$proof_dir/build/evidence/$kind-result.txt"
# Activity starts a new result file on each launch. Media URLs and keys are never written.
deadline=$((SECONDS + 100))
while ((SECONDS < deadline)); do
  "$adb_bin" -s "$serial" exec-out run-as "$package" cat "files/$kind-result.txt" > "$result" 2>/dev/null || true
  if grep -q '^FAIL ' "$result"; then cat "$result"; exit 1; fi
  if grep -q '^PASS ' "$result" && grep -q '^SCREENSHOT ' "$result"; then
    # Screenshot is copied asynchronously from the actual video SurfaceView.
    if "$adb_bin" -s "$serial" exec-out run-as "$package" cat "files/$kind-proof.png" > "$proof_dir/build/evidence/$kind.png" 2>/dev/null; then
      cat "$result"
      echo "Evidence: $proof_dir/build/evidence/$kind.png"
      exit 0
    fi
  fi
  sleep 1
done
cat "$result"
echo 'Timed out waiting for native playback proof.' >&2
exit 1
