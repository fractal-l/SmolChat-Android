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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Search
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Users
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.CharacterPreset
import io.shubham0204.smollmandroid.data.Chat
import io.shubham0204.smollmandroid.data.SystemPrompt
import io.shubham0204.smollmandroid.ui.components.AppBarTitleText
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

@Serializable
data class EditableChatSettings(
    val name: String,
    val systemPrompt: String,
    val minP: Float,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val repeatPenalty: Float,
    val repeatLastN: Int,
    val seed: Int,
    val contextSize: Int,
) {
    companion object {
        fun fromChat(chat: Chat): EditableChatSettings {
            return EditableChatSettings(
                name = chat.name,
                systemPrompt = chat.systemPrompt,
                minP = chat.minP,
                temperature = chat.temperature,
                topK = chat.topK,
                topP = chat.topP,
                repeatPenalty = chat.repeatPenalty,
                repeatLastN = chat.repeatLastN,
                seed = chat.seed,
                contextSize = chat.contextSize,
            )
        }
    }

    fun toChat(existingChat: Chat): Chat {
        existingChat.name = name
        existingChat.systemPrompt = systemPrompt
        existingChat.minP = minP
        existingChat.temperature = temperature
        existingChat.topK = topK
        existingChat.topP = topP
        existingChat.repeatPenalty = repeatPenalty
        existingChat.repeatLastN = repeatLastN
        existingChat.seed = seed
        existingChat.contextSize = contextSize
        return existingChat
    }
}

