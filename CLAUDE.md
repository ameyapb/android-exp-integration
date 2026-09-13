# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A personal project to let the user talk to an AI assistant from a spare Android phone, starting with a minimal Termux CLI and growing over time toward the assistant having more control over that phone. Single user (the repo owner), no external users. Current foundation: `ask-gemini.js`, a Node.js CLI that sends a one-off text prompt to Google's Gemini API and surfaces the reply as both stdout and a Termux Android notification.

`ask-gemini.js` calls Gemini's free tier directly over HTTPS via the official `@google/genai` SDK. An earlier version of this script shelled out to the Claude Code CLI to bill usage against a Claude Code subscription instead of a metered key, but the Claude Code CLI does not run in Termux on Android, and running a local model on the spare phone is too heavy for the hardware — Gemini's free tier avoids both problems while still not requiring a paid API key. This requires a `GEMINI_API_KEY` from Google AI Studio (https://aistudio.google.com/apikey), stored in a gitignored `.env` file, and is intended for light, manual, personal use.

Hosted privately on GitHub at `github.com/ameyapb/android-exp-integration`. The code is developed on the user's PC and deployed by cloning/pulling the repo inside Termux on the phone (`pkg install nodejs git`, then `git clone`/`git pull`, then `npm install`).

### Remote access to Termux from the PC

Termux on the phone can be driven directly from a PC terminal over USB, instead of typing on the phone's touch keyboard. This is optional tooling for the user's own workflow, not part of the app itself.

Phone side (Termux, one-time setup):
```
pkg install openssh
passwd        # sets a password for SSH login
```

Phone side (each Termux session):
```
sshd          # starts the SSH server on port 8022
whoami        # prints the Termux username, e.g. u0_a296
```

PC side (Windows, one-time setup): install Android platform-tools (`adb`) via `winget install Google.PlatformTools` or the Android developer site, and enable USB debugging in the phone's Developer Options. On first USB connection, authorize the PC from the prompt shown on the phone.

PC side (each session):
```
adb devices                    # confirm the device shows as "device", not "unauthorized"
adb forward tcp:8022 tcp:8022  # tunnel Termux's sshd port over the USB cable
ssh -p 8022 <username>@localhost
```

The `sshd` start and the `adb forward` do not persist across phone reboots or Termux restarts and must be re-run each session.

## Claude Code Instructions

These rules are carried over from the user's other projects (`cloud_kitchen`, `daily_tracker`, `transport_ledger`, `stock_algo`, `ks8_learning`, `dota_2_helper`, `context_compressor`) where they are applied consistently regardless of stack. They apply here from the start.

- Make surgical, minimal diffs — don't refactor what isn't broken, but if you see code repeating in 2+ places, extract something reusable.
- ALWAYS FOLLOW DRY — never repeat even a single line of logic; extract duplicated expressions into a named constant, helper, or shared module.
- NEVER USE MAGIC NUMBERS OR STRINGS — every literal that carries meaning (timeouts, thresholds, status values, identifiers, ports, etc.) must live in a named constant, not be scattered inline.
- Naming — use long, self-explanatory names for functions, variables, and types. No abbreviations unless universally understood (e.g. `id`, `url`).
- Write modular code — keep functions and components small and single-purpose; split a file when it starts covering more than one concern rather than letting it grow unbounded.
- Use a scalable directory structure — organize into subfolders per service/domain rather than flat-dumping files at the root.
- No unnecessary comments — don't restate what the code already says. Only comment to explain a non-obvious constraint, workaround, or subtle invariant.
- No emojis and no em dashes (—) in code, commit messages, or this file — they read as AI-generated filler.
- Get rid of dead code as soon as you see it — don't comment it out or leave it "just in case". If it's not used, it doesn't belong in the codebase.
- Production mindset — treat every change as if it ships to production tomorrow. No half-finished logic, no debug leftovers, no disabled safety checks.
- Prefer a library over hand-rolled low-level code — if an established library already solves it, use it instead of reimplementing. Always ask before adding the dependency.
- Never introduce new libraries or dependencies without asking first.
- Never change core architecture (data model, navigation structure, execution flow) without asking first.
- Always use existing patterns in the codebase for new queries, endpoints, or screens — don't invent a second way to do something the codebase already does.
- If a proposed approach conflicts with an existing pattern in this codebase, call it out before implementing and prefer the existing pattern unless there's a clear, documented reason to change.
- If a task or request is vague or ambiguous, ask a single clarifying question before writing code to avoid rework.
- Before writing calls against any external API/SDK, read the exact versioned docs — API surfaces change between versions.
- Keep business logic out of presentational/UI components.
- Keep this file updated in the same change whenever architecture changes, a coding rule is added or modified, or a development workflow step changes.

### Mistakes to avoid (learned the hard way, from other repos)

- Before deleting or renaming any file, grep the whole repo — code, comments, and docs — for references to its name and update every one in the same change.
- Never delete a tracked file that's wired into a documented workflow without asking first and getting explicit confirmation, even if its own docstring seems to pre-authorize deletion under some condition — verify the condition is actually met and let the user make the call.
- When a second module needs a small convention another module already implements, extract the shared helper the moment the second consumer appears — don't let it duplicate first and fix later.

## Testing

- Every new module, endpoint, or non-trivial function must get a corresponding test in the same change — don't defer tests to a later pass.
- Run the full test suite before considering any change complete; all tests must pass.
- Never remove, comment out, or loosen a test to make a change land. If a test fails after a change, fix the code to restore the behavior, not the test. If a feature is intentionally removed, the failing test is evidence that must be explicitly discussed and confirmed before the test is touched.
- Prefer testing real behavior over mocks; mock only external boundaries the repo doesn't control.

## Security

- Never hardcode API keys, credentials, or secrets — load them from environment variables or a gitignored config, and never log them.
- Never log or expose sensitive content beyond what's strictly required for the immediate computation.
- Follow the principle of least privilege for any external API or platform usage.

## Process & workflow notes (from other repos, apply once this project has real work)

- Pre-production only: work directly on the main branch — don't create git worktrees or feature branches for skill-driven workflows (plans, subagent-driven development) unless the user explicitly asks for one. Revisit once there's a real deployment or shared branch to protect.
- If an implementation plan has multiple phases, never execute all of them in one uninterrupted run unless explicitly told to — implement directly in-session and check in before starting a new phase.
- Token budget constraint: the user runs on a metered plan, not unlimited tokens. Treat context window usage as a real cost: prefer direct, concise implementation over exploratory back-and-forth, and don't re-read files or re-derive already-established context. When phasing an implementation plan, size each phase to roughly 5-8 files touched/created as a ceiling so it fits in one session without the context window filling up mid-phase.
- Termux:Widget does not reliably list symlinked scripts in `~/.shortcuts/` — deploy shortcut scripts there with `cp`, not `ln -s`, and re-copy after every `git pull` that changes the script (confirmed by direct testing on-device: the widget picker showed zero scripts with a symlink present, and started listing the script immediately after replacing it with a real copy).

## Commands

- `npm install` — install `@google/genai` and `dotenv` (once per phone/clone).
- `node ask-gemini.js "<prompt>"` — send a prompt to Gemini and print/notify the reply.
- `node ask-gemini.js --voice` — record a spoken prompt via Termux:API, confirm the transcript, send it to Gemini, and speak the reply aloud.
- `npm test` — run the test suite.

No build step, no TypeScript, no lint tooling yet — add these when the project grows past a single script.

## Architecture Overview

Runs inside Termux on Android. `ask-gemini.js` is a single-file CLI with separated concerns: calling Gemini via the `@google/genai` SDK (`ai.models.generateContent({ model, contents })`), capturing a spoken prompt via Termux:API's `termux-speech-to-text` (`captureVoicePrompt`, used when the `--voice` flag is passed), confirming a voice transcript with the user before sending it — preferring a native Android dialog via Termux:API's `termux-dialog` (`confirmPromptWithUserViaDialog`, wrapped by `confirmPromptWithFallback`), which shows the full transcript as the read-only, scrollable body of a `confirm`-type dialog (not the `text` widget's editable field, which only supports single-line hint/placeholder text and can't display or scroll a long transcript) with Yes/No buttons (note: termux-dialog's confirm widget always reports `code: 0` for both buttons — the tapped button is only distinguishable via the JSON `text` field, `"yes"` or `"no"`): Yes sends the transcript unchanged, No discards it; falls back to the original stdin `y/N` prompt (`confirmPromptWithUser`) if `termux-dialog` is unavailable — displaying the result (stdout plus a `termux-notification` shell-out that degrades gracefully if unavailable), and, in `--voice` mode only, speaking the reply aloud via Termux:API's `termux-tts-speak` (`speakResponseAloud`, which likewise degrades gracefully if unavailable). The API key is loaded from a gitignored `.env` file via `dotenv`, never hardcoded or logged. `shortcuts/ask-gemini-voice.sh` is a Termux:Widget shortcut script that runs `node ask-gemini.js --voice`, deployed with `cp` (not symlinked, see the Termux:Widget gotcha above) into `~/.shortcuts/` on the phone for a one-tap, no-typing voice query from the home screen. Every run through `main()`, in both plain-prompt and `--voice` mode, pauses briefly (`WIDGET_SESSION_CLOSE_DELAY_MS`) after displaying its result or error so the terminal session launched by the Termux:Widget shortcut always exits rather than lingering open. This is the foundation for giving the assistant broader control over the phone in future iterations — document new components here as they're added.
