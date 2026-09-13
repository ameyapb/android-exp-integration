# Voice Input Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `--voice` flag to `ask-gemini.js` that captures a spoken prompt via Termux:API, confirms it with the user, then sends it to Gemini through the existing pipeline — paired with a Termux:Widget shortcut script so the whole flow is a single home-screen tap.

**Architecture:** `ask-gemini.js` gains two new pure-ish functions — `captureVoicePrompt` (shells out to `termux-speech-to-text` via `execFile`, same pattern as the existing `termux-notification` call) and `confirmPromptWithUser` (prints the transcript, reads a y/N line from stdin via `readline`). `main()` branches on `--voice` to route through capture → confirm → the existing `sendPromptToGemini` → `displayResultToUser`. A new `shortcuts/ask-gemini-voice.sh` invokes `node ask-gemini.js --voice` and gets symlinked into `~/.shortcuts/` for Termux:Widget.

**Tech Stack:** Node.js built-ins only (`child_process.execFile`, `node:readline`, `node:test` for tests) — no new npm dependencies. New non-npm system dependency: Termux:API's `termux-speech-to-text` command and the Termux:Widget app (both documented as setup steps, not code dependencies).

## Global Constraints

- No new npm dependencies (spec: "No new npm dependencies").
- Every literal that carries meaning must be a named constant (project-wide rule from CLAUDE.md) — e.g. the `termux-speech-to-text` command name, the `--voice` flag string, confirmation prompt text, the `y`/`yes` accepted values.
- No abbreviations in names; long self-explanatory function/variable names (CLAUDE.md).
- No comments except where a non-obvious constraint/invariant needs explaining (CLAUDE.md).
- Tests use Node's built-in `node:test` + `node:assert/strict`, plain hand-written fakes for external boundaries (matches `ask-gemini.test.js`'s existing style) — no mocking library.
- Every new function gets a test in the same change; full suite (`npm test`) must pass before considering the work done.
- The existing `node ask-gemini.js "text"` path and all currently-exported functions (`sendPromptToGemini`, `describeGeminiApiError`, `displayResultToUser`, `GEMINI_MODEL_NAME`) must remain unchanged in behavior and signature.
- Update `CLAUDE.md` and `README.md` in the same change that introduces the feature they describe (CLAUDE.md: "Keep this file updated... whenever architecture changes").

---

### Task 1: `captureVoicePrompt` — speech capture via Termux:API

**Files:**
- Modify: `ask-gemini.js`
- Test: `ask-gemini.test.js`

**Interfaces:**
- Produces: `captureVoicePrompt(execFileFn = execFile)` — async function, no required arguments in production use (the real `execFile` is the default); returns `Promise<string>` (trimmed transcript, possibly empty string for silence); rejects with an `Error` whose message starts with `"Voice input error: "` when the underlying command fails.
- Produces: constant `TERMUX_SPEECH_TO_TEXT_COMMAND = "termux-speech-to-text"`.
- Consumes: `child_process.execFile` (already imported in `ask-gemini.js`).

- [ ] **Step 1: Write the failing tests**

Add to `ask-gemini.test.js`, right after the existing imports (extend the destructured `require` once the export exists — see Step 3):

```js
test("captureVoicePrompt returns the trimmed transcript from termux-speech-to-text", async () => {
  const recordedCalls = [];
  const fakeExecFile = (command, args, callback) => {
    recordedCalls.push({ command, args });
    callback(null, "  what's the weather today  \n", "");
  };

  const transcript = await captureVoicePrompt(fakeExecFile);

  assert.equal(transcript, "what's the weather today");
  assert.equal(recordedCalls.length, 1);
  assert.equal(recordedCalls[0].command, TERMUX_SPEECH_TO_TEXT_COMMAND);
});

test("captureVoicePrompt surfaces a readable error when termux-speech-to-text fails", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(new Error("command not found"), "", "");
  };

  await assert.rejects(
    () => captureVoicePrompt(fakeExecFile),
    (thrownError) => {
      assert.match(thrownError.message, /Voice input error/);
      assert.match(thrownError.message, /command not found/);
      return true;
    },
  );
});
```

