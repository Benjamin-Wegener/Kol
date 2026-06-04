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
runnable APK. Model downloads, voice loop, UI, voice selection, and expression playback are
functional; call-workflow logic is not yet implemented.

### What the app does

1. **Listens** — `AudioCapture` opens the mic with hardware AEC and noise suppression.
2. **Detects speech** — Silero VAD v4 segments the audio stream into utterances in real time.
3. **Understands** — The raw audio bytes of each utterance are fed directly to Gemma 4 E2B
   (LiteRT-LM). No intermediate speech-to-text model is involved; Gemma is multimodal and
   transcribes + answers in one shot.
4. **Signals thinking** — The moment VAD fires the end-of-turn event (before Gemma has
   produced a single token) the assistant plays a short natural expression — *"hmm"*,
   *"uh-huh"*, *"let me think…"* or similar — synthesised by SupertonicTTS so the
   conversation never feels dead.
5. **Streams the reply** — Gemma tokens are accumulated by `SentenceBatcher`. Each
   complete sentence is handed to `MultilingualTTS`, synthesised with SupertonicTTS int8,
   and queued for playback while the next sentence is still being generated.
6. **Speaks** — `AudioPlayer` plays the PCM queue through the device speaker. The
   language is auto-detected from Gemma's `[lang=xx]` prefix so the TTS voice always
   matches the reply language.
7. **Allows barge-in** — VAD keeps running during playback. If the user speaks,
   LLM generation is cancelled and the audio queue is flushed immediately.
8. **Lets you pick the voice** — A voice-switcher button on the main screen cycles
   through the ten Supertonic speakers (five male M1–M5, five female F1–F5). The
   selection is persisted in `AppSettings` and survives app restarts.

### What works
- Full pipeline: Mic → VAD → Gemma 4 E2B (LiteRT-LM) → SentenceBatcher → SupertonicTTS → Speaker
- Barge-in: VAD fires during TTS playback → cancels LLM generation + flushes audio queue
- Language detection: Gemma prefixes replies with `[lang=xx]`; TTS selects voice accordingly
- **Expression playback**: a short filler sound (*hmm*, *uh-huh*, *let me think…*) is
  synthesised and played the instant the user's turn ends, before the first LLM token arrives
- **Voice switcher button**: tapping cycles through Supertonic's ten voices (M1–M5, F1–F5);
  label updates in real time; selection persisted across restarts
- Scrollable WhatsApp-style chat UI with left/right bubbles
- Model download screen with per-file progress bars, speed (KB/s or MB/s), ETA, retry on error
- Auto-skips files already cached on disk
- Animated orb UI reacts to engine state (Idle / Listening / Thinking / Speaking / Error)
- 16 KB page-size compliant APK (`.so` files stored uncompressed, zipalign at 16384)
- Debug APK sideloadable via `adb push` or Files app

### What is not yet implemented
- Call reception / telephony integration
- Conversation history / multi-turn memory
- Settings screen (language, speed)
- Wake-word activation
- Background service / always-on mode

### Near-term roadmap
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
                            │
                            ├─▶ [expression] SupertonicTTS → AudioPlayer  (immediate, before LLM)
                            │
                            └─▶ GemmaLiteRtInference
                                   │  Gemma 4 E2B (LiteRT-LM)
                                   │  GPU backend → CPU fallback
                                   │  streams tokens
                                   └─▶ SentenceBatcher
                                           └─▶ MultilingualTTS
                                                  │  SupertonicTTS int8
                                                  │  31 languages · selected voice (M1–M5 / F1–F5)
                                                  └─▶ AudioPlayer
                                                          └─▶ Speaker
