# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A personal project to let the user talk to an AI assistant from a spare Android phone, starting with a minimal Termux CLI and growing over time toward the assistant having more control over that phone. Single user (the repo owner), no external users. Current foundation: `ask-gemini.js`, a Node.js CLI that sends a one-off text prompt to Google's Gemini API and surfaces the reply as both stdout and a Termux Android notification.

`ask-gemini.js` calls Gemini's free tier directly over HTTPS via the official `@google/genai` SDK. An earlier version of this script shelled out to the Claude Code CLI to bill usage against a Claude Code subscription instead of a metered key, but the Claude Code CLI does not run in Termux on Android, and running a local model on the spare phone is too heavy for the hardware — Gemini's free tier avoids both problems while still not requiring a paid API key. This requires a `GEMINI_API_KEY` from Google AI Studio (https://aistudio.google.com/apikey), stored in a gitignored `.env` file, and is intended for light, manual, personal use.

Hosted privately on GitHub at `github.com/ameyapb/android-exp-integration`. The code is developed on the user's PC and deployed by cloning/pulling the repo inside Termux on the phone (`pkg install nodejs git`, then `git clone`/`git pull`, then `npm install`).

### Future direction: native Android app

The long-term goal is an assistant with real control over the phone: triggering actions/automations, reading phone state and context, and eventually running as an always-on background agent, not just a one-shot request/reply from a terminal shortcut. The Termux CLI (`ask-gemini.js`) is the current foundation but is understood to be a stepping stone, not the end state.

Decision: the next major iteration will be rebuilt as a **native Android app in Kotlin**, not Termux plus more `termux-*` commands, and not an Expo/React Native app.

Why native Kotlin over Expo: Expo's managed workflow only exposes what's in the Expo SDK, and the capabilities this project ultimately needs — `NotificationListenerService` (reading other apps' notifications), `AccessibilityService` (observing/acting on other apps, how automation tools like Tasker work), and a true `ForegroundService` for durable background operation — all require native modules via a config plugin (ejecting from Expo Go). That native code is identical to what a pure-native app would write, just with an added JS bridge, an extra plugin/prebuild compatibility surface, and RN version churn on top. Native Kotlin gives every capability Expo could eventually reach, with strictly less complexity, so there is no capability upside to starting in Expo — only faster initial UI iteration, which does not outweigh avoiding a framework migration later.

Why native Kotlin over the current Termux approach: Termux is an unprivileged Linux userland app. It can only reach phone features that Termux:API's helper app explicitly wraps and shells out to (`termux-notification`, `termux-location`, etc.) — a fixed menu it does not control. It cannot register as a `NotificationListenerService` or `AccessibilityService`, run a proper `ForegroundService`, integrate with `WorkManager`/`AlarmManager`, or declare the sensitive permissions those require, since those all need an installed app with its own manifest and permission declarations.

This is a platform decision — see `roadmap.md` for what gets built and in what order.

### Native app architecture and tech stack

The native app is built to production-quality engineering standards from Phase 1 onward, even though it has one user — this is a deliberate choice, not scope creep, so the codebase doesn't need a rewrite once it grows. Concretely:

- **Layered architecture**: UI / (optional) domain / data layers, per Google's official Android architecture guidance (`developer.android.com/topic/architecture/recommendations`). The domain layer is added only once business logic needs to be shared across more than one ViewModel — not created speculatively.
- **UI layer**: Jetpack Compose, MVVM with unidirectional data flow — ViewModels expose state via `StateFlow`, the UI sends actions back via method calls, never the reverse.
- **Data layer**: repository pattern wraps every external call (starting with the Gemini API), so UI/ViewModels never talk to a data source directly. The Gemini call is made with a direct HTTPS POST (via `HttpURLConnection` + `org.json`, both built into the Android platform, no extra dependency) to the `models.generateContent` REST endpoint, not the official `google-genai-kotlin` SDK — see the security note below for why.
- **Dependency injection**: Hilt, constructor injection throughout.
- **Module strategy**: a single Gradle module now, with internal packages laid out by layer-then-feature (`ui/`, `domain/`, `data/`, `di/`, with feature subpackages under `ui/`) so the boundaries already match Google's modularization guide (`developer.android.com/topic/modularization`). Split into real Gradle `:feature:*`/`:core:*` modules only once the app has enough independent features to justify the build-complexity cost — not before. This mirrors the YAGNI principle already in this file: production-quality structure now, premature multi-module ceremony deferred.
- **Testing**: JUnit for ViewModels and repositories, fakes preferred over mocks (matches the Testing section below), Compose UI tests added once screens stabilize. Robolectric is the accepted exception for classes that wrap a boundary a fake can't stand in for (real Android framework internals invoked by a support-library call, or `org.json` parsing, which is an empty stub in the plain JVM unit-test classpath and only works under Robolectric or on-device) — see the native app architecture section for where it's used.
- **CI**: a GitHub Actions workflow runs build, lint, and test on every push, since the repo is already hosted on GitHub.
- **Security note on the API key**: the official `google-genai-kotlin` SDK does not merely discourage embedding an API key on Android, it hard-blocks it — `Client(apiKey = ...)` throws `IllegalStateException("SECURITY FATAL: ...")` unconditionally at construction time on Android, with no override, confirmed by decompiling the SDK jar (`SecurityContextKt.validateSecurityContext`, unconditional on `hasApiKey || hasCredentials`). This was only discovered by running the Phase 1 app on-device, since the earlier design took Google's own SDK docs (which frame this as a reverse-engineering risk to accept, not a hard stop) at face value. Since this app is sideloaded for personal use and never distributed publicly, the underlying risk the check guards against doesn't apply here, so the fix is to bypass the SDK's `Client` entirely and call the Gemini REST API directly over HTTPS, keeping local-config key storage (mirroring the current `.env` approach, e.g. `local.properties` read into `BuildConfig`, gitignored) exactly as originally planned. Revisit with Firebase AI Logic + App Check if the app is ever distributed beyond the user's own device — that remains Google's recommended path for a real multi-device/public app.

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

The `sshd` start and the `adb forward` do not persist across phone reboots or Termux restarts and must be re-run each session. Unplugging and reconnecting the USB cable also drops the `adb forward` (confirmed by direct testing: `adb forward --list` came back empty after a replug, causing `ssh: connect to host localhost port 8022: Connection refused`), even if `sshd` is still running on the phone and `adb devices` still shows the device as authorized. There is no way to make the forward persist across a replug; re-run `adb forward tcp:8022 tcp:8022` after every USB reconnect, and only re-run `sshd` on the phone if `pgrep sshd` shows it's no longer running.

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

### Native Android app (`android-app/`)

Phase 1 native app per `roadmap.md`, living alongside the untouched Termux CLI at the repo root. Single Gradle module (`android-app/app`), package `com.ameyapb.androidexp`, built with AGP 9's built-in Kotlin support (no separate `org.jetbrains.kotlin.android` plugin), the `org.jetbrains.kotlin.plugin.compose` Compose compiler plugin, and `com.android.legacy-kapt` for Hilt's annotation processor (`org.jetbrains.kotlin.kapt` is incompatible with built-in Kotlin; Hilt's Gradle plugin needs 2.59.2+ for AGP 9, hence Hilt 2.60.1 here). Dependency versions are pinned in `android-app/gradle/libs.versions.toml`.

- `GeminiApp` (`@HiltAndroidApp`) and `MainActivity` (`@AndroidEntryPoint`, single Activity hosting Compose content, requests `POST_NOTIFICATIONS` on launch on Android 13+ only) sit at the package root.
- `ui/askgemini/`: `AskGeminiScreen` (prompt field, icon-based send/mic buttons, reply area — see the `ui/theme/` bullet below for the Deep visual theme), `AskGeminiViewModel` (`@HiltViewModel`, exposes `StateFlow<AskGeminiUiState>`, unidirectional data flow), `AskGeminiUiState`.
- `data/gemini/`: `GeminiClient`/`GeminiClientImpl` call the Gemini `models.generateContent` REST endpoint directly over `HttpURLConnection` (JSON built/parsed with `org.json`), not the official `google-genai-kotlin` SDK — that SDK hard-blocks API-key construction on Android (see the security note above), so it was dropped entirely after the Phase 1 on-device crash it caused. The pure request-building/response-parsing/error-mapping logic lives in top-level `internal` functions (`buildGenerateContentRequestBody`, `parseGenerateContentResponse`, `extractReplyText`, `extractErrorMessage`) so it's directly unit-testable without mocking the network; only the thin `HttpURLConnection` I/O glue is untested. `GeminiHttpException(code, message)` carries the HTTP status code for downstream mapping. `GeminiRepository`/`GeminiRepositoryImpl` wrap `GeminiClient` and map `GeminiHttpException.code` into the same auth/rate-limit/generic messages as the script's `describeGeminiApiError` (401 → check the API key, 429 → free-tier rate limit, else generic); `GeminiConstants` holds `GEMINI_MODEL_NAME`/`GEMINI_SYSTEM_INSTRUCTION`/the REST base URL/timeout, kept identical to the script's for output parity.
- `data/notification/`: `GeminiNotifier`/`GeminiNotifierImpl` wrap `NotificationManagerCompat`, posting a "Gemini"-titled notification (matching `TERMUX_NOTIFICATION_TITLE`) that degrades silently if notifications are disabled or denied — mirroring the script's graceful degradation around `termux-notification`.
- `di/AppModule.kt`: Hilt bindings for the three interfaces above, plus `provideGeminiApiKey()` (qualified with the `@GeminiApiKey` annotation, since an unqualified `String` binding risks collisions), which fails fast with a clear message if `BuildConfig.GEMINI_API_KEY` is blank rather than surfacing an opaque error on first send.
- The API key is loaded from a gitignored `android-app/local.properties` (see `local.properties.example`) into `BuildConfig.GEMINI_API_KEY`, mirroring the script's `.env` handling — never hardcoded or logged (including in the REST call, where it rides in the URL query string but is never written to Logcat).
- Unit tests (JUnit + `kotlinx-coroutines-test`, fakes over mocks) cover `GeminiRepositoryImpl` and `AskGeminiViewModel`. `GeminiClientImpl`'s pure functions and `GeminiNotifierImpl` both run under Robolectric (shared `ROBOLECTRIC_SDK_LEVEL` constant in `src/test/.../RobolectricTestConfig.kt`): `org.json` is an empty stub on the plain JVM unit-test classpath and only has a real implementation under Robolectric or on-device, and separately `NotificationCompat.Builder.build()` calls real `android.app.Notification.Builder` internals the plain stub jar can't execute either — so a fake or mock of our own code can't reach either failure. `.github/workflows/android-ci.yml` runs `./gradlew build lint testDebugUnitTest` on pushes/PRs touching `android-app/**`.
- No launcher icon resource is set (manifest omits `android:icon`) — accepted simplification for a sideloaded personal app.
- `data/voice/`: Phase 2 voice input, both sub-phases complete. `VoiceRecognizer`/`VoiceRecognizerImpl` wrap `android.speech.SpeechRecognizer` behind `suspend fun listen(): Result<String>`, using `suspendCancellableCoroutine` and a `RecognitionListener`; a top-level `internal fun describeVoiceRecognitionError(errorCode: Int): String` maps `SpeechRecognizer.ERROR_*` codes to human-readable messages, mirroring `GeminiClientImpl`'s pure parsing functions. `GeminiSpeaker`/`GeminiSpeakerImpl` wrap `android.speech.tts.TextToSpeech` behind `fun speak(text: String)`, tracking an `isReady` flag set by the `TextToSpeech.OnInitListener` callback and silently skipping (with a logged warning) if init failed, never yet completed, or the reply is a typed (not voice-confirmed) one. `RECORD_AUDIO` is requested on demand (first mic tap), not eagerly like `POST_NOTIFICATIONS`. `AskGeminiUiState` gained `isListening`/`pendingVoiceTranscript`; `AskGeminiViewModel` gained `onMicClicked`/`onVoiceTranscriptConfirmed`/`onVoiceTranscriptDiscarded`, with the send-and-notify logic shared between the typed and voice paths via a private `sendPromptAndHandleResult(prompt, speakReplyAloud: Boolean)` — only the voice-confirmed path passes `speakReplyAloud = true` and calls `geminiSpeaker.speak(replyText)`. `AskGeminiScreen` adds a "Speak"/"Listening..." button and a Compose `AlertDialog` showing the full transcript (scrollable) with Send/Discard actions; dismissing the dialog counts as Discard. `VoiceRecognizerImplTest` and `GeminiSpeakerImplTest` both run under Robolectric, same precedent as `GeminiClientImplTest`/`GeminiNotifierImplTest`. Two Robolectric shadow quirks worth knowing: `SpeechRecognizer`'s shadow dispatches listener registration through the main-thread `Handler`, so tests need `shadowOf(Looper.getMainLooper()).idle()` after `runCurrent()` before triggering a callback; `TextToSpeech`'s shadow never calls `onInit` on its own (the real engine-connection logic that would call it is replaced entirely by the shadow's `initTts()`), so `GeminiSpeakerImplTest` calls `shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(...)` directly to simulate init completing or failing. Post-Phase-2 audit fixes: `AskGeminiViewModel.onSendClicked`/`onMicClicked` now guard against `isLoading`/`isListening` already being true, closing a race where a typed send and a voice capture in flight at the same time could both resolve and double-notify/double-speak; `VoiceRecognizerImpl.listen()` wraps the `suspendCancellableCoroutine` block in a try/catch (re-throwing `CancellationException`) so a synchronous platform exception from `createSpeechRecognizer`/`startListening` becomes a `Result.failure` instead of crashing the app; `GeminiSpeaker` gained `shutdown()` (stops and releases the `TextToSpeech` engine), wired via `ViewModel.addCloseable` in `AskGeminiViewModel`'s init block since `GeminiSpeakerImpl` is a Hilt singleton with no other teardown hook; denying the `RECORD_AUDIO` permission prompt now calls a new `AskGeminiViewModel.onMicPermissionDenied()` to surface an error instead of silently no-opping; and the `ContextCompat.checkSelfPermission(...) == PackageManager.PERMISSION_GRANTED` check duplicated between `MainActivity` (notifications) and `AskGeminiScreen` (microphone) was extracted into `util/PermissionUtils.kt#isPermissionGranted`.
- `ui/theme/`: `Color.kt` (named color tokens: `SurfaceBase`, `SurfaceRaised`, `SurfaceComposer`, `BorderComposer`, `Accent`, `AccentMuted`, `TextPrimary`, `TextMuted`) and `Theme.kt` (`GeminiAppTheme`, wrapping a fixed dark `ColorScheme` — no light-mode or dynamic/Material You theming, since the app is single-user and the chosen "Deep" visual direction is inherently dark). Applied in `MainActivity` around `AskGeminiScreen`. `AskGeminiScreen` was restructured to match: a `TopAppBar` (title plus an overflow icon button that is currently a visual anchor only, not wired to a menu yet) over a rounded-top-corner `Surface` for body content, and `FilledIconButton`/`OutlinedIconButton` (`material-icons-core` dependency) for send/mic instead of full-width text buttons, with loading/listening state now expressed via `enabled`/`contentDescription` rather than button-label text swaps. The mic glyph is a hand-drawn `ImageVector` (`AskGeminiScreen.kt`'s private `MicIcon`) since `material-icons-core` doesn't include one and `material-icons-extended` would pull in roughly 2000 unrelated icons for a single glyph. Chosen via `superpowers:brainstorming`'s visual companion; design spec: `docs/superpowers/specs/2026-09-15-basic-ux-polish-design.md`. Verified on-device via `adb screencap`: the full golden path (typing a prompt, tapping send, a live Gemini reply rendering with the teal avatar dot/label) matches the approved mockup.
