package com.ameyapb.androidexp.data.gemini

import android.app.Application
import com.ameyapb.androidexp.ROBOLECTRIC_SDK_LEVEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SUCCESS_STATUS = 200
private const val UNAUTHENTICATED_STATUS = 401

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK_LEVEL], application = Application::class)
class GeminiClientImplTest {

    @Test
    fun `buildGenerateContentRequestBody embeds the prompt and system instruction`() {
        val body = buildGenerateContentRequestBody("why is the sky blue")

        val promptText = body.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        val systemInstructionText = body.getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        assertEquals("why is the sky blue", promptText)
        assertEquals(GEMINI_SYSTEM_INSTRUCTION, systemInstructionText)
    }

    @Test
    fun `parseGenerateContentResponse returns the trimmed reply text on success`() {
        val responseBody = """
            {"candidates": [{"content": {"parts": [{"text": "  the sky is blue  "}]}}]}
        """.trimIndent()

        val result = parseGenerateContentResponse(SUCCESS_STATUS, responseBody)

        assertEquals("the sky is blue", result)
    }

    @Test
    fun `parseGenerateContentResponse returns an empty string when there are no candidates`() {
        val result = parseGenerateContentResponse(SUCCESS_STATUS, """{"candidates": []}""")

        assertEquals("", result)
    }

    @Test
    fun `parseGenerateContentResponse throws GeminiHttpException with the status and error message on failure`() {
        val responseBody = """{"error": {"message": "API key not valid"}}"""

        val exception = assertThrows(GeminiHttpException::class.java) {
            parseGenerateContentResponse(UNAUTHENTICATED_STATUS, responseBody)
        }

        assertEquals(UNAUTHENTICATED_STATUS, exception.code)
        assertEquals("API key not valid", exception.message)
    }

    @Test
    fun `extractErrorMessage falls back to the raw body when it is not valid JSON`() {
        val result = extractErrorMessage("not json")

        assertEquals("not json", result)
    }
}