@Preview
@Composable
private fun PreviewEditChatSettingsScreen() {
    EditChatSettingsScreen(
        settings = EditableChatSettings.fromChat(Chat()),
        llmModelContextSize = 2048,
        systemPrompts = persistentListOf(),
        characterPresets = persistentListOf(),
        onUpdateChat = {},
        onAddSystemPrompt = { _, _ -> },
        onDeleteSystemPrompt = {},
        onBackClicked = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditChatSettingsScreen(
    settings: EditableChatSettings,
    llmModelContextSize: Int,
    systemPrompts: ImmutableList<SystemPrompt>,
    characterPresets: ImmutableList<CharacterPreset>,
    onUpdateChat: (EditableChatSettings) -> Unit,
    onAddSystemPrompt: (String, String) -> Unit,
    onDeleteSystemPrompt: (Long) -> Unit,
    onBackClicked: () -> Unit,
) {
    var chatName by rememberSaveable { mutableStateOf(settings.name) }
    var systemPrompt by rememberSaveable { mutableStateOf(settings.systemPrompt) }
    var minP by rememberSaveable { mutableFloatStateOf(settings.minP) }
    var temperature by rememberSaveable { mutableFloatStateOf(settings.temperature) }
    var topK by rememberSaveable { mutableIntStateOf(settings.topK) }
    var topP by rememberSaveable { mutableFloatStateOf(settings.topP) }
    var repeatPenalty by rememberSaveable { mutableFloatStateOf(settings.repeatPenalty) }
    var repeatLastN by rememberSaveable { mutableIntStateOf(settings.repeatLastN) }
    var randomSeed by rememberSaveable { mutableStateOf(settings.seed < 0) }
    var seedText by rememberSaveable { mutableStateOf(settings.seed.coerceAtLeast(0).toString()) }
    var contextSizeText by rememberSaveable { mutableStateOf(settings.contextSize.toString()) }
    var takeContextSizeFromModel by rememberSaveable { mutableStateOf(false) }
    val contextSize =
        if (takeContextSizeFromModel) llmModelContextSize
        else contextSizeText.toIntOrNull() ?: 0
    val settingsValid =
        chatName.isNotBlank() && systemPrompt.isNotBlank() && contextSize > 0

    var showSystemPromptsDialog by remember { mutableStateOf(false) }
    var showAddSystemPromptDialog by remember { mutableStateOf(false) }
    var showCharacterPresetsDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    SmolLMAndroidTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { AppBarTitleText(stringResource(R.string.edit_chat_screen_title)) },
                    navigationIcon = {
                        IconButton(onClick = { onBackClicked() }) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = "Navigate Back")
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = settingsValid,
                            onClick = {
                                val updatedChat =
                                    settings.copy(
                                        name = chatName,
                                        systemPrompt = systemPrompt,
                                        minP = minP,
                                        temperature = temperature,
                                        topK = topK,
                                        topP = topP,
                                        repeatPenalty = repeatPenalty,
                                        repeatLastN = repeatLastN,
                                        seed =
                                            if (randomSeed) {
                                                -1
                                            } else {
                                                seedText.toIntOrNull() ?: -1
                                            },
                                        contextSize = contextSize,
                                    )
                                if (settings != updatedChat) {
                                    onUpdateChat(updatedChat)
                                    // viewModel.updateChat()
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.edit_chat_new_settings_applied
                                        ),
                                        Toast.LENGTH_LONG,
                                    )
                                        .show()
                                }
                                onBackClicked()
                            }
                        ) {
                            Icon(FeatherIcons.Check, contentDescription = "Save settings")
                        }
                    },
                )
            }
        ) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
            ) {
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = chatName,
                    onValueChange = { chatName = it },
                    label = { Text(stringResource(R.string.chat_settings_label_chat_name)) },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(capitalization = KeyboardCapitalization.Words),
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.chat_settings_label_sys_prompt)) },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(
                            capitalization = KeyboardCapitalization.Sentences
                        ),
                    maxLines = 5,
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { showSystemPromptsDialog = true }) {
                                Icon(
                                    FeatherIcons.Search,
                                    contentDescription = "Choose System Prompt"
                                )
                            }
                            IconButton(onClick = { showAddSystemPromptDialog = true }) {
                                Icon(FeatherIcons.Plus, contentDescription = "Add System Prompt")
                            }
                            IconButton(onClick = { showCharacterPresetsDialog = true }) {
                                Icon(
                                    FeatherIcons.Users,
                                    contentDescription =
                                        stringResource(R.string.chat_settings_choose_character),
                                )
                            }
                        }
                    },
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    stringResource(R.string.chat_settings_label_minp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.chat_settings_desc_minp),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = minP,
                    onValueChange = { minP = it },
                    valueRange = 0.0f..1.0f,
                    steps = 100,
                )
                Text(text = "%.2f".format(minP), style = MaterialTheme.typography.labelSmall)

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    stringResource(R.string.chat_settings_label_temp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.chat_settings_desc_temp),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0.0f..5.0f,
                    steps = 50,
                )
                Text(text = "%.1f".format(temperature), style = MaterialTheme.typography.labelSmall)

                Spacer(modifier = Modifier.height(24.dp))

                Text(text = "Top-K", style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        "Keep only the K most likely tokens during sampling. Set to 0 to disable top-K.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = topK.toFloat(),
                    onValueChange = { topK = it.toInt() },
                    valueRange = 0.0f..100.0f,
                    steps = 99,
                )
                Text(text = "$topK", style = MaterialTheme.typography.labelSmall)

                Spacer(modifier = Modifier.height(24.dp))

                Text(text = "Top-P", style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        "Nucleus sampling keeps the smallest set of tokens whose combined probability reaches Top-P.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0.0f..1.0f,
                    steps = 99,
                )
                Text(text = "%.2f".format(topP), style = MaterialTheme.typography.labelSmall)

                Spacer(modifier = Modifier.height(24.dp))

                Text(text = "Repeat penalty", style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        "Penalizes recently used tokens to reduce repetition. 1.00 disables the penalty.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = repeatPenalty,
                    onValueChange = { repeatPenalty = it },
                    valueRange = 1.0f..2.0f,
                    steps = 99,
                )
                Text(
                    text = "%.2f".format(repeatPenalty),
                    style = MaterialTheme.typography.labelSmall,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(text = "Repeat last N tokens", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Number of recent tokens considered by the repeat penalty.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = repeatLastN.toFloat(),
                    onValueChange = { repeatLastN = it.toInt() },
                    valueRange = 0.0f..512.0f,
                    steps = 63,
                )
                Text(
                    text = "$repeatLastN tokens",
                    style = MaterialTheme.typography.labelSmall,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(text = "Seed", style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        "A fixed seed makes sampling reproducible. Use a random seed to get different responses.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = randomSeed,
                        onCheckedChange = { randomSeed = it },
                    )
                    Text(
                        text = "Random seed",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (!randomSeed) {
                    TextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = seedText,
                        onValueChange = { seedText = it.filter(Char::isDigit).take(10) },
                        label = { Text("Seed value") },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    stringResource(R.string.chat_settings_label_ctx_size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.chat_settings_desc_ctx_length),
                    style = MaterialTheme.typography.labelSmall,
                )
                TextField(
                    enabled = !takeContextSizeFromModel,
                    modifier = Modifier.fillMaxWidth(),
                    value =
                        if (takeContextSizeFromModel) {
                            llmModelContextSize.toString()
                        } else {
                            contextSizeText
                        },
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() }) {
                            contextSizeText = newValue
                        }
                    },
                    isError = contextSize <= 0,
                    label = {
                        if (contextSize == 0) {
                            Text(stringResource(R.string.chat_settings_err_min_ctx_size))
                        } else {
                            if (takeContextSizeFromModel) {
                                Text(stringResource(R.string.context_size_taken_from_model))
                            } else {
                                Text(stringResource(R.string.chat_settings_title_num_tokens))
                            }
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(
                            keyboardType = KeyboardType.Number,
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = takeContextSizeFromModel,
                        onCheckedChange = { takeContextSizeFromModel = it },
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_take_from_gguf),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (showSystemPromptsDialog) {
            SystemPromptsDialog(
                systemPrompts = systemPrompts,
                onPromptSelected = {
                    systemPrompt = it
                    showSystemPromptsDialog = false
                },
                onDeletePrompt = onDeleteSystemPrompt,
                onDismissRequest = { showSystemPromptsDialog = false },
            )
        }

        if (showAddSystemPromptDialog) {
            var promptName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddSystemPromptDialog = false },
                title = { Text("Save System Prompt") },
                text = {
                    Column {
                        Text("Enter a name for this system prompt:")
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = promptName,
                            onValueChange = { promptName = it },
                            placeholder = { Text("Prompt Name") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (promptName.isNotBlank()) {
                                onAddSystemPrompt(promptName, systemPrompt)
                                showAddSystemPromptDialog = false
                            }
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddSystemPromptDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showCharacterPresetsDialog) {
            CharacterPresetsDialog(
                characterPresets = characterPresets,
                onPresetSelected = { preset ->
                    systemPrompt = preset.systemPrompt
                    temperature = preset.temperature
                    minP = preset.minP
                    topK = preset.topK
                    topP = preset.topP
                    repeatPenalty = preset.repeatPenalty
                    repeatLastN = preset.repeatLastN
                    randomSeed = preset.seed < 0
                    seedText = preset.seed.coerceAtLeast(0).toString()
                    showCharacterPresetsDialog = false
                },
                onDismissRequest = { showCharacterPresetsDialog = false },
            )
        }
    }
}

@Composable
private fun CharacterPresetsDialog(
    characterPresets: ImmutableList<CharacterPreset>,
    onPresetSelected: (CharacterPreset) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.chat_settings_choose_character)) },
        text = {
            if (characterPresets.isEmpty()) {
                Text(stringResource(R.string.characters_empty))
            } else {
                LazyColumn {
                    items(
                        items = characterPresets,
                        key = { it.id },
                        contentType = { "character_preset" },
                    ) { preset ->
                        ListItem(
                            headlineContent = { Text(preset.name) },
                            supportingContent = {
                                Text(
                                    text =
                                        "temp %.2f · min-p %.2f · top-k %d · top-p %.2f · repeat %.2f/%d · seed %s"
                                            .format(
                                                preset.temperature,
                                                preset.minP,
                                                preset.topK,
                                                preset.topP,
                                                preset.repeatPenalty,
                                                preset.repeatLastN,
                                                if (preset.seed < 0) "random"
                                                else preset.seed.toString(),
                                            ),
                                    maxLines = 2,
                                )
                            },
                            modifier =
                                Modifier
                                    .animateItem()
                                    .fillMaxWidth()
                                    .clickable { onPresetSelected(preset) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismissRequest) { Text("Close") } },
    )
}

@Composable
private fun SystemPromptsDialog(
    systemPrompts: ImmutableList<SystemPrompt>,
    onPromptSelected: (String) -> Unit,
    onDeletePrompt: (Long) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Choose System Prompt") },
        text = {
            if (systemPrompts.isEmpty()) {
                Text("No system prompts saved.")
            } else {
                LazyColumn {
                    items(
                        items = systemPrompts,
                        key = { it.id },
                        contentType = { "system_prompt" },
                    ) { prompt ->
                        ListItem(
                            headlineContent = { Text(prompt.name) },
                            supportingContent = { Text(prompt.body, maxLines = 1) },
                            trailingContent = {
                                IconButton(onClick = { onDeletePrompt(prompt.id) }) {
                                    Icon(FeatherIcons.Trash2, contentDescription = "Delete Prompt")
                                }
                            },
                            modifier =
                                Modifier
                                    .animateItem()
                                    .fillMaxWidth()
                                    .clickable { onPromptSelected(prompt.body) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismissRequest) { Text("Close") } },
    )
}