Update the top-of-file import in `ask-gemini.test.js` to include the new names:

```js
const {
  sendPromptToGemini,
  describeGeminiApiError,
  captureVoicePrompt,
  TERMUX_SPEECH_TO_TEXT_COMMAND,
  GEMINI_MODEL_NAME,
} = require("./ask-gemini.js");
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL — `captureVoicePrompt` and `TERMUX_SPEECH_TO_TEXT_COMMAND` are not exported yet (`TypeError` or `undefined is not a function`).

- [ ] **Step 3: Implement `captureVoicePrompt`**

In `ask-gemini.js`, add the constant near the other `TERMUX_*` constant:

```js
const TERMUX_SPEECH_TO_TEXT_COMMAND = "termux-speech-to-text";
```

Add the function after `sendPromptToGemini`/`describeGeminiApiError` (keeping the file's existing top-to-bottom order of "capture-ish" concerns before "send" is not required — place it directly above `displayResultToUser` since both shell out via `execFile`):

```js
/**
 * Records speech via Termux:API and returns the transcribed text.
 *
 * @param {typeof execFile} [execFileFn] - The execFile implementation to use; defaults to Node's child_process.execFile, overridable in tests.
 * @returns {Promise<string>} The trimmed transcript. Empty string if nothing was heard.
 * @throws {Error} A human-readable error if termux-speech-to-text is unavailable or fails.
 */
function captureVoicePrompt(execFileFn = execFile) {
  return new Promise((resolve, reject) => {
    execFileFn(TERMUX_SPEECH_TO_TEXT_COMMAND, [], (execError, stdout) => {
      if (execError) {
        reject(
          new Error(
            `Voice input error: ${execError.message}. Check that Termux:API is installed (pkg install termux-api) and the Termux:API Android app is granted microphone access.`,
          ),
        );
        return;
      }
      resolve(stdout.trim());
    });
  });
}
```

Add `captureVoicePrompt` and `TERMUX_SPEECH_TO_TEXT_COMMAND` to the `module.exports` block at the bottom of `ask-gemini.js`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test`
Expected: PASS for both new tests and all pre-existing tests.

- [ ] **Step 5: Commit**

```bash
git add ask-gemini.js ask-gemini.test.js
git commit -m "Add captureVoicePrompt for termux-speech-to-text voice capture"
```

---

### Task 2: `confirmPromptWithUser` — stdin confirmation

**Files:**
- Modify: `ask-gemini.js`
- Test: `ask-gemini.test.js`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `confirmPromptWithUser(transcriptText, readLineFn)` — async function; `readLineFn` is an injected function `() => Promise<string>` that resolves one line of raw stdin input (production default reads from `process.stdin` via `node:readline`); returns `Promise<boolean>`. Empty/whitespace-only `transcriptText` resolves `false` immediately without calling `readLineFn`.
- Produces: constant `VOICE_CONFIRMATION_ACCEPTED_VALUES = ["y", "yes"]` (case-insensitive match).

- [ ] **Step 1: Write the failing tests**

Add to `ask-gemini.test.js`, extending the import list with `confirmPromptWithUser`:

