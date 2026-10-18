package io.shubham0204.smollmandroid.ui.screens.chat.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
) {
    if (isUserMessage) {
        UserMessageListItem(
            message,
            allowEditing,
            onCopyClicked,
            onShareClicked,
            onMessageEdited,
            onMessageRollback,
        )
    } else {
        LLMMessageListItem(
            modifier,
            message,
            onCopyClicked,
            onShareClicked,
            onCodeSnippetCopyClicked,
            onMessageRollback,
            responseGenerationSpeed,
            responseGenerationTimeSecs
        )
    }
}

@Composable
private fun LazyItemScope.UserMessageListItem(
    message: ChatMessage,
    allowEditing: Boolean,
    onCopyClicked: () -> Unit,
    onShareClicked: () -> Unit,
    onMessageEdited: (String) -> Unit,
    onMessageRollback: () -> Unit,
) {
    var isEditing by rememberSaveable(message.id) { mutableStateOf(false) }
    var editedText by rememberSaveable(message.id) { mutableStateOf(message.message) }
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            if (isEditing) {
                TextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier =
                        Modifier
                            .padding(8.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(16.dp),
                            )
                            .padding(8.dp)
                            .widthIn(max = 250.dp),
                    colors =
                        TextFieldDefaults.colors(
                            errorContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        ),
                )
            } else {
                ChatMessageText(
                    modifier =
                        Modifier
                            .padding(4.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(16.dp),
                            )
                            .padding(8.dp)
                            .widthIn(max = 250.dp),
                    textColor = MaterialTheme.colorScheme.onSurface.toArgb(),
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
                            showCopyCodeSnippetOption = false
                        )
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (allowEditing) {
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isEditing) {
                        Text(
                            text = stringResource(R.string.edit_chat_message_done),
                            modifier =
                                Modifier.clickable {
                                    isEditing = false
                                    onMessageEdited(editedText)
                                },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.dialog_neg_cancel),
                            modifier =
                                Modifier.clickable {
                                    editedText = message.message
                                    isEditing = false
                                },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
        }
    }
}

@Composable
private fun LazyItemScope.LLMMessageListItem(
    modifier: Modifier,
    message: ChatMessage,
    onCopyClicked: () -> Unit,
    onShareClicked: () -> Unit,
    onCodeSnippetCopyClicked: (List<String>) -> Unit,
    onMessageRollback: () -> Unit,
    responseGenerationSpeed: Float?,
    responseGenerationTimeSecs: Int?
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            ChatMessageText(
                // to make pointerInput work in MarkdownText use disableLinkMovementMethod
                // https://github.com/jeziellago/compose-markdown/issues/85#issuecomment-2184040304
                modifier =
                    Modifier
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(4.dp)
                        .fillMaxSize(),
                textColor = MaterialTheme.colorScheme.onBackground.toArgb(),
                textSize = 16f,
                message = message.renderedMessage,
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
                        showCopyCodeSnippetOption = messageProperties.codeSnippets.isNotEmpty()
                    )
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                responseGenerationSpeed?.let {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "%.2f tokens/s".format(it), fontSize = 8.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier =
                            Modifier
                                .size(2.dp)
                                .clip(CircleShape)
                                .background(Color.DarkGray)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "$responseGenerationTimeSecs s", fontSize = 8.sp)
                }
            }
        }
    }
}
