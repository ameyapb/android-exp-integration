package com.ameyapb.androidexp.ui.askgemini

import com.ameyapb.androidexp.data.gemini.GeminiRepository

class FakeGeminiRepository : GeminiRepository {
    var result: Result<String> = Result.success("fake reply")

    override suspend fun sendPrompt(prompt: String): Result<String> = result
}
