#!/usr/bin/env bash
set -euo pipefail

APK=/tmp/apk/app-debug.apk
test -s "$APK"
adb install -r "$APK"
adb shell pm grant com.smartfk.keyboard android.permission.RECORD_AUDIO

echo 'Testing actual Mozilla neural inference with installed model files...'
adb logcat -c
adb shell am start -n com.smartfk.keyboard/.NeuralSmokeTestActivity
for i in $(seq 1 75); do
  LOG=$(adb logcat -d -s SMARTFK_NEURAL_TEST:I '*:S' || true)
  if echo "$LOG" | grep -q 'TR_ES_FAILED\|ES_TR_FAILED'; then
    echo "$LOG"
    exit 1
  fi
  if echo "$LOG" | grep -q 'TR_ES_OK:' && echo "$LOG" | grep -q 'ES_TR_OK:'; then
    echo "$LOG"
    echo "PASS: translated Turkish → Spanish and Spanish → Turkish entirely offline."
    break
  fi
  if [ "$i" -eq 75 ]; then
    echo "$LOG"
    echo "ERROR: no successful neural translation after Android runtime test."
    exit 1
  fi
  sleep 2
done
echo 'Testing real Turkish and Spanish speech with Vosk and Whisper...'
adb logcat -c
adb shell am start -n com.smartfk.keyboard/.AsrSmokeTestActivity
for i in $(seq 1 150); do
  LOG=$(adb logcat -d -s SMARTFK_ASR_TEST:I '*:S' || true)
  if echo "$LOG" | grep -q 'ASR_TEST_FAILED'; then
    echo "$LOG"
    exit 1
  fi
  if echo "$LOG" | grep -q 'ASR_TEST_COMPLETE'; then
    echo "$LOG" | grep -E 'VOSK_RESULT|WHISPER_RESULT|ASR_TEST_COMPLETE'
    break
  fi
  if [ "$i" -eq 150 ]; then
    echo "$LOG"
    echo 'ERROR: speech decoding did not finish'
    exit 1
  fi
  sleep 2
done
adb shell am start -n com.smartfk.keyboard/.SetupActivity
sleep 3

IME_ID=""
for i in $(seq 1 20); do
  IME_ID=$(adb shell ime list -a -s | tr -d '\r' | grep -m1 'com.smartfk.keyboard' || true)
  if [[ -n "$IME_ID" ]]; then break; fi
  sleep 2
done
if [[ -z "$IME_ID" ]]; then
  adb shell ime list -a
  adb shell dumpsys package com.smartfk.keyboard | grep -A 12 -B 8 SmartFkInputMethod || true
  echo 'ERROR: SmartFk is not registered as an IME'
  exit 1
fi
echo "Android registered input method: $IME_ID"
adb shell ime enable "$IME_ID"
adb shell ime set "$IME_ID"

python3 - <<'PY'
import re, subprocess, time, xml.etree.ElementTree as ET
def adb(*args):
    return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)
for attempt in range(6):
    adb('shell','uiautomator','dump','/sdcard/smartfk-ui.xml')
    xml=adb('exec-out','cat','/sdcard/smartfk-ui.xml')
    root=ET.fromstring(xml)
    fields=[e for e in root.iter() if e.get('class')=='android.widget.EditText']
    if fields:
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',fields[0].get('bounds',''))
        if m:
            x1,y1,x2,y2=map(int,m.groups())
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
            print('Tapped SmartFk test field',x1,y1,x2,y2)
            break
    adb('shell','input','swipe','400','1450','400','500','350')
    time.sleep(.7)
else:
    raise RuntimeError('Could not locate the test field after scrolling SetupActivity')
PY
sleep 12
mkdir -p /tmp/smartfk-evidence
adb shell screencap -p /sdcard/smartfk-screen.png
adb pull /sdcard/smartfk-screen.png /tmp/smartfk-evidence/smartfk-screen.png
adb logcat -d -v brief > /tmp/smartfk-evidence/logcat.txt
adb shell dumpsys input_method > /tmp/smartfk-evidence/input_method.txt
if grep -A 14 'FATAL EXCEPTION' /tmp/smartfk-evidence/logcat.txt | grep -q 'com.smartfk.keyboard'; then
    echo 'ERROR: SmartFk encountered a fatal Android exception'
    grep -A 25 'FATAL EXCEPTION' /tmp/smartfk-evidence/logcat.txt | tail -60
    exit 1
fi
grep -q 'com.smartfk.keyboard' /tmp/smartfk-evidence/input_method.txt
echo 'PASS: SmartFk installed, registered, enabled, and test field opened without fatal exception'
