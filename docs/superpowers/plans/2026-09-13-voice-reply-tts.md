# Voice-Mode Reply Text-to-Speech Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Speak Gemini's reply aloud during `--voice` mode using Termux:API's `termux-tts-speak`, so the voice interaction is hands-free end to end.

**Architecture:** Add one new function, `speakResponseAloud`, following the exact shape of the existing `captureVoicePrompt` Termux:API wrapper (injectable `execFileFn`, resolves on success, warns-and-resolves on failure rather than rejecting). Wire it into `runVoiceFlow` as a new injectable dependency, called after `displayResultToUserFn`. Plain-text mode is untouched.

**Tech Stack:** Node.js, `node:test` + `node:assert/strict` (existing test runner, no new libraries), Termux:API's `termux-tts-speak` CLI (no new dependency — same family as the already-used `termux-speech-to-text` and `termux-notification`).

## Global Constraints

- No new npm dependencies (project only uses `@google/genai` and `dotenv`).
- No new CLI flags — TTS is implicit in `--voice` mode.
- Failures in `termux-tts-speak` must warn to stderr and continue, never throw/reject — matches the existing `displayResultToUser` notification-failure pattern.
- No interrupt/cancel-speech control.
- Naming: long, self-explanatory names, no abbreviations (matches existing codebase style).
- All new constants must be named, not inline literals (`TERMUX_TTS_SPEAK_COMMAND`).
- Every new function needs a corresponding test in the same change.
- Full test suite (`npm test`) must pass before this is done.

---

### Task 1: Add `speakResponseAloud` with tests

**Files:**
- Modify: `ask-gemini.js` (add constant + function + exports)
- Test: `ask-gemini.test.js` (add two new tests)

**Interfaces:**
- Consumes: `execFile` from `child_process` (already imported in `ask-gemini.js:18`).
- Produces: `speakResponseAloud(textToSpeak, execFileFn = execFile) => Promise<void>` and `TERMUX_TTS_SPEAK_COMMAND` constant, both exported from `ask-gemini.js` for Task 2 and for tests.

- [ ] **Step 1: Write the failing tests**

Add to `ask-gemini.test.js`. First update the require block at the top of the file to include the two new exports:

```js
const {
  sendPromptToGemini,
  describeGeminiApiError,
  captureVoicePrompt,
  TERMUX_SPEECH_TO_TEXT_COMMAND,
  confirmPromptWithUser,
  runVoiceFlow,
  GEMINI_MODEL_NAME,
  speakResponseAloud,
  TERMUX_TTS_SPEAK_COMMAND,
} = require("./ask-gemini.js");
```

Then append these two tests at the end of the file:

