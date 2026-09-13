# Dialog-based voice confirmation and terminal auto-close

## Problem

Launching `ask-gemini.js --voice` from the Termux:Widget home-screen shortcut opens a plain Termux terminal window: the transcript confirmation is a typed `y/N` prompt, and the terminal session stays open indefinitely after the reply is shown, notified, and spoken. Neither is ideal for a one-tap, no-typing widget flow — the terminal is not a friendly UI surface, and leftover sessions accumulate as orphaned processes over repeated widget taps.

Termux:Widget shortcuts always launch by spawning a terminal session to run the script; there is no way to make a shortcut open a graphical overlay directly instead. Within that constraint, two improvements are in scope:

1. Replace the typed `y/N` confirmation with a native Android dialog.
2. Make the widget-launched terminal session always close itself, after a brief readable pause, instead of staying open.

## Solution

### 1. Dialog-based confirmation via `termux-dialog`

Add a new confirmation path for `--voice` mode that shows the transcript in an editable native dialog (`termux-dialog text`) instead of printing it and reading a typed answer from stdin. The user can review — and optionally correct a misheard word in — the transcript before it's sent to Gemini, using OK/Cancel buttons rather than typing `y`/`N`.

`termux-dialog` is part of Termux:API, already a project dependency (used today by `termux-speech-to-text` and `termux-tts-speak`), so no new dependency is introduced.

This change is scoped to `--voice` mode only. Plain-prompt mode (`node ask-gemini.js "prompt"`) has no confirmation step today and is unaffected — it's normally run manually from an already-open terminal, not the widget, so adding a dialog there would only add friction.

If `termux-dialog` is unavailable or fails, fall back to the existing stdin `y/N` prompt (`confirmPromptWithUser`) rather than failing the run — this keeps `--voice` usable from a plain SSH/adb shell, where there's no Termux:API dialog surface to render into, matching the graceful-degradation pattern already used for `termux-notification` and `termux-tts-speak`.

#### New constant

```js
const TERMUX_DIALOG_COMMAND = "termux-dialog";
const TERMUX_DIALOG_TITLE = "Confirm prompt";
const TERMUX_DIALOG_CANCELLED_CODE = -1;
```

Placed alongside the existing Termux command constants.

#### New function: `confirmPromptWithUserViaDialog`

Mirrors the shape of `captureVoicePrompt` and `speakResponseAloud` (the existing Termux:API wrappers), for consistency with the codebase's established pattern for shelling out to Termux:API commands.

```js
/**
 * Shows the transcript in an editable native dialog via Termux:API's
 * termux-dialog and asks the user to confirm (optionally correcting the
 * text) before sending it to Gemini.
 *
 * @param {string} transcriptText - The transcript to confirm.
 * @param {typeof execFile} [execFileFn] - The execFile implementation to use; defaults to Node's child_process.execFile, overridable in tests.
 * @returns {Promise<{ confirmed: boolean, promptText: string }>} Whether the user confirmed, and the (possibly edited) text to send.
 * @throws {Error} If termux-dialog is unavailable or returns unparseable output; callers should fall back to confirmPromptWithUser on failure.
 */
function confirmPromptWithUserViaDialog(transcriptText, execFileFn = execFile) {
  return new Promise((resolve, reject) => {
    execFileFn(
      TERMUX_DIALOG_COMMAND,
      ["text", "-t", TERMUX_DIALOG_TITLE, "-i", transcriptText],
      (execError, stdout) => {
        if (execError) {
          reject(new Error(`${TERMUX_DIALOG_COMMAND} unavailable or failed: ${execError.message}`));
          return;
        }

        let parsedResult;
        try {
          parsedResult = JSON.parse(stdout);
        } catch (parseError) {
          reject(new Error(`${TERMUX_DIALOG_COMMAND} returned unparseable output: ${parseError.message}`));
          return;
        }

        if (parsedResult.code === TERMUX_DIALOG_CANCELLED_CODE || !parsedResult.text) {
          resolve({ confirmed: false, promptText: "" });
          return;
        }

        resolve({ confirmed: true, promptText: parsedResult.text.trim() });
      },
    );
  });
}
```

#### Wiring into `runVoiceFlow`

`runVoiceFlow` tries the dialog confirmation first when launched from the widget, and falls back to the stdin prompt if the dialog call throws:

```js
async function confirmPromptWithFallback(transcriptText) {
  if (!transcriptText) {
    console.log("Heard nothing, cancelling.");
    return { confirmed: false, promptText: "" };
  }

  try {
    return await confirmPromptWithUserViaDialog(transcriptText);
  } catch (dialogError) {
    console.warn(`Warning: falling back to text confirmation (${dialogError.message})`);
    const confirmed = await confirmPromptWithUser(transcriptText);
    return { confirmed, promptText: transcriptText };
  }
}
```

