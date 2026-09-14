package com.ameyapb.androidexp.data.gemini

import com.google.genai.kotlin.ClientException
import com.google.genai.kotlin.ServerException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiRepositoryImplTest {
    private lateinit var fakeGeminiClient: FakeGeminiClient
    private lateinit var repository: GeminiRepositoryImpl

    @Before
    fun setUp() {
        fakeGeminiClient = FakeGeminiClient()
        repository = GeminiRepositoryImpl(fakeGeminiClient)
    }

    @Test
    fun `sendPrompt returns the reply text on success`() = runTest {
        fakeGeminiClient.responseText = "the sky is blue"

        val result = repository.sendPrompt("why is the sky blue")

        assertEquals("the sky is blue", result.getOrNull())
    }

    @Test
    fun `sendPrompt maps a 401 to an auth error message`() = runTest {
        fakeGeminiClient.errorToThrow = ClientException(401, "UNAUTHENTICATED", "bad key")

        val result = repository.sendPrompt("prompt")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("local.properties"))
    }

    @Test
    fun `sendPrompt maps a 429 to a rate-limit error message`() = runTest {
        fakeGeminiClient.errorToThrow = ClientException(429, "RESOURCE_EXHAUSTED", "too many requests")

        val result = repository.sendPrompt("prompt")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("rate limit"))
    }

    @Test
    fun `sendPrompt maps a 500 to a generic error message`() = runTest {
        fakeGeminiClient.errorToThrow = ServerException(500, "INTERNAL", "boom")

        val result = repository.sendPrompt("prompt")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.startsWith("Gemini API error:"))
        assertTrue(message.contains("boom"))
    }

    @Test
    fun `sendPrompt maps a network failure to a generic error message`() = runTest {
        fakeGeminiClient.errorToThrow = IOException("Unable to resolve host")

        val result = repository.sendPrompt("prompt")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.startsWith("Gemini API error:"))
        assertTrue(message.contains("Unable to resolve host"))
    }

    @Test(expected = CancellationException::class)
    fun `sendPrompt rethrows coroutine cancellation instead of wrapping it`() = runTest {
        fakeGeminiClient.errorToThrow = CancellationException("scope cancelled")

        repository.sendPrompt("prompt")
    }
}
