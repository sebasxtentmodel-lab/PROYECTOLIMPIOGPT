#!/usr/bin/env python3
"""Download official Vosk mobile ASR models; stage exactly the Android assets.
This replaces a fragile dependency on an expiring GitHub Actions artifact.
"""
from pathlib import Path
import hashlib
import shutil
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/"app/src/main/assets/models"
MODELS={
    "tr": ("vosk-model-small-tr-0.3", "8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5"),
    "es": ("vosk-model-small-es-0.42", "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"),
}
BASE="https://alphacephei.com/vosk/models/"
for lang, (name, expected_sha) in MODELS.items():
    archive=ROOT/"build-model-cache"/f"{name}.zip"
    archive.parent.mkdir(parents=True,exist_ok=True)
    valid=archive.is_file() and hashlib.sha256(archive.read_bytes()).hexdigest()==expected_sha
    if not valid:
        req=urllib.request.Request(BASE+name+".zip",headers={"User-Agent":"SmartFk-OfflineBuild/1.0"})
        with urllib.request.urlopen(req,timeout=180) as source,archive.open("wb") as target:
            shutil.copyfileobj(source,target,2**18)
    digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != expected_sha:
        raise ValueError(f"Vosk archive {lang} SHA-256 mismatch: {digest}")
    print(f"OFFICIAL VOSK {lang} VERIFIED sha256={digest} size={archive.stat().st_size}",flush=True)
    output=DEST/lang
    output.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        members=[x for x in z.infolist() if not x.is_dir()]
        if len(members)<8:raise ValueError("Incomplete official Vosk archive")
        for member in members:
            parts=Path(member.filename).parts
            if len(parts)<2 or parts[0]!=name or ".." in parts or member.file_size>300*1024*1024:
                raise ValueError("Unexpected Vosk archive layout: "+member.filename)
            target=output.joinpath(*parts[1:])
            target.parent.mkdir(parents=True,exist_ok=True)
            with z.open(member) as src,target.open("wb") as dst:
                shutil.copyfileobj(src,dst,2**17)
    found=list(output.rglob("final.mdl"))
    if len(found)!=1 or not found[0].is_file():
        raise ValueError("Missing Vosk acoustic model "+lang)
    print(f"OFFICIAL VOSK {lang} installed {len(members)} files",flush=True)
