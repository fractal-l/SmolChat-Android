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

package io.shubham0204.smollmandroid.ui.screens.manage_characters

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash
import compose.icons.feathericons.User
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.CharacterPreset
import io.shubham0204.smollmandroid.data.CharacterPresetsStore
import io.shubham0204.smollmandroid.ui.components.AppAlertDialog
import io.shubham0204.smollmandroid.ui.components.AppBarTitleText
import io.shubham0204.smollmandroid.ui.components.createAlertDialog
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme
import org.koin.compose.koinInject

class ManageCharactersActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val store = koinInject<CharacterPresetsStore>()
            val presets by store.presets.collectAsState()
            CharactersScreenUI(
                presets = presets,
                onAddPreset = store::addPreset,
                onUpdatePreset = store::updatePreset,
                onDeletePreset = store::deletePreset,
                onBack = { finish() },
            )
        }
    }
}

@Preview
@Composable
private fun PreviewCharactersScreenUI() {
    CharactersScreenUI(
        presets =
            listOf(
                CharacterPreset(
                    id = 1,
                    name = "Code reviewer",
                    systemPrompt = "You are a senior software engineer.",
                    temperature = 0.2f,
                    minP = 0.05f,
                )
            ),
        onAddPreset = { _, _, _, _ -> },
        onUpdatePreset = {},
        onDeletePreset = {},
        onBack = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharactersScreenUI(
    presets: List<CharacterPreset>,
    onAddPreset: (String, String, Float, Float) -> Unit,
    onUpdatePreset: (CharacterPreset) -> Unit,
    onDeletePreset: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<CharacterPreset?>(null) }
    SmolLMAndroidTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { AppBarTitleText(stringResource(R.string.characters_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = "Navigate Back")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                editingPreset = null
                                showEditor = true
                            }
                        ) {
                            Icon(FeatherIcons.Plus, contentDescription = "Add Character")
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(paddingValues)
            ) {
                Text(
                    text = stringResource(R.string.characters_desc),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(16.dp),
                )
                if (presets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.characters_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        items(presets, key = { it.id }) { preset ->
                            val deleteTitle = stringResource(R.string.characters_delete_title)
                            val deleteText =
                                stringResource(R.string.characters_delete_text, preset.name)
                            val deleteButton = stringResource(R.string.dialog_pos_delete)
                            val cancelButton = stringResource(R.string.dialog_neg_cancel)
                            CharacterItem(
                                preset = preset,
                                onEditClick = {
                                    editingPreset = preset
                                    showEditor = true
                                },
                                onDeleteClick = {
                                    createAlertDialog(
                                        dialogTitle = deleteTitle,
                                        dialogText = deleteText,
                                        dialogPositiveButtonText = deleteButton,
                                        dialogNegativeButtonText = cancelButton,
                                        onPositiveButtonClick = { onDeletePreset(preset.id) },
                                        onNegativeButtonClick = {},
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        if (showEditor) {
            CharacterPresetDialog(
                initialPreset = editingPreset,
                onDismiss = { showEditor = false },
                onSave = { preset ->
                    if (editingPreset == null) {
                        onAddPreset(
                            preset.name,
                            preset.systemPrompt,
                            preset.temperature,
                            preset.minP,
                        )
                    } else {
                        onUpdatePreset(preset)
                    }
                    showEditor = false
                },
            )
        }
        AppAlertDialog()
    }
}

@Composable
private fun CharacterItem(
    preset: CharacterPreset,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FeatherIcons.User,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(text = preset.name, style = MaterialTheme.typography.titleSmall)
                if (preset.systemPrompt.isNotBlank()) {
                    Text(
                        text = preset.systemPrompt,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text =
                        "temp %.2f · min-p %.2f".format(
                            preset.temperature,
                            preset.minP,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEditClick) {
                Icon(FeatherIcons.Edit2, contentDescription = "Edit Character")
            }
            IconButton(onClick = onDeleteClick) {
                Icon(FeatherIcons.Trash, contentDescription = "Delete Character")
            }
        }
    }
}

@Composable
private fun CharacterPresetDialog(
    initialPreset: CharacterPreset?,
    onDismiss: () -> Unit,
    onSave: (CharacterPreset) -> Unit,
) {
    var name by remember { mutableStateOf(initialPreset?.name ?: "") }
    var systemPrompt by remember { mutableStateOf(initialPreset?.systemPrompt ?: "") }
    var temperature by remember {
        mutableFloatStateOf(initialPreset?.temperature ?: 0.8f)
    }
    var minP by remember { mutableFloatStateOf(initialPreset?.minP ?: 0.1f) }
    Surface {
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(16.dp)
            ) {
                Text(
                    text =
                        stringResource(
                            if (initialPreset == null) R.string.characters_add
                            else R.string.characters_edit
                        ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.characters_name)) },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(
                            capitalization = KeyboardCapitalization.Words
                        ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.characters_system_prompt)) },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(
                            capitalization = KeyboardCapitalization.Sentences
                        ),
                    maxLines = 6,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.characters_temperature),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0.0f..5.0f,
                )
                Text(
                    text = "%.2f".format(temperature),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.characters_min_p),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = minP,
                    onValueChange = { minP = it },
                    valueRange = 0.0f..1.0f,
                )
                Text(
                    text = "%.2f".format(minP),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onSave(
                            CharacterPreset(
                                id = initialPreset?.id ?: 0L,
                                name = name.trim(),
                                systemPrompt = systemPrompt,
                                temperature = temperature,
                                minP = minP,
                            )
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.characters_save))
                }
            }
        }
    }
}
