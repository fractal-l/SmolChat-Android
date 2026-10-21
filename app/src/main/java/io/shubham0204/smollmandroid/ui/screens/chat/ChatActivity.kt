/*
 * Copyright (C) 2024 Shubham Panchal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.shubham0204.smollmandroid.ui.screens.chat

import CustomNavTypes
import android.content.Intent
import android.os.Bundle
import android.text.Spanned
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import compose.icons.FeatherIcons
import compose.icons.feathericons.Menu
import compose.icons.feathericons.MoreVertical
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.Chat
import io.shubham0204.smollmandroid.ui.components.AppBarTitleText
import io.shubham0204.smollmandroid.ui.components.SelectModelsList
import io.shubham0204.smollmandroid.ui.components.TextFieldDialog
import io.shubham0204.smollmandroid.ui.preview.dummyChats
import io.shubham0204.smollmandroid.ui.preview.dummyFolders
import io.shubham0204.smollmandroid.ui.preview.dummyLLMModels
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.ChangeFolderDialogUI
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.ChatMessageOptionsDialog
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.ChatMoreOptionsPopup
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.CodeSnippetsListDialog
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.FolderOptionsDialog
import io.shubham0204.smollmandroid.ui.screens.chat.messages.MessagesList
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.reflect.typeOf

private const val LOGTAG = "[ChatActivity-Kt]"
private val LOGD: (String) -> Unit = { Log.d(LOGTAG, it) }

@Serializable
private object ChatRoute

@Serializable
private object BenchmarkModelRoute

@Serializable
private data class EditChatSettingsRoute(val chat: Chat, val modelContextSize: Int)

class ChatActivity : ComponentActivity() {

    private val viewModel: ChatScreenViewModel by viewModel()
    private var modelUnloaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val navController = rememberNavController()
            Box(modifier = Modifier.safeDrawingPadding()) {
                NavHost(
                    navController = navController,
                    startDestination = ChatRoute,
                    enterTransition = {
                        slideInHorizontally(
                            animationSpec =
                                tween(durationMillis = 240, easing = FastOutSlowInEasing),
                            initialOffsetX = { it / 8 },
                        ) + fadeIn(tween(durationMillis = 180))
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            animationSpec =
                                tween(durationMillis = 180, easing = FastOutSlowInEasing),
                            targetOffsetX = { -it / 12 },
                        ) + fadeOut(tween(durationMillis = 160))
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            animationSpec =
                                tween(durationMillis = 240, easing = FastOutSlowInEasing),
                            initialOffsetX = { -it / 8 },
                        ) + fadeIn(tween(durationMillis = 180))
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            animationSpec =
                                tween(durationMillis = 180, easing = FastOutSlowInEasing),
                            targetOffsetX = { it / 12 },
                        ) + fadeOut(tween(durationMillis = 160))
                    },
                ) {
                    composable<BenchmarkModelRoute> {
                        BenchmarkModelScreen(
                            onBackClicked = { navController.navigateUp() },
                            viewModel::onEvent,
                        )
                    }
                    composable<EditChatSettingsRoute>(
                        typeMap = mapOf(typeOf<Chat>() to CustomNavTypes.ChatNavType)
                    ) { backStackEntry ->
                        val route: EditChatSettingsRoute = backStackEntry.toRoute()
                        val settings = EditableChatSettings.fromChat(route.chat)
                        val uiState by
                        viewModel.uiState.collectAsStateWithLifecycle(
                            LocalLifecycleOwner.current
                        )
                        EditChatSettingsScreen(
                            settings,
                            route.modelContextSize,
                            uiState.systemPrompts,
                            uiState.characterPresets,
                            onUpdateChat = { editableChatSettings ->
                                viewModel.onEvent(
                                    ChatScreenUIEvent.ChatEvents.UpdateChatSettings(
                                        editableChatSettings,
                                        route.chat,
                                    )
                                )
                            },
                            onAddSystemPrompt = { name, body ->
                                viewModel.onEvent(
                                    ChatScreenUIEvent.SystemPromptEvents.AddSystemPrompt(name, body)
                                )
                            },
                            onDeleteSystemPrompt = { id ->
                                viewModel.onEvent(
                                    ChatScreenUIEvent.SystemPromptEvents.DeleteSystemPrompt(id)
                                )
                            },
                            onBackClicked = { navController.navigateUp() },
                        )
                    }
                    composable<ChatRoute> {
                        val uiState by
                        viewModel.uiState.collectAsStateWithLifecycle(
                            LocalLifecycleOwner.current
                        )
                        val renderedPartialResponse =
                            viewModel.renderedPartialResponse.collectAsStateWithLifecycle(
                                LocalLifecycleOwner.current
                            )
                        val defaultQuestion =
                            remember(uiState.chat.id) {
                                viewModel.consumeQuestionTextDefaultVal()
                            }
                        ChatActivityScreenUI(
                            uiState,
                            renderedPartialResponse = renderedPartialResponse,
                            defaultQuestion = defaultQuestion,
                            onEditChatParamsClick = { chat, modelContextSize ->
                                navController.navigate(
                                    EditChatSettingsRoute(chat, modelContextSize)
                                )
                            },
                            onBenchmarkModelClick = { navController.navigate(BenchmarkModelRoute) },
                            viewModel::onEvent,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                val chatCount = viewModel.appDB.getChatsCount()
                val newChat = viewModel.appDB.addChat(chatName = "Untitled ${chatCount + 1}")
                viewModel.onEvent(ChatScreenUIEvent.ChatEvents.SwitchChat(newChat))
                viewModel.questionTextDefaultVal = text
            }
        }
    }

    /**
     * Load the model when the activity is visible to the user and unload the model when the
     * activity is not visible to the user. see
     * https://developer.android.com/guide/components/activities/activity-lifecycle
     */
    override fun onStart() {
        super.onStart()
        if (modelUnloaded) {
            viewModel.loadModel()
            LOGD("onStart() called - model loaded")
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            modelUnloaded = viewModel.unloadModel()
            LOGD("onStop() called - model unloaded result: $modelUnloaded")
        }
    }
}

