package com.ameyapb.androidexp.data.gemini

import com.google.genai.kotlin.Client
import com.google.genai.kotlin.types.Content
import com.google.genai.kotlin.types.GenerateContentConfig
import javax.inject.Inject

class GeminiClientImpl @Inject constructor(private val client: Client) : GeminiClient {
    override suspend fun generateContent(prompt: String): String {
        val response = client.models.generateContent(
            model = GEMINI_MODEL_NAME,
            text = prompt,
            config = GenerateContentConfig(
                systemInstruction = Content.fromText(GEMINI_SYSTEM_INSTRUCTION),
            ),
        )
        return response.text.orEmpty().trim()
    }
}
