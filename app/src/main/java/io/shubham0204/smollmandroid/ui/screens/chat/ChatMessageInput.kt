package io.shubham0204.smollmandroid.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Mic
import compose.icons.feathericons.MicOff
import compose.icons.feathericons.Send
import compose.icons.feathericons.SkipForward
import compose.icons.feathericons.StopCircle
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.Chat
import io.shubham0204.smollmandroid.ui.screens.chat.ChatScreenViewModel.ModelLoadingState

@Composable
fun MessageInput(
    currChat: Chat,
    modelLoadingState: ModelLoadingState,
    audioTranscriptionUIState: AudioTranscriptionUIState,
    isGeneratingResponse: Boolean,
    onEvent: (ChatScreenUIEvent) -> Unit,
    defaultQuestion: String? = null,
) {
    if (currChat.llmModelId == -1L) {
        ComposerStatusCard(text = stringResource(R.string.chat_select_model))
        return
    }
    if (modelLoadingState == ModelLoadingState.FAILURE) {
        ComposerStatusCard(
            text = stringResource(R.string.chat_model_cannot_be_loaded),
            actionLabel = stringResource(R.string.action_try_again),
            onAction = { onEvent(ChatScreenUIEvent.ChatEvents.LoadChatModel) },
            secondaryActionLabel = stringResource(R.string.chat_choose_another_model),
            onSecondaryAction = {
                onEvent(
                    ChatScreenUIEvent.DialogEvents.ToggleSelectModelListDialog(visible = true)
                )
            },
        )
        return
    }
    if (modelLoadingState != ModelLoadingState.SUCCESS) {
        ComposerStatusCard(
            text = stringResource(R.string.chat_loading_model),
            showProgress = true,
        )
        return
    }

    var questionText by rememberSaveable { mutableStateOf(defaultQuestion ?: "") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val sendMessage = {
        val message = questionText.trim()
        if (message.isNotEmpty()) {
            keyboardController?.hide()
            onEvent(ChatScreenUIEvent.ChatEvents.SendUserQuery(message))
            questionText = ""
        }
    }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier =
                Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (audioTranscriptionUIState.isAvailable) {
                IconButton(
                    onClick = {
                        if (audioTranscriptionUIState.isRecording) {
                            onEvent(ChatScreenUIEvent.ChatEvents.StopAudioTranscription)
                        } else {
                            onEvent(
                                ChatScreenUIEvent.ChatEvents.StartAudioTranscription {
                                    questionText = it
                                }
                            )
                        }
                    },
                    modifier =
                        Modifier
                            .size(44.dp)
                            .background(
                                if (audioTranscriptionUIState.isRecording) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    Color.Transparent
                                },
                                CircleShape,
                            ),
                ) {
                    if (audioTranscriptionUIState.isRecording) {
                        Icon(
                            FeatherIcons.MicOff,
                            contentDescription = stringResource(R.string.chat_stop_audio),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        Icon(
                            FeatherIcons.Mic,
                            contentDescription = stringResource(R.string.chat_start_audio),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(2.dp))
            }
            TextField(
                modifier = Modifier.weight(1f),
                value = questionText,
                onValueChange = { questionText = it },
                shape = RoundedCornerShape(22.dp),
                minLines = 1,
                maxLines = 5,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                placeholder = {
                    Text(
                        text =
                            if (audioTranscriptionUIState.isRecording) {
                                stringResource(R.string.chat_listening)
                            } else {
                                stringResource(R.string.chat_message_hint)
                            }
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendMessage() }),
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (isGeneratingResponse) {
                FilledIconButton(
                    onClick = { onEvent(ChatScreenUIEvent.ChatEvents.StopGeneration) },
                    modifier = Modifier.size(48.dp),
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                ) {
                    Icon(
                        FeatherIcons.StopCircle,
                        contentDescription = stringResource(R.string.chat_stop_generating),
                    )
                }
            } else if (questionText.isBlank()) {
                FilledIconButton(
                    onClick = {
                        keyboardController?.hide()
                        onEvent(ChatScreenUIEvent.ChatEvents.SkipTurn)
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = FeatherIcons.SkipForward,
                        contentDescription = stringResource(R.string.chat_skip_turn),
                    )
                }
            } else {
                FilledIconButton(
                    onClick = sendMessage,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = FeatherIcons.Send,
                        contentDescription = stringResource(R.string.chat_send_message),
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerStatusCard(
    text: String,
    showProgress: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.Cpu,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionLabel != null && onAction != null) {
                    Row {
                        TextButton(onClick = onAction) {
                            Text(actionLabel)
                        }
                        if (secondaryActionLabel != null && onSecondaryAction != null) {
                            TextButton(onClick = onSecondaryAction) {
                                Text(secondaryActionLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}
