# Phase 2a: Voice capture and confirm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SpeechRecognizer-based voice capture with an in-app confirm/discard dialog to the native app, reusing the existing Gemini send path (no spoken reply yet — that's Phase 2b).

**Architecture:** A new `data/voice/VoiceRecognizer`/`VoiceRecognizerImpl` pair wraps `android.speech.SpeechRecognizer` behind a `suspend fun listen(): Result<String>`, mirroring how `GeminiClient` wraps the Gemini REST call. `AskGeminiUiState` gains `isListening` and `pendingVoiceTranscript`; `AskGeminiViewModel` gains `onMicClicked`/`onVoiceTranscriptConfirmed`/`onVoiceTranscriptDiscarded`, with the existing send-and-notify logic extracted into a shared private function so both the typed and voice paths use it. `AskGeminiScreen` adds a mic button (on-demand `RECORD_AUDIO` permission request) and a Compose `AlertDialog` for confirmation.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, `kotlinx-coroutines`, `android.speech.SpeechRecognizer` (platform API, no new dependency), JUnit + Robolectric for tests.

## Global Constraints

- Package root: `com.ameyapb.androidexp`; new voice code lives under `data/voice/`.
- No new Gradle dependencies — `SpeechRecognizer` is a platform API and Robolectric (already a `testImplementation`) ships shadows for it.
- `RECORD_AUDIO` is requested on demand on first mic tap, never eagerly at launch.
- Typed-prompt behavior (`onSendClicked`) must be unchanged after the refactor — existing `AskGeminiViewModelTest` cases must keep passing verbatim.
- No magic strings/numbers: every literal that carries meaning (error text fragments, dialog title) lives in a named constant.
- Fakes over mocks for `AskGeminiViewModelTest`, per `CLAUDE.md`; Robolectric for classes wrapping a real Android framework boundary a fake can't reach.
- Every new class gets a test in the same change; run the full `testDebugUnitTest` suite before considering the task done.

---

### Task 1: `VoiceRecognizer` interface and `VoiceRecognizerImpl`

**Files:**
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/data/voice/VoiceRecognizer.kt`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/data/voice/VoiceRecognizerImpl.kt`
- Test: `android-app/app/src/test/java/com/ameyapb/androidexp/data/voice/VoiceRecognizerImplTest.kt`

**Interfaces:**
- Produces: `interface VoiceRecognizer { suspend fun listen(): Result<String> }`, `class VoiceRecognizerImpl(context: Context) : VoiceRecognizer`, and `internal fun describeVoiceRecognitionError(errorCode: Int): String` — both consumed by Task 2 (DI) and indirectly by Task 3 (`AskGeminiViewModel` depends only on the `VoiceRecognizer` interface).

- [ ] **Step 1: Write `VoiceRecognizer.kt`**

```kotlin
package com.ameyapb.androidexp.data.voice

interface VoiceRecognizer {
    suspend fun listen(): Result<String>
}
```

- [ ] **Step 2: Write `VoiceRecognizerImpl.kt`**

```kotlin
package com.ameyapb.androidexp.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val VOICE_RECOGNITION_UNAVAILABLE_MESSAGE =
    "Voice input error: speech recognition is not available on this device. Install or " +
        "enable a speech recognition service (e.g. the Google app) and try again."

class VoiceRecognizerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceRecognizer {

    override suspend fun listen(): Result<String> {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return Result.failure(Exception(VOICE_RECOGNITION_UNAVAILABLE_MESSAGE))
        }

        return suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val transcript = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    recognizer.destroy()
                    if (continuation.isActive) continuation.resume(Result.success(transcript))
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(Exception(describeVoiceRecognitionError(error))))
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            continuation.invokeOnCancellation {
                recognizer.stopListening()
                recognizer.destroy()
            }

            recognizer.startListening(buildRecognizerIntent())
        }
    }
}

private fun buildRecognizerIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

internal fun describeVoiceRecognitionError(errorCode: Int): String {
    val reason = when (errorCode) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "didn't catch any speech"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission was denied"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "a network error occurred"
        SpeechRecognizer.ERROR_AUDIO -> "an audio recording error occurred"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the recognizer is busy, try again"
        else -> "an unknown error occurred (code $errorCode)"
    }
    return "Voice input error: $reason."
}
```

- [ ] **Step 3: Write `VoiceRecognizerImplTest.kt`**

