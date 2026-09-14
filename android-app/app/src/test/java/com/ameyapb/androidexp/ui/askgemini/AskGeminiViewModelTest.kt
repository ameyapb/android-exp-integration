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
    private lateinit var viewModel: AskGeminiViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeGeminiRepository = FakeGeminiRepository()
        fakeGeminiNotifier = FakeGeminiNotifier()
        viewModel = AskGeminiViewModel(fakeGeminiRepository, fakeGeminiNotifier)
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
}
