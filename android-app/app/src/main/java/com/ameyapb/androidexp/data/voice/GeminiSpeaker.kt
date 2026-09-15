package com.ameyapb.androidexp.data.voice

interface GeminiSpeaker {
    fun speak(text: String)
    fun shutdown()
}
