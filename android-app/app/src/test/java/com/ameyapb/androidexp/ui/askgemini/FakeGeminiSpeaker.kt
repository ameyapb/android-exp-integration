package com.ameyapb.androidexp.ui.askgemini

import com.ameyapb.androidexp.data.voice.GeminiSpeaker

class FakeGeminiSpeaker : GeminiSpeaker {
    val spokenReplies = mutableListOf<String>()

    override fun speak(text: String) {
        spokenReplies.add(text)
    }
}
