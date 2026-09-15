package com.ameyapb.androidexp.data.voice

interface VoiceRecognizer {
    suspend fun listen(): Result<String>
}
