# Design: Phase 1 native Android app (Gemini text parity, no voice)

Date: 2026-09-14

## Problem

`ask-gemini.js` is a Termux CLI: it works, but Termux is an unprivileged
Linux userland app that can never reach `NotificationListenerService`,
`AccessibilityService`, a proper `ForegroundService`, or `WorkManager` —
the capabilities the long-term assistant goal needs. `CLAUDE.md` already
decided the next iteration is a native Kotlin Android app, not more
Termux and not Expo/React Native. `roadmap.md` names Phase 1 as the first
milestone: a native app with feature parity to `ask-gemini.js` minus
voice, replacing the Termux CLI as the delivery mechanism.

This design turns that Phase 1 scope bullet list into a concrete
architecture, file layout, and testing/CI plan ready for an
implementation plan.

## Decision

Build a single-Activity, single-screen native Android app in
`android-app/` (new subdirectory in this repo; the Termux CLI at the
repo root is untouched and keeps working independently). The app sends
a typed prompt to Gemini via the official `google-genai-kotlin` SDK,
shows the reply on-screen and as an Android notification, and follows
the layered architecture, MVVM, Hilt, and repository-pattern decisions
already recorded in `CLAUDE.md`.

Key parameters fixed in this session:
- `applicationId` / base package: `com.ameyapb.androidexp`.
- `minSdk` 31 (Android 12, matches the target spare phone); `compileSdk`
  and `targetSdk` are set to the latest stable release at implementation
  time rather than pinned in this document.
- Failed Gemini calls are shown as inline error text in the reply area
  only; no error notification is fired. The send button re-enables so
  the user can retry.

## Scope

In scope:
- Single screen: prompt text field, send button, reply text area.
- Sending the prompt to Gemini via `google-genai-kotlin` and displaying
  the reply on-screen.
- Showing the reply as an Android notification, titled "Gemini" to
  match the script's `TERMUX_NOTIFICATION_TITLE`, in addition to the
  on-screen display.
