# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A personal project to let the user talk to Claude from a spare Android phone, starting with a minimal Termux CLI and growing over time toward Claude having more control over that phone. Single user (the repo owner), no external users. Current foundation: `ask-claude.js`, a Node.js CLI that sends a one-off text prompt to the Claude API and surfaces the reply as both stdout and a Termux Android notification.

Hosted privately on GitHub at `github.com/ameyapb/android-exp-integration`. The code is developed on the user's PC and deployed by cloning/pulling the repo inside Termux on the phone (`pkg install nodejs git`, then `git clone`/`git pull`, then `npm install`).

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

## Commands

- `npm install` — install dependencies (currently just `@anthropic-ai/sdk`).
- `node ask-claude.js "<prompt>"` — send a prompt to Claude and print/notify the reply.

No build step, no TypeScript, no lint/test tooling yet — add these when the project grows past a single script.

## Architecture Overview

Runs inside Termux on Android. `ask-claude.js` is a single-file CLI with three separated concerns: reading/validating `ANTHROPIC_API_KEY` from the environment, calling the Claude API via `@anthropic-ai/sdk`, and displaying the result (stdout plus a `termux-notification` shell-out that degrades gracefully if unavailable). The model name is a top-of-file constant (`CLAUDE_MODEL_NAME`, currently `claude-haiku-4-5-20251001`) swappable for `claude-sonnet-5` for higher-quality answers at higher cost. This is the foundation for giving Claude broader control over the phone in future iterations — document new components here as they're added.
