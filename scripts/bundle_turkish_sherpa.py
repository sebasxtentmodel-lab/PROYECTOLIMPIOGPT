#!/usr/bin/env python3
"""Fetch pinned, openly licensed Turkish Whisper int8 models from Hugging Face.
The SHA256SUMS manifest is checked from the SAME immutable source commit.
No runtime model downloads: data ships inside the Android APK.
"""
import hashlib
from pathlib import Path
import shutil
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/"app/src/main/assets/turkish-sherpa"
CACHE=ROOT/"build-model-cache"/"turkish-sherpa"
REV="dba827b0c88f6ac7e9caf08b2a95e8256f01d07a"
BASE=f"https://huggingface.co/Tinlera/whisper-turkish-sherpa-onnx/resolve/{REV}/"
FILES=("base-tr-encoder.int8.onnx","base-tr-decoder.int8.onnx","tokens.txt")
DEST.mkdir(parents=True,exist_ok=True)
CACHE.mkdir(parents=True,exist_ok=True)
def fetch(name,dest):
    error=None
    for attempt in range(3):
        try:
            req=urllib.request.Request(BASE+name,headers={"User-Agent":"SmartFk-verified-offline-builder"})
            with urllib.request.urlopen(req,timeout=250) as r,dest.open("wb") as f:
                shutil.copyfileobj(r,f,2**19)
            return
        except Exception as e:
            error=e
            print("retry",name,attempt+1,str(e),flush=True)
            time.sleep(2*(attempt+1))
    raise RuntimeError(f"Cannot retrieve pinned file {name}: {error}")
def sha(p):
    h=hashlib.sha256()
    with p.open("rb") as f:
        for part in iter(lambda:f.read(2**20),b""):h.update(part)
    return h.hexdigest()
manifest=CACHE/"SHA256SUMS"
fetch("SHA256SUMS",manifest)
hashes={}
for line in manifest.read_text().splitlines():
    words=line.strip().split()
    if len(words)==2 and len(words[0])==64:
        hashes[words[1].removeprefix("*")]=words[0]
for name in FILES:
    if name not in hashes:raise ValueError("Model not listed in repository checksum manifest: "+name)
    archive=CACHE/name
    if not archive.is_file() or sha(archive)!=hashes[name]:
        fetch(name,archive)
    actual=sha(archive)
    if actual!=hashes[name]:raise ValueError(f"Checksum mismatch {name} {actual}")
    shutil.copyfile(archive,DEST/name)
    print("VERIFIED TURKISH SHERPA",name,(DEST/name).stat().st_size,actual,flush=True)
