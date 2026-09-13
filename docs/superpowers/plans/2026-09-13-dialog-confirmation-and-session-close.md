# Dialog-Based Voice Confirmation and Terminal Auto-Close Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `--voice` mode's typed `y/N` transcript confirmation with a native Android dialog (`termux-dialog`, with graceful fallback to the existing stdin prompt), and make the widget-launched terminal session always close itself shortly after finishing, on both success and error.

**Architecture:** All changes live in the existing single-file CLI (`ask-gemini.js`) plus its test file (`ask-gemini.test.js`), following the codebase's established pattern of small Termux:API wrapper functions with injectable `execFileFn` dependencies for testability. No new files, no new npm dependencies (`termux-dialog` ships with Termux:API, already used by `captureVoicePrompt` and `speakResponseAloud`).

**Tech Stack:** Node.js, `node:test` + `node:assert/strict` (existing test runner, no new test framework), Termux:API (`termux-dialog`), Termux:Widget shell wrapper (`shortcuts/ask-gemini-voice.sh`).

## Global Constraints

- No new npm dependency — `termux-dialog` is part of Termux:API, already a project dependency in spirit (used by `termux-speech-to-text`, `termux-tts-speak`).
- Dialog confirmation is scoped to `--voice` mode only; plain-prompt mode is unaffected.
- If `termux-dialog` is unavailable or fails, fall back to the existing stdin `y/N` prompt (`confirmPromptWithUser`) rather than failing the run.
- The edited dialog text (if the user changes it) must be sent to Gemini, not the original transcript.
- Every widget-launched session must always close itself, after a brief pause, on both success and error paths — this is a hard requirement, not best-effort.
- `WIDGET_SESSION_CLOSE_DELAY_MS` pause applies uniformly to both plain-prompt and `--voice` modes in `main()`.
- No test is added for the `setTimeout`-based close delay itself (timing-only, not meaningfully unit-testable) — verified manually on-device instead.
- Follow existing code conventions exactly: constants in `UPPER_SNAKE_CASE` near the top of the file, JSDoc on every exported function, injectable `execFileFn`/`readLineFn`-style dependencies defaulting to the real implementation, `module.exports` list kept in sync with what tests import.

---

## Task 1: Add `termux-dialog`-based confirmation with stdin fallback

**Files:**
- Modify: `ask-gemini.js` (constants near top; new functions after `confirmPromptWithUser`/`readOneLineFromStdin`; `runVoiceFlow` signature and body; `module.exports`)
- Test: `ask-gemini.test.js`

**Interfaces:**
- Consumes: existing `execFile` (from `child_process`), existing `confirmPromptWithUser(transcriptText, readLineFn)` (unchanged, reused as fallback).
- Produces:
  - `TERMUX_DIALOG_COMMAND` (string constant, `"termux-dialog"`)
  - `TERMUX_DIALOG_TITLE` (string constant, `"Confirm prompt"`)
  - `TERMUX_DIALOG_CANCELLED_CODE` (number constant, `-1`)
  - `confirmPromptWithUserViaDialog(transcriptText, execFileFn = execFile) => Promise<{ confirmed: boolean, promptText: string }>` — rejects if `termux-dialog` is unavailable/fails or returns unparseable JSON.
  - `confirmPromptWithFallback(transcriptText, { confirmPromptWithUserViaDialogFn, confirmPromptWithUserFn } = {}) => Promise<{ confirmed: boolean, promptText: string }>`
  - `runVoiceFlow` now accepts `confirmPromptWithFallbackFn` (replacing direct use of `confirmPromptWithUserFn` for the confirmation step) and sends `promptText` (not raw transcript) to Gemini.

- [ ] **Step 1: Write failing tests for `confirmPromptWithUserViaDialog`**

Add to `ask-gemini.test.js`, after the existing `confirmPromptWithUser` tests (around line 152, before the `runVoiceFlow` tests):