```kotlin
package com.ameyapb.androidexp.data.voice

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.ameyapb.androidexp.ROBOLECTRIC_SDK_LEVEL
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK_LEVEL], application = Application::class)
class VoiceRecognizerImplTest {

    private lateinit var recognizer: VoiceRecognizerImpl

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        registerFakeRecognitionService(context)
        recognizer = VoiceRecognizerImpl(context)
    }

    private fun registerFakeRecognitionService(context: android.content.Context) {
        val resolveInfo = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = "com.fake.speech"
                name = "FakeRecognitionService"
            }
        }
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), resolveInfo)
    }

    @Test
    fun `listen returns the transcript when recognition succeeds`() = runTest {
        val resultDeferred = kotlinx.coroutines.async { recognizer.listen() }
        runCurrent()

        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("why is the sky blue"))
        }
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(bundle)
        advanceUntilIdle()

        assertEquals(Result.success("why is the sky blue"), resultDeferred.getCompleted())
    }

    @Test
    fun `listen returns a failure with the mapped message when recognition errors`() = runTest {
        val resultDeferred = kotlinx.coroutines.async { recognizer.listen() }
        runCurrent()

        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            .triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        advanceUntilIdle()

        val result = resultDeferred.getCompleted()
        assertEquals(true, result.isFailure)
        assertEquals(
            "Voice input error: didn't catch any speech.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `describeVoiceRecognitionError maps unknown codes to a generic message`() {
        assertEquals(
            "Voice input error: an unknown error occurred (code 999).",
            describeVoiceRecognitionError(999),
        )
    }
}
```

- [ ] **Step 4: Run the new test**

Run: `./gradlew testDebugUnitTest --tests "com.ameyapb.androidexp.data.voice.VoiceRecognizerImplTest"`
Expected: PASS for all three tests. If `getCompleted()` throws because the deferred hasn't completed, or `isRecognitionAvailable` still returns false, inspect the actual failure — fix the fake `ResolveInfo`/service registration or the `runCurrent`/`advanceUntilIdle` sequencing based on the real error, not by guessing further; this is exactly the kind of Robolectric-boundary detail Task 1 exists to nail down with a real test run.

- [ ] **Step 5: Run the full unit test suite to confirm nothing else broke**

Run: `./gradlew testDebugUnitTest`
Expected: all existing and new tests PASS.

- [ ] **Step 6: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/data/voice/VoiceRecognizer.kt \
        android-app/app/src/main/java/com/ameyapb/androidexp/data/voice/VoiceRecognizerImpl.kt \
        android-app/app/src/test/java/com/ameyapb/androidexp/data/voice/VoiceRecognizerImplTest.kt
git commit -m "Add VoiceRecognizer wrapping SpeechRecognizer for Phase 2 voice capture"
```

---

### Task 2: Hilt binding for `VoiceRecognizer`

**Files:**
- Modify: `android-app/app/src/main/java/com/ameyapb/androidexp/di/AppModule.kt`

**Interfaces:**
- Consumes: `VoiceRecognizer`, `VoiceRecognizerImpl` from Task 1.
- Produces: `VoiceRecognizer` now resolvable via Hilt constructor injection anywhere in the graph (needed by Task 3's `AskGeminiViewModel`).

- [ ] **Step 1: Add the binding**

Add these imports next to the existing `data.notification` imports:

```kotlin
import com.ameyapb.androidexp.data.voice.VoiceRecognizer
import com.ameyapb.androidexp.data.voice.VoiceRecognizerImpl
```

Add this binding inside the `AppModule` class, next to `bindGeminiNotifier`:

```kotlin
    @Binds
    @Singleton
    abstract fun bindVoiceRecognizer(impl: VoiceRecognizerImpl): VoiceRecognizer
```

- [ ] **Step 2: Verify the module compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/di/AppModule.kt
git commit -m "Bind VoiceRecognizer in the Hilt module"
```

---

### Task 3: `AskGeminiUiState` and `AskGeminiViewModel` voice wiring

**Files:**
- Modify: `android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiUiState.kt`
- Modify: `android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiViewModel.kt`
- Create: `android-app/app/src/test/java/com/ameyapb/androidexp/ui/askgemini/FakeVoiceRecognizer.kt`
- Modify: `android-app/app/src/test/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiViewModelTest.kt`