```

---

## Voices

SupertonicTTS exposes ten built-in speakers indexed by ID. The voice-switcher button on
the main screen cycles through all ten in order. The selection is saved in `AppSettings`
(SharedPreferences key `voice_id`) and applied on every TTS call.

| ID | Label | Gender |
|----|-------|--------|
| 0  | M1    | Male   |
| 1  | M2    | Male   |
| 2  | M3    | Male   |
| 3  | M4    | Male   |
| 4  | M5    | Male   |
| 5  | F1    | Female |
| 6  | F2    | Female |
| 7  | F3    | Female |
| 8  | F4    | Female |
| 9  | F5    | Female |

Reference: [supertone-inc.github.io/supertonic-py/voices](https://supertone-inc.github.io/supertonic-py/voices/)

The selected speaker ID is passed to `MultilingualTTS` which forwards it as the `sid`
field in `GenerationConfig`. The language-to-voice mapping applies on top: the
`[lang=xx]` tag selects the correct language model; the speaker ID controls which
voice within that model is used.

---

## Expression Sounds

To eliminate the silent gap between the end of the user's turn and the first spoken word
of the assistant's reply, the engine synthesises a short filler expression the moment
VAD detects end-of-utterance — before Gemma has produced any tokens.

The expression is picked at random from a small pool:

```
"Hmm."
"Uh-huh."
"Let me think…"
"Right."
"Okay."
```

It is synthesised with the currently selected voice and speaker ID and pushed directly
to `AudioPlayer`. Because it is queued first, it plays seamlessly before the first real
sentence arrives from `SentenceBatcher`.

---

## Known Optimizations & Open Issues

These are concrete improvements identified from code review and live logcat analysis.
Each entry names the exact file (or log source), the observed problem, and the fix.

---

### 🔴 Critical — GPU sampler `.so` symbol mismatch

**Logcat:**
```
samplerfactory.cc: OpenCL sampler not available, falling back to statically linked C API
Could not load shared library libLiteRtTopKOpenClSampler.so
— cannot locate symbol LiteRtCreateEnvironment
```
**Issue:** The top-k sampling step falls back to CPU because
`libLiteRtTopKOpenClSampler.so` (and/or `libLiteRtTopKWebGpuSampler.so`) are built
against a different LiteRT ABI than the `libLiteRt.so` that is loaded at runtime.
Sampling on CPU burns extra decode time on every token.
**Fix:** Rebuild `libLiteRtTopKOpenClSampler.so` against the exact LiteRT version
bundled in your dependency. Verify the `LiteRtCreateEnvironment` symbol is exported
from the correct `libLiteRt.so` first (`nm -D libLiteRt.so | grep LiteRtCreate`).
Place the matching `.so` in `app/src/main/jniLibs/arm64-v8a/`.

---

### 🔴 Critical — KV cache flushed on every turn

**Logcat / engine config:**
```
clear_kv_cache_before_prefill: true
```
**Issue:** The full KV cache is discarded at the start of every turn. The model
re-pays the entire prefill cost each time and cannot page context across turns.
For a multi-turn voice conversation this is a significant latency regression.
**File:** wherever `LlmInference.Options` / `InferenceOptions` is constructed
(likely `GemmaLiteRtInference.kt`).
**Fix:** Set `clear_kv_cache_before_prefill = false` unless you are actively
debugging stale-context bugs. Manage context length manually by trimming the
history when it approaches `GEMMA_MAX_TOKENS`.

---

### 🔴 Critical — TTS quality: `TTS_NUM_STEPS` too low

**File:** `ModelConfig.kt`
**Current:** `TTS_NUM_STEPS = 4`
**Issue:** SupertonicTTS uses a flow-matching vocoder. 4 inference steps produces
audibly rough speech. 16–32 steps yield noticeably smoother, more natural output.
**Fix:** Raise to `16` (balanced) or `32` (maximum quality). Consider exposing
this as a runtime setting in a future Settings screen.

---

### 🟡 High — `Gemma4DataProcessor` recreated on every turn

**Logcat (appears on every turn):**
```
Creating Gemma4DataProcessor
W melfilterbank.cc: Missing 10 bands starting at 0 in mel-frequency design
```
**Issue:** The audio data processor and its mel filterbank are being
allocated/torn down per utterance instead of being kept alive across turns.
This adds avoidable allocation and mel-filterbank re-setup cost on the hot path.
**File:** `GemmaLiteRtInference.kt` — the processor is likely created inside
`generateAsync()` or equivalent instead of in `init`.
**Fix:** Allocate `Gemma4DataProcessor` once in `init` and reuse the same instance
across all turns. The mel-filterbank warning will also disappear after the first
creation.

---

### 🟡 High — LiteRT-LM thread pools capped at 1

**Logcat:**
```
ThreadPool 'execution_thread_pool': Running up to 1 threads
ThreadPool 'callback_thread_pool': Running up to 1 threads
```
**Issue:** Both LiteRT-LM thread pools use a single thread. The audio encoder and
embedder stages run CPU-bound work that would benefit from 2–4 threads, matching
the `NumThreads(CPU only): 4` already configured for the audio adapter.
**File:** `GemmaLiteRtInference.kt` — `LlmInference.Options` or
`InferenceOptions` builder.
**Fix:** Set `setNumThreads(4)` (or `availableProcessors().coerceAtMost(4)`) on
the options builder before creating the inference engine.

---

### 🟡 High — TTS threads under-provisioned

**File:** `MultilingualTTS.kt` → `OfflineTtsModelConfig`
**Current:** `numThreads = 2`
**Issue:** ARM Cortex-A cores on modern phones (Snapdragon 8 Gen 2+) have 4+
performance cores. The vocoder is left unnecessarily slow.
**Fix:** `numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)`

---

### 🟡 High — Speaker ID hardcoded; voice switcher not wired

**File:** `MultilingualTTS.kt`
**Current:** `private val speakerId = 6` (constant `val`)
**Issue:** Changing the voice in the UI has no effect — `speakerId` is frozen at
construction time.
**Fix:** Promote to `@Volatile var speakerId: Int = 6`. Read the persisted value
from `AppSettings.getVoiceId(context)` at the start of each
`synthesize()` / `synthesizeStreaming()` call in `VoiceAssistantEngine`.

---

### 🟡 High — `AppSettings` missing `voice_id` key

**File:** `AppSettings.kt`
**Current:** Only `KEY_LANGUAGE` is persisted.
**Issue:** Voice selection resets on every app restart.
**Fix:** Add `KEY_VOICE_ID` (default `6` = F2) with `getVoiceId()` /
`setVoiceId()` helpers following the same pattern as the language key.

---

### 🟡 Medium — Expression playback not yet implemented

**File:** `VoiceAssistantEngine.kt` — utterance handler, before LLM launch
**Issue:** The user hears silence for the full LLM + first-sentence TTS latency
(typically 1–3 s) after they stop speaking.
**Fix:** Immediately after VAD end-of-utterance, synthesise one randomly chosen
filler from `["Hmm.", "Uh-huh.", "Right.", "Okay.", "Let me think…"]` using
`tts.synthesize()` and enqueue the samples into `AudioPlayer` before launching
the LLM coroutine.

---

### 🟡 Medium — Streaming TTS path not always taken

**File:** `VoiceAssistantEngine.kt` — `synthesizeAndPlay()` vs `synthesizeAndStream()`
**Issue:** `synthesizeAndPlay()` blocks until the entire sentence is synthesised
before any audio is enqueued. `synthesizeAndStream()` with the vocoder callback is
present but not uniformly used, leaving time-to-first-audio unnecessarily high.
**Fix:** Route all sentence synthesis (including the expression filler) through
`synthesizeAndStream()` so the vocoder pipes audio chunks to `AudioPlayer` as they
are produced.

---

### 🟡 Medium — `ashmem` pinning deprecated

**Logcat:**
```
E ashmem: Pinning is deprecated since Android Q. Please use trim or other methods.
```
**Issue:** Something in the stack (likely sherpa-onnx or a support lib) still calls
the old `pin` ioctl. Android logs this at `ERROR` level.
**Fix:** Identify the caller with `adb logcat -v threadtime | grep -B5 ashmem`.
Replace with `madvise(MADV_WILLNEED)` or `MADV_SEQUENTIAL` as appropriate. If it
is inside an unmodifiable dependency, file an upstream issue.

---

### 🟡 Medium — `attributionTag` not declared in manifest

**Logcat (repeated throughout session):**
```
E AppOps systemserver: attributionTag not declared in manifest of com.voiceassistant
```
**File:** `AndroidManifest.xml`
**Fix:** Add an `<attribution>` element inside `<manifest>` for microphone access:
```xml
<attribution android:tag="microphone" android:label="@string/mic_usage" />
```
This suppresses the system-server errors and correctly attributes
privacy-sensitive sensor access on Android 11+.

---

### 🟢 Low — Stale shader cache purged on every launch

**Logcat:**
```
executor_settings_base.cc: Deleted 1 stale cache files
```
**Issue:** Compiled GPU shader/delegate caches are being invalidated on every cold
start, forcing ~5 s of subgraph recompilation each time.
**Fix:** Ensure the cache file path includes a stable, versioned key tied to the
model checksum or app version code. If the cache directory is being wiped by a
clean-up routine, exclude it. A warm launch should hit the cache and skip
recompilation entirely.

---

### 🟢 Low — `OnBackInvokedCallback` not enabled

**Logcat:**
```
W WindowOnBackDispatcher: OnBackInvokedCallback is not enabled for the application.
```
**File:** `AndroidManifest.xml` → `<application>`
**Fix:**
```xml
<application android:enableOnBackInvokedCallback="true" …>
```
No functional change on current Android versions; future-proofs for Android 13+
predictive back gesture.

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
| TTS | SupertonicTTS int8 (31 languages, 10 voices) via sherpa-onnx JNI |
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
- Streaming TTS: sentences are synthesised and queued as Gemma tokens arrive
- **Instant expression feedback**: a natural filler sound plays the moment the user stops
  speaking, eliminating the silent thinking gap
- **Voice switcher**: one-tap cycling through ten Supertonic voices (M1–M5, F1–F5),
  persisted across restarts
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
│                             #   TTS_NUM_STEPS, TTS_SPEED, GEMMA_* tuning knobs
├── ModelDownloader.kt        # Download manager with per-file progress, speed, ETA
├── VoiceAssistantEngine.kt   # Pipeline orchestrator (VAD → expression → Gemma → TTS)
│                             #   barge-in logic, sentence batching, playback tickets
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
│                             #   speakerId, numThreads, numSteps — quality knobs here
└── ui/
    ├── AppSettings.kt        # SharedPreferences: language + voice_id persistence
    ├── ChatAdapter.kt        # RecyclerView adapter for chat bubbles
    ├── ChatMessage.kt        # Data class for chat messages
    ├── ConversationStore.kt  # In-memory conversation state
    ├── MainActivity.kt       # Main screen, permissions, engine lifecycle, voice switcher
    ├── MainViewModel.kt      # ViewModel bridging engine state + selected voice to UI
    ├── OrbView.kt            # Animated orb canvas view (idle/listening/thinking/speaking)
    └── SetupActivity.kt      # First-run model download UI with detailed progress
```

---

## Future Goals

- **Call assistant**: receive and handle phone calls entirely through Kol — answer, speak,
  listen, and respond using the same on-device pipeline with no human needed to pick up.

---

## License

Apache 2.0 — see [LICENSE](LICENSE)
