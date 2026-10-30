#!/usr/bin/env python3
"""Fail closed when the Android emulator ASR produces garbled Turkish/Spanish.
Input: emulator logcat lines from the REAL Vosk and whisper.cpp JNI decoders.
Synthetic espeak fixtures prove basic ASR functionality, not conversational quality.
"""
import json
import re
import sys
import unicodedata

REFERENCE = {
    "TR": "Merhaba. Nasılsın? Bugün hava çok güzel. Yarın saat dokuzda görüşebilir miyiz?",
    "ES": "Hola, buenos días. Cómo estás? Mañana nos vemos a las nueve de la mañana.",
}
MIN_ACCURACY = {"TR": 0.60, "ES": 0.60}
DATA = sys.stdin.read()

def normalize(s):
    s=s.lower().replace("ı","i")
    s="".join(c for c in unicodedata.normalize("NFKD",s) if not unicodedata.combining(c))
    return re.findall(r"[a-z]+", s)

def distance(a,b):
    prev=list(range(len(b)+1))
    for i,tok in enumerate(a,1):
        row=[i]
        for j,other in enumerate(b,1):
            row.append(min(row[-1]+1,prev[j]+1,prev[j-1]+(tok!=other)))
        prev=row
    return prev[-1]

report={"gate":"offline_ASR_quality","status":"FAIL","languages":{}}
ok=True
for language, expected in REFERENCE.items():
    ref=normalize(expected)
    options=[]
    for engine in (("VOSK","WHISPER","SHERPA","SHERPA_STREAM") if language=="TR" else ("VOSK","WHISPER")):
        matches=re.findall(rf"{language}_{engine}_RESULT=(.*)",DATA)
        raw=matches[-1].strip() if matches else ""
        # Whisper emits language code and newline; "tr" or "es" alone is NOT transcription.
        if engine=="WHISPER":
            raw=re.sub(rf"^{language.lower()}[\r\n ]*", "", raw).strip()
        recognized=normalize(raw)
        wer=distance(ref,recognized)/len(ref)
        accuracy=max(0.0,1-wer)
        options.append({"engine":engine,"raw":raw[:400],"word_accuracy":round(accuracy,3)})
    best=max(options,key=lambda x:x["word_accuracy"])
    passed=best["word_accuracy"] >= MIN_ACCURACY[language]
    report["languages"][language]={"reference":expected,"best":best,"candidates":options,
        "threshold":MIN_ACCURACY[language],"passed":passed}
    ok &= passed
for result in ("TR_AUDIO_TRANSLATED=","ES_AUDIO_TRANSLATED=","ASR_TEST_COMPLETE"):
    if result not in DATA:
        ok=False
        report.setdefault("missing",[]).append(result)
report["status"]="PASS" if ok else "FAIL"
print(json.dumps(report,ensure_ascii=False,indent=2))
if not ok:
    print("FAIL: recognized speech is too inaccurate for a confirmed release.",file=sys.stderr)
    sys.exit(1)
print("PASS: minimum scripted-speech accuracy reached; real human speech and phone still require validation.")
