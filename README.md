<div align="center">
  <img src="logo_v1.png" alt="Kol Logo" width="220" />
</div>

**Kol** is a local-first Android voice assistant.

It runs a full speech pipeline entirely on-device: voice activity detection, multimodal
audio-to-text-to-speech inference, and natural voice output — with no cloud roundtrip,
no account, and no data leaving the phone.

---

## Current Status

Active development — alpha. The core pipeline is wired end-to-end and builds to a
runnable APK. Model downloads, voice loop, and UI are functional; call-workflow logic
is not yet implemented.

### What works
- Full pipeline: Mic → VAD → Gemma 4 E2B (LiteRT-LM) → SentenceBatcher → SupertonicTTS → Speaker
- Barge-in: VAD fires during TTS playback → cancels LLM generation + flushes audio queue
- Language detection: Gemma prefixes replies with `[lang=xx]`; TTS selects voice accordingly
- Model download screen with per-file progress bars, speed (KB/s or MB/s), ETA, retry on error
- Auto-skips files already cached on disk
- Animated orb UI reacts to engine state (Idle / Listening / Thinking / Speaking / Error)
- 16 KB page-size compliant APK (`.so` files stored uncompressed, zipalign at 16384)
- Debug APK sideloadable via `adb push` or Files app

### What is not yet implemented
- Call reception / telephony integration
- Conversation history / multi-turn memory
- Settings screen (voice, language, speed)
- Wake-word activation
- Background service / always-on mode

### Near-term roadmap
- Turn the main screen into a scrollable WhatsApp-style chat with left/right bubbles.
- Show the user transcript and assistant replies as separate chat messages.
- Keep the conversation pinned to the latest message while still allowing manual scrollback.
- Stop microphone capture while the assistant is speaking, and resume only after playback ends.
- Investigate and fix TTS sentence ordering so later snippets do not play before earlier ones.
- Review shared audio state for races in `VoiceAssistantEngine`, `AudioPlayer`, and playback handoff logic.
- Keep Gemma in no-thinking mode while still allowing longer, fuller answers when useful.
- Make the launcher icon and in-app logo match `logo_v1.png` exactly.
- Rebuild after each pass, then verify the chat surface and audio handoff on device.

---

## Pipeline Architecture

```
Microphone
    └─▶ AudioCapture (AEC + NoiseSuppressor)
            └─▶ VadDetector (Silero VAD v4 / sherpa-onnx)
                    └─▶  [utterance complete]
                            └─▶ GemmaLiteRtInference
                                   │  Gemma 4 E2B (LiteRT-LM)
                                   │  GPU backend → CPU fallback
                                   │  streams tokens
                                   └─▶ SentenceBatcher
                                           └─▶ MultilingualTTS
                                                  │  SupertonicTTS int8
                                                  │  31 languages
                                                  └─▶ AudioPlayer
                                                          └─▶ Speaker
```

---

## Models

| Model | File | Size | Source |
|---|---|---|---|
| Silero VAD v4 | `silero_vad.onnx` | ~2 MB | k2-fsa/sherpa-onnx |
| Gemma 4 E2B | `gemma-4-E2B-it.litertlm` | ~2.5 GB | litert-community/gemma-4-E2B-it-litert-lm |
| SupertonicTTS int8 | `sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2` | ~300 MB | k2-fsa/sherpa-onnx |

All models are downloaded on first launch via `SetupActivity` into `filesDir/models/`.
No model files are bundled in the APK.

---

## Runtime Stack

| Layer | Technology |
|---|---|
| LLM | Gemma 4 E2B via Google LiteRT-LM (`com.google.ai.edge.litertlm`) |
| VAD | Silero VAD v4 via sherpa-onnx JNI |
| TTS | SupertonicTTS int8 (31 languages) via sherpa-onnx JNI |
| Audio I/O | Android `AudioRecord` + `AudioTrack` |
| AEC / NS | `AcousticEchoCanceler` + `NoiseSuppressor` (Android audiofx) |
| Concurrency | Kotlin coroutines + `StateFlow` |
| UI | View-based (ViewBinding), custom `OrbView` canvas animation |

---

## Features

- Fully on-device voice loop — audio and transcripts never leave the device
- Multimodal: Gemma 4 E2B receives raw audio bytes directly (no separate STT model)
- Multilingual: 31-language TTS, language auto-detected from Gemma's `[lang=xx]` prefix
- Barge-in support: user can interrupt the assistant mid-reply
- Streaming TTS: sentences are synthesized and queued as Gemma tokens arrive
- GPU-accelerated inference with automatic CPU fallback
- Offline-capable after initial model download

---

## Privacy Model

1. Voice activity detection runs locally
2. Speech understanding runs locally (Gemma 4 E2B on-device)
3. Voice synthesis runs locally (SupertonicTTS on-device)
4. No mandatory cloud roundtrip at any stage

Network is used only for the one-time model download on first launch.

---

## Requirements

- Android Studio (latest stable)
- Android SDK 34+
- `minSdk` 26 (Android 8.0)
- `abiFilter`: `arm64-v8a` only
- Physical device strongly recommended (emulators lack NNAPI / GPU delegates)
- ~3 GB free storage for models

---

## Build

```bash
git clone <repo-url>
cd Kol
```

Create `local.properties` if needed:

```properties
sdk.dir=/Users/<your-user>/Library/Android/sdk
```

Build debug APK:

```bash
./gradlew :app:assembleDebug
```

Push to device:

```bash
~/Library/Android/sdk/platform-tools/adb push \
  app/build/outputs/apk/debug/app-debug.apk \
  /sdcard/Download/Kol.apk
```

Then open the Files app on the phone and tap `Kol.apk` to install
(requires *Install unknown apps* enabled for Files).

---

## Project Structure

```
app/src/main/java/org/kol/
├── ModelConfig.kt            # Model filenames, URLs, runtime constants, system prompt
├── ModelDownloader.kt        # Download manager with per-file progress, speed, ETA
├── VoiceAssistantEngine.kt   # Pipeline orchestrator (VAD → Gemma → TTS), barge-in logic
├── ai/
│   └── RuntimeProviders.kt  # NNAPI / CPU provider selection helpers
├── audio/
│   ├── AudioCapture.kt       # Mic capture, AEC, NS, VAD integration, utterance detection
│   ├── AudioPlayer.kt        # AudioTrack-based PCM player with queue
│   └── VadDetector.kt        # Silero VAD v4 wrapper (sherpa-onnx)
├── llm/
│   ├── GemmaLiteRtInference.kt  # LiteRT-LM engine, GPU→CPU fallback, streaming tokens
│   └── SentenceBatcher.kt       # Buffers tokens, flushes complete sentences to TTS
├── tts/
│   └── MultilingualTTS.kt    # SupertonicTTS wrapper, language → voice mapping
└── ui/
    ├── MainActivity.kt       # Main screen, permission handling, engine lifecycle
    ├── MainViewModel.kt      # ViewModel bridging engine state to UI
    ├── OrbView.kt            # Animated orb canvas view (idle/listening/thinking/speaking)
    └── SetupActivity.kt      # First-run model download UI with detailed progress
```

---

## License

Apache 2.0 — see [LICENSE](LICENSE)
