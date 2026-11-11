#!/usr/bin/env bash
# Finds the most recent EARLIER build whose debug APK is signed with the same key as this run's APK
# (the case a phone sees when it updates), downloads it to ./baseline. Prints nothing fatal if none.
# Usage: find-baseline.sh <repo> <this-run-id> <new.apk>
set -uo pipefail
repo=$1; this=$2; new=$3
here=$(dirname "$0")
want=$(python3 "$here/apkcert.py" --digest "$new")
mkdir -p baseline
for run in $(gh api "repos/$repo/actions/workflows/ci.yml/runs?per_page=30" -q ".workflow_runs[] | select(.id < $this) | .id"); do
  num=$(gh api "repos/$repo/actions/runs/$run" -q .run_number)
  name="cinemind-debug-$num"
  gh api "repos/$repo/actions/runs/$run/artifacts" -q ".artifacts[] | select(.name == \"$name\" and .expired == false) | .name" | grep -q . || continue
  tries=$(( ${tries:-0} + 1 )); [ "$tries" -gt 8 ] && break
  tmp=$(mktemp -d)
  gh run download "$run" -R "$repo" -n "$name" -D "$tmp" >/dev/null 2>&1 || continue
  apk=$(ls "$tmp"/*.apk 2>/dev/null | head -1)
  [ -n "$apk" ] || continue
  if [ "$(python3 "$here/apkcert.py" --digest "$apk")" = "$want" ]; then
    cp "$apk" baseline/
    echo "baseline: build #$num (run $run), same signing key"
    exit 0
  fi
  last_other="$apk"
done
# None with the same key: keep the newest other build so the test can report the key mismatch.
if [ -n "${last_other:-}" ]; then cp "$last_other" baseline/; echo "no earlier build with the same key; using an older differently-signed build"; fi
exit 0
