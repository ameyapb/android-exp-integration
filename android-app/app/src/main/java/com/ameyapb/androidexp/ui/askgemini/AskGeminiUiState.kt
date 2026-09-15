package com.ameyapb.androidexp.ui.askgemini

data class AskGeminiUiState(
    val promptText: String = "",
    val replyText: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isListening: Boolean = false,
    val pendingVoiceTranscript: String? = null,
)
