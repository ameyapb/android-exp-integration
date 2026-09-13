# Design: Voice-triggered Gemini queries via a Termux:Widget shortcut

Date: 2026-09-13

## Problem

`ask-gemini.js` works, but typing `node ask-gemini.js "<prompt>"` on the
phone's touch keyboard every time is a hassle. The user wants to reduce this
to: tap a home-screen icon, speak the prompt, get Gemini's reply as a
notification -- no typing at all.

This was explicitly deferred as future work in the prior design
(`2026-09-13-gemini-backend-design.md`, "Out of scope: Voice input/output").

## Decision

Add an optional `--voice` flag to the existing `ask-gemini.js` entry point
that captures speech via Termux:API's `termux-speech-to-text`, shows the
transcript, and asks for confirmation before sending it to Gemini. Pair this
with a new `shortcuts/ask-gemini-voice.sh` script, deployed as a
Termux:Widget home-screen shortcut, so the whole flow is a single tap.

The existing `node ask-gemini.js "text"` path is untouched.

## Scope

In scope:
- `--voice` flag on `ask-gemini.js`: records and transcribes speech via
  `termux-speech-to-text`, prints the transcript, prompts for
  confirmation (`y`/`N`) via stdin, then reuses the existing
  `sendPromptToGemini` / `displayResultToUser` functions unchanged.
- Graceful handling of: empty/cancelled transcript (clean exit, no API
  call), user declining confirmation (clean exit, no API call),
  `termux-speech-to-text` unavailable or failing (clear error message,
  mirroring `describeGeminiApiError`'s style).
- New `shortcuts/ask-gemini-voice.sh`, tracked in git, that `cd`s into the
  repo and runs `node ask-gemini.js --voice`.
- Setup documentation (README.md, CLAUDE.md) for installing Termux:API and
  Termux:Widget, and symlinking the shortcut into `~/.shortcuts/`.
- Tests for the new voice-capture and confirmation logic (mocking
  `execFile` and stdin, consistent with how the existing notification call
  is tested as an external boundary).

Out of scope (future iterations):
- Text-to-speech for the reply -- stdout + notification stays as the only
  output.
- Wake-word or always-listening capture.
- Any change to the non-voice text path or to `sendPromptToGemini`'s
  interface.
- A fully headless/backgrounded widget -- tapping it still opens a Termux
  terminal session, it just removes typing.

## Architecture

`ask-gemini.js` gains one new concern alongside the existing two
(send-to-Gemini, display-result): **capturing a voice prompt**.

1. **Capturing the prompt** (`captureVoicePrompt`, new): shells out to
   `termux-speech-to-text` via `execFile` (same pattern as
   `displayResultToUser`'s `termux-notification` call), returning the
   transcribed text. Throws a human-readable error if the command is
   unavailable or fails.
2. **Confirming the prompt** (`confirmPromptWithUser`, new): prints the
   transcript, reads a `y`/`N` line from stdin (via `readline`), and
   returns a boolean. Empty transcript short-circuits to "not confirmed"
   without prompting.
3. **Sending the prompt** (`sendPromptToGemini`, existing): unchanged.
4. **Displaying the result** (`displayResultToUser`, existing): unchanged.

`main()` branches near the top: if `process.argv` includes `--voice`,
route through capture -> confirm -> send -> display; otherwise keep the
existing text-argument path.

## Data flow

```
[widget tap] -> shortcuts/ask-gemini-voice.sh -> node ask-gemini.js --voice
  -> captureVoicePrompt(): termux-speech-to-text -> transcript
  -> confirmPromptWithUser(transcript): print + read stdin y/N
       -> not confirmed: print "Cancelled." and exit 0
       -> confirmed: sendPromptToGemini(transcript) -> reply
                     -> displayResultToUser(reply)
```

## Error handling

- `termux-speech-to-text` missing or failing: caught in
  `captureVoicePrompt`, converted to a clear message telling the user to
  install Termux:API (`pkg install termux-api` plus the companion Android
  app), mirroring how `describeGeminiApiError` reports Gemini failures.
  Non-fatal for the rest of the app -- exits with a clear error, same as
  the missing-API-key path today.
- Empty transcript (silence, or speech-to-text returns nothing): treated
  as "not confirmed", clean exit, no Gemini call.
- User declines confirmation: clean exit, no Gemini call, no error.
- All existing error handling for `sendPromptToGemini` and
  `displayResultToUser` is unchanged.

## Dependencies

No new npm dependencies. `termux-speech-to-text` is a Termux:API shell
command (same family as the already-used `termux-notification`), invoked
via the existing `child_process.execFile`, not an npm package.

New system-level (non-npm) dependency requiring user setup on the phone:
- **Termux:API** app + `pkg install termux-api` (provides
  `termux-speech-to-text`; `termux-notification` already assumes this is
  installed, so this formalizes an existing implicit dependency).
- **Termux:Widget** app (provides the home-screen shortcut mechanism that
  launches `shortcuts/ask-gemini-voice.sh`).

## Testing

- `captureVoicePrompt`: mocks `execFile` to verify (a) a successful
  transcript is returned trimmed, (b) a failure produces a clear,
  human-readable error.
- `confirmPromptWithUser`: verifies (a) an empty transcript short-circuits
  to `false` without reading stdin, (b) `y`/`yes` input resolves `true`,
  (c) anything else (including empty input) resolves `false`.
- `main()` voice branch: verifies the capture -> confirm -> send -> display
  sequence wires together correctly and that a cancelled confirmation
  never calls `sendPromptToGemini`.
- Existing tests for `sendPromptToGemini`, `describeGeminiApiError`, and
  `displayResultToUser` remain unchanged and must keep passing.

## Documentation updates required

- `CLAUDE.md`: document the `--voice` flag, the `shortcuts/` folder and
  its purpose, and the Termux:API/Termux:Widget setup steps, under
  "Architecture Overview" and a new setup subsection.
- `README.md` (if present) / setup instructions: add Termux:API and
  Termux:Widget install steps and the symlink command for
  `~/.shortcuts/`.
- `package.json`: no changes expected (still invoked via `node
  ask-gemini.js`, just with an added flag).
