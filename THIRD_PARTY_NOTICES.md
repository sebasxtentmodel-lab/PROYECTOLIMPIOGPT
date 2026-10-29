# SmartFk – components reused under their respective licenses

## WM Keyboard
Source: https://github.com/wasi-master/wmkeyboard , commit 887396aff4bd27a9e83dc1cc217bb6faf8cdf067
Copyright (c) 2026 Wasi Master. License MIT.
This project reuses the published native JNI interface/binaries for Bergamot and whisper.cpp,
and follows its Android input method architecture; it does not claim to be WM Keyboard.

## Mozilla Firefox Translations / Bergamot
Mozilla Bergamot offline inference (Marian), models served from Mozilla Remote Settings,
licensed under MPL-2.0 and applicable model-specific notices.
Original sources: https://github.com/browsermt/bergamot-translator
Models: https://firefox-settings-attachments.cdn.mozilla.net/main-workspace/translations-models-v2/
Every model archive is pinned and verified by SHA-256 at build time. Language pairs
tr→en→es and es→en→tr use English as an intermediate language.

## whisper.cpp
The multilingual tiny quantized weights are obtained from
https://huggingface.co/ggerganov/whisper.cpp (MIT).
The native library is from WM Keyboard's whisper.cpp JNI bridge.

## Vosk (Kaldi)
https://alphacephei.com/vosk/ , Apache-2.0; small Turkish and Spanish ASR models.
The original Kaldi model assets are taken from the official Vosk distribution.

The full upstream copyright notices and corresponding native source are available
from the linked source repositories. Review all redistributed binary/model notices
before releasing SmartFk to third parties.
