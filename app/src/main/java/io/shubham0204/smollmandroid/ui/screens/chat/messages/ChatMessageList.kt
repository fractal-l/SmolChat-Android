package io.shubham0204.smollmandroid.ui.screens.chat.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.Spanned
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Cpu
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.ChatMessage
import io.shubham0204.smollmandroid.ui.screens.chat.ChatScreenUIEvent
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.showCodeSnippetsListDialog
import io.shubham0204.smollmandroid.ui.screens.chat.stripThinkingForClipboard
import kotlinx.collections.immutable.ImmutableList

@Composable
fun ColumnScope.MessagesList(
    messages: ImmutableList<ChatMessage>,
    isGeneratingResponse: Boolean,
    renderedPartialResponse: Spanned?,
    chatId: Long,
    assistantName: String,
    responseGenerationsSpeed: Float? = null,
    responseGenerationTimeSecs: Int? = null,
    onEvent: (ChatScreenUIEvent) -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val lastUserMessageIndex = messages.indexOfLast { it.isUserMessage }
    val reversedMessages = remember(messages) { messages.reversed() }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(renderedPartialResponse) {
        if (isGeneratingResponse && !listState.isScrollInProgress) {
            if (listState.firstVisibleItemIndex <= 1) {
                listState.scrollToItem(0)
            }
        }
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier
                .fillMaxSize()
                .weight(1f),
    ) {
        if (messages.isEmpty() && !isGeneratingResponse) {
            item(key = "empty_chat") {
                EmptyChatState(
                    assistantName = assistantName,
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        }
        if (isGeneratingResponse) {
            item(key = "generating_response") {
                if (renderedPartialResponse != null) {
                    MessageListItem(
                        message = ChatMessage(renderedMessage = renderedPartialResponse),
                        responseGenerationSpeed = null,
                        responseGenerationTimeSecs = null,
                        isUserMessage = false,
                        onCopyClicked = {},
                        onShareClicked = {},
                        onMessageEdited = {
                            // Not applicable as allowEditing is set to false
                        },
                        onMessageRollback = {
                            // Not applicable for the partial response
                        },
                        allowEditing = false,
                        onCodeSnippetCopyClicked = {
                            // Not applicable as partial messages (may) not have code snippets
                        },
                        assistantName = assistantName,
                        isStreaming = true,
                    )
                } else {
                    TypingIndicator(assistantName)
                }
            }
        }
        itemsIndexed(reversedMessages, key = { _, message -> message.id }) { i, chatMessage ->
            val originalIndex = messages.size - 1 - i
            MessageListItem(
                message = chatMessage,
                responseGenerationSpeed =
                    if (originalIndex == messages.size - 1) responseGenerationsSpeed else null,
                responseGenerationTimeSecs =
                    if (originalIndex == messages.size - 1) responseGenerationTimeSecs else null,
                isUserMessage = chatMessage.isUserMessage,
                onCopyClicked = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val plain =
                        if (chatMessage.isUserMessage) {
                            chatMessage.message
                        } else {
                            chatMessage.message.stripThinkingForClipboard()
                        }
                    val clip = ClipData.newPlainText("Copied message", plain)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        context,
                        context.getString(R.string.chat_message_copied),
                        Toast.LENGTH_SHORT,
                    )
                        .show()
                },
                onShareClicked = {
                    val plain =
                        if (chatMessage.isUserMessage) {
                            chatMessage.message
                        } else {
                            chatMessage.message.stripThinkingForClipboard()
                        }
                    context.startActivity(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, plain)
                        }
                    )
                },
                onMessageEdited = { newMessage ->
                    onEvent(
                        ChatScreenUIEvent.ChatEvents.OnMessageEdited(
                            chatId,
                            chatMessage,
                            newMessage,
                        )
                    )
                },
                onMessageRollback = {
                    onEvent(
                        ChatScreenUIEvent.ChatEvents.OnMessageRollback(
                            chatId,
                            chatMessage,
                        )
                    )
                },
                allowEditing = (originalIndex == lastUserMessageIndex),
                onCodeSnippetCopyClicked = {
                    showCodeSnippetsListDialog(it)
                },
                assistantName = assistantName,
            )
        }
    }
}

@Composable
private fun TypingIndicator(assistantName: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AssistantAvatar()
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = assistantName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
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
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(3) { index ->
                        AnimatedDot(delayMillis = index * 140)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedDot(delayMillis: Int) {
    val transition = rememberInfiniteTransition(label = "typing_dot")
    val alpha by
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis = 450,
                            delayMillis = delayMillis,
                            easing = LinearEasing,
                        ),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "typing_dot_alpha",
        )
    Box(
        modifier =
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
    )
}

@Composable
private fun EmptyChatState(assistantName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = FeatherIcons.Cpu,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.chat_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = FeatherIcons.Cpu,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = assistantName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
