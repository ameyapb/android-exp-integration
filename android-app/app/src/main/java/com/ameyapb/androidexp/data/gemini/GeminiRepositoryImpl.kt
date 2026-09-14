package com.ameyapb.androidexp.data.gemini

import com.google.genai.kotlin.GenAiApiException
import javax.inject.Inject

private const val GEMINI_AUTH_ERROR_HTTP_STATUS = 401
private const val GEMINI_RATE_LIMIT_HTTP_STATUS = 429
private const val GEMINI_API_KEY_PROPERTY_NAME = "GEMINI_API_KEY"

class GeminiRepositoryImpl @Inject constructor(
    private val geminiClient: GeminiClient,
) : GeminiRepository {
    override suspend fun sendPrompt(prompt: String): Result<String> {
        return try {
            Result.success(geminiClient.generateContent(prompt))
        } catch (apiError: GenAiApiException) {
            Result.failure(Exception(describeGeminiApiError(apiError)))
        }
    }
}

internal fun describeGeminiApiError(apiError: GenAiApiException): String {
    return when (apiError.code) {
        GEMINI_AUTH_ERROR_HTTP_STATUS ->
            "Gemini API error: ${apiError.message}. Check that $GEMINI_API_KEY_PROPERTY_NAME in " +
                "local.properties is set to a valid Google AI Studio key."
        GEMINI_RATE_LIMIT_HTTP_STATUS ->
            "Gemini API error: ${apiError.message}. You've hit the free tier rate limit, wait a " +
                "bit and try again."
        else -> "Gemini API error: ${apiError.message}"
    }
}
