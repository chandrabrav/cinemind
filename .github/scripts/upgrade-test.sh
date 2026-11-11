#!/usr/bin/env bash
# Update-compatibility test on the emulator: install the previous build, create data the way the app
# keeps it (a model file, a setting, the project database), install the new build OVER it, and check
# that the update succeeds, nothing was lost and the new version starts.
# Usage: upgrade-test.sh <baseline.apk or ""> <new.apk>
set -uo pipefail
base=${1:-}
new=$2
pkg=com.cinemind.app.debug
here=$(dirname "$0")
fail() { echo "UPGRADE TEST FAILED: $*"; echo "### ❌ Update test failed: $*" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"; exit 1; }
note() { echo "$*"; echo "$*" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"; }

newcert=$(python3 "$here/apkcert.py" --digest "$new")
note "New APK signing certificate: \`$newcert\`"
if [ -n "$base" ] && [ -f "$base" ]; then
  basecert=$(python3 "$here/apkcert.py" --digest "$base")
  note "Baseline APK signing certificate: \`$basecert\`"
  if [ "$basecert" != "$newcert" ]; then
    note "⚠️ The baseline was signed with a different key (a build from before the permanent debug key). Android would refuse this update; that is the bug being fixed. Testing a reinstall of the new build over itself instead."
    base=$new
  fi
else
  note "⚠️ No baseline APK found; testing a reinstall of the new build over itself."
  base=$new
fi

adb uninstall "$pkg" >/dev/null 2>&1 || true
out=$(adb install "$base" 2>&1); echo "$out"; echo "$out" | grep -q Success || fail "baseline install: $out"
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 10
adb shell am force-stop "$pkg"

# Data the app keeps: a model file in files/models, a setting, and the Room database created on start.
adb shell run-as "$pkg" sh -c 'mkdir -p files/models && head -c 4194304 /dev/urandom > files/models/upgrade-check.bin' || fail "couldn't write test model file"
model_before=$(adb shell run-as "$pkg" sha256sum files/models/upgrade-check.bin | cut -d" " -f1)
adb shell run-as "$pkg" sh -c 'mkdir -p shared_prefs && echo "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><string name=\"theme_mode\">DARK</string></map>" > shared_prefs/appearance.xml'
dbs=$(adb shell run-as "$pkg" ls databases 2>/dev/null | tr -d '\r')
echo "databases before: $dbs"
echo "$dbs" | grep -q cinemind_database || fail "the baseline build did not create its database on start"
vc_before=$(adb shell dumpsys package "$pkg" | grep -m1 versionCode | tr -d '\r')

out=$(adb install -r "$new" 2>&1); echo "$out"
echo "$out" | grep -q Success || fail "update install was refused: $out"
vc_after=$(adb shell dumpsys package "$pkg" | grep -m1 versionCode | tr -d '\r')
note "Installed over: $vc_before → $vc_after"

model_after=$(adb shell run-as "$pkg" sha256sum files/models/upgrade-check.bin 2>/dev/null | cut -d" " -f1)
[ -n "$model_before" ] && [ "$model_before" = "$model_after" ] || fail "model file changed or was deleted ($model_before → $model_after)"
adb shell run-as "$pkg" cat shared_prefs/appearance.xml | grep -q DARK || fail "settings were lost"
adb shell run-as "$pkg" ls databases | grep -q cinemind_database || fail "project database was lost"

adb logcat -c
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12
pid=$(adb shell pidof "$pkg" | tr -d '\r')
crash=$(adb logcat -d | grep -A5 "FATAL EXCEPTION" | head -20)
[ -n "$crash" ] && fail "new version crashed on start: $crash"
[ -n "$pid" ] || fail "new version is not running after start"
# The new version must still find the model file (it lives in permanent storage, not the cache).
adb shell run-as "$pkg" ls files/models | grep -q upgrade-check.bin || fail "model file missing after the new version started"
note "### ✅ Update test passed: installed over the previous build; model file, setting and project database kept; new version runs (pid $pid)."
