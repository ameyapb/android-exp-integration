package com.ameyapb.androidexp.ui.askgemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskGeminiViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeGeminiRepository: FakeGeminiRepository
    private lateinit var fakeGeminiNotifier: FakeGeminiNotifier
    private lateinit var fakeVoiceRecognizer: FakeVoiceRecognizer
    private lateinit var fakeGeminiSpeaker: FakeGeminiSpeaker
    private lateinit var viewModel: AskGeminiViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeGeminiRepository = FakeGeminiRepository()
        fakeGeminiNotifier = FakeGeminiNotifier()
        fakeVoiceRecognizer = FakeVoiceRecognizer()
        fakeGeminiSpeaker = FakeGeminiSpeaker()
        viewModel = AskGeminiViewModel(
            fakeGeminiRepository,
            fakeGeminiNotifier,
            fakeVoiceRecognizer,
            fakeGeminiSpeaker,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onPromptTextChanged updates the prompt text`() {
        viewModel.onPromptTextChanged("why is the sky blue")

        assertEquals("why is the sky blue", viewModel.uiState.value.promptText)
    }

    @Test
    fun `onSendClicked on success updates reply text and notifies`() = runTest {
        viewModel.onPromptTextChanged("why is the sky blue")
        fakeGeminiRepository.result = Result.success("because of Rayleigh scattering")

        viewModel.onSendClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("because of Rayleigh scattering", state.replyText)
        assertTrue(!state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(listOf("because of Rayleigh scattering"), fakeGeminiNotifier.notifiedReplies)
        assertTrue(fakeGeminiSpeaker.spokenReplies.isEmpty())
    }

    @Test
    fun `onSendClicked on failure sets error and does not notify`() = runTest {
        viewModel.onPromptTextChanged("why is the sky blue")
        fakeGeminiRepository.result = Result.failure(Exception("Gemini API error: bad key"))

        viewModel.onSendClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Gemini API error: bad key", state.errorMessage)
        assertTrue(!state.isLoading)
        assertTrue(fakeGeminiNotifier.notifiedReplies.isEmpty())
    }

    @Test
    fun `onSendClicked with a blank prompt does nothing`() = runTest {
        viewModel.onPromptTextChanged("   ")

        viewModel.onSendClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(!viewModel.uiState.value.isLoading)
        assertTrue(fakeGeminiNotifier.notifiedReplies.isEmpty())
    }

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
        assertEquals(listOf("because of Rayleigh scattering"), fakeGeminiSpeaker.spokenReplies)
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

    @Test
    fun `onMicPermissionDenied sets an error message`() {
        viewModel.onMicPermissionDenied()

        assertEquals(
            "Microphone permission is required for voice input.",
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun `onSendClicked is ignored while a voice capture is in progress`() = runTest {
        viewModel.onPromptTextChanged("why is the sky blue")
        viewModel.onMicClicked()
        assertTrue(viewModel.uiState.value.isListening)

        viewModel.onSendClicked()

        assertTrue(!viewModel.uiState.value.isLoading)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(fakeGeminiNotifier.notifiedReplies.isEmpty())
    }

    @Test
    fun `onMicClicked is ignored while a send is in progress`() = runTest {
        viewModel.onPromptTextChanged("why is the sky blue")
        viewModel.onSendClicked()
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.onMicClicked()

        assertTrue(!viewModel.uiState.value.isListening)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.pendingVoiceTranscript)
    }
}
