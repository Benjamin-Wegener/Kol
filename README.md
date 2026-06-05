<div align="center">
  <img src="logo_v1.png" alt="Kol Logo" width="220" />
</div>

**Kol** is a local-first Android voice assistant.

## Roadmap

### Target program flow

The app should run as one simple linear voice-turn pipeline, with no extra side tracks, no parallel feature-specific event trees, and no interrupt-heavy orchestration except the absolute minimum state handoff between stages.

1. User speaks.
2. App records the utterance until end-of-speech is detected.
3. Recorded audio is sent directly into the LLM.
4. The LLM handles speech understanding internally and turns the user audio into text inside the model flow itself.
5. The parser prints the user's spoken words into the user chat bubble.
6. The assistant bubble is created immediately when the reply starts.
7. The first spoken chunk for TTS should be at most 5 tokens, unless punctuation arrives earlier, in which case it should be shorter.
8. TTS starts speaking that first chunk immediately.
9. The assistant bubble keeps filling with the rest of the LLM answer as tokens continue streaming.
10. TTS continues reading the rest of the answer in order.
11. When the answer is done, the app returns to listening status.
12. Nothing else happens.

### Rules for implementation

- One active turn at a time.
- One straight program flow from mic input to spoken reply.
- No extra workflow forms, branch-specific handlers, or feature-specific detours in the main turn loop.
- No separate STT subsystem if the multimodal LLM already does the transcription internally.
- User transcript appears only after the model has produced the recognized text.
- Assistant speech starts as soon as the first short safe chunk is available.
- The rest of the reply is appended to the same assistant bubble and spoken in the same order.
- Final state after completion is simply `listening`.

### Current flow — what is wrong

The engine is 689 lines split across two parallel coroutine drain loops (`drainLlmQueue` and
`drainTtsQueue`) that run independently for the lifetime of the app.
A voice turn goes through at least four async handoff points before a word is spoken:

```
AudioCapture callback
    └─▶ llmSentenceQueue.trySend()          ← async drop into Channel
            └─▶ drainLlmQueue coroutine
                    └─▶ processUtterance()
                            └─▶ Gemma.onToken → feedTokenToTts()
                                    └─▶ ttsSentenceQueue.trySend()   ← second Channel
                                            └─▶ drainTtsQueue coroutine
                                                    └─▶ synthesizeAndStream()
                                                            └─▶ scope.launch { delay → setState }
```

Specific problems:

- **Two unbounded `Channel` queues** (`llmSentenceQueue`, `ttsSentenceQueue`) decouple
  production from consumption in ways that make execution order opaque. If the LLM is slow,
  segments pile up and the drain loop processes them out of conversational order.
- **State is set from four different call sites** — `onSpeechStart`, `onSpeechEnd`,
  `feedTokenToTts`, `synthesizeAndStream`, and a `scope.launch { delay(...) }` timer inside
  `synthesizeAndStream`. Any of these can clobber each other.
- **`playbackTicket` / `AtomicLong` / `Mutex` / `synchronized(ttsFeedLock)`** — four separate
  concurrency guards for what should be one sequential thing. The ticket pattern in particular
  exists only because multiple coroutines race to write state.
- **`ttsFeedBuffer` word/char caps** (`ttsInitialWordCap=18`, `ttsRollingWordCap=24`,
  `ttsInitialCharCap=140`, `ttsRollingCharCap=180`) are a secondary batching layer on top of
  the token-count trigger, creating two conflicting flush policies for the same buffer.
- **`synthesizeAndPlay`** (the non-streaming path) and **`synthesizeAndStream`** (the streaming
  path) both exist and have diverging state-transition logic. Only one should exist.
- **`drainTtsQueue` ignores `EndOfTurn`** — the item is received and discarded with `Unit`,
  meaning the turn-end signal does nothing.
- **Mic resume** is estimated via `estimateSpeechDurationMs()` (a word-count heuristic) and
  also via `suspendProcessing(playbackDurationMs + 1150L)` called twice in the same path.
  These two timers overlap and neither is reliable.
- **`capture?.suspendProcessing(…)` is called inside `synthesizeAndStream`** before synthesis
  even starts, using an estimate. If TTS is slower or faster than the estimate the mic opens
  at the wrong time.