`runVoiceFlow` calls `confirmPromptWithFallback` instead of `confirmPromptWithUser` directly, and sends `promptText` (not the original `transcriptText`) to Gemini once confirmed, so an edited correction is respected:

```js
async function runVoiceFlow(
  geminiClient,
  {
    captureVoicePromptFn = captureVoicePrompt,
    confirmPromptWithFallbackFn = confirmPromptWithFallback,
    displayResultToUserFn = displayResultToUser,
    speakResponseAloudFn = speakResponseAloud,
  } = {},
) {
  const transcriptText = await captureVoicePromptFn();
  const { confirmed, promptText } = await confirmPromptWithFallbackFn(transcriptText);

  if (!confirmed) {
    console.log("Cancelled.");
    return;
  }

  const geminiResponseText = await sendPromptToGemini(promptText, geminiClient);
  await displayResultToUserFn(geminiResponseText);
  await speakResponseAloudFn(geminiResponseText);
}
```

`confirmPromptWithUser` (the existing stdin-based function) is kept as-is and reused inside the fallback path — not removed, per DRY.

### 2. Always-closing terminal session

After the result is shown (success or error path), the script should pause briefly so the notification/dialog/TTS has time to land, then always exit — the terminal session must never be left open, to avoid orphaned processes accumulating across repeated widget taps. This is a hard requirement (confirmed with the user): every widget-launched session gets closed, on both success and error, not just left to the user to swipe away.

#### New constant

```js
const WIDGET_SESSION_CLOSE_DELAY_MS = 3000;
```

#### `main()` change

After the existing success/error display logic finishes (both the plain-prompt and `--voice` branches), wait `WIDGET_SESSION_CLOSE_DELAY_MS` before returning from `main()`, so the process exit isn't instantaneous:

```js
await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
```

This applies uniformly whether the run succeeded or hit an error — matching "always kill, but pause first." It's a fixed delay (not a keypress-wait), since a Termux:Widget session has no reliable keyboard focus to wait on.

#### `shortcuts/ask-gemini-voice.sh` change

Termux:Widget does not document a public broadcast/intent for a script to force-close its own launching session — unlike the `cp`-vs-symlink shortcut-listing gotcha already recorded in `CLAUDE.md`, this isn't something to guess at from general Android `am` knowledge, since an unverified intent action could silently no-op and leave the hard "always killed" requirement unmet.

The implementation step for this part of the design is therefore, in order:

1. Ensure `main()`'s exit is prompt and clean (the pause in section 2, then return normally with no lingering handles/timers so the Node process itself always terminates on its own).
2. Test on-device whether a Termux:Widget session actually dismisses once its underlying process exits. This needs verification rather than assumption — it is plausible Termux:Widget already does this reliably, in which case step 1 alone satisfies the requirement and the wrapper script needs no further change.
3. If on-device testing shows the session is *not* reliably dismissed (window stays open after the process ends), research Termux:Widget's actual documented close mechanism at implementation time and add it to the wrapper script, updating this spec with the confirmed command before shipping it. Do not guess an `am` intent action without confirming it against Termux:Widget's source or documentation first.

```sh
#!/data/data/com.termux/files/usr/bin/sh

cd ~/android-exp-integration || exit 1
node ask-gemini.js --voice
```

No content change to the wrapper is committed to in this spec beyond what's already there — the auto-close behavior comes from `main()` exiting promptly (section 2) plus whatever on-device verification finds about Termux:Widget's own dismissal behavior.

## Testing

- `confirmPromptWithUserViaDialog`: injected fake `execFileFn` cases for (a) OK with unedited text, (b) OK with edited text, (c) Cancel (`code: -1`), (d) OK with empty text, (e) a failing/unavailable `execFileFn` rejecting the promise.
- `confirmPromptWithFallback`: (a) empty transcript short-circuits without calling the dialog, (b) dialog succeeds and its result is passed through, (c) dialog throws and the stdin fallback (`confirmPromptWithUser`) is called instead, using the original transcript.
- `runVoiceFlow`: extend existing tests to assert `sendPromptToGemini` is called with the (possibly edited) `promptText` from confirmation, not always the raw transcript.
- Widget auto-close delay: since `setTimeout`-based pausing is timing-only and not meaningfully unit-testable, no test is added for the delay itself; this is verified manually on-device per the existing "test on real hardware" pattern used for other Termux:API integrations in this project.

## Out of scope

- No dialog/confirmation added to plain-prompt mode.
- No change to the existing `confirmPromptWithUser` stdin function itself — it's reused unchanged as the fallback.
- No general-purpose "overlay app" — this stays within Termux:Widget's terminal-launch model, per the constraint noted in Problem.
