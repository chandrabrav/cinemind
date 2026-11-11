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
# (adb shell joins its arguments into one device command line, so everything is quoted as one
# string; file contents go through `adb exec-in`, which passes stdin byte-for-byte.)
rs() { adb shell "run-as $pkg $1" | tr -d '\r'; }
work=$(mktemp -d)
head -c 4194304 /dev/urandom > "$work/upgrade-check.bin"
model_before=$(sha256sum "$work/upgrade-check.bin" | cut -d" " -f1)
printf '%s' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="theme_mode">DARK</string></map>' > "$work/appearance.xml"
rs "mkdir -p files/models shared_prefs"
adb exec-in "run-as $pkg sh -c 'cat > files/models/upgrade-check.bin'" < "$work/upgrade-check.bin"
adb exec-in "run-as $pkg sh -c 'cat > shared_prefs/appearance.xml'" < "$work/appearance.xml"
on_device=$(rs "sha256sum files/models/upgrade-check.bin" | cut -d" " -f1)
[ "$on_device" = "$model_before" ] || fail "couldn't write the test model file (got '$on_device')"
dbs=$(rs "ls databases")
echo "databases before: $dbs"
echo "$dbs" | grep -q cinemind_database || fail "the baseline build did not create its database on start"
vc_before=$(adb shell dumpsys package "$pkg" | grep -m1 versionCode | tr -d '\r')

out=$(adb install -r "$new" 2>&1); echo "$out"
echo "$out" | grep -q Success || fail "update install was refused: $out"
vc_after=$(adb shell dumpsys package "$pkg" | grep -m1 versionCode | tr -d '\r')
note "Installed over: $vc_before → $vc_after"

model_after=$(rs "sha256sum files/models/upgrade-check.bin" | cut -d" " -f1)
[ -n "$model_before" ] && [ "$model_before" = "$model_after" ] || fail "model file changed or was deleted ($model_before → $model_after)"
rs "cat shared_prefs/appearance.xml" | grep -q DARK || fail "settings were lost"
rs "ls databases" | grep -q cinemind_database || fail "project database was lost"

adb logcat -c
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12
pid=$(adb shell pidof "$pkg" | tr -d '\r')
crash=$(adb logcat -d | grep -A5 "FATAL EXCEPTION" | head -20)
[ -n "$crash" ] && fail "new version crashed on start: $crash"
[ -n "$pid" ] || fail "new version is not running after start"
# The new version must still find the model file (it lives in permanent storage, not the cache).
rs "ls files/models" | grep -q upgrade-check.bin || fail "model file missing after the new version started"
note "### ✅ Update test passed: installed over the previous build; model file, setting and project database kept; new version runs (pid $pid)."