```js
test("confirmPromptWithUser resolves false without prompting when the transcript is empty", async () => {
  let readLineCallCount = 0;
  const fakeReadLine = async () => {
    readLineCallCount += 1;
    return "y";
  };

  const confirmed = await confirmPromptWithUser("", fakeReadLine);

  assert.equal(confirmed, false);
  assert.equal(readLineCallCount, 0);
});

test("confirmPromptWithUser resolves true when the user types y", async () => {
  const fakeReadLine = async () => "y";

  const confirmed = await confirmPromptWithUser("what's 2+2", fakeReadLine);

  assert.equal(confirmed, true);
});

test("confirmPromptWithUser resolves true when the user types Yes in any case", async () => {
  const fakeReadLine = async () => "Yes";

  const confirmed = await confirmPromptWithUser("what's 2+2", fakeReadLine);

  assert.equal(confirmed, true);
});

test("confirmPromptWithUser resolves false when the user types anything else", async () => {
  const fakeReadLine = async () => "n";

  const confirmed = await confirmPromptWithUser("what's 2+2", fakeReadLine);

  assert.equal(confirmed, false);
});

test("confirmPromptWithUser resolves false on empty input", async () => {
  const fakeReadLine = async () => "";

  const confirmed = await confirmPromptWithUser("what's 2+2", fakeReadLine);

  assert.equal(confirmed, false);
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL — `confirmPromptWithUser` is not exported yet.

- [ ] **Step 3: Implement `confirmPromptWithUser`**

In `ask-gemini.js`, add `const readline = require("node:readline");` near the top alongside the other `require` calls.

Add the constant near `TERMUX_SPEECH_TO_TEXT_COMMAND`:

```js
const VOICE_CONFIRMATION_ACCEPTED_VALUES = ["y", "yes"];
```

Add the function after `captureVoicePrompt`:

```js
/**
 * Prints the transcribed voice prompt and asks the user to confirm sending
 * it to Gemini.
 *
 * @param {string} transcriptText - The transcript to confirm.
 * @param {() => Promise<string>} [readLineFn] - Reads one line of raw stdin input; defaults to reading process.stdin, overridable in tests.
 * @returns {Promise<boolean>} True if the user confirmed, false otherwise (including an empty transcript, which skips prompting).
 */
async function confirmPromptWithUser(transcriptText, readLineFn = readOneLineFromStdin) {
  if (!transcriptText) {
    console.log("Heard nothing, cancelling.");
    return false;
  }

  console.log(`Heard: "${transcriptText}"`);
  const rawAnswer = await readLineFn();
  return VOICE_CONFIRMATION_ACCEPTED_VALUES.includes(rawAnswer.trim().toLowerCase());
}

/**
 * Reads a single line of input from process.stdin.
 *
 * @returns {Promise<string>} The raw line entered by the user.
 */
function readOneLineFromStdin() {
  const readlineInterface = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });

  return new Promise((resolve) => {
    readlineInterface.question("Send this to Gemini? [y/N] ", (answer) => {
      readlineInterface.close();
      resolve(answer);
    });
  });
}
```

Add `confirmPromptWithUser` and `VOICE_CONFIRMATION_ACCEPTED_VALUES` to `module.exports`. (`readOneLineFromStdin` stays unexported — it's the production default, not something tests call directly.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test`
Expected: PASS for all new and pre-existing tests.

- [ ] **Step 5: Commit**

```bash
git add ask-gemini.js ask-gemini.test.js
git commit -m "Add confirmPromptWithUser for voice transcript confirmation"
```

---

### Task 3: Wire `--voice` into `main()`

**Files:**
- Modify: `ask-gemini.js`
- Test: `ask-gemini.test.js`

**Interfaces:**
- Consumes: `captureVoicePrompt` (Task 1), `confirmPromptWithUser` (Task 2), `sendPromptToGemini`/`displayResultToUser` (existing).
- Produces: constant `VOICE_FLAG_NAME = "--voice"`. `main()`'s behavior is not itself unit-tested directly (it reads `process.argv`/env and was never exported); this task's test coverage instead locks in the *sequencing contract* — capture → confirm → send → display, and that a declined confirmation short-circuits — via a small exported orchestration function that `main()` calls, so the logic is testable without spawning a subprocess.

**Design note:** `main()` currently isn't exported or unit-tested (it's only invoked via `require.main === module`). To keep the branching logic testable without spawning real subprocesses, extract a `runVoiceFlow(geminiClient, dependencies)` function that contains the capture → confirm → send → display sequence, and have `main()` call it when `--voice` is present. This mirrors the existing separation where `main()` stays thin and the real logic lives in testable exported functions.

