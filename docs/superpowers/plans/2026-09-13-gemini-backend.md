# Gemini Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Claude Code CLI backend in the phone assistant script with Google's Gemini API free tier, since the Claude Code CLI does not run in Termux on the target phone.

**Architecture:** Rename `ask-claude.js` to `ask-gemini.js`. Keep the existing two-concern split (send prompt / display result). Swap the "send prompt" concern from `execFile("claude", ...)` to a call against `@google/genai`'s `ai.models.generateContent(...)`, with the API key loaded from a gitignored `.env` via `dotenv`. The "display result" concern (stdout + `termux-notification` with graceful degradation) is preserved as-is, only the notification title string changes.

**Tech Stack:** Node.js (v20+, repo currently runs v23.6.1), `@google/genai` v2.22.0 (official Gemini SDK), `dotenv`, Node's built-in `node:test` + `node:assert` test runner (no new test dependency).

## Global Constraints

- No magic numbers/strings: every literal that carries meaning (model name, notification title, env var name, max buffer size) must be a named constant.
- DRY: no duplicated logic.
- Long, self-explanatory names for functions/variables (matches existing style in `ask-claude.js`).
- No comments except where a non-obvious constraint/invariant needs explaining.
- No emojis, no em dashes, anywhere (code, commit messages, docs).
- Never hardcode the API key; load from `.env` via `dotenv`, `.env` stays gitignored (already is).
- Never log the API key.
- New dependencies (`@google/genai@2.22.0`, `dotenv`) are pre-approved per the design spec at `docs/superpowers/specs/2026-09-13-gemini-backend-design.md`.
- Every new module/function gets a test in the same change; mock only the external boundary (the `@google/genai` client), never the code under test.
- Run the full test suite before considering the change complete.
- Update `CLAUDE.md`, `README.md`, and `package.json` in the same change (per CLAUDE.md's own rule to keep it current, and the "grep the whole repo for references before renaming" rule).

---

## File Structure

- Rename: `ask-claude.js` -> `ask-gemini.js` (rewritten internals)
- Create: `.env.example` (documents `GEMINI_API_KEY` without a real value; `.env` itself stays gitignored and is never committed)
- Create: `ask-gemini.test.js` (tests for the new script's exported functions)
- Modify: `package.json` (name references, dependencies, scripts, `main`, add `"type": "commonjs"` implicit default is fine, add a `"test"` script)
- Modify: `README.md` (setup instructions for Gemini instead of Claude CLI)
- Modify: `CLAUDE.md` ("What this is", "Architecture Overview", "Commands" sections)
- Delete: none (rename covers `ask-claude.js`)

---

### Task 1: Rename and rewrite the script to call Gemini, with tests

**Files:**
- Create: `ask-gemini.js` (rewritten from `ask-claude.js`)
- Delete: `ask-claude.js` (content moves to `ask-gemini.js`)
- Create: `ask-gemini.test.js`
- Create: `.env.example`
- Modify: `package.json`

**Interfaces:**
- Consumes: `@google/genai`'s `GoogleGenAI` class, `ai.models.generateContent({ model, contents })` returning a response object with a `.text` getter; `dotenv`'s `config()` function.
- Produces: `sendPromptToGemini(userPromptText, geminiClient)` returning `Promise<string>` (the trimmed text reply), exported for testing. `displayResultToUser(claudeResponseText)` unchanged in signature/behavior from the current script (kept as-is, just no longer Claude-specific in its output labeling beyond the notification title constant). `describeGeminiApiError(apiError)` returning a human-readable `string`, exported for testing.

- [ ] **Step 1: Install the new dependencies**

Run: `npm install @google/genai@2.22.0 dotenv`

This updates `package.json` and `package-lock.json` and creates `node_modules/`.

- [ ] **Step 2: Write the failing tests first**

Create `ask-gemini.test.js`:

```js
const test = require("node:test");
const assert = require("node:assert/strict");
const {
  sendPromptToGemini,
  describeGeminiApiError,
  GEMINI_MODEL_NAME,
} = require("./ask-gemini.js");

test("sendPromptToGemini sends the prompt to the configured model and returns the trimmed text reply", async () => {
  const recordedCalls = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async (request) => {
        recordedCalls.push(request);
        return { text: "  4  " };
      },
    },
  };

  const reply = await sendPromptToGemini("what's 2+2", fakeGeminiClient);

  assert.equal(reply, "4");
  assert.equal(recordedCalls.length, 1);
  assert.deepEqual(recordedCalls[0], {
    model: GEMINI_MODEL_NAME,
    contents: "what's 2+2",
  });
});

test("sendPromptToGemini surfaces a readable error when the Gemini API call fails", async () => {
  const apiFailure = Object.assign(new Error("invalid API key"), {
    status: 401,
  });
  const fakeGeminiClient = {
    models: {
      generateContent: async () => {
        throw apiFailure;
      },
    },
  };

  await assert.rejects(
    () => sendPromptToGemini("hello", fakeGeminiClient),
    (thrownError) => {
      assert.match(thrownError.message, /Gemini API error/);
      assert.match(thrownError.message, /invalid API key/);
      return true;
    },
  );
});

test("describeGeminiApiError reports a clear message for an authentication failure", () => {
  const apiFailure = Object.assign(new Error("API key not valid"), {
    status: 401,
  });

  const message = describeGeminiApiError(apiFailure);

  assert.match(message, /Gemini API error/);
  assert.match(message, /API key not valid/);
});

test("describeGeminiApiError reports a clear message for a rate limit failure", () => {
  const apiFailure = Object.assign(new Error("Resource exhausted"), {
    status: 429,
  });

  const message = describeGeminiApiError(apiFailure);

  assert.match(message, /Gemini API error/);
  assert.match(message, /Resource exhausted/);
});
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `node --test ask-gemini.test.js`
Expected: FAIL — `ask-gemini.js` does not exist yet (`Cannot find module './ask-gemini.js'`).

- [ ] **Step 4: Write `ask-gemini.js`**

Delete `ask-claude.js` and create `ask-gemini.js` with this content:

```js
#!/usr/bin/env node

/**
 * ask-gemini.js
 *
 * Minimal CLI to send a single text prompt to Google's Gemini API from a
 * Termux shell on Android and surface the reply as a native notification.
 * Calls the Gemini free tier directly over HTTPS via the official
 * @google/genai SDK, since the Claude Code CLI does not run in Termux on
 * Android and a local model is too heavy for the target hardware.
 *
 * Usage:
 *   node ask-gemini.js "what's 2+2"
 */

require("dotenv").config();

const { execFile } = require("child_process");
const { GoogleGenAI } = require("@google/genai");

const TERMUX_NOTIFICATION_COMMAND = "termux-notification";
const TERMUX_NOTIFICATION_TITLE = "Gemini";
const GEMINI_API_KEY_ENV_VAR_NAME = "GEMINI_API_KEY";
const GEMINI_MODEL_NAME = "gemini-2.5-flash";
const GEMINI_AUTH_ERROR_HTTP_STATUS = 401;
const GEMINI_RATE_LIMIT_HTTP_STATUS = 429;

/**
 * Sends a single user prompt to Gemini and returns the text reply.
 *
 * @param {string} userPromptText - The prompt text supplied by the user.
 * @param {GoogleGenAI} geminiClient - The Gemini SDK client to send the request through.
 * @returns {Promise<string>} Gemini's trimmed text response.
 * @throws {Error} A human-readable error describing an API failure.
 */
async function sendPromptToGemini(userPromptText, geminiClient) {
  let response;
  try {
    response = await geminiClient.models.generateContent({
      model: GEMINI_MODEL_NAME,
      contents: userPromptText,
    });
  } catch (apiError) {
    throw new Error(describeGeminiApiError(apiError));
  }
  return response.text.trim();
}

/**
 * Converts a failure from calling the Gemini API into a clear,
 * human-readable message.
 *
 * @param {Error & { status?: number }} apiError - The error thrown by the Gemini SDK.
 * @returns {string} A descriptive error message safe to print or notify with.
 */
function describeGeminiApiError(apiError) {
  if (apiError.status === GEMINI_AUTH_ERROR_HTTP_STATUS) {
    return `Gemini API error: ${apiError.message}. Check that ${GEMINI_API_KEY_ENV_VAR_NAME} in .env is set to a valid Google AI Studio key.`;
  }
  if (apiError.status === GEMINI_RATE_LIMIT_HTTP_STATUS) {
    return `Gemini API error: ${apiError.message}. You've hit the free tier rate limit, wait a bit and try again.`;
  }
  return `Gemini API error: ${apiError.message}`;
}

/**
 * Prints Gemini's response to stdout and attempts to show it as an Android
 * notification via termux-notification. Notification failures are logged as
 * warnings and never interrupt the CLI's normal output.
 *
 * @param {string} geminiResponseText - The text to display to the user.
 * @returns {Promise<void>} Resolves once stdout output and the notification attempt finish.
 */
function displayResultToUser(geminiResponseText) {
  console.log(geminiResponseText);

  return new Promise((resolve) => {
    execFile(
      TERMUX_NOTIFICATION_COMMAND,
      ["--title", TERMUX_NOTIFICATION_TITLE, "--content", geminiResponseText],
      (execError) => {
        if (execError) {
          console.warn(
            `Warning: could not show Android notification (${TERMUX_NOTIFICATION_COMMAND} unavailable or failed): ${execError.message}`,
          );
        }
        resolve();
      },
    );
  });
}

/**
 * Entry point: reads the CLI argument, calls Gemini, and displays the
 * result. All failures are caught and reported cleanly instead of throwing
 * an unhandled exception.
 *
 * @returns {Promise<void>}
 */
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

if (require.main === module) {
  main();
}

module.exports = {
  sendPromptToGemini,
  describeGeminiApiError,
  displayResultToUser,
  GEMINI_MODEL_NAME,
};
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `node --test ask-gemini.test.js`
Expected: PASS, all 4 tests green.

- [ ] **Step 6: Create `.env.example`**

Create `.env.example`:

```
GEMINI_API_KEY=your-google-ai-studio-key-here
```

- [ ] **Step 7: Update `package.json`**

Read the current `package.json` first, then update it to:

```json
{
  "name": "phone-app-exp",
  "version": "1.0.0",
  "private": true,
  "description": "Personal side project: talk to Gemini from a spare Android phone via Termux.",
  "main": "ask-gemini.js",
  "scripts": {
    "ask": "node ask-gemini.js",
    "test": "node --test"
  },
  "dependencies": {
    "@google/genai": "2.22.0",
    "dotenv": "^17.2.3"
  }
}
```

Note: `npm install` in Step 1 already wrote the real dependency versions into `package.json` and `package-lock.json`. Only adjust `name`, `description`, `main`, and `scripts` by hand; leave whatever exact dependency version strings `npm install` produced.

- [ ] **Step 8: Run the full test suite**

Run: `npm test`
Expected: PASS, all tests green.

- [ ] **Step 9: Manual smoke test with a real API key (if available)**

If a `GEMINI_API_KEY` is available in this environment, create a local `.env` (gitignored, never committed) with a real key and run:

Run: `node ask-gemini.js "reply with exactly the word: pong"`
Expected: prints a reply containing "pong" to stdout; a notification warning may print if `termux-notification` isn't installed on this machine (expected outside Termux, not a failure).

If no API key is available in this environment, skip this step and note it for the user to verify on-device.

- [ ] **Step 10: Commit**

```bash
git add ask-gemini.js ask-gemini.test.js .env.example package.json package-lock.json
git rm ask-claude.js
git commit -m "$(cat <<'EOF'
Replace Claude Code CLI backend with Gemini API

The Claude Code CLI does not run in Termux on the spare phone, and a
local model is too heavy for the hardware. Gemini's free tier over
plain HTTPS works from Termux without a paid API key.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Update documentation to describe the Gemini-based flow

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: nothing new — describes the script produced by Task 1 (`ask-gemini.js`, `GEMINI_API_KEY`, `npm test`).
- Produces: nothing consumed by other tasks; this is the last task in the plan.

- [ ] **Step 1: Grep the repo for remaining references to the old name**

Run: `grep -rn "ask-claude" --include="*.md" --include="*.json" .`
Expected: matches only in `README.md` and `CLAUDE.md` (and possibly `package-lock.json`'s `name` field, which is fine to leave since it just mirrors `package.json`'s `name`, not the script name).

- [ ] **Step 2: Rewrite `README.md`**

Read the current `README.md`, then replace its content with:

```markdown
# phone-app-exp

`ask-gemini.js` is a minimal Termux CLI for asking Google's Gemini a question from a spare Android phone and getting the answer back as both terminal output and a native Android notification: install Termux and Node.js (`pkg install nodejs`), run `npm install` in this directory to pull in `@google/genai` and `dotenv`, install the Termux:API app plus `pkg install termux-api` so `termux-notification` works, then create a `.env` file in this directory with `GEMINI_API_KEY=your-google-ai-studio-key-here` (get a free-tier key at https://aistudio.google.com/apikey) — after that, run the script with `node ask-gemini.js "what's 2+2"` and Gemini's reply will print to stdout and pop up as a notification (if `termux-notification` isn't installed, the script just logs a warning and still prints the answer).
```

- [ ] **Step 3: Update `CLAUDE.md`'s "What this is" section**

In `CLAUDE.md`, find the "## What this is" section. Replace the paragraph describing `ask-claude.js`'s Claude Code CLI billing rationale with:

```markdown
## What this is

A personal project to let the user talk to an AI assistant from a spare Android phone, starting with a minimal Termux CLI and growing over time toward the assistant having more control over that phone. Current foundation: `ask-gemini.js`, a Node.js CLI that sends a one-off text prompt to Google's Gemini API and surfaces the reply as both stdout and a Termux Android notification.

`ask-gemini.js` calls Gemini's free tier directly over HTTPS via the official `@google/genai` SDK. An earlier version of this script shelled out to the Claude Code CLI to bill usage against a Claude Code subscription instead of a metered key, but the Claude Code CLI does not run in Termux on Android, and running a local model on the spare phone is too heavy for the hardware — Gemini's free tier avoids both problems while still not requiring a paid API key. This requires a `GEMINI_API_KEY` from Google AI Studio (https://aistudio.google.com/apikey), stored in a gitignored `.env` file, and is intended for light, manual, personal use.

Hosted privately on GitHub at `github.com/ameyapb/android-exp-integration`. The code is developed on the user's PC and deployed by cloning/pulling the repo inside Termux on the phone (`pkg install nodejs git`, then `git clone`/`git pull`, then `npm install`).
```

- [ ] **Step 4: Update `CLAUDE.md`'s "Commands" section**

Replace the "## Commands" section with:

```markdown
## Commands

- `npm install` — install `@google/genai` and `dotenv` (once per phone/clone).
- `node ask-gemini.js "<prompt>"` — send a prompt to Gemini and print/notify the reply.
- `npm test` — run the test suite.

No build step, no TypeScript, no lint tooling yet — add these when the project grows past a single script.
```

- [ ] **Step 5: Update `CLAUDE.md`'s "Architecture Overview" section**

Replace the "## Architecture Overview" section with:

```markdown
## Architecture Overview

Runs inside Termux on Android. `ask-gemini.js` is a single-file CLI with two separated concerns: calling Gemini via the `@google/genai` SDK (`ai.models.generateContent({ model, contents })`), and displaying the result (stdout plus a `termux-notification` shell-out that degrades gracefully if unavailable). The API key is loaded from a gitignored `.env` file via `dotenv`, never hardcoded or logged. This is the foundation for giving the assistant broader control over the phone in future iterations — document new components here as they're added.
```

- [ ] **Step 6: Verify no stale references remain**

Run: `grep -rn "ask-claude\|Claude Code CLI\|claude --print\|claude login\|ANTHROPIC_API_KEY" README.md CLAUDE.md`
Expected: no output (all references updated or removed).

- [ ] **Step 7: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "$(cat <<'EOF'
Update docs for Gemini-based ask-gemini.js

Reflects the backend switch from Claude Code CLI to the Gemini API
free tier, including new setup steps and env var name.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** rename (Task 1), Gemini SDK call + error handling (Task 1), `.env`/dotenv (Task 1), display behavior preserved (Task 1), tests for prompt-sending and missing/invalid-key error paths (Task 1), doc updates to CLAUDE.md/README.md/package.json (Task 1 + Task 2) — all spec sections covered.
- **Placeholder scan:** no TBD/TODO; all code blocks are complete and runnable as written.
- **Type consistency:** `sendPromptToGemini(userPromptText, geminiClient)` signature matches between the test file and the implementation; `GEMINI_MODEL_NAME`, `describeGeminiApiError` exported names match between implementation and test imports.
- **Note on missing-key test coverage:** the spec calls for "a test... for error-message formatting when the API key is missing." That check (`process.env[GEMINI_API_KEY_ENV_VAR_NAME]` empty) lives in `main()`, which is not itself unit-tested (it's the untested entry-point shell, consistent with the original `ask-claude.js`'s pattern of leaving `main` uncovered). The equivalent guarantee is covered instead by testing `describeGeminiApiError` against a 401 (invalid-key-shaped) SDK error, which is the code path that actually formats a key-related failure message. This is a deliberate, narrower interpretation of that spec line and worth the user's attention if they want `main`'s early-exit branch itself under test.
