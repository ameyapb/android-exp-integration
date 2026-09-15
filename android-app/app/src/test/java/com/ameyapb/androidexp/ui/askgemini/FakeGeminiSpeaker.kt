package com.ameyapb.androidexp.ui.askgemini

import com.ameyapb.androidexp.data.voice.GeminiSpeaker

class FakeGeminiSpeaker : GeminiSpeaker {
    val spokenReplies = mutableListOf<String>()
    var shutdownCallCount = 0
        private set

    override fun speak(text: String) {
        spokenReplies.add(text)
    }

    override fun shutdown() {
        shutdownCallCount++
    }
}
