package com.ameyapb.androidexp.ui.askgemini

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AskGeminiScreen(viewModel: AskGeminiViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

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
                label = { Text("Ask Gemini") },
            )

            Button(
                onClick = viewModel::onSendClicked,
                enabled = !uiState.isLoading && uiState.promptText.isNotBlank(),
            ) {
                Text(if (uiState.isLoading) "Sending..." else "Send")
            }

            val errorMessage = uiState.errorMessage
            if (errorMessage != null) {
                Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
            } else if (uiState.replyText.isNotEmpty()) {
                Text(text = uiState.replyText)
            }
        }
    }
}
