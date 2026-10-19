package io.shubham0204.smollmandroid.ui.screens.chat.messages

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Cpu
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.ChatMessage
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.createChatMessageOptionsDialog

data class ChatMessageProperties(
    val codeSnippets: List<String>
)

fun buildMessageProperties(message: String): ChatMessageProperties {
    val codeSnippetPattern = Regex("""```(?:[a-zA-Z0-9+#-]*\n)?([\s\S]*?)```""")
    val codeSnippets = codeSnippetPattern.findAll(message).map { it.groupValues[1].trim() }.toList()
    return ChatMessageProperties(codeSnippets)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyItemScope.MessageListItem(
    message: ChatMessage,
    responseGenerationSpeed: Float?,
    responseGenerationTimeSecs: Int?,
    isUserMessage: Boolean,
    onCopyClicked: () -> Unit,
    onShareClicked: () -> Unit,
    onCodeSnippetCopyClicked: (List<String>) -> Unit,
    onMessageEdited: (String) -> Unit,
    onMessageRollback: () -> Unit,
    modifier: Modifier = Modifier,
    allowEditing: Boolean,
    assistantName: String = "Assistant",
    isStreaming: Boolean = false,
) {
    if (isUserMessage) {
        UserMessageListItem(
            message = message,
            allowEditing = allowEditing,
            onCopyClicked = onCopyClicked,
            onShareClicked = onShareClicked,
            onMessageEdited = onMessageEdited,
            onMessageRollback = onMessageRollback,
            modifier = modifier,
        )
    } else {
        LLMMessageListItem(
            modifier = modifier,
            message = message,
            assistantName = assistantName,
            isStreaming = isStreaming,
            onCopyClicked = onCopyClicked,
            onShareClicked = onShareClicked,
            onCodeSnippetCopyClicked = onCodeSnippetCopyClicked,
            onMessageRollback = onMessageRollback,
            responseGenerationSpeed = responseGenerationSpeed,
            responseGenerationTimeSecs = responseGenerationTimeSecs,
        )
    }
}

@Composable
private fun UserMessageListItem(
    message: ChatMessage,
    allowEditing: Boolean,
    onCopyClicked: () -> Unit,
    onShareClicked: () -> Unit,
    onMessageEdited: (String) -> Unit,
    onMessageRollback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEditing by rememberSaveable(message.id) { mutableStateOf(false) }
    var editedText by rememberSaveable(message.id) { mutableStateOf(message.message) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isEditing) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 8,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                errorContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                editedText = message.message
                                isEditing = false
                            }
                        ) {
                            Text(stringResource(R.string.dialog_neg_cancel))
                        }
                        TextButton(
                            onClick = {
                                isEditing = false
                                onMessageEdited(editedText)
                            }
                        ) {
                            Text(stringResource(R.string.edit_chat_message_done))
                        }
                    }
                }
            }
        } else {
            Surface(
                shape =
                    RoundedCornerShape(
                        topStart = 22.dp,
                        topEnd = 22.dp,
                        bottomEnd = 6.dp,
                        bottomStart = 22.dp,
                    ),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                ChatMessageText(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer.toArgb(),
                    textSize = 16f,
                    message = message.renderedMessage,
                    onLongClick = {
                        createChatMessageOptionsDialog(
                            showEditOption = allowEditing,
                            onEditClick = {
                                editedText = message.message
                                isEditing = true
                            },
                            onCopyClick = { onCopyClicked() },
                            onShareClick = { onShareClicked() },
                            onRollbackClick = { onMessageRollback() },
                            showRollbackOption = true,
                            showCopyCodeSnippetOption = false,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LLMMessageListItem(
    modifier: Modifier,
    message: ChatMessage,
    assistantName: String,
    isStreaming: Boolean,
    onCopyClicked: () -> Unit,
    onShareClicked: () -> Unit,
    onCodeSnippetCopyClicked: (List<String>) -> Unit,
    onMessageRollback: () -> Unit,
    responseGenerationSpeed: Float?,
    responseGenerationTimeSecs: Int?,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AssistantAvatar()
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = assistantName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape =
                    RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 22.dp,
                        bottomEnd = 22.dp,
                        bottomStart = 22.dp,
                    ),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border =
                    BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ChatMessageText(
                    modifier = Modifier.padding(14.dp),
                    textColor = MaterialTheme.colorScheme.onSurface.toArgb(),
                    textSize = 16f,
                    message =
                        if (isStreaming) {
                            message.renderedMessage.withStreamingCursor(
                                MaterialTheme.colorScheme.primary.toArgb()
                            )
                        } else {
                            message.renderedMessage
                        },
                    onLongClick = {
                        val messageProperties = buildMessageProperties(message.message)
                        createChatMessageOptionsDialog(
                            showEditOption = false,
                            onEditClick = {
                                /** Not applicable as showEditOption is set to false * */
                            },
                            onCopyClick = { onCopyClicked() },
                            onShareClick = { onShareClicked() },
                            onRollbackClick = { onMessageRollback() },
                            showRollbackOption = true,
                            onCodeSnippetCopyClick = {
                                onCodeSnippetCopyClicked(messageProperties.codeSnippets)
                            },
                            showCopyCodeSnippetOption = messageProperties.codeSnippets.isNotEmpty(),
                        )
                    },
                )
            }
            if (responseGenerationSpeed != null || responseGenerationTimeSecs != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    responseGenerationSpeed?.let {
                        Text(
                            text = "%.1f tok/s".format(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    responseGenerationTimeSecs?.let {
                        Text(
                            text = "${it}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AssistantAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = FeatherIcons.Cpu,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun Spanned.withStreamingCursor(color: Int): Spanned {
    val builder = SpannableStringBuilder(this)
    val start = builder.length
    builder.append(" ▍")
    builder.setSpan(
        ForegroundColorSpan(color),
        start,
        builder.length,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    return builder
}
