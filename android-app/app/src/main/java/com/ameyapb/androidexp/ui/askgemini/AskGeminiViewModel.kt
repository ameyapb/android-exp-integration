package com.ameyapb.androidexp.ui.askgemini

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ameyapb.androidexp.data.gemini.GeminiRepository
import com.ameyapb.androidexp.data.notification.GeminiNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AskGeminiViewModel @Inject constructor(
    private val geminiRepository: GeminiRepository,
    private val geminiNotifier: GeminiNotifier,
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

        viewModelScope.launch {
            geminiRepository.sendPrompt(prompt).fold(
                onSuccess = { replyText ->
                    _uiState.update { it.copy(replyText = replyText, isLoading = false) }
                    geminiNotifier.notify(replyText)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message, isLoading = false)
                    }
                },
            )
        }
    }
}
