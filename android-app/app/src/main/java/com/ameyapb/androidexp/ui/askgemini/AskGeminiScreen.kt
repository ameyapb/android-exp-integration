package com.ameyapb.androidexp.ui.askgemini

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ameyapb.androidexp.util.isPermissionGranted

private const val PROMPT_FIELD_LABEL = "Ask Gemini"
private const val SEND_BUTTON_LABEL = "Send"
private const val SEND_BUTTON_LOADING_LABEL = "Sending..."
private const val SPEAK_BUTTON_LABEL = "Speak"
private const val SPEAK_BUTTON_LISTENING_LABEL = "Listening..."
private const val VOICE_CONFIRM_DIALOG_TITLE = "Confirm prompt"
private const val VOICE_CONFIRM_SEND_LABEL = "Send"
private const val VOICE_CONFIRM_DISCARD_LABEL = "Discard"
private const val VOICE_TRANSCRIPT_DIALOG_MAX_HEIGHT_DP = 240

@Composable
fun AskGeminiScreen(viewModel: AskGeminiViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onMicClicked() else viewModel.onMicPermissionDenied()
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.promptText,
                onValueChange = viewModel::onPromptTextChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(PROMPT_FIELD_LABEL) },
            )

            Button(
                onClick = viewModel::onSendClicked,
                enabled = !uiState.isLoading && !uiState.isListening && uiState.promptText.isNotBlank(),
            ) {
                Text(if (uiState.isLoading) SEND_BUTTON_LOADING_LABEL else SEND_BUTTON_LABEL)
            }

            Button(
                onClick = {
                    if (isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) {
                        viewModel.onMicClicked()
                    } else {
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !uiState.isListening && !uiState.isLoading,
            ) {
                Text(if (uiState.isListening) SPEAK_BUTTON_LISTENING_LABEL else SPEAK_BUTTON_LABEL)
            }

            val errorMessage = uiState.errorMessage
            if (errorMessage != null) {
                Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
            } else if (uiState.replyText.isNotEmpty()) {
                Text(text = uiState.replyText)
            }
        }
    }

    val pendingVoiceTranscript = uiState.pendingVoiceTranscript
    if (pendingVoiceTranscript != null) {
        AlertDialog(
            onDismissRequest = viewModel::onVoiceTranscriptDiscarded,
            title = { Text(VOICE_CONFIRM_DIALOG_TITLE) },
            text = {
                Text(
                    text = pendingVoiceTranscript,
                    modifier = Modifier
                        .heightIn(max = VOICE_TRANSCRIPT_DIALOG_MAX_HEIGHT_DP.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Button(onClick = viewModel::onVoiceTranscriptConfirmed) { Text(VOICE_CONFIRM_SEND_LABEL) }
            },
            dismissButton = {
                Button(onClick = viewModel::onVoiceTranscriptDiscarded) { Text(VOICE_CONFIRM_DISCARD_LABEL) }
            },
        )
    }
}