- [ ] **Step 1: Write the failing tests**

Add to `ask-gemini.test.js`, extending the import list with `runVoiceFlow`:

```js
test("runVoiceFlow sends the confirmed transcript to Gemini and displays the reply", async () => {
  const recordedGeminiCalls = [];
  const recordedDisplayCalls = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async (request) => {
        recordedGeminiCalls.push(request);
        return { text: "It's sunny." };
      },
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's the weather",
    confirmPromptWithUserFn: async () => true,
    displayResultToUserFn: async (text) => {
      recordedDisplayCalls.push(text);
    },
  });

  assert.equal(recordedGeminiCalls.length, 1);
  assert.equal(recordedGeminiCalls[0].contents, "what's the weather");
  assert.deepEqual(recordedDisplayCalls, ["It's sunny."]);
});

test("runVoiceFlow does not call Gemini when the user does not confirm", async () => {
  const recordedGeminiCalls = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async (request) => {
        recordedGeminiCalls.push(request);
        return { text: "should not be reached" };
      },
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's the weather",
    confirmPromptWithUserFn: async () => false,
    displayResultToUserFn: async () => {
      throw new Error("displayResultToUserFn should not be called");
    },
  });

  assert.equal(recordedGeminiCalls.length, 0);
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL — `runVoiceFlow` is not exported yet.

- [ ] **Step 3: Implement `runVoiceFlow` and wire it into `main()`**

Add the constant near the other flag-like constants (top of file, with the other named constants):

```js
const VOICE_FLAG_NAME = "--voice";
```

Add `runVoiceFlow` after `confirmPromptWithUser`/`readOneLineFromStdin` and before `main()`:

```js
/**
 * Runs the voice input flow: capture a spoken prompt, confirm it with the
 * user, send it to Gemini if confirmed, and display the reply.
 *
 * @param {GoogleGenAI} geminiClient - The Gemini SDK client to send the request through.
 * @param {object} [dependencies] - Injectable dependencies, overridable in tests.
 * @param {typeof captureVoicePrompt} [dependencies.captureVoicePromptFn]
 * @param {typeof confirmPromptWithUser} [dependencies.confirmPromptWithUserFn]
 * @param {typeof displayResultToUser} [dependencies.displayResultToUserFn]
 * @returns {Promise<void>}
 */