### Rewrite plan

Replace the two-channel / two-coroutine engine with one sequential suspend function that
executes the turn top to bottom inside a single coroutine. Internal concurrency (TTS
streaming, audio chunking) stays hidden behind `suspend` helpers that return only when their
stage is done.

**Step 1 — Remove the LLM channel**

`AudioCapture` currently calls `llmSentenceQueue.trySend()`. Replace this with a direct
call to `processUtterance()` via one dedicated coroutine that reads from the mic callback.
One utterance at a time; the next one waits until `processUtterance` returns.

**Step 2 — Remove the TTS channel**

Inside `processUtterance`, call TTS directly from the token accumulator instead of
`ttsSentenceQueue.trySend()`. The accumulator still buffers tokens until a flush condition
is met, but it calls `synthesizeAndStream()` as a blocking suspend call — the LLM token
loop simply `runBlocking`-style awaits each chunk before continuing. This forces
strict order: chunk N is always synthesised before chunk N+1 starts.

**Step 3 — Unify the flush policy**

Delete `ttsInitialWordCap`, `ttsRollingWordCap`, `ttsInitialCharCap`, `ttsRollingCharCap`.
Keep one rule: flush when token count ≥ 5, or when a punctuation boundary is hit first,
whichever comes first. No word/char secondary caps.

**Step 4 — Single state writer**

Only `processUtterance` writes `_state`. The sequence is:
```
Listening → Understanding → Thinking → Speaking → Listening
```
No other function sets state. `feedTokenToTts` and `synthesizeAndStream` do not touch
`_state` at all — they return results; `processUtterance` sets state from those results.

**Step 5 — Mic mute/unmute at boundaries only**

Stop the mic at the top of `processUtterance`, before calling Gemma. Resume it at the
bottom, after `player` drains. No estimates, no `suspendProcessing`, no timers.

**Step 6 — Delete dead code**

- Delete `synthesizeAndPlay` (non-streaming path).
- Delete `drainTtsQueue` and `drainLlmQueue`.
- Delete `llmSentenceQueue`, `ttsSentenceQueue`, `TtsQueueItem`.
- Delete `playbackTicket`.
- Delete `ttsMutex`, `gemmaMutex` (only needed because two coroutines raced; with one they are unnecessary).
- Delete `ttsInitialWordCap`, `ttsRollingWordCap`, `ttsInitialCharCap`, `ttsRollingCharCap`.

**Target shape of `processUtterance` after rewrite:**

```kotlin
private suspend fun processUtterance(samples: FloatArray) {
    capture?.mute()
    _state.value = State.Understanding

    val wavBytes = toWavBytes(samples, ModelConfig.STT_SAMPLE_RATE)
    _response.value = ""
    resetTtsFeedBuffer()
    _state.value = State.Thinking("")

    gemma.generateFromAudio(
        audioWavBytes = wavBytes,
        onToken = { token ->
            _response.value += token
            feedTokenToTts(token)   // buffers; suspends + speaks when flush condition met
        },
        onUserText = { _transcript.value = it },
        onLanguage  = { detectedLanguage = preferredLanguageCode ?: it },
        onDone      = { flushTtsFeedBuffer() }  // flush whatever remains
    )

    player?.awaitDrained()          // block until all PCM is played
    capture?.unmute()
    _state.value = State.Listening
}
```

**User bubble timing**

`onUserText` fires when Gemma emits the transcript. The UI observes `_transcript` and
appends the user bubble at that point — not before, not from a VAD callback.

**Assistant bubble timing**

The assistant bubble is created the moment `_state` transitions to `Thinking`. It grows as
`_response` accumulates tokens. The bubble is never replaced, only appended to.

---

### Why `processUtterance` must be the single entry point

#### What an utterance is

`AudioCapture` keeps a continuous mic loop running in a background coroutine.
It feeds every raw 16-bit PCM chunk through Silero VAD frame by frame.
VAD does not understand words — it only decides, per chunk, whether that chunk contains
human speech energy or silence.

When VAD flips from silence → speech, `AudioCapture` starts accumulating samples into
`speechBuffer`, prepended with ~500 ms of pre-roll so the first syllable is never clipped.
When VAD flips back to silence and silence holds for `VAD_MIN_SILENCE_MS`, the accumulated
buffer is sealed and the engine calls `onUtterance(samples: FloatArray)`.
That `FloatArray` is one complete spoken turn by the user — one utterance.

