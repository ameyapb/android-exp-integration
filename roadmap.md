# Roadmap

Tracks the path from the current Termux CLI (`ask-gemini.js`) to a native Android app with deeper control over the phone. See `CLAUDE.md` for the platform decision (native Kotlin, not Expo, not more Termux) and the architecture/tech-stack decision that applies from Phase 1 onward.

## Phase 1: Minimal native app (built, not yet verified on-device)

Goal: a native Android app that does everything `ask-gemini.js` currently does except voice, nothing more. This replaces the Termux CLI as the delivery mechanism, not as a feature upgrade.

**Status (2026-09-14): implemented and committed to `main` in `android-app/`.** Design spec: `docs/superpowers/specs/2026-09-14-phase1-native-app-design.md`. Implementation plan (includes real-toolchain findings that changed the original design, e.g. AGP 9's built-in Kotlin support, Hilt version bump for AGP 9 compatibility, compileSdk 37): `docs/superpowers/plans/2026-09-14-phase1-native-app.md`.

**Post-implementation audit (2026-09-14):** an end-to-end code review found and fixed three issues before on-device verification: `GeminiRepositoryImpl.sendPrompt` only caught the SDK's `GenAiApiException`, so a network/IO failure (no connectivity, DNS failure) would have crashed the app instead of showing a graceful error, same parity gap as if `ask-gemini.js`'s blanket `catch` were narrowed; the fix broadens the catch to `Exception` while explicitly rethrowing `CancellationException` so coroutine cancellation isn't swallowed. `android-ci.yml` requested the SDK package `platforms;android-37.2`, which isn't a valid `sdkmanager` package id (platform ids are integer-only) and would have failed CI on the first push; fixed to `platforms;android-37`. `GeminiNotifierImpl` and `GeminiClientImpl` had no tests, breaking the project's per-change test rule; added `GeminiClientImplTest` (Mockito-kotlin, mocking the third-party SDK's concrete `Client`/`Models` types, which aren't fakeable interfaces) and `GeminiNotifierImplTest` (Robolectric, since `NotificationCompat.Builder.build()` calls real `android.app.Notification.Builder` internals that Android's plain unit-test stub jar can't execute). Both are new `testImplementation`-only dependencies, pinned in `libs.versions.toml`. Verified with a real Gradle build against an installed Android SDK: `./gradlew build lint testDebugUnitTest` passes (14 unit tests, zero lint issues) and both debug and release APKs assemble successfully.

**On-device verification (2026-09-14):** the first install crashed immediately on launch — `com.google.genai.kotlin.Client(apiKey = ...)` throws an unconditional `IllegalStateException` on Android ("SECURITY FATAL: Initializing the Client with an API Key or Credentials on Android is blocked..."), confirmed via `adb logcat` and by decompiling the SDK jar (no override flag exists). This invalidated the Phase 1 plan's assumption, carried over from the SDK's own docs, that embedding a key was an acceptable-but-discouraged risk rather than a hard stop. Fix: dropped the `google-genai-kotlin` dependency entirely and rewrote `GeminiClientImpl` to call the Gemini `models.generateContent` REST endpoint directly over `HttpURLConnection`/`org.json` (both platform-provided, no new dependency), keeping the same local-`BuildConfig`-key approach. Also dropped the now-unused `mockito-kotlin` dependency and moved `GeminiClientImpl`'s tests to Robolectric (needed anyway, since `org.json` is a stub on the plain JVM test classpath). Verified for real after the fix: `./gradlew build lint testDebugUnitTest` passes, the APK installs and launches on the physical device without crashing, a live prompt round-trips a real Gemini reply to the on-screen text area, and the Android notification posts on the `gemini_replies` channel. See the security note in `CLAUDE.md`'s native app architecture section for the full detail.

Scope (all delivered):
- Single screen: a text input for the prompt, a send button, and a text area showing Gemini's reply.
- Sends the prompt to the Gemini API and displays the reply on-screen. Originally planned to go through the official `google-genai-kotlin` SDK (Google's first-party Kotlin/Android client, the direct analog of the `@google/genai` SDK the script uses); switched to a direct HTTPS REST call after on-device verification found the SDK hard-blocks API-key usage on Android (see the on-device verification note above and the security note in `CLAUDE.md`).
- Shows the reply as an Android notification as well as on-screen, matching the script's current notification behavior.
- API key loaded from local, gitignored app config (`android-app/local.properties` read into `BuildConfig`), never hardcoded or logged, mirroring the script's `.env` handling. See the security note in `CLAUDE.md` about client-embedded keys.
- Built on the architecture and module layout defined in `CLAUDE.md` (single Gradle module, layered `ui`/`data`/`di` packages, MVVM + unidirectional data flow, repository pattern around the Gemini call) rather than a throwaway single-Activity script — this is the foundation later phases build on. No `domain` package yet, per `CLAUDE.md`'s own rule that it's added only once logic needs to be shared across more than one ViewModel.
- Unit tests for the ViewModel and the Gemini repository (fakes over mocks), plus the Gemini SDK wrapper and the notifier (Mockito-kotlin and Robolectric respectively, added in the post-implementation audit above for the two classes that wrap boundaries fakes can't reach), plus a GitHub Actions workflow (`.github/workflows/android-ci.yml`) running build/lint/test on push.
- No voice input, no `NotificationListenerService`, no `AccessibilityService`, no background/foreground service, no automations. Those are explicitly out of scope until later phases.

## Phase 2: Voice input (in progress)

Goal: reach feature parity with `ask-gemini.js --voice` inside the native app. This is the next milestone now that Phase 1 is built, chosen first among the deferred capabilities because it's already proven in the script and is the fastest path to full parity.

Design spec: `docs/superpowers/specs/2026-09-15-phase2-voice-input-design.md`. Split into two implementation sub-phases per `CLAUDE.md`'s per-session file-count ceiling; sub-phase 2a's plan is `docs/superpowers/plans/2026-09-15-phase2a-voice-capture.md`.

**Status (2026-09-15): sub-phase 2a implemented and committed to `main`.** Voice capture (`data/voice/VoiceRecognizer`/`VoiceRecognizerImpl`, wrapping `android.speech.SpeechRecognizer`), the mic button with on-demand `RECORD_AUDIO` permission request, and the in-app confirm/discard `AlertDialog` are all built and covered by tests (`VoiceRecognizerImplTest` under Robolectric, expanded `AskGeminiViewModelTest`). A confirmed voice transcript already sends through the existing Gemini path and shows/notifies the reply exactly like a typed prompt. `./gradlew build lint testDebugUnitTest` passes.

**Remaining for sub-phase 2b (not yet built):** spoken replies. This needs a new `data/voice/GeminiSpeaker`/`GeminiSpeakerImpl` pair wrapping `android.speech.tts.TextToSpeech` (mirroring the `VoiceRecognizer` wrapper), a Hilt binding for it in `AppModule`, wiring a `speakReplyAloud: Boolean` parameter into `AskGeminiViewModel`'s shared `sendPromptAndHandleResult` function so only voice-confirmed replies (not typed ones) call `geminiSpeaker.speak(replyText)`, and Robolectric tests for `GeminiSpeakerImpl` plus the new ViewModel cases (typed send never speaks; voice-confirmed send does). Full detail is in the design spec's Architecture/Data flow/Testing sections and the "2b" bullet under "Implementation phasing" — no separate `VoiceConstants.kt` file was created in 2a (see the spec's Implementation phasing note), so 2b's plan should either add recognizer/TTS-shared constants directly where needed or introduce that file only if something is actually shared between the two wrappers.

Once 2b lands, this phase reaches full parity with `ask-gemini.js --voice`. Still explicitly out of scope for all of Phase 2: `NotificationListenerService`, `AccessibilityService`, background/foreground service, automations.

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