**Interfaces:**
- Consumes: `VoiceRecognizer` (Task 1), `GeminiRepository`/`GeminiNotifier` (existing).
- Produces: `AskGeminiUiState(promptText, replyText, isLoading, errorMessage, isListening, pendingVoiceTranscript)`; `AskGeminiViewModel.onMicClicked()`, `.onVoiceTranscriptConfirmed()`, `.onVoiceTranscriptDiscarded()` — consumed by Task 4's `AskGeminiScreen`.

- [ ] **Step 1: Update `AskGeminiUiState.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

data class AskGeminiUiState(
    val promptText: String = "",
    val replyText: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isListening: Boolean = false,
    val pendingVoiceTranscript: String? = null,
)
```

- [ ] **Step 2: Write `FakeVoiceRecognizer.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

import com.ameyapb.androidexp.data.voice.VoiceRecognizer

class FakeVoiceRecognizer : VoiceRecognizer {
    var result: Result<String> = Result.success("fake transcript")

    override suspend fun listen(): Result<String> = result
}
```

- [ ] **Step 3: Write the failing ViewModel tests**

Append to `AskGeminiViewModelTest.kt` (add `fakeVoiceRecognizer` to the existing `setUp`/constructor call too — see Step 4):

```kotlin
    @Test
    fun `onMicClicked with a non-blank transcript sets pendingVoiceTranscript`() = runTest {
        fakeVoiceRecognizer.result = Result.success("why is the sky blue")

        viewModel.onMicClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("why is the sky blue", state.pendingVoiceTranscript)
        assertTrue(!state.isListening)
    }

    @Test
    fun `onMicClicked with a blank transcript sets an error and no pending transcript`() = runTest {
        fakeVoiceRecognizer.result = Result.success("   ")

        viewModel.onMicClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.pendingVoiceTranscript)
        assertEquals("Heard nothing.", state.errorMessage)
        assertTrue(!state.isListening)
    }

    @Test
    fun `onMicClicked on recognition failure sets the error message`() = runTest {
        fakeVoiceRecognizer.result = Result.failure(Exception("Voice input error: didn't catch any speech."))

        viewModel.onMicClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Voice input error: didn't catch any speech.", state.errorMessage)
        assertNull(state.pendingVoiceTranscript)
        assertTrue(!state.isListening)
    }

    @Test
    fun `onVoiceTranscriptConfirmed sends the transcript and notifies on success`() = runTest {
        fakeVoiceRecognizer.result = Result.success("why is the sky blue")
        fakeGeminiRepository.result = Result.success("because of Rayleigh scattering")
        viewModel.onMicClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onVoiceTranscriptConfirmed()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.pendingVoiceTranscript)
        assertEquals("because of Rayleigh scattering", state.replyText)
        assertEquals(listOf("because of Rayleigh scattering"), fakeGeminiNotifier.notifiedReplies)
    }

    @Test
    fun `onVoiceTranscriptDiscarded clears the pending transcript without sending`() = runTest {
        fakeVoiceRecognizer.result = Result.success("why is the sky blue")
        viewModel.onMicClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onVoiceTranscriptDiscarded()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.pendingVoiceTranscript)
        assertEquals("", state.replyText)
        assertTrue(fakeGeminiNotifier.notifiedReplies.isEmpty())
    }
```

- [ ] **Step 4: Wire `fakeVoiceRecognizer` into `setUp`**

In `AskGeminiViewModelTest.kt`, change:

```kotlin
    private lateinit var fakeGeminiRepository: FakeGeminiRepository
    private lateinit var fakeGeminiNotifier: FakeGeminiNotifier
    private lateinit var viewModel: AskGeminiViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeGeminiRepository = FakeGeminiRepository()
        fakeGeminiNotifier = FakeGeminiNotifier()
        viewModel = AskGeminiViewModel(fakeGeminiRepository, fakeGeminiNotifier)
    }
```

to:

```kotlin
    private lateinit var fakeGeminiRepository: FakeGeminiRepository
    private lateinit var fakeGeminiNotifier: FakeGeminiNotifier
    private lateinit var fakeVoiceRecognizer: FakeVoiceRecognizer
    private lateinit var viewModel: AskGeminiViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeGeminiRepository = FakeGeminiRepository()
        fakeGeminiNotifier = FakeGeminiNotifier()
        fakeVoiceRecognizer = FakeVoiceRecognizer()
        viewModel = AskGeminiViewModel(fakeGeminiRepository, fakeGeminiNotifier, fakeVoiceRecognizer)
    }
```