The multimodal Gemma model expects exactly this: a block of raw audio bytes covering one
complete utterance. It does not accept a stream of small frames; it needs the whole thing
in one call so it can see the full acoustic context before it produces any output.

#### Why everything hangs on it

`processUtterance` is the function that receives that block and drives every downstream
stage. Without it nothing happens:

- No utterance → no call to `generateFromAudio` → no tokens → no transcript → no reply → no TTS.
- The function is the only place that has the audio, so it is also the only right place to
  open the assistant bubble, start TTS, mute the mic, and transition state.
- If utterance delivery is async and unbounded (as it currently is via `llmSentenceQueue`),
  a second utterance can be enqueued before the first one finishes. The model is not
  re-entrant; the second call to `generateFromAudio` while the first is still running
  corrupts generation or stalls. Serialising through `processUtterance` as a blocking suspend
  call makes this impossible by construction.

#### The segment path and why it exists but should not drive the main flow

`AudioCapture` also fires `onUtteranceSegment` after a shorter silence threshold (~300 ms).
This was added to give the LLM a head start on very long utterances: send the first segment
while the user is still talking, so Gemma warms up. In practice this creates the race
described above — a segment arrives, `processUtterance` starts, then the complete utterance
arrives and is also enqueued. Both end up in `llmSentenceQueue` and both get processed.
The segment path should be removed or disabled for the default flow. The complete-utterance
path is sufficient and correct.

#### The suspendProcessing hack and why it must go

Currently `AudioCapture.suspendProcessing(durationMs)` is called from inside
`synthesizeAndStream` to prevent the mic from picking up the assistant's own voice while it
is speaking. It works by checking `System.currentTimeMillis() < suspendedUntilMs` at the
top of every chunk loop iteration and discarding audio while suspended.

The duration is estimated from word count (`estimateSpeechDurationMs`) before synthesis
even starts, and then adjusted again after synthesis with `playbackDurationMs + 1150L`,
both times as wall-clock guesses. If TTS is slower than the estimate the mic opens early
and the assistant hears itself. If it is faster the mic stays closed too long and cuts the
first word of the user's next turn.

The correct fix is to mute the mic at the start of `processUtterance` (before Gemma runs)
and unmute it only after `player.awaitDrained()` returns — i.e., after the last PCM sample
has been sent to the speaker. No estimate, no timer, no `suspendedUntilMs`. The mic is
closed for exactly the duration of the turn and no longer.

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

All items below serve the single-flow pipeline described at the top of this file.
Each fix removes something that currently breaks the linear turn or adds junk between stages.

- **Collapse the turn into one top-to-bottom flow** — rewrite `VoiceAssistantEngine` so the
  voice turn reads as a single straight call chain: record → infer → parse user text → open
  assistant bubble → chunk first TTS segment → stream rest → speak → set listening. No extra
  branches, no detached coroutines that write to UI from outside that flow.
- **Print user transcript at the right moment** — the user bubble should appear when Gemma
  emits the recognised text, not before, so the UI order always matches the audio order.
- **First TTS chunk at ≤ 5 tokens** — replace sentence-boundary batching with a token counter
  that fires TTS as soon as 5 non-punctuation tokens have accumulated, or earlier if a
  punctuation boundary is hit first. This eliminates the latency spike before first speech.
- **Fix TTS ordering** — later chunks must never play before earlier ones; enforce strict
  queue ordering so the spoken output always matches the text in the assistant bubble.
- **Stop the mic while speaking, resume after** — mute `AudioCapture` for the duration of
  assistant playback and re-enable it only when the audio queue drains. Barge-in is a
  separate concern and should not complicate the default no-interrupt path.
- **Scroll pin** — keep the chat scrolled to the latest message during streaming;
  allow manual scrollback but snap back to bottom when a new turn starts.
- **Logo** — make the launcher icon and in-app logo match `logo_v1.png` exactly.
- **Rebuild and verify on device after each step** — do not stack changes; confirm the
  audio handoff and chat surface work before moving to the next item.

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