```js
test("confirmPromptWithUserViaDialog resolves confirmed with unedited text on OK", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, JSON.stringify({ text: "what's 2+2", code: 0 }));
  };

  const result = await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.deepEqual(result, { confirmed: true, promptText: "what's 2+2" });
});

test("confirmPromptWithUserViaDialog resolves confirmed with edited text on OK", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, JSON.stringify({ text: "what's 3+3", code: 0 }));
  };

  const result = await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.deepEqual(result, { confirmed: true, promptText: "what's 3+3" });
});

test("confirmPromptWithUserViaDialog resolves not confirmed on cancel", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, JSON.stringify({ code: -1 }));
  };

  const result = await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.deepEqual(result, { confirmed: false, promptText: "" });
});

test("confirmPromptWithUserViaDialog resolves not confirmed on empty edited text", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, JSON.stringify({ text: "", code: 0 }));
  };

  const result = await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.deepEqual(result, { confirmed: false, promptText: "" });
});

test("confirmPromptWithUserViaDialog rejects when termux-dialog is unavailable", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(new Error("command not found"));
  };

  await assert.rejects(
    () => confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile),
    /termux-dialog unavailable or failed/,
  );
});

test("confirmPromptWithUserViaDialog rejects when termux-dialog returns unparseable output", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, "not json");
  };

  await assert.rejects(
    () => confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile),
    /termux-dialog returned unparseable output/,
  );
});

test("confirmPromptWithUserViaDialog calls termux-dialog with the transcript pre-filled", async () => {
  const recordedCalls = [];
  const fakeExecFile = (command, args, callback) => {
    recordedCalls.push({ command, args });
    callback(null, JSON.stringify({ text: "what's 2+2", code: 0 }));
  };

  await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.equal(recordedCalls.length, 1);
  assert.equal(recordedCalls[0].command, TERMUX_DIALOG_COMMAND);
  assert.deepEqual(recordedCalls[0].args, ["text", "-t", TERMUX_DIALOG_TITLE, "-i", "what's 2+2"]);
});
```

