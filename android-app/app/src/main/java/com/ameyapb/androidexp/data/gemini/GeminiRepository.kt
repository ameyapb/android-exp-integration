package com.ameyapb.androidexp.data.gemini

interface GeminiRepository {
    suspend fun sendPrompt(prompt: String): Result<String>
}
