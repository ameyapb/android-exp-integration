# Design: Phase 2 voice input

Date: 2026-09-15

## Problem

`ask-gemini.js --voice` already does speech-in/speech-out today: it
captures a spoken prompt via `termux-speech-to-text`, confirms the
transcript with the user via `termux-dialog` (falling back to a stdin
y/N prompt), sends the confirmed transcript to Gemini, and speaks the
reply back via `termux-tts-speak`. `roadmap.md` names this as Phase 2
of the native app, chosen first among the deferred capabilities because
it is already proven in the script and is the fastest path to full
parity with it. The Phase 1 native app (`android-app/`) currently only
supports a typed prompt with an on-screen/notification reply.

This design turns Phase 2's roadmap scope bullets into a concrete
architecture, file layout, and testing plan ready for an implementation
plan.

## Decision

Add voice capture and playback to the existing single-screen app rather
than introducing a new screen or navigation. Voice capture uses the raw
`android.speech.SpeechRecognizer` API (not the simpler
`RecognizerIntent` system dialog), wrapped the same way `GeminiClient`
and `GeminiNotifier` wrap their respective Android/SDK boundaries, so
the new code matches the app's existing repository/wrapper pattern and
stays fakeable in `AskGeminiViewModel` tests. Playback uses
`android.speech.tts.TextToSpeech`, wrapped the same way. Both wrappers
are tested under Robolectric, matching the precedent already set by
`GeminiClientImpl` and `GeminiNotifierImpl` in Phase 1 (real Android
framework internals a plain JVM unit-test stub can't execute).

Key parameters fixed in this session:
- Voice-originated replies are spoken aloud automatically; typed replies
  from the existing text field stay silent, exactly matching the
  script's behavior (plain-text mode never calls `speakResponseAloud`).
- `RECORD_AUDIO` is requested on demand, the first time the mic button
  is tapped, not eagerly at launch alongside `POST_NOTIFICATIONS` — it
  is a sensitive permission and voice is an opt-in feature.
- The confirmation dialog is a single new state field on the existing
  `AskGeminiUiState` (`pendingVoiceTranscript: String?`), not a separate
  voice-specific state machine. The flow is a small linear sequence
  (listen -> confirm -> send -> speak), not complex enough to justify a
  parallel state type.

## Scope

In scope:
- Voice capture via `SpeechRecognizer`, replacing the script's
  `termux-speech-to-text` shell-out.
- In-app confirmation UI (Compose `AlertDialog`, scrollable transcript
  body, Send/Discard actions) replacing the script's `termux-dialog`
  confirm step. Same intent: show the transcript, let the user accept
  or discard it unchanged before it is sent.
- Spoken replies via `TextToSpeech`, replacing `termux-tts-speak`, for
  voice-originated replies only.
- Reuses the Phase 1 `GeminiRepository`/`AskGeminiViewModel` for the
  actual Gemini call — voice is a new input/output surface on the same
  data layer, not a parallel path.
- `RECORD_AUDIO` runtime permission, requested on first mic tap.

Out of scope (deferred to later phases per `roadmap.md`):
- `NotificationListenerService`, `AccessibilityService`, background or
  foreground service, automations (Phases 3-4).
- Compose UI tests for the new dialog/mic button — still deferred until
  screens stabilize, per the Testing section of `CLAUDE.md` (unchanged
  from the Phase 1 decision).
- Any change to the typed-prompt flow's behavior or notification title.

## Architecture

```
android-app/app/src/main/java/com/ameyapb/androidexp/
  data/voice/
    VoiceRecognizer.kt        interface: suspend fun listen(): Result<String>
    VoiceRecognizerImpl.kt    wraps SpeechRecognizer + RecognitionListener via
                               suspendCancellableCoroutine; checks
                               SpeechRecognizer.isRecognitionAvailable() first;
                               top-level internal fun
                               describeVoiceRecognitionError(errorCode: Int): String
                               maps SpeechRecognizer.ERROR_* to human-readable text
    GeminiSpeaker.kt          interface: fun speak(text: String)
    GeminiSpeakerImpl.kt      wraps TextToSpeech; no-ops with a logged warning if
                               init fails or the engine is unavailable
    VoiceConstants.kt         recognizer locale, TTS utterance id, and any other
                               literal shared between the two impls
  di/
    AppModule.kt               (edit) two more @Binds @Singleton bindings:
                               VoiceRecognizer -> VoiceRecognizerImpl,
                               GeminiSpeaker -> GeminiSpeakerImpl
  ui/askgemini/
    AskGeminiUiState.kt        (edit) + isListening: Boolean,
                               + pendingVoiceTranscript: String?
    AskGeminiViewModel.kt      (edit) + onMicClicked(), onVoiceTranscriptConfirmed(),
                               onVoiceTranscriptDiscarded(); send-and-handle-result
                               logic extracted into a shared private suspend fun
                               taking speakReplyAloud: Boolean, used by both the
                               existing onSendClicked (false) and the new confirmed-
                               voice path (true)
    AskGeminiScreen.kt         (edit) + mic Button ("Speak"/"Listening..."), RECORD_AUDIO
                               permission check + rememberLauncherForActivityResult,
                               + AlertDialog shown when pendingVoiceTranscript != null
  AndroidManifest.xml          (edit) + RECORD_AUDIO permission
```

`VoiceRecognizer` and `GeminiSpeaker` are the two structural additions,
mirroring why `GeminiClient` exists in Phase 1: `SpeechRecognizer` and
`TextToSpeech` are not designed to be substituted directly, so
`AskGeminiViewModel` needs fakeable interfaces in front of them to keep
its own tests on fakes rather than a mocking library.

## Data flow

```
User taps mic button
  -> Screen checks/requests RECORD_AUDIO (first tap only)
  -> granted -> ViewModel.onMicClicked(): isListening=true, errorMessage=null
  -> VoiceRecognizer.listen()
       success, blank transcript -> isListening=false, errorMessage="Heard nothing."
       success, non-blank transcript -> isListening=false, pendingVoiceTranscript=transcript
       failure -> isListening=false, errorMessage=mapped error text
  -> Screen shows AlertDialog with the transcript while pendingVoiceTranscript != null
       Discard (or dismiss) -> ViewModel.onVoiceTranscriptDiscarded(): pendingVoiceTranscript=null
       Send -> ViewModel.onVoiceTranscriptConfirmed(): pendingVoiceTranscript=null,
               shared send-and-handle-result(transcript, speakReplyAloud=true)
  -> GeminiRepository.sendPrompt() (same call typed prompts use)
       success -> replyText updated, GeminiNotifier.notify(replyText),
                  GeminiSpeaker.speak(replyText) (voice path only)
       failure -> errorMessage set (same mapping as the typed-prompt path)
```

## Error handling

- Microphone permission denied: the mic button simply remains tappable
  to retry; no persistent error state is introduced for this case, kept
  consistent with how `POST_NOTIFICATIONS` denial is handled today
  (both degrade silently rather than nagging).
- `SpeechRecognizer` unavailable on the device, or a recognition error
  (no match, network, audio, etc.): mapped by
  `describeVoiceRecognitionError` to a message surfaced through the
  existing `errorMessage` field, no new UI element.
- Empty transcript ("heard nothing"): matches the script's
  `confirmPromptWithUser`, which logs and skips confirmation entirely
  rather than showing a dialog for nothing to confirm.
- `TextToSpeech` init failure or missing engine: `GeminiSpeakerImpl`
  degrades silently (logs a warning), never blocks or errors the
  on-screen/notification reply already shown, matching
  `GeminiNotifierImpl`'s and the script's `speakResponseAloud` pattern.
- Discarding a transcript (button or dialog dismiss) never sends
  anything to Gemini, matching the script's "No" path exactly.

## Testing

- `VoiceRecognizerImplTest` (Robolectric, `ShadowSpeechRecognizer`):
  successful listen returns the transcript, an error code maps to the
  correct message via `describeVoiceRecognitionError`, recognizer
  unavailable is reported before any listen attempt starts.
- `GeminiSpeakerImplTest` (Robolectric, `ShadowTextToSpeech`): `speak()`
  invokes the underlying engine with the given text; a failed/unready
  init does not throw.
- `AskGeminiViewModelTest` (extended with new `FakeVoiceRecognizer`,
  `FakeGeminiSpeaker`): successful listen sets
  `pendingVoiceTranscript`; empty transcript sets `errorMessage` and
  leaves `pendingVoiceTranscript` null; listen failure sets
  `errorMessage`; confirming sends the prompt, notifies, and speaks;
  discarding clears state without sending; a typed `onSendClicked` send
  never calls `GeminiSpeaker.speak`.
- No Compose UI tests, per the Phase 1 precedent restated in Scope
  above.

## Dependencies

No new external dependencies. `SpeechRecognizer` and `TextToSpeech` are
platform APIs (`android.speech.*`), already available without adding
anything to `libs.versions.toml`. The mic button reuses the existing
Material 3 `Button` composable (plain text label), so no icon library is
added.

## Implementation phasing

Per `CLAUDE.md`'s rule against executing multi-phase plans in one
uninterrupted run, the implementation plan for this spec is split into
two sub-phases with a check-in between them (roughly 5-8 files each,
per `CLAUDE.md`'s per-session ceiling):

- **2a — capture and confirm**: `VoiceRecognizer`/`VoiceRecognizerImpl`,
  `AskGeminiUiState`/`AskGeminiViewModel` wiring for
  `onMicClicked`/`onVoiceTranscriptConfirmed`/`onVoiceTranscriptDiscarded`,
  the mic button + `AlertDialog` + `RECORD_AUDIO` permission flow in
  `AskGeminiScreen`, the manifest permission, and the DI binding.
  Confirmed transcripts already send through the existing (silent)
  path at the end of this sub-phase.
- **2b — spoken replies and full test coverage**: `GeminiSpeaker`/
  `GeminiSpeakerImpl`, wiring `speakReplyAloud` into the shared
  send-and-handle-result function, the DI binding, and the Robolectric
  tests for both new wrapper classes plus the expanded
  `AskGeminiViewModelTest` coverage listed under Testing above.

**2a implementation note (2026-09-15):** no separate `VoiceConstants.kt`
file was created. `VoiceRecognizerImpl`'s one literal
(`VOICE_RECOGNITION_UNAVAILABLE_MESSAGE`) is a private const directly in
that file, matching how `GeminiRepositoryImpl` keeps its own private
constants rather than pulling in a shared-constants file for a single
consumer — per YAGNI, nothing is actually shared between
`VoiceRecognizer` and `GeminiSpeaker` yet. 2b should follow the same
rule: only introduce `VoiceConstants.kt` if a literal turns out to be
genuinely shared between the two wrappers, not just co-located by
topic. Also, Robolectric's `SpeechRecognizer` shadow dispatches
listener registration through the main-thread `Handler`, so any
Robolectric test that triggers a `RecognitionListener`/`TextToSpeech`
callback needs `shadowOf(Looper.getMainLooper()).idle()` after
`runCurrent()` and before triggering the callback — confirmed by an
`UnExecutedRunnablesException` hint in a real 2a test failure before
this was added.

## Documentation updates required

Tracked here for the implementation plan; not performed as part of
writing this spec:
- `CLAUDE.md` Architecture Overview (native Android app section):
  document `data/voice/` and the new `AskGeminiScreen`/`ViewModel`
  behavior once built, per the file's own instruction to document new
  components as they're added.
- `roadmap.md`: mark Phase 2 as implemented once both sub-phases land,
  matching how Phase 1's status line was updated.