@Preview
@Composable
private fun PreviewChatActivityScreenUI() {
    ChatActivityScreenUI(
        uiState =
            ChatScreenUIState(
                chat = dummyChats[0].copy(llmModel = dummyLLMModels[1]),
                folders = dummyFolders.toImmutableList(),
                chats = dummyChats.toImmutableList(),
                models = dummyLLMModels.toImmutableList(),
            ),
        onEditChatParamsClick = { _, _ -> },
        onBenchmarkModelClick = {},
        onEvent = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatActivityScreenUI(
    uiState: ChatScreenUIState,
    renderedPartialResponse: State<Spanned?> = mutableStateOf<Spanned?>(null),
    defaultQuestion: String? = null,
    onEditChatParamsClick: (Chat, Int) -> Unit,
    onBenchmarkModelClick: () -> Unit,
    onEvent: (ChatScreenUIEvent) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    SmolLMAndroidTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerUI(
                    uiState.chat,
                    uiState.chats,
                    uiState.folders,
                    onCloseDrawer = { scope.launch { drawerState.close() } },
                    onEvent = onEvent,
                )
                BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
            },
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    Column {
                        TopAppBar(
                            colors =
                                TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                            title = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    AppBarTitleText(uiState.chat.name)
                                    if (uiState.chat.llmModelId != -1L) {
                                        Text(
                                            text = uiState.chat.llmModel?.name ?: "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(
                                        FeatherIcons.Menu,
                                        contentDescription = stringResource(R.string.chat_view_chats),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            actions = {
                                Box {
                                    IconButton(
                                        onClick = {
                                            onEvent(
                                                ChatScreenUIEvent.DialogEvents.ToggleMoreOptionsPopup(
                                                    visible = true
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            FeatherIcons.MoreVertical,
                                            contentDescription = "Options",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    ChatMoreOptionsPopup(
                                        uiState.chat,
                                        uiState.showMoreOptionsPopup,
                                        uiState.memoryUsage != null,
                                        onEditChatSettingsClick = {
                                            onEditChatParamsClick(
                                                uiState.chat,
                                                uiState.chat.llmModel?.contextSize ?: 0,
                                            )
                                        },
                                        onBenchmarkModelClick = { onBenchmarkModelClick() },
                                        onEvent = onEvent,
                                    )
                                }
                            },
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                },
            ) { innerPadding ->
                Column(
                    modifier =
                        Modifier
                            .padding(innerPadding)
                            .imePadding()
                            .background(MaterialTheme.colorScheme.surface)
                ) {
                    ScreenUI(uiState, renderedPartialResponse, defaultQuestion, onEvent)
                }
            }

            if (uiState.showSelectModelListDialog) {
                SelectModelsList(
                    onDismissRequest = {
                        onEvent(
                            ChatScreenUIEvent.DialogEvents.ToggleSelectModelListDialog(
                                visible = false
                            )
                        )
                    },
                    uiState.models,
                    onModelListItemClick = { model ->
                        onEvent(ChatScreenUIEvent.ChatEvents.UpdateChatModel(model))
                    },
                    onModelDeleteClick = { model ->
                        onEvent(ChatScreenUIEvent.ChatEvents.DeleteModel(model))
                    },
                    onModelUpdate = { model ->
                        onEvent(ChatScreenUIEvent.ChatEvents.UpdateModel(model))
                    },
                    showModelEditIcon = true,
                )
            }
            if (uiState.showChangeFolderDialog) {
                ChangeFolderDialogUI(
                    onDismissRequest = {
                        onEvent(
                            ChatScreenUIEvent.DialogEvents.ToggleChangeFolderDialog(visible = false)
                        )
                    },
                    uiState.chat.folderId,
                    uiState.folders,
                    onUpdateFolderId = { folderId ->
                        onEvent(ChatScreenUIEvent.FolderEvents.UpdateChatFolder(folderId))
                    },
                )
            }
            FolderOptionsDialog()
            TextFieldDialog()
            ChatMessageOptionsDialog()
            CodeSnippetsListDialog()
        }
    }
}

@Composable
private fun ColumnScope.ScreenUI(
    uiState: ChatScreenUIState,
    renderedPartialResponse: State<Spanned?>,
    defaultQuestion: String?,
    onEvent: (ChatScreenUIEvent) -> Unit,
) {
    if (uiState.memoryUsage != null) {
        RAMUsageLabel(uiState.memoryUsage)
    }
    Spacer(modifier = Modifier.height(4.dp))
    val assistantName =
        uiState.chat.llmModel?.name ?: stringResource(R.string.chat_role_assistant)
    MessagesList(
        messages = uiState.messages,
        isGeneratingResponse = uiState.isGeneratingResponse,
        renderedPartialResponse = renderedPartialResponse.value,
        chatId = uiState.chat.id,
        assistantName = assistantName,
        responseGenerationsSpeed = uiState.responseGenerationsSpeed,
        responseGenerationTimeSecs = uiState.responseGenerationTimeSecs,
        onEvent = onEvent,
    )
    MessageInput(
        uiState.chat,
        uiState.modelLoadingState,
        uiState.audioTranscriptionUIState,
        uiState.isGeneratingResponse,
        onEvent,
        defaultQuestion = defaultQuestion,
    )
}

@Composable
private fun RAMUsageLabel(memoryUsage: Pair<Float, Float>) {
    val context = LocalContext.current
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        context.getString(R.string.label_device_ram).format(memoryUsage.first, memoryUsage.second),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}