```js
test("speakResponseAloud calls termux-tts-speak with the response text", async () => {
  const recordedCalls = [];
  const fakeExecFile = (command, args, callback) => {
    recordedCalls.push({ command, args });
    callback(null, "", "");
  };

  await speakResponseAloud("It's sunny.", fakeExecFile);

  assert.equal(recordedCalls.length, 1);
  assert.equal(recordedCalls[0].command, TERMUX_TTS_SPEAK_COMMAND);
  assert.deepEqual(recordedCalls[0].args, ["It's sunny."]);
});

test("speakResponseAloud warns and resolves when termux-tts-speak fails", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(new Error("command not found"), "", "");
  };
  const originalWarn = console.warn;
  const recordedWarnings = [];
  console.warn = (message) => recordedWarnings.push(message);

  try {
    await speakResponseAloud("It's sunny.", fakeExecFile);
  } finally {
    console.warn = originalWarn;
  }

  assert.equal(recordedWarnings.length, 1);
  assert.match(recordedWarnings[0], /could not speak response aloud/);
  assert.match(recordedWarnings[0], /command not found/);
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL — `speakResponseAloud` and `TERMUX_TTS_SPEAK_COMMAND` are not exported/defined yet (destructuring gives `undefined`, calling it throws "speakResponseAloud is not a function").

- [ ] **Step 3: Implement `speakResponseAloud`**

In `ask-gemini.js`, add the constant next to the other Termux command constants (near `TERMUX_SPEECH_TO_TEXT_COMMAND` at line 24):

```js
const TERMUX_TTS_SPEAK_COMMAND = "termux-tts-speak";
```

Add the function after `captureVoicePrompt` (after line 91):

```js
/**
 * Speaks the given text aloud via Termux:API's termux-tts-speak. Failures
 * are logged as warnings and never interrupt the CLI's normal output,
 * matching the existing notification-failure handling in displayResultToUser.
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

Add both to the `module.exports` block at the bottom of `ask-gemini.js`:

```js
module.exports = {
  sendPromptToGemini,
  describeGeminiApiError,
  captureVoicePrompt,
  TERMUX_SPEECH_TO_TEXT_COMMAND,
  confirmPromptWithUser,
  VOICE_CONFIRMATION_ACCEPTED_VALUES,
  runVoiceFlow,
  VOICE_FLAG_NAME,
  displayResultToUser,
  GEMINI_MODEL_NAME,
  speakResponseAloud,
  TERMUX_TTS_SPEAK_COMMAND,
};
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test`
Expected: PASS — all tests including the two new ones.

- [ ] **Step 5: Commit**

```bash
git add ask-gemini.js ask-gemini.test.js
git commit -m "Add speakResponseAloud for termux-tts-speak playback

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Wire `speakResponseAloud` into `runVoiceFlow`

**Files:**
- Modify: `ask-gemini.js:169-187` (`runVoiceFlow` function signature and body)
- Test: `ask-gemini.test.js` (extend the two existing `runVoiceFlow` tests, add one new test)

**Interfaces:**
- Consumes: `speakResponseAloud(textToSpeak, execFileFn) => Promise<void>` from Task 1.
- Produces: `runVoiceFlow` now accepts an additional injectable `speakResponseAloudFn` dependency (default `speakResponseAloud`); no change to its public call signature for existing callers (`main()` calls `runVoiceFlow(geminiClient)` with no options, unaffected).

- [ ] **Step 1: Write the failing test**

Add a new test to `ask-gemini.test.js`, after the existing `"runVoiceFlow sends the confirmed transcript to Gemini and displays the reply"` test:

```js
test("runVoiceFlow speaks the reply aloud after a confirmed prompt", async () => {
  const recordedSpokenText = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async () => ({ text: "It's sunny." }),
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's the weather",
    confirmPromptWithUserFn: async () => true,
    displayResultToUserFn: async () => {},
    speakResponseAloudFn: async (text) => {
      recordedSpokenText.push(text);
    },
  });

  assert.deepEqual(recordedSpokenText, ["It's sunny."]);
});

test("runVoiceFlow does not speak when the user does not confirm", async () => {
  const fakeGeminiClient = {
    models: {
      generateContent: async () => ({ text: "should not be reached" }),
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's the weather",
    confirmPromptWithUserFn: async () => false,
    displayResultToUserFn: async () => {
      throw new Error("displayResultToUserFn should not be called");
    },
    speakResponseAloudFn: async () => {
      throw new Error("speakResponseAloudFn should not be called");
    },
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL — the first new test fails because `recordedSpokenText` stays empty (`runVoiceFlow` never calls `speakResponseAloudFn`); the second new test passes trivially already (no regression risk there, but keep it since it locks in the "no side effects on decline" behavior going forward).

- [ ] **Step 3: Implement the wiring**

In `ask-gemini.js`, modify `runVoiceFlow` (currently lines 169-187):

```js
/**
 * Runs the voice input flow: capture a spoken prompt, confirm it with the
 * user, send it to Gemini if confirmed, display the reply, and speak it
 * aloud.
 *
 * @param {GoogleGenAI} geminiClient - The Gemini SDK client to send the request through.
 * @param {object} [dependencies] - Injectable dependencies, overridable in tests.
 * @param {typeof captureVoicePrompt} [dependencies.captureVoicePromptFn]
 * @param {typeof confirmPromptWithUser} [dependencies.confirmPromptWithUserFn]
 * @param {typeof displayResultToUser} [dependencies.displayResultToUserFn]
 * @param {typeof speakResponseAloud} [dependencies.speakResponseAloudFn]
 * @returns {Promise<void>}
 */
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test`
Expected: PASS — all tests including both new ones, and the two pre-existing `runVoiceFlow` tests (which don't pass a `speakResponseAloudFn` override) still pass because the real `speakResponseAloud` default will attempt to call `termux-tts-speak` via the real `execFile` — verify this doesn't hang or reject in the test environment; if `termux-tts-speak` is absent (as it will be on a PC), `execFile` invokes the callback with an error asynchronously and `speakResponseAloud` resolves anyway (per Task 1's warn-and-resolve behavior), so these tests still complete and pass, only printing an extra harmless warning to stderr.

- [ ] **Step 5: Commit**

```bash
git add ask-gemini.js ask-gemini.test.js
git commit -m "Speak Gemini replies aloud in --voice mode

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Update CLAUDE.md architecture documentation

**Files:**
- Modify: `CLAUDE.md` (Architecture Overview section)

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing consumed by later tasks (this is the final task).

- [ ] **Step 1: Update the Architecture Overview paragraph**

In `CLAUDE.md`, find the "Architecture Overview" section's paragraph describing `ask-gemini.js`'s components (it currently lists: calling Gemini, capturing a spoken prompt, confirming a transcript, and displaying the result). Add the new TTS component to that same paragraph, immediately after the mention of `confirmPromptWithUser`:

Replace:

```
capturing a spoken prompt via Termux:API's `termux-speech-to-text` (`captureVoicePrompt`, used when the `--voice` flag is passed), confirming a voice transcript with the user before sending it (`confirmPromptWithUser`), and displaying the result (stdout plus a `termux-notification` shell-out that degrades gracefully if unavailable).
```

With:

```
capturing a spoken prompt via Termux:API's `termux-speech-to-text` (`captureVoicePrompt`, used when the `--voice` flag is passed), confirming a voice transcript with the user before sending it (`confirmPromptWithUser`), displaying the result (stdout plus a `termux-notification` shell-out that degrades gracefully if unavailable), and, in `--voice` mode only, speaking the reply aloud via Termux:API's `termux-tts-speak` (`speakResponseAloud`, which likewise degrades gracefully if unavailable).
```

- [ ] **Step 2: Verify the full test suite still passes**

Run: `npm test`
Expected: PASS (no code changed in this task, this just confirms nothing was left broken from Task 2).

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "Document speakResponseAloud in architecture overview

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage:** `TERMUX_TTS_SPEAK_COMMAND` constant (Task 1), `speakResponseAloud` function with warn-and-continue error handling (Task 1), wiring into `runVoiceFlow` only, not plain-text mode (Task 2), tests for both the new function and the new wiring including the "not called on decline" case (Tasks 1-2), CLAUDE.md architecture doc update (Task 3, per CLAUDE.md's own rule to keep that section current). Widget script requires no change per the spec, so no task touches `shortcuts/ask-gemini-voice.sh`.
- **Placeholder scan:** none found — all steps have full code.
- **Type consistency:** `speakResponseAloud(textToSpeak, execFileFn = execFile) => Promise<void>` is defined identically in Task 1 and consumed identically in Task 2's `speakResponseAloudFn` default and JSDoc.
