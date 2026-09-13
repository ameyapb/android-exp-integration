# Text-to-speech for voice-mode replies

## Problem

`ask-gemini.js --voice` currently captures a spoken prompt, confirms it with the user, sends it to Gemini, and shows the reply as stdout text plus an Android notification. The user still has to read the reply off the phone screen. Since the request already came in by voice, the reply should be able to go out by voice too, closing the hands-free loop.

## Solution

When running in `--voice` mode, speak Gemini's reply aloud via Termux:API's `termux-tts-speak` command, in addition to the existing stdout print and notification. Plain-text mode (`node ask-gemini.js "prompt"`) is unaffected; speech is scoped to `--voice` mode only, since that's the only mode where the user is already interacting hands-free.

No new dependency is introduced. `termux-tts-speak` ships as part of Termux:API, which this project already depends on for `termux-speech-to-text`.

## Design

### New constant

```js
const TERMUX_TTS_SPEAK_COMMAND = "termux-tts-speak";
```

Placed alongside the existing Termux command constants (`TERMUX_NOTIFICATION_COMMAND`, `TERMUX_SPEECH_TO_TEXT_COMMAND`).

### New function: `speakResponseAloud`

Mirrors the shape of `captureVoicePrompt` (the existing Termux:API wrapper), for consistency with the codebase's established pattern for shelling out to Termux:API commands.

```js
/**
 * Speaks the given text aloud via Termux:API's termux-tts-speak. Failures are
 * logged as warnings and never interrupt the CLI's normal output, matching
 * the existing notification-failure handling in displayResultToUser.
 *
 * @param {string} textToSpeak - The text to speak aloud.
 * @param {typeof execFile} [execFileFn] - The execFile implementation to use; defaults to Node's child_process.execFile, overridable in tests.
 * @returns {Promise<void>} Resolves once the speech attempt finishes (success or failure).
 */
function speakResponseAloud(textToSpeak, execFileFn = execFile) {
  return new Promise((resolve) => {
    execFileFn(TERMUX_TTS_SPEAK_COMMAND, [textToSpeak], (execError) => {
      if (execError) {
        console.warn(
          `Warning: could not speak response aloud (${TERMUX_TTS_SPEAK_COMMAND} unavailable or failed): ${execError.message}`,
        );
      }
      resolve();
    });
  });
}
```

### Wiring into `runVoiceFlow`

`runVoiceFlow` gains a `speakResponseAloudFn` injectable dependency (matching the existing `captureVoicePromptFn` / `confirmPromptWithUserFn` / `displayResultToUserFn` pattern) and calls it after displaying the result:

```js
async function runVoiceFlow(
  geminiClient,
  {
    captureVoicePromptFn = captureVoicePrompt,
    confirmPromptWithUserFn = confirmPromptWithUser,
    displayResultToUserFn = displayResultToUser,
    speakResponseAloudFn = speakResponseAloud,
  } = {},
) {
  const transcriptText = await captureVoicePromptFn();
  const isConfirmed = await confirmPromptWithUserFn(transcriptText);

  if (!isConfirmed) {
    console.log("Cancelled.");
    return;
  }

  const geminiResponseText = await sendPromptToGemini(transcriptText, geminiClient);
  await displayResultToUserFn(geminiResponseText);
  await speakResponseAloudFn(geminiResponseText);
}
```

Plain-text mode in `main()` is untouched — no call to `speakResponseAloud` there.

### Error handling

Same "warn and continue" pattern already used for notification failures: if `termux-tts-speak` is missing or fails, log a warning to stderr and let the rest of the flow finish normally. No interrupt/stop control is added — replies are short, single-turn answers, so playback is left to finish on its own (matches Termux's own behavior of queuing/interrupting on repeated taps).

### Widget script

`shortcuts/ask-gemini-voice.sh` requires no changes — it already runs `node ask-gemini.js --voice`, and this change lives entirely inside `ask-gemini.js`'s voice flow. The existing Termux:Widget deployment gotcha (documented in `CLAUDE.md`: deploy via `cp`, not `ln -s`, and re-copy after every `git pull` that changes the script) still applies to future changes to that script, but there is nothing to re-copy for this change since the script's contents don't change.

## Testing

- `speakResponseAloud`: injected fake `execFileFn` to assert it's called with `TERMUX_TTS_SPEAK_COMMAND` and the reply text; a second case simulating a failing `execFileFn` to confirm it warns and resolves rather than rejecting.
- `runVoiceFlow`: extend existing tests to assert `speakResponseAloudFn` is called with the Gemini reply text after a confirmed prompt, and is *not* called when the user declines confirmation (`isConfirmed` false, early return).
- No changes needed to plain-text-mode tests in `main()`, since that path is untouched.

## Out of scope

- No new CLI flag; behavior is implicit in `--voice` mode.
- No interrupt/cancel-speech control.
- No TTS in plain-text mode.
