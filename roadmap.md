# Roadmap

Tracks the path from the current Termux CLI (`ask-gemini.js`) to a native Android app with deeper control over the phone. See `CLAUDE.md` for the platform decision (native Kotlin, not Expo, not more Termux) and the architecture/tech-stack decision that applies from Phase 1 onward.

## Phase 1: Minimal native app (built, not yet verified on-device)

Goal: a native Android app that does everything `ask-gemini.js` currently does except voice, nothing more. This replaces the Termux CLI as the delivery mechanism, not as a feature upgrade.

**Status (2026-09-14): implemented and committed to `main` in `android-app/`.** Design spec: `docs/superpowers/specs/2026-09-14-phase1-native-app-design.md`. Implementation plan (includes real-toolchain findings that changed the original design, e.g. AGP 9's built-in Kotlin support, Hilt version bump for AGP 9 compatibility, compileSdk 37): `docs/superpowers/plans/2026-09-14-phase1-native-app.md`.

**Post-implementation audit (2026-09-14):** an end-to-end code review found and fixed three issues before on-device verification: `GeminiRepositoryImpl.sendPrompt` only caught the SDK's `GenAiApiException`, so a network/IO failure (no connectivity, DNS failure) would have crashed the app instead of showing a graceful error, same parity gap as if `ask-gemini.js`'s blanket `catch` were narrowed; the fix broadens the catch to `Exception` while explicitly rethrowing `CancellationException` so coroutine cancellation isn't swallowed. `android-ci.yml` requested the SDK package `platforms;android-37.2`, which isn't a valid `sdkmanager` package id (platform ids are integer-only) and would have failed CI on the first push; fixed to `platforms;android-37`. `GeminiNotifierImpl` and `GeminiClientImpl` had no tests, breaking the project's per-change test rule; added `GeminiClientImplTest` (Mockito-kotlin, mocking the third-party SDK's concrete `Client`/`Models` types, which aren't fakeable interfaces) and `GeminiNotifierImplTest` (Robolectric, since `NotificationCompat.Builder.build()` calls real `android.app.Notification.Builder` internals that Android's plain unit-test stub jar can't execute). Both are new `testImplementation`-only dependencies, pinned in `libs.versions.toml`. Verified with a real Gradle build against an installed Android SDK: `./gradlew build lint testDebugUnitTest` passes (14 unit tests, zero lint issues) and both debug and release APKs assemble successfully.

**Not yet verified**: no run on a physical device or emulator, so the UI and the on-device notification have not been visually confirmed.

Scope (all delivered):
- Single screen: a text input for the prompt, a send button, and a text area showing Gemini's reply.
- Sends the prompt to the Gemini API and displays the reply on-screen, via the official `google-genai-kotlin` SDK (Google's first-party Kotlin/Android client, the direct analog of the `@google/genai` SDK the script uses) rather than hand-rolled HTTPS calls.
- Shows the reply as an Android notification as well as on-screen, matching the script's current notification behavior.
- API key loaded from local, gitignored app config (`android-app/local.properties` read into `BuildConfig`), never hardcoded or logged, mirroring the script's `.env` handling. See the security note in `CLAUDE.md` about client-embedded keys.
- Built on the architecture and module layout defined in `CLAUDE.md` (single Gradle module, layered `ui`/`data`/`di` packages, MVVM + unidirectional data flow, repository pattern around the Gemini call) rather than a throwaway single-Activity script — this is the foundation later phases build on. No `domain` package yet, per `CLAUDE.md`'s own rule that it's added only once logic needs to be shared across more than one ViewModel.
- Unit tests for the ViewModel and the Gemini repository (fakes over mocks), plus the Gemini SDK wrapper and the notifier (Mockito-kotlin and Robolectric respectively, added in the post-implementation audit above for the two classes that wrap boundaries fakes can't reach), plus a GitHub Actions workflow (`.github/workflows/android-ci.yml`) running build/lint/test on push.
- No voice input, no `NotificationListenerService`, no `AccessibilityService`, no background/foreground service, no automations. Those are explicitly out of scope until later phases.

## Phase 2: Voice input (next target)

Goal: reach feature parity with `ask-gemini.js --voice` inside the native app. This is the next milestone now that Phase 1 is built, chosen first among the deferred capabilities because it's already proven in the script and is the fastest path to full parity.

Scope:
- Voice capture via Android's `SpeechRecognizer` API, replacing the script's `termux-speech-to-text` shell-out.
- In-app confirmation UI (an `AlertDialog` or Compose dialog showing the full transcript, scrollable, with confirm/discard actions) replacing the script's `termux-dialog`-based confirm step. Same intent as the script's flow: show the transcript, let the user accept or discard it unchanged before it's sent.
- Spoken replies via Android's `TextToSpeech` API, replacing `termux-tts-speak`.
- Reuses the Phase 1 repository/ViewModel layer for the actual Gemini call — voice is a new input/output surface on top of the same data layer, not a parallel path.
- Still explicitly out of scope: `NotificationListenerService`, `AccessibilityService`, background/foreground service, automations.

## Phase 3: Background & always-on operation

Not yet designed in detail. Expected shape once it's prioritized:
- A `ForegroundService` so the assistant can run persistently rather than only in response to a foreground launch.
- `WorkManager`/`AlarmManager` integration for scheduled or triggered background work.
- Groundwork for `NotificationListenerService` (reading other apps' notifications as context), without necessarily acting on them yet.

## Phase 4: Deep phone control

Not yet designed in detail. Expected shape once it's prioritized:
- `AccessibilityService`-based observation of and action on other apps (the mechanism automation tools like Tasker use).
- Turns the assistant from "answers when asked" into something that can trigger actions/automations across the phone, per the long-term goal described in `CLAUDE.md`.

Ordering past Phase 2 is not locked in — Phase 3 and 4 may be reprioritized or interleaved once Phase 2 ships and it's clearer which capability matters most.
