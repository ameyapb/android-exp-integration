package com.ameyapb.androidexp.data.gemini

import com.google.genai.kotlin.Client
import com.google.genai.kotlin.Models
import com.google.genai.kotlin.types.GenerateContentConfig
import com.google.genai.kotlin.types.GenerateContentResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class GeminiClientImplTest {

    @Test
    fun `generateContent returns the trimmed reply text from the SDK response`() = runTest {
        val response = mock<GenerateContentResponse> { on { text } doReturn "  the sky is blue  " }
        val modelsClient = mock<Models> {
            on { generateContent(any<String>(), any<String>(), any<GenerateContentConfig>()) } doReturn response
        }
        val client = mock<Client> { on { models } doReturn modelsClient }
        val geminiClient = GeminiClientImpl(client)

        val result = geminiClient.generateContent("why is the sky blue")

        assertEquals("the sky is blue", result)
    }

    @Test
    fun `generateContent returns an empty string when the SDK response has no text`() = runTest {
        val response = mock<GenerateContentResponse> { on { text } doReturn null }
        val modelsClient = mock<Models> {
            on { generateContent(any<String>(), any<String>(), any<GenerateContentConfig>()) } doReturn response
        }
        val client = mock<Client> { on { models } doReturn modelsClient }
        val geminiClient = GeminiClientImpl(client)

        val result = geminiClient.generateContent("prompt")

        assertEquals("", result)
    }
}
