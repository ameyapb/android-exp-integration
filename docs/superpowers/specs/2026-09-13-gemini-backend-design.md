# Design: Switch phone assistant backend from Claude Code CLI to Gemini API

Date: 2026-09-13

## Problem

`ask-claude.js` currently shells out to the Claude Code CLI (`claude --print`) so
usage is billed against the user's Claude Code subscription instead of a
metered Anthropic API key. This was confirmed in a prior session to not work
on the phone's Termux environment — the Claude Code CLI does not run there.
Running a local LLM on the spare phone was also ruled out as too heavy for the
hardware.

The user wants a Siri-like personal assistant on the spare phone, starting
from a working text-in/text-out foundation, without paying for a metered API.

## Decision

Replace the Claude Code CLI backend with Google's Gemini API free tier,
called directly over HTTPS via the official `@google/genai` Node.js SDK. The
free tier requires an API key from Google AI Studio but no payment method for
free-tier usage.

`ask-claude.js` is renamed to `ask-gemini.js` and rewritten in place — no
Claude-CLI code paths are kept, since the CLI does not work in the target
environment (Termux on Android).

## Scope

In scope:
- Renaming `ask-claude.js` -> `ask-gemini.js`, updating all references
  (`package.json` `main`/`scripts`, `README.md`, `CLAUDE.md`).
- Sending a single text prompt to Gemini via `@google/genai` and returning the
  text reply.
- Loading `GEMINI_API_KEY` from a gitignored `.env` file via `dotenv`.
- Preserving the existing display behavior: print to stdout, fire a
  `termux-notification` (Android), degrade gracefully with a warning if
  Termux:API isn't installed.
- Tests for the new prompt-sending function (SDK call mocked, since Gemini is
  an external boundary this repo doesn't control) and for error-message
  formatting when the API key is missing.

Out of scope (future iterations):
- Voice input/output (Termux:API speech-to-text / text-to-speech).
- Any integration with the Google Assistant / Gemini Android app.
- Multi-turn conversation history, tool use, or broader phone control.
- Model selection/tuning beyond picking one sensible default free-tier model.

## Architecture

Same two-concern separation as the current script:

1. **Sending the prompt** (`sendPromptToGemini`): calls the Gemini API via
   `@google/genai`, using a default model constant (e.g. `gemini-2.5-flash`)
   and the API key loaded from `.env`. Returns the text reply. Throws a
   human-readable error on failure (e.g. missing/invalid API key, network
   failure) — no raw SDK stack traces surfaced to the user.
2. **Displaying the result** (`displayResultToUser`): unchanged in behavior.
   Prints to stdout, then attempts `termux-notification` with a "Gemini"
   title (renamed from "Claude"), catching and warning on failure without
   interrupting the main flow.

## Data flow

```
node ask-gemini.js "prompt"
  -> dotenv loads GEMINI_API_KEY from .env
  -> sendPromptToGemini(prompt) calls @google/genai
  -> text reply returned
  -> displayResultToUser: console.log + termux-notification
```

## Error handling

- Missing `GEMINI_API_KEY`: fail fast with a clear message telling the user
  to set it in `.env`, before attempting any network call.
- Gemini API errors (auth failure, rate limit, network issue): caught and
  converted to a readable message, mirroring
  `describeClaudeCliError`'s role in the current script.
- Notification failures: caught, logged as a warning, never fatal — same as
  today.

## Dependencies

New dependencies (both require user confirmation per CLAUDE.md, already
given during design):
- `@google/genai` — official Gemini SDK.
- `dotenv` — loads `.env`. This repo used this exact pattern previously for
  `ANTHROPIC_API_KEY` before switching to the Claude Code CLI, so it is a
  known, proven approach here.

## Testing

- Unit test for `sendPromptToGemini`, mocking the `@google/genai` client
  (external boundary) to verify the prompt is sent correctly and the text
  reply is extracted and returned.
- Unit test for the missing-API-key error path, verifying a clear message is
  thrown before any SDK call is attempted.
- Existing `displayResultToUser` behavior (stdout + notification with
  graceful degradation) is preserved as-is; a test confirms notification
  failure doesn't throw.

## Documentation updates required

- `CLAUDE.md`: update "What this is", "Architecture Overview", and
  "Commands" sections to describe the Gemini-based flow instead of the
  Claude Code CLI flow. Remove the now-inaccurate rationale about billing
  against the Claude Code subscription for the on-phone flow.
- `README.md`: update setup instructions (env var name, install command,
  dependency name).
- `package.json`: update `main`, `scripts.ask`, and `description`.