async function runVoiceFlow(
  geminiClient,
  {
    captureVoicePromptFn = captureVoicePrompt,
    confirmPromptWithUserFn = confirmPromptWithUser,
    displayResultToUserFn = displayResultToUser,
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
}
```

Modify `main()` to branch on the flag. Replace the current start of `main()`:

```js
async function main() {
  const userPromptText = process.argv[2];

  if (!userPromptText) {
    console.error('Usage: node ask-gemini.js "your prompt here"');
    process.exitCode = 1;
    return;
  }

  if (!process.env[GEMINI_API_KEY_ENV_VAR_NAME]) {
    console.error(
      `Error: ${GEMINI_API_KEY_ENV_VAR_NAME} is not set. Add it to a .env file in this directory (see .env.example).`,
    );
    process.exitCode = 1;
    return;
  }

  const geminiClient = new GoogleGenAI({
    apiKey: process.env[GEMINI_API_KEY_ENV_VAR_NAME],
  });

  let geminiResponseText;
  try {
    geminiResponseText = await sendPromptToGemini(userPromptText, geminiClient);
  } catch (apiError) {
    console.error(`Error: ${apiError.message}`);
    process.exitCode = 1;
    return;
  }

  await displayResultToUser(geminiResponseText);
}
```

with:

```js
async function main() {
  const isVoiceMode = process.argv.includes(VOICE_FLAG_NAME);
  const userPromptText = process.argv[2];

  if (!isVoiceMode && !userPromptText) {
    console.error('Usage: node ask-gemini.js "your prompt here"');
    console.error(`   or: node ask-gemini.js ${VOICE_FLAG_NAME}`);
    process.exitCode = 1;
    return;
  }

  if (!process.env[GEMINI_API_KEY_ENV_VAR_NAME]) {
    console.error(
      `Error: ${GEMINI_API_KEY_ENV_VAR_NAME} is not set. Add it to a .env file in this directory (see .env.example).`,
    );
    process.exitCode = 1;
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
    return;
  }

  let geminiResponseText;
  try {
    geminiResponseText = await sendPromptToGemini(userPromptText, geminiClient);
  } catch (apiError) {
    console.error(`Error: ${apiError.message}`);
    process.exitCode = 1;
    return;
  }

  await displayResultToUser(geminiResponseText);
}
```

Add `runVoiceFlow` and `VOICE_FLAG_NAME` to `module.exports`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test`
Expected: PASS for all new and pre-existing tests.

- [ ] **Step 5: Manual smoke check of argument parsing (no phone required)**

Run: `node ask-gemini.js` (no args, no `--voice`)
Expected: prints the two-line usage message and exits with code 1 — confirms the branch didn't break the existing no-argument path.

- [ ] **Step 6: Commit**

```bash
git add ask-gemini.js ask-gemini.test.js
git commit -m "Wire --voice flag into main() via runVoiceFlow"
```

---

### Task 4: Widget shortcut script and setup docs

**Files:**
- Create: `shortcuts/ask-gemini-voice.sh`
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `node ask-gemini.js --voice` (Task 3), already-documented repo location conventions.
- Produces: no code interface — this is a shell script and documentation only.

- [ ] **Step 1: Create the shortcut script**

Create `shortcuts/ask-gemini-voice.sh`:

```sh
#!/data/data/com.termux/files/usr/bin/sh

cd ~/android-exp-integration || exit 1
node ask-gemini.js --voice
```

Note: `~/android-exp-integration` matches the clone target implied by CLAUDE.md's deploy instructions (`git clone` into the home directory under the repo's GitHub name). If the user's actual clone path differs, they adjust this one line during setup — call this out in the README step below rather than guessing a path that might be wrong.

- [ ] **Step 2: Make the script executable and verify shell syntax**

Run: `chmod +x shortcuts/ask-gemini-voice.sh` (on a Unix-like shell; on Windows this is a no-op for git's purposes but harmless — Termux itself will need the executable bit, which the symlink step preserves since it's the same inode).

Run: `sh -n shortcuts/ask-gemini-voice.sh` (if a POSIX shell is available locally, e.g. Git Bash) to syntax-check without executing.
Expected: no output (syntax OK). If `sh` isn't available locally, skip this check — the script is simple enough to review by inspection, and it will be exercised for real on the phone during manual setup.

- [ ] **Step 3: Update README.md**

Read the current single-paragraph `README.md` and extend it with a second paragraph covering voice setup. Replace the full file content with:

```markdown
# phone-app-exp

`ask-gemini.js` is a minimal Termux CLI for asking Google's Gemini a question from a spare Android phone and getting the answer back as both terminal output and a native Android notification: install Termux and Node.js (`pkg install nodejs`), run `npm install` in this directory to pull in `@google/genai` and `dotenv`, install the Termux:API app plus `pkg install termux-api` so `termux-notification` works, then create a `.env` file in this directory with `GEMINI_API_KEY=your-google-ai-studio-key-here` (get a free-tier key at https://aistudio.google.com/apikey) — after that, run the script with `node ask-gemini.js "what's 2+2"` and Gemini's reply will print to stdout and pop up as a notification (if `termux-notification` isn't installed, the script just logs a warning and still prints the answer).

To ask Gemini by voice instead of typing, run `node ask-gemini.js --voice` — it uses Termux:API's `termux-speech-to-text` (already installed above) to record and transcribe what you say, prints the transcript and asks you to confirm before sending it to Gemini. For a one-tap home-screen shortcut: install the Termux:Widget app, then symlink the shortcut script into Termux's widget directory with `mkdir -p ~/.shortcuts && ln -s ~/android-exp-integration/shortcuts/ask-gemini-voice.sh ~/.shortcuts/` (adjust the path if you cloned this repo somewhere other than `~/android-exp-integration`), then add a Termux:Widget widget to your home screen and pick `ask-gemini-voice.sh` from the list — tapping it opens Termux and starts listening immediately.
```