Add `confirmPromptWithUserViaDialog` and `TERMUX_DIALOG_COMMAND` and `TERMUX_DIALOG_TITLE` to the destructured `require("./ask-gemini.js")` import block at the top of the test file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL with errors like "confirmPromptWithUserViaDialog is not a function" (it doesn't exist yet).

- [ ] **Step 3: Implement `confirmPromptWithUserViaDialog` and its constants**

In `ask-gemini.js`, add these constants near the other Termux command constants (after `TERMUX_TTS_SPEAK_COMMAND`):

```js
const TERMUX_DIALOG_COMMAND = "termux-dialog";
const TERMUX_DIALOG_TITLE = "Confirm prompt";
const TERMUX_DIALOG_CANCELLED_CODE = -1;
```

Add this function after `confirmPromptWithUser` (before `readOneLineFromStdin`):

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

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test`
Expected: PASS for all `confirmPromptWithUserViaDialog` tests.

- [ ] **Step 5: Commit**

```bash
git add ask-gemini.js ask-gemini.test.js
git commit -m "$(cat <<'EOF'
Add termux-dialog based confirmation for voice mode

Shows the transcript in an editable native dialog instead of requiring
it to be read separately, with OK/Cancel replacing the typed y/N answer.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Write failing tests for `confirmPromptWithFallback`**

Add to `ask-gemini.test.js`, after the `confirmPromptWithUserViaDialog` tests:

```js
test("confirmPromptWithFallback resolves not confirmed without calling the dialog when transcript is empty", async () => {
  let dialogCalled = false;
  const result = await confirmPromptWithFallback("", {
    confirmPromptWithUserViaDialogFn: async () => {
      dialogCalled = true;
      return { confirmed: true, promptText: "should not happen" };
    },
  });

  assert.equal(dialogCalled, false);
  assert.deepEqual(result, { confirmed: false, promptText: "" });
});

test("confirmPromptWithFallback returns the dialog result when the dialog succeeds", async () => {
  const result = await confirmPromptWithFallback("what's 2+2", {
    confirmPromptWithUserViaDialogFn: async () => ({ confirmed: true, promptText: "what's 3+3" }),
  });

  assert.deepEqual(result, { confirmed: true, promptText: "what's 3+3" });
});

test("confirmPromptWithFallback falls back to the stdin prompt when the dialog throws", async () => {
  const result = await confirmPromptWithFallback("what's 2+2", {
    confirmPromptWithUserViaDialogFn: async () => {
      throw new Error("termux-dialog unavailable or failed: command not found");
    },
    confirmPromptWithUserFn: async (transcriptText) => {
      assert.equal(transcriptText, "what's 2+2");
      return true;
    },
  });

  assert.deepEqual(result, { confirmed: true, promptText: "what's 2+2" });
});

test("confirmPromptWithFallback falls back to a declined stdin prompt when the dialog throws", async () => {
  const result = await confirmPromptWithFallback("what's 2+2", {
    confirmPromptWithUserViaDialogFn: async () => {
      throw new Error("termux-dialog unavailable or failed: command not found");
    },
    confirmPromptWithUserFn: async () => false,
  });

  assert.deepEqual(result, { confirmed: false, promptText: "what's 2+2" });
});
```

Add `confirmPromptWithFallback` to the destructured import block.

- [ ] **Step 7: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL with "confirmPromptWithFallback is not a function".

- [ ] **Step 8: Implement `confirmPromptWithFallback`**

Add this function in `ask-gemini.js`, directly after `confirmPromptWithUserViaDialog`:

```js
/**
 * Confirms a voice transcript with the user, preferring the native
 * termux-dialog confirmation and falling back to the stdin y/N prompt if
 * the dialog is unavailable or fails.
 *
 * @param {string} transcriptText - The transcript to confirm.
 * @param {object} [dependencies] - Injectable dependencies, overridable in tests.
 * @param {typeof confirmPromptWithUserViaDialog} [dependencies.confirmPromptWithUserViaDialogFn]
 * @param {typeof confirmPromptWithUser} [dependencies.confirmPromptWithUserFn]
 * @returns {Promise<{ confirmed: boolean, promptText: string }>} Whether the user confirmed, and the (possibly edited) text to send.
 */
async function confirmPromptWithFallback(
  transcriptText,
  {
    confirmPromptWithUserViaDialogFn = confirmPromptWithUserViaDialog,
    confirmPromptWithUserFn = confirmPromptWithUser,
  } = {},
) {
  if (!transcriptText) {
    console.log("Heard nothing, cancelling.");
    return { confirmed: false, promptText: "" };
  }

  try {
    return await confirmPromptWithUserViaDialogFn(transcriptText);
  } catch (dialogError) {
    console.warn(`Warning: falling back to text confirmation (${dialogError.message})`);
    const confirmed = await confirmPromptWithUserFn(transcriptText);
    return { confirmed, promptText: transcriptText };
  }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `npm test`
Expected: PASS for all `confirmPromptWithFallback` tests.

- [ ] **Step 10: Commit**

```bash
git add ask-gemini.js ask-gemini.test.js
git commit -m "$(cat <<'EOF'
Add stdin fallback for dialog confirmation

Keeps --voice usable from a plain SSH/adb shell where termux-dialog has
no surface to render into, by falling back to the existing y/N prompt.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 11: Update `runVoiceFlow` to use the fallback confirmation and send the (possibly edited) prompt text**

Update the two existing `runVoiceFlow` tests that use `confirmPromptWithUserFn` (around lines 154-200) to use `confirmPromptWithFallbackFn` instead:

```js
test("runVoiceFlow sends the confirmed transcript to Gemini and displays the reply", async () => {
  const recordedPrompts = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async (request) => {
        recordedPrompts.push(request.contents);
        return { text: "4" };
      },
    },
  };
  const displayedResults = [];

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's 2+2",
    confirmPromptWithFallbackFn: async () => ({ confirmed: true, promptText: "what's 2+2" }),
    displayResultToUserFn: async (text) => {
      displayedResults.push(text);
    },
  });

  assert.deepEqual(recordedPrompts, ["what's 2+2"]);
  assert.deepEqual(displayedResults, ["4"]);
});

test("runVoiceFlow does not call Gemini when the user does not confirm", async () => {
  const fakeGeminiClient = {
    models: {
      generateContent: async () => {
        throw new Error("generateContent should not be called");
      },
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's 2+2",
    confirmPromptWithFallbackFn: async () => ({ confirmed: false, promptText: "" }),
    displayResultToUserFn: async () => {
      throw new Error("displayResultToUserFn should not be called");
    },
  });
});
```

Add a new test asserting an edited dialog correction is what gets sent:

```js
test("runVoiceFlow sends the edited prompt text to Gemini, not the raw transcript", async () => {
  const recordedPrompts = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async (request) => {
        recordedPrompts.push(request.contents);
        return { text: "6" };
      },
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's 2+2",
    confirmPromptWithFallbackFn: async () => ({ confirmed: true, promptText: "what's 3+3" }),
    displayResultToUserFn: async () => {},
  });

  assert.deepEqual(recordedPrompts, ["what's 3+3"]);
});
```

Also update the two `speakResponseAloud`-related `runVoiceFlow` tests (around lines 234-270) to use `confirmPromptWithFallbackFn` returning `{ confirmed: true/false, promptText: ... }` instead of `confirmPromptWithUserFn` returning a bare boolean, following the same pattern shown above.

- [ ] **Step 12: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL — `runVoiceFlow` doesn't yet accept `confirmPromptWithFallbackFn` or use `promptText`.

- [ ] **Step 13: Update `runVoiceFlow` implementation**

Replace the existing `runVoiceFlow` function in `ask-gemini.js`:

```js
/**
 * Runs the voice input flow: capture a spoken prompt, confirm it with the
 * user (via dialog with stdin fallback), send it to Gemini if confirmed,
 * display the reply, and speak it aloud.
 *
 * @param {GoogleGenAI} geminiClient - The Gemini SDK client to send the request through.
 * @param {object} [dependencies] - Injectable dependencies, overridable in tests.
 * @param {typeof captureVoicePrompt} [dependencies.captureVoicePromptFn]
 * @param {typeof confirmPromptWithFallback} [dependencies.confirmPromptWithFallbackFn]
 * @param {typeof displayResultToUser} [dependencies.displayResultToUserFn]
 * @param {typeof speakResponseAloud} [dependencies.speakResponseAloudFn]
 * @returns {Promise<void>}
 */
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

Update `module.exports` to add `confirmPromptWithUserViaDialog`, `confirmPromptWithFallback`, `TERMUX_DIALOG_COMMAND`, `TERMUX_DIALOG_TITLE`, `TERMUX_DIALOG_CANCELLED_CODE`.

- [ ] **Step 14: Run full test suite to verify everything passes**

Run: `npm test`
Expected: PASS, all tests including the updated and new ones.

- [ ] **Step 15: Commit**

```bash
git add ask-gemini.js ask-gemini.test.js
git commit -m "$(cat <<'EOF'
Wire dialog confirmation into runVoiceFlow

Sends the (possibly edited) dialog text to Gemini instead of always
using the raw voice transcript.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Always close the widget terminal session after a brief pause

**Files:**
- Modify: `ask-gemini.js` (new constant; `main()` body)
- Test: `ask-gemini.test.js` (no new test — see Global Constraints; this task is verified by manual on-device testing per Step 4 below)

**Interfaces:**
- Consumes: existing `main()` control flow (both plain-prompt and `--voice` branches).
- Produces: `WIDGET_SESSION_CLOSE_DELAY_MS` (number constant, `3000`).

- [ ] **Step 1: Add the delay constant**

In `ask-gemini.js`, add near the other top-level constants (after `GEMINI_RATE_LIMIT_HTTP_STATUS`):

```js
const WIDGET_SESSION_CLOSE_DELAY_MS = 3000;
```

- [ ] **Step 2: Add the pause to the end of every path through `main()`**

In `ask-gemini.js`, `main()` currently has four exit points: the usage-error early return, the missing-API-key early return, the voice-mode branch (success or caught error), and the plain-prompt branch (success, after `displayResultToUser`, or the caught-error early return). Add the pause immediately before every `return` in `main()` (including the early-usage and missing-key returns, so the terminal doesn't linger even on a bad invocation), by inserting this line directly before each `return` statement in the function body:

```js
  await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
```

The end of `main()` (after `await displayResultToUser(geminiResponseText);`, which has no explicit `return`) also needs the pause appended as the last line of the function body.

Resulting `main()`:

```js
async function main() {
  const isVoiceMode = process.argv.includes(VOICE_FLAG_NAME);
  const userPromptText = process.argv[2];

  if (!isVoiceMode && !userPromptText) {
    console.error('Usage: node ask-gemini.js "your prompt here"');
    console.error(`   or: node ask-gemini.js ${VOICE_FLAG_NAME}`);
    process.exitCode = 1;
    await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
    return;
  }

  if (!process.env[GEMINI_API_KEY_ENV_VAR_NAME]) {
    console.error(
      `Error: ${GEMINI_API_KEY_ENV_VAR_NAME} is not set. Add it to a .env file in this directory (see .env.example).`,
    );
    process.exitCode = 1;
    await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
    return;
  }

  const geminiClient = new GoogleGenAI({
    apiKey: process.env[GEMINI_API_KEY_ENV_VAR_NAME],
  });

  if (isVoiceMode) {
    try {
      await runVoiceFlow(geminiClient);
    } catch (voiceError) {
      console.error(`Error: ${voiceError.message}`);
      process.exitCode = 1;
    }
    await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
    return;
  }

  let geminiResponseText;
  try {
    geminiResponseText = await sendPromptToGemini(userPromptText, geminiClient);
  } catch (apiError) {
    console.error(`Error: ${apiError.message}`);
    process.exitCode = 1;
    await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
    return;
  }

  await displayResultToUser(geminiResponseText);
  await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
}
```

- [ ] **Step 3: Run the full test suite**

Run: `npm test`
Expected: PASS. `main()` is not directly unit-tested (it's the CLI entry point, guarded by `require.main === module`), so no existing tests exercise this path; this step confirms the change didn't break anything else.

- [ ] **Step 4: Manually verify on-device (required — this is how the hard "always closed" requirement gets confirmed)**

This step cannot be automated and must be done on the phone, over the PC-to-Termux SSH connection described in `CLAUDE.md`, after deploying the updated `ask-gemini.js` (`git pull` inside Termux):

1. Trigger the `ask-gemini-voice` widget shortcut from the phone's home screen.
2. Time how long the terminal window stays visible after the reply/notification appears, for both a successful run and a forced error (e.g. temporarily rename `.env` to trigger the missing-API-key path, then rename it back).
3. If the terminal session visibly dismisses itself within roughly `WIDGET_SESSION_CLOSE_DELAY_MS` plus a small margin after the process exits, the requirement is met by process-exit-plus-pause alone — no wrapper script change needed, proceed to Step 5.
4. If the session does **not** dismiss and stays open indefinitely after the process exits, stop here and report back: this means Termux:Widget requires an explicit close mechanism, and per the design spec (`docs/superpowers/specs/2026-09-13-dialog-confirmation-and-session-close-design.md`), the next step is to research Termux:Widget's actual documented close mechanism (not guess an `am` intent) and add it to `shortcuts/ask-gemini-voice.sh` as a follow-up change — do not fabricate an unverified command.

- [ ] **Step 5: Commit**

```bash
git add ask-gemini.js
git commit -m "$(cat <<'EOF'
Always close the widget terminal session after a brief pause

Every path through main() now pauses briefly for the notification/
dialog/TTS to land, then exits, so the process never lingers open
regardless of success or error. Confirmed on-device that this alone
dismisses the Termux:Widget session (see docs/superpowers/plans/
2026-09-13-dialog-confirmation-and-session-close.md Task 2 Step 4).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Update CLAUDE.md architecture documentation

**Files:**
- Modify: `CLAUDE.md` (Architecture Overview section)

**Interfaces:**
- Consumes: nothing (documentation-only task).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the new behavior to the Architecture Overview**

In `CLAUDE.md`, in the "Architecture Overview" section, after the existing sentence describing `confirmPromptWithUser` (`"...confirming a voice transcript with the user before sending it (confirmPromptWithUser)..."`), insert:

```
Voice-mode confirmation prefers a native Android dialog via Termux:API's `termux-dialog` (`confirmPromptWithUserViaDialog`, wrapped by `confirmPromptWithFallback`), letting the user review or correct the transcript with OK/Cancel instead of typing `y`/`N`; if `termux-dialog` is unavailable, it falls back to the original stdin `y/N` prompt (`confirmPromptWithUser`). Every run through `main()`, in both plain-prompt and `--voice` mode, pauses briefly (`WIDGET_SESSION_CLOSE_DELAY_MS`) after displaying its result or error so the terminal session launched by the Termux:Widget shortcut always exits rather than lingering open.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
Document dialog confirmation and session auto-close in architecture overview

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** Section 1 (dialog confirmation, fallback, edited-text handling) covered by Task 1. Section 2 (always-closing session) covered by Task 2, including the spec's explicit "verify on-device before touching the wrapper script" ordering. `CLAUDE.md` update requirement (from the project's own CLAUDE.md: "Keep this file updated... whenever architecture changes") covered by Task 3.
- **Placeholder scan:** No TBD/TODO — Task 2 Step 4's on-device branch is a real conditional procedure with a concrete stopping point and pointer back to the spec, not an unresolved placeholder.
- **Type consistency:** `confirmPromptWithUserViaDialog` and `confirmPromptWithFallback` both resolve `{ confirmed: boolean, promptText: string }` consistently across Task 1's steps; `runVoiceFlow`'s `confirmPromptWithFallbackFn` matches this shape everywhere it's used, including the pre-existing tests updated in Step 11.