- [ ] **Step 5: Run the tests to verify they fail to compile (ViewModel doesn't accept the new dependency/methods yet)**

Run: `./gradlew testDebugUnitTest --tests "com.ameyapb.androidexp.ui.askgemini.AskGeminiViewModelTest"`
Expected: compilation FAILURE referencing the missing `VoiceRecognizer` constructor param and missing `onMicClicked`/`onVoiceTranscriptConfirmed`/`onVoiceTranscriptDiscarded` methods.

- [ ] **Step 6: Implement `AskGeminiViewModel.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ameyapb.androidexp.data.gemini.GeminiRepository
import com.ameyapb.androidexp.data.notification.GeminiNotifier
import com.ameyapb.androidexp.data.voice.VoiceRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val HEARD_NOTHING_MESSAGE = "Heard nothing."

@HiltViewModel
class AskGeminiViewModel @Inject constructor(
    private val geminiRepository: GeminiRepository,
    private val geminiNotifier: GeminiNotifier,
    private val voiceRecognizer: VoiceRecognizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AskGeminiUiState())
    val uiState: StateFlow<AskGeminiUiState> = _uiState.asStateFlow()

    fun onPromptTextChanged(text: String) {
        _uiState.update { it.copy(promptText = text) }
    }

    fun onSendClicked() {
        val prompt = _uiState.value.promptText
        if (prompt.isBlank()) {
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch { sendPromptAndHandleResult(prompt) }
    }

    fun onMicClicked() {
        _uiState.update { it.copy(isListening = true, errorMessage = null) }

        viewModelScope.launch {
            voiceRecognizer.listen().fold(
                onSuccess = { transcript ->
                    if (transcript.isBlank()) {
                        _uiState.update { it.copy(isListening = false, errorMessage = HEARD_NOTHING_MESSAGE) }
                    } else {
                        _uiState.update { it.copy(isListening = false, pendingVoiceTranscript = transcript) }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isListening = false, errorMessage = error.message) }
                },
            )
        }
    }

    fun onVoiceTranscriptConfirmed() {
        val transcript = _uiState.value.pendingVoiceTranscript ?: return
        _uiState.update { it.copy(pendingVoiceTranscript = null, isLoading = true, errorMessage = null) }
        viewModelScope.launch { sendPromptAndHandleResult(transcript) }
    }

    fun onVoiceTranscriptDiscarded() {
        _uiState.update { it.copy(pendingVoiceTranscript = null) }
    }

    private suspend fun sendPromptAndHandleResult(prompt: String) {
        geminiRepository.sendPrompt(prompt).fold(
            onSuccess = { replyText ->
                _uiState.update { it.copy(replyText = replyText, isLoading = false) }
                geminiNotifier.notify(replyText)
            },
            onFailure = { error ->
                _uiState.update { it.copy(errorMessage = error.message, isLoading = false) }
            },
        )
    }
}
```

- [ ] **Step 7: Run the ViewModel tests again**

Run: `./gradlew testDebugUnitTest --tests "com.ameyapb.androidexp.ui.askgemini.AskGeminiViewModelTest"`
Expected: all tests PASS, including the pre-existing `onSendClicked` tests (unchanged behavior after the refactor).

- [ ] **Step 8: Run the full unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: all tests PASS.

- [ ] **Step 9: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiUiState.kt \
        android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiViewModel.kt \
        android-app/app/src/test/java/com/ameyapb/androidexp/ui/askgemini/FakeVoiceRecognizer.kt \
        android-app/app/src/test/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiViewModelTest.kt
git commit -m "Wire voice capture and confirm/discard into AskGeminiViewModel"
```

---

### Task 4: Mic button, permission flow, and confirm dialog in `AskGeminiScreen`

**Files:**
- Modify: `android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiScreen.kt`
- Modify: `android-app/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AskGeminiUiState.isListening`/`pendingVoiceTranscript` and `AskGeminiViewModel.onMicClicked()`/`onVoiceTranscriptConfirmed()`/`onVoiceTranscriptDiscarded()` from Task 3.

- [ ] **Step 1: Add the `RECORD_AUDIO` permission**

In `android-app/app/src/main/AndroidManifest.xml`, add after the existing `POST_NOTIFICATIONS` line:

```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 2: Rewrite `AskGeminiScreen.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

private const val VOICE_CONFIRM_DIALOG_TITLE = "Confirm prompt"

@Composable
fun AskGeminiScreen(viewModel: AskGeminiViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.onMicClicked() }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.promptText,
                onValueChange = viewModel::onPromptTextChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ask Gemini") },
            )

            Button(
                onClick = viewModel::onSendClicked,
                enabled = !uiState.isLoading && uiState.promptText.isNotBlank(),
            ) {
                Text(if (uiState.isLoading) "Sending..." else "Send")
            }

            Button(
                onClick = {
                    val alreadyGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED

                    if (alreadyGranted) {
                        viewModel.onMicClicked()
                    } else {
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !uiState.isListening,
            ) {
                Text(if (uiState.isListening) "Listening..." else "Speak")
            }

            val errorMessage = uiState.errorMessage
            if (errorMessage != null) {
                Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
            } else if (uiState.replyText.isNotEmpty()) {
                Text(text = uiState.replyText)
            }
        }
    }

    val pendingVoiceTranscript = uiState.pendingVoiceTranscript
    if (pendingVoiceTranscript != null) {
        AlertDialog(
            onDismissRequest = viewModel::onVoiceTranscriptDiscarded,
            title = { Text(VOICE_CONFIRM_DIALOG_TITLE) },
            text = {
                Text(
                    text = pendingVoiceTranscript,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Button(onClick = viewModel::onVoiceTranscriptConfirmed) { Text("Send") }
            },
            dismissButton = {
                Button(onClick = viewModel::onVoiceTranscriptDiscarded) { Text("Discard") }
            },
        )
    }
}
```

- [ ] **Step 3: Build the app module to confirm the Compose code compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full verification suite**

Run: `./gradlew build lint testDebugUnitTest`
Expected: BUILD SUCCESSFUL, zero lint issues, all unit tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiScreen.kt \
        android-app/app/src/main/AndroidManifest.xml
git commit -m "Add mic button, RECORD_AUDIO permission flow, and voice confirm dialog"
```

---

### Task 5: Update documentation to match what was built

**Files:**
- Modify: `roadmap.md`
- Modify: `docs/superpowers/specs/2026-09-15-phase2-voice-input-design.md`
- Modify: `CLAUDE.md`

**Interfaces:** none (documentation only).

- [ ] **Step 1: Update `roadmap.md`'s Phase 2 section**

Add a status note under the `## Phase 2: Voice input` heading (mirroring how Phase 1's status note is written) recording that sub-phase 2a (capture + confirm dialog, no spoken reply yet) is implemented and committed, that sub-phase 2b (`GeminiSpeaker`/TTS) is the remaining work, and pointing at the design spec and this plan file. This is the file the next session's "impl the rest" request will read as ground truth, so it must state precisely what remains: adding `GeminiSpeaker`/`GeminiSpeakerImpl`, wiring `speakReplyAloud` into the confirmed-voice send path, and the two Robolectric wrapper tests.

- [ ] **Step 2: Update the design spec's Implementation phasing section**

In `docs/superpowers/specs/2026-09-15-phase2-voice-input-design.md`, append a short note to the "Implementation phasing" section recording that 2a shipped without a separate `VoiceConstants.kt` file (the one recognizer-only string constant lives directly in `VoiceRecognizerImpl.kt`, per YAGNI — nothing is shared with `GeminiSpeaker` yet), so 2b's plan doesn't assume a file that doesn't exist.

- [ ] **Step 3: Update `CLAUDE.md`'s native Android app section**

Add a bullet documenting `data/voice/VoiceRecognizer`/`VoiceRecognizerImpl`, the new `AskGeminiUiState` fields, the new `AskGeminiViewModel` methods, and the mic button/dialog/permission flow in `AskGeminiScreen`, following the file's own instruction to document new components as they're added — matching the level of detail already used for the `data/gemini/` and `data/notification/` bullets.

- [ ] **Step 4: Commit**

```bash
git add roadmap.md docs/superpowers/specs/2026-09-15-phase2-voice-input-design.md CLAUDE.md
git commit -m "Document Phase 2a voice capture completion in roadmap, spec, and CLAUDE.md"
```