- [ ] **Step 4: Update CLAUDE.md**

Modify the "Architecture Overview" section in `CLAUDE.md` (the final paragraph of the file) to mention the voice flow and the `shortcuts/` folder. Change:

```
Runs inside Termux on Android. `ask-gemini.js` is a single-file CLI with two separated concerns: calling Gemini via the `@google/genai` SDK (`ai.models.generateContent({ model, contents })`), and displaying the result (stdout plus a `termux-notification` shell-out that degrades gracefully if unavailable). The API key is loaded from a gitignored `.env` file via `dotenv`, never hardcoded or logged. This is the foundation for giving the assistant broader control over the phone in future iterations — document new components here as they're added.
```

to:

```
Runs inside Termux on Android. `ask-gemini.js` is a single-file CLI with separated concerns: calling Gemini via the `@google/genai` SDK (`ai.models.generateContent({ model, contents })`), capturing a spoken prompt via Termux:API's `termux-speech-to-text` (`captureVoicePrompt`, used when the `--voice` flag is passed), confirming a voice transcript with the user before sending it (`confirmPromptWithUser`), and displaying the result (stdout plus a `termux-notification` shell-out that degrades gracefully if unavailable). The API key is loaded from a gitignored `.env` file via `dotenv`, never hardcoded or logged. `shortcuts/ask-gemini-voice.sh` is a Termux:Widget shortcut script that runs `node ask-gemini.js --voice`, symlinked into `~/.shortcuts/` on the phone for a one-tap, no-typing voice query from the home screen. This is the foundation for giving the assistant broader control over the phone in future iterations — document new components here as they're added.
```

Also update the "Commands" section to add the voice invocation alongside the existing `node ask-gemini.js "<prompt>"` line:

```
- `node ask-gemini.js --voice` — record a spoken prompt via Termux:API, confirm the transcript, and send it to Gemini.
```

- [ ] **Step 5: Commit**

```bash
git add shortcuts/ask-gemini-voice.sh README.md CLAUDE.md
git commit -m "Add Termux:Widget voice shortcut script and setup docs"
```

---

### Task 5: Full-suite verification

**Files:** none (verification only).

**Interfaces:** none.

- [ ] **Step 1: Run the full test suite**

Run: `npm test`
Expected: PASS — every test in `ask-gemini.test.js` (existing and new) passes, zero failures.

- [ ] **Step 2: Confirm no unintended changes to existing exports**

Run: `node -e "const m = require('./ask-gemini.js'); console.log(Object.keys(m));"`
Expected output includes exactly: `sendPromptToGemini`, `describeGeminiApiError`, `displayResultToUser`, `GEMINI_MODEL_NAME`, `captureVoicePrompt`, `TERMUX_SPEECH_TO_TEXT_COMMAND`, `confirmPromptWithUser`, `VOICE_CONFIRMATION_ACCEPTED_VALUES`, `runVoiceFlow`, `VOICE_FLAG_NAME`.

- [ ] **Step 3: Manual review checklist (no phone required)**

Confirm by reading the diff:
- No new npm dependencies were added to `package.json`.
- No magic strings/numbers were introduced outside named constants.
- `main()`'s existing text-argument path is untouched aside from the added branch.

This task has no code changes and no commit — it's a checkpoint before considering the plan complete. If any check fails, return to the relevant task, fix, and re-commit there.
