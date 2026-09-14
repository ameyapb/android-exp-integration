package com.ameyapb.androidexp.data.gemini

class FakeGeminiClient : GeminiClient {
    var responseText: String = "fake reply"
    var errorToThrow: Throwable? = null

    override suspend fun generateContent(prompt: String): String {
        errorToThrow?.let { throw it }
        return responseText
    }
}
