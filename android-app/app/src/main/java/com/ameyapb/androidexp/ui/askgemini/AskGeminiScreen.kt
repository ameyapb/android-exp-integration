package com.ameyapb.androidexp.ui.askgemini

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ameyapb.androidexp.ui.theme.AccentMuted
import com.ameyapb.androidexp.ui.theme.BorderComposer
import com.ameyapb.androidexp.ui.theme.SurfaceComposer
import com.ameyapb.androidexp.util.isPermissionGranted

private const val APP_BAR_TITLE = "Gemini"
private const val OVERFLOW_MENU_CONTENT_DESCRIPTION = "More options"
private const val PROMPT_FIELD_LABEL = "Ask Gemini"
private const val MIC_BUTTON_CONTENT_DESCRIPTION = "Speak prompt"
private const val MIC_BUTTON_LISTENING_CONTENT_DESCRIPTION = "Listening for prompt"
private const val SEND_BUTTON_CONTENT_DESCRIPTION = "Send prompt"
private const val REPLY_AUTHOR_LABEL = "Gemini"
private const val VOICE_CONFIRM_DIALOG_TITLE = "Confirm prompt"
private const val VOICE_CONFIRM_SEND_LABEL = "Send"
private const val VOICE_CONFIRM_DISCARD_LABEL = "Discard"
private const val VOICE_TRANSCRIPT_DIALOG_MAX_HEIGHT_DP = 240
private const val CONTENT_SURFACE_CORNER_RADIUS_DP = 24
private const val ICON_BUTTON_SIZE_DP = 44
private const val AVATAR_DOT_SIZE_DP = 10
private const val MIC_ICON_VIEWPORT_SIZE = 24f

/**
 * material-icons-core doesn't include a mic glyph (only material-icons-extended does, which
 * would pull in the full ~2000-icon set for one icon), so this mirrors Material Design's
 * standard "mic" path data directly.
 */
private val MicIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Mic",
        defaultWidth = MIC_ICON_VIEWPORT_SIZE.dp,
        defaultHeight = MIC_ICON_VIEWPORT_SIZE.dp,
        viewportWidth = MIC_ICON_VIEWPORT_SIZE,
        viewportHeight = MIC_ICON_VIEWPORT_SIZE,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 14f)
            curveTo(13.66f, 14f, 14.99f, 12.66f, 14.99f, 11f)
            lineTo(15f, 5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            lineTo(9f, 11f)
            curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
            close()
            moveTo(17.3f, 11f)
            curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
            curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
            lineTo(5f, 11f)
            curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
            lineTo(11f, 21f)
            lineTo(13f, 21f)
            lineTo(13f, 17.72f)
            curveTo(16.28f, 17.23f, 19f, 14.41f, 19f, 11f)
            lineTo(17.3f, 11f)
            close()
        }
    }.build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskGeminiScreen(viewModel: AskGeminiViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onMicClicked() else viewModel.onMicPermissionDenied()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(APP_BAR_TITLE) },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.MoreVert, contentDescription = OVERFLOW_MENU_CONTENT_DESCRIPTION)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            shape = RoundedCornerShape(
                topStart = CONTENT_SURFACE_CORNER_RADIUS_DP.dp,
                topEnd = CONTENT_SURFACE_CORNER_RADIUS_DP.dp,
            ),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val errorMessage = uiState.errorMessage
                    if (errorMessage != null) {
                        Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
                    } else if (uiState.replyText.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(AVATAR_DOT_SIZE_DP.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                            Text(
                                text = REPLY_AUTHOR_LABEL,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(text = uiState.replyText, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.promptText,
                        onValueChange = viewModel::onPromptTextChanged,
                        modifier = Modifier.weight(1f),
                        label = { Text(PROMPT_FIELD_LABEL) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceComposer,
                            unfocusedContainerColor = SurfaceComposer,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = BorderComposer,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )

                    OutlinedIconButton(
                        onClick = {
                            if (isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) {
                                viewModel.onMicClicked()
                            } else {
                                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !uiState.isListening && !uiState.isLoading,
                        modifier = Modifier.size(ICON_BUTTON_SIZE_DP.dp),
                        colors = IconButtonDefaults.outlinedIconButtonColors(
                            containerColor = SurfaceComposer,
                            contentColor = AccentMuted,
                        ),
                        border = BorderStroke(1.dp, BorderComposer),
                    ) {
                        Icon(
                            MicIcon,
                            contentDescription = if (uiState.isListening) {
                                MIC_BUTTON_LISTENING_CONTENT_DESCRIPTION
                            } else {
                                MIC_BUTTON_CONTENT_DESCRIPTION
                            },
                        )
                    }

                    FilledIconButton(
                        onClick = viewModel::onSendClicked,
                        enabled = !uiState.isLoading && !uiState.isListening && uiState.promptText.isNotBlank(),
                        modifier = Modifier.size(ICON_BUTTON_SIZE_DP.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = SEND_BUTTON_CONTENT_DESCRIPTION)
                    }
                }
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
