package com.ameyapb.androidexp.data.gemini

interface GeminiClient {
    suspend fun generateContent(prompt: String): String
}