- Same model and system instruction as the script
  (`GEMINI_MODEL_NAME = "gemini-3.1-flash-lite"`, and the "plain spoken
  prose, no markdown, short and conversational" system instruction),
  kept for output parity even though Phase 1 has no voice output yet.
- API key loaded from `android-app/local.properties` (gitignored) into
  `BuildConfig.GEMINI_API_KEY`, mirroring the script's `.env` handling.
- Layered `ui` / `data` / `di` package structure, MVVM with
  unidirectional data flow, Hilt constructor injection, repository
  pattern around the Gemini call — per the architecture already decided
  in `CLAUDE.md`. No `domain` layer yet (nothing to share across
  multiple ViewModels in a single-screen app).
- Unit tests for the ViewModel and the Gemini repository, using fakes.
- A GitHub Actions workflow running build/lint/test on push, scoped to
  `android-app/**`.

Out of scope (deferred to later phases per `roadmap.md`):
- Voice input/output (Phase 2).
- `NotificationListenerService`, `AccessibilityService`, background or
  foreground service, scheduled/triggered automations (Phases 3-4).
- Compose UI tests — deferred until screens stabilize, per the Testing
  section of `CLAUDE.md`.
- Multi-turn conversation history, tool use, or any other capability
  beyond what `ask-gemini.js` (minus voice) already does.

## Architecture

```
android-app/app/src/main/java/com/ameyapb/androidexp/
  GeminiApp.kt                    @HiltAndroidApp application class
  MainActivity.kt                 single Activity hosting Compose content; requests
                                   POST_NOTIFICATIONS on launch (Android 13+ only)
  ui/askgemini/
    AskGeminiScreen.kt            Composable: prompt field, send button, reply area
    AskGeminiViewModel.kt         @HiltViewModel, exposes StateFlow<AskGeminiUiState>
    AskGeminiUiState.kt           data class: promptText, replyText, isLoading, errorMessage
  data/gemini/
    GeminiClient.kt               thin interface wrapping the google-genai-kotlin call
    GeminiClientImpl.kt           real implementation using the GoogleGenAI SDK client
    GeminiRepository.kt           interface: suspend fun sendPrompt(prompt): Result<String>
    GeminiRepositoryImpl.kt       depends on GeminiClient; maps SDK failures to messages
                                   mirroring describeGeminiApiError (auth/rate-limit/generic)
    GeminiConstants.kt            GEMINI_MODEL_NAME, GEMINI_SYSTEM_INSTRUCTION (same values
                                   as ask-gemini.js, for parity)
  data/notification/
    GeminiNotifier.kt             wraps NotificationManagerCompat; channel + "Gemini"-titled
                                   notification, same title as TERMUX_NOTIFICATION_TITLE
  di/
    AppModule.kt                  Hilt bindings: GoogleGenAI client (reads BuildConfig API
                                   key), GeminiClient -> GeminiClientImpl,
                                   GeminiRepository -> GeminiRepositoryImpl
```

`GeminiClient` is the one structural addition beyond a literal
repository wrap around the SDK: `GeminiRepositoryImpl` needs something
fakeable in tests, and the `google-genai-kotlin` client class itself is
not designed to be substituted. This keeps repository tests on fakes
rather than a mocking library, per the Testing section of `CLAUDE.md`.

## Data flow

```
User types prompt, taps Send
  -> ViewModel sets isLoading=true, clears errorMessage
  -> GeminiRepository.sendPrompt() -> GeminiClient (google-genai-kotlin) with
     GEMINI_MODEL_NAME + GEMINI_SYSTEM_INSTRUCTION
  -> success: Result.success(text) -> ViewModel updates replyText, isLoading=false,
     GeminiNotifier posts a "Gemini"-titled notification (skipped silently if
     POST_NOTIFICATIONS was denied)
  -> failure: Result.failure(mapped exception) -> ViewModel sets errorMessage,
     isLoading=false, no notification
```

## Error handling

- `GeminiRepositoryImpl` maps SDK failures the same way the script's
  `describeGeminiApiError` does: an auth failure produces a message
  telling the user to check `GEMINI_API_KEY` in `local.properties`; a
  rate-limit failure tells the user they hit the free-tier limit and to
  wait and retry; anything else surfaces a generic message built from
  the underlying error text. The exact exception type and fields
  `google-genai-kotlin` throws must be confirmed against its versioned
  docs during implementation before this mapping is written — the
  Node SDK's `status` field is not assumed to carry over as-is.
- A missing or blank API key at build time makes `GeminiClientImpl` fail
  fast with a clear message rather than surfacing an opaque SDK error
  the first time a prompt is sent.
- Notification failures (permission denied, `NotificationManager`
  unavailable) never block displaying the reply on-screen, matching the
  script's graceful degradation around `termux-notification` failures.
- POST_NOTIFICATIONS (Android 13+ only) is requested once, on app
  launch, via a standard runtime permission request. If denied, the app
  does not re-prompt; it silently skips notifications for the rest of
  the session while the on-screen reply still works.

## Config

- API key stored in `android-app/local.properties` (gitignored) as
  `GEMINI_API_KEY=...`, read into `BuildConfig.GEMINI_API_KEY` via a
  `buildConfigField` in `app/build.gradle.kts` — the Gradle/Kotlin
  analog of the script's `.env` + `dotenv` handling.
- `android-app/local.properties.example` is checked into git, mirroring
  the existing `.env.example` at the repo root.

## Testing

- `AskGeminiViewModel` tests: JUnit + `kotlinx-coroutines-test`, driven
  through a `FakeGeminiRepository`, verifying state transitions
  (loading -> success with reply text; loading -> error for each of the
  auth, rate-limit, and generic error categories).
- `GeminiRepositoryImpl` tests: JUnit, driven through a
  `FakeGeminiClient`, verifying the error-message mapping without any
  real network call — `google-genai-kotlin` is the external boundary
  this repo doesn't control, so it is faked rather than exercised
  directly in unit tests.
- No Compose UI tests in Phase 1, per the explicit `CLAUDE.md` deferral
  ("Compose UI tests added once screens stabilize").

## Dependencies

New dependencies for `android-app/` (all already implied by decisions
recorded in `CLAUDE.md`, restated here for the implementation plan to
pin exact versions against current docs):
- `com.google.genai:google-genai-kotlin` — official Gemini Kotlin/Android
  SDK, the direct analog of `@google/genai` already used by the script.
- Jetpack Compose (BOM-managed) — UI layer.
- Hilt (`com.google.dagger:hilt-android` + compiler) — dependency
  injection.
- `kotlinx-coroutines-test` — ViewModel/repository test support.
- AndroidX Core / Lifecycle / Activity-Compose — standard Compose app
  scaffolding.

## CI

A new `.github/workflows/android-ci.yml`, path-filtered to
`android-app/**`, runs `./gradlew build lint testDebugUnitTest` on push
and pull request. This is separate from any future Node-based CI for
the Termux script, since the two projects have independent toolchains
and change independently.

## Documentation updates required

Tracked here for the implementation plan; not performed as part of
writing this spec:
- `CLAUDE.md` Architecture Overview: document the new native app's
  components once built, per the file's own instruction to document new
  components as they're added.
- `README.md`: add an Android app setup section (Android Studio /
  `local.properties` / `./gradlew` commands), alongside the existing
  Termux CLI instructions.
- `roadmap.md`: no changes needed — Phase 1's scope description there
  already matches this design.
