package com.ameyapb.androidexp.ui.askgemini

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ameyapb.androidexp.data.gemini.GeminiRepository
import com.ameyapb.androidexp.data.notification.GeminiNotifier
import com.ameyapb.androidexp.data.voice.GeminiSpeaker
import com.ameyapb.androidexp.data.voice.VoiceRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val HEARD_NOTHING_MESSAGE = "Heard nothing."

@HiltViewModel
class AskGeminiViewModel @Inject constructor(
    private val geminiRepository: GeminiRepository,
    private val geminiNotifier: GeminiNotifier,
    private val voiceRecognizer: VoiceRecognizer,
    private val geminiSpeaker: GeminiSpeaker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AskGeminiUiState())
    val uiState: StateFlow<AskGeminiUiState> = _uiState.asStateFlow()

    fun onPromptTextChanged(text: String) {
        _uiState.update { it.copy(promptText = text) }
    }

    fun onSendClicked() {
        val prompt = _uiState.value.promptText
        if (prompt.isBlank()) {
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch { sendPromptAndHandleResult(prompt, speakReplyAloud = false) }
    }

    fun onMicClicked() {
        _uiState.update { it.copy(isListening = true, errorMessage = null) }

        viewModelScope.launch {
            voiceRecognizer.listen().fold(
                onSuccess = { transcript ->
                    if (transcript.isBlank()) {
                        _uiState.update { it.copy(isListening = false, errorMessage = HEARD_NOTHING_MESSAGE) }
                    } else {
                        _uiState.update { it.copy(isListening = false, pendingVoiceTranscript = transcript) }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isListening = false, errorMessage = error.message) }
                },
            )
        }
    }

    fun onVoiceTranscriptConfirmed() {
        val transcript = _uiState.value.pendingVoiceTranscript ?: return
        _uiState.update { it.copy(pendingVoiceTranscript = null, isLoading = true, errorMessage = null) }
        viewModelScope.launch { sendPromptAndHandleResult(transcript, speakReplyAloud = true) }
    }

    fun onVoiceTranscriptDiscarded() {
        _uiState.update { it.copy(pendingVoiceTranscript = null) }
    }

    private suspend fun sendPromptAndHandleResult(prompt: String, speakReplyAloud: Boolean) {
        geminiRepository.sendPrompt(prompt).fold(
            onSuccess = { replyText ->
                _uiState.update { it.copy(replyText = replyText, isLoading = false) }
                geminiNotifier.notify(replyText)
                if (speakReplyAloud) {
                    geminiSpeaker.speak(replyText)
                }
            },
            onFailure = { error ->
                _uiState.update { it.copy(errorMessage = error.message, isLoading = false) }
            },
        )
    }
}
