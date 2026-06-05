# Kol Single-Flow Migration Roadmap

Goal: transfer the working single-turn flow from `/Users/user/dev/Kol-single-turn-worktree` into `/Users/user/dev/Kol` in small, verifiable steps. Keep `/Users/user/dev/Kol-main` as the known-good reference and verify on-device after every step before moving on.

## Baseline

- Source of truth for the current working app path: `/Users/user/dev/Kol-main`
- Reference implementation of the streamlined single-turn flow: `/Users/user/dev/Kol-single-turn-worktree`
- Target tree for migration: `/Users/user/dev/Kol`

## Migration Rules

- Change one behavior slice at a time.
- After each slice, run the app on-device and verify the exact user interaction that exposed the regression.
- If a slice fails, stop and revert only that slice in `/Users/user/dev/Kol`, then investigate before continuing.
- Do not combine prompt, parser, lifecycle, and native-library changes in the same step unless the previous step has already proven safe.

## Step 0: Establish the baseline in `/Users/user/dev/Kol`

- Confirm the current `kol-main` baseline is working on device.
- Record the expected logs for a successful German utterance:
  - user transcript appears once
  - assistant visible token(s) appear
  - assistant audio plays
  - turn completes

Verification:

- Run the exact phrase: `Guten Tag, wie geht es Ihnen?`
- Confirm the app produces a user bubble, assistant text, and assistant audio.

## Step 1: Recreate the minimal single-turn turn gate

Implement only the single active-turn flow from the single-turn tree:

- Replace queue/drain orchestration with a direct `processUtterance(...)` suspend path.
- Gate turns with a `Mutex.tryLock()` so only one utterance is processed at a time.
- Keep the existing capture, player, and Gemma initialization shape otherwise unchanged.

Files:

- `/Users/user/dev/Kol/app/src/main/java/org/kol/VoiceAssistantEngine.kt`

Verification:

- Speak one short utterance and confirm one turn starts and ends cleanly.
- Confirm no duplicate turn processing occurs when speech overlaps or when the user interrupts.
- Confirm assistant audio still plays.

## Step 2: Restore turn-local transcript handling

Ensure the engine updates user transcript state in the same way as the single-turn flow:

- Set the pending transcript placeholder at turn start.
- Replace it with the real transcript once Gemma emits `[user=...]`.
- Clear pending/user/response state only when appropriate.

Files:

- `/Users/user/dev/Kol/app/src/main/java/org/kol/VoiceAssistantEngine.kt`
- `/Users/user/dev/Kol/app/src/main/java/org/kol/ui/MainViewModel.kt` if needed for UI binding alignment

Verification:

- Confirm the placeholder bubble appears before the final transcript.
- Confirm the transcript does not duplicate across a single turn.

## Step 3: Reintroduce the single-turn TTS feeding model

Bring over the simpler TTS path from the single-turn tree:

- Feed assistant tokens directly into a per-turn TTS buffer.
- Flush on short sentence boundaries or at turn end.
- Avoid the queue/drain TTS architecture until the core flow is stable.

Files:

- `/Users/user/dev/Kol/app/src/main/java/org/kol/VoiceAssistantEngine.kt`

Verification:

- Confirm visible assistant tokens are emitted before playback.
- Confirm assistant audio starts as soon as the first sentence is ready.
- Confirm the app no longer hangs waiting for queue drain.

## Step 4: Restore the Gemma turn lifecycle

Bring back the working Gemma lifecycle behavior from the streamlined tree:

- Recreate or clear conversation history after each completed turn.
- Keep `conversationMutex` guarding the conversation lifecycle.
- Keep speculative decoding and sampler settings unchanged unless this step proves they are the cause.

Files:

- `/Users/user/dev/Kol/app/src/main/java/org/kol/llm/GemmaLiteRtInference.kt`
- `/Users/user/dev/Kol/app/src/main/java/org/kol/VoiceAssistantEngine.kt`

Verification:

- Run two back-to-back German utterances.
- Confirm the second turn still produces visible assistant text and audio.
- Confirm the `[lang=de]` loop does not return.

## Step 5: Reconcile prompt and parser expectations

Only after the single-turn pipeline is stable, align the prompt and parser so they agree on the visible output format.

Possible options:

- Keep `[lang=xx]` in the prompt and ensure the parser can safely extract visible text after it.
- Or switch the prompt back to plain-text assistant output and simplify parsing accordingly.

Files:

- `/Users/user/dev/Kol/app/src/main/java/org/kol/ModelConfig.kt`
- `/Users/user/dev/Kol/app/src/main/java/org/kol/llm/GemmaLiteRtInference.kt`

Verification:

- Confirm the model emits real assistant text, not only tags.
- Confirm the parser forwards visible tokens immediately.

## Step 6: Only then revisit native sampler/library differences

If the loop still appears after the pure Kotlin flow is restored, test native/runtime differences separately:

- Compare `libLiteRtClGlAccelerator.so` behavior.
- Disable speculative decoding temporarily if needed.
- Verify the GPU sampler fallback path does not alter visible output.

Files:

- `/Users/user/dev/Kol/app/src/main/jniLibs/arm64-v8a/libLiteRtClGlAccelerator.so`
- `/Users/user/dev/Kol/app/src/main/java/org/kol/llm/GemmaLiteRtInference.kt`

Verification:

- Compare logs with and without the native library.
- Confirm whether the output loop changes or remains identical.

## Exit Criteria

The migration is done when `/Users/user/dev/Kol` behaves like the working single-flow version on device:

- One utterance equals one assistant turn.
- The user transcript appears once.
- Assistant text appears visibly.
- Assistant audio plays.
- No `[lang=de]`-only loop appears.
- Consecutive turns remain stable.

