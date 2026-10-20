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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
import compose.icons.feathericons.Users
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.CharacterPreset
import io.shubham0204.smollmandroid.data.CharacterPresetsStore
import io.shubham0204.smollmandroid.ui.components.AppAlertDialog
import io.shubham0204.smollmandroid.ui.components.AppBarTitleText
import io.shubham0204.smollmandroid.ui.components.EmptyState
import io.shubham0204.smollmandroid.ui.components.PageIntro
import io.shubham0204.smollmandroid.ui.components.SectionHeader
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
        onAddPreset = {},
        onUpdatePreset = {},
        onDeletePreset = {},
        onBack = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharactersScreenUI(
    presets: List<CharacterPreset>,
    onAddPreset: (CharacterPreset) -> Unit,
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
                        .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                PageIntro(
                    text = stringResource(R.string.characters_desc),
                    icon = FeatherIcons.Users,
                )
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(
                    title = "Your characters",
                    description =
                        if (presets.isEmpty()) {
                            "Add a character to reuse a prompt and sampler settings."
                        } else {
                            "${presets.size} character${if (presets.size == 1) "" else "s"} available."
                        },
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (presets.isEmpty()) {
                    EmptyState(
                        icon = FeatherIcons.User,
                        title = stringResource(R.string.characters_empty),
                        description =
                            "Characters bundle a system prompt with temperature, min-p, top-k, top-p, repeat, and seed settings.",
                        actionLabel = stringResource(R.string.characters_add),
                        onAction = {
                            editingPreset = null
                            showEditor = true
                        },
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
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
                        onAddPreset(preset)
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

private fun samplerSummary(preset: CharacterPreset): String =
    "temp %.2f · min-p %.2f · top-k %d · top-p %.2f · repeat %.2f/%d · seed %s"
        .format(
            preset.temperature,
            preset.minP,
            preset.topK,
            preset.topP,
            preset.repeatPenalty,
            preset.repeatLastN,
            if (preset.seed < 0) "random" else preset.seed.toString(),
        )

@Composable
private fun CharacterItem(
    preset: CharacterPreset,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FeatherIcons.User,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier =
                    Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                        .padding(11.dp),
            )
            Spacer(modifier = Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preset.systemPrompt.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = preset.systemPrompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = samplerSummary(preset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
    var topK by remember { mutableIntStateOf(initialPreset?.topK ?: 40) }
    var topP by remember { mutableFloatStateOf(initialPreset?.topP ?: 0.95f) }
    var repeatPenalty by remember { mutableFloatStateOf(initialPreset?.repeatPenalty ?: 1.1f) }
    var repeatLastN by remember { mutableIntStateOf(initialPreset?.repeatLastN ?: 64) }
    var randomSeed by remember { mutableStateOf((initialPreset?.seed ?: -1) < 0) }
    var seedText by remember {
        mutableStateOf((initialPreset?.seed ?: -1).coerceAtLeast(0).toString())
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = FeatherIcons.User,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier =
                            Modifier
                                .size(40.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(10.dp),
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        text =
                            stringResource(
                                if (initialPreset == null) R.string.characters_add
                                else R.string.characters_edit
                            ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.characters_name)) },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(
                            capitalization = KeyboardCapitalization.Words
                        ),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.characters_system_prompt)) },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(
                            capitalization = KeyboardCapitalization.Sentences
                        ),
                    minLines = 3,
                    maxLines = 6,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.characters_temperature),
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0.0f..5.0f,
                )
                Text(
                    text = "%.2f".format(temperature),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.characters_min_p),
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = minP,
                    onValueChange = { minP = it },
                    valueRange = 0.0f..1.0f,
                )
                Text(
                    text = "%.2f".format(minP),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Top-K", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = topK.toFloat(),
                    onValueChange = { topK = it.toInt() },
                    valueRange = 0.0f..100.0f,
                    steps = 99,
                )
                Text(
                    text = "$topK",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Top-P", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0.0f..1.0f,
                    steps = 99,
                )
                Text(
                    text = "%.2f".format(topP),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Repeat penalty", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = repeatPenalty,
                    onValueChange = { repeatPenalty = it },
                    valueRange = 1.0f..2.0f,
                    steps = 99,
                )
                Text(
                    text = "%.2f".format(repeatPenalty),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Repeat last N tokens",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = repeatLastN.toFloat(),
                    onValueChange = { repeatLastN = it.toInt() },
                    valueRange = 0.0f..512.0f,
                    steps = 63,
                )
                Text(
                    text = "$repeatLastN",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Seed", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = randomSeed,
                        onCheckedChange = { randomSeed = it },
                    )
                    Text(
                        text = "Random seed",
                        style = MaterialTheme.typography.labelMedium,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_neg_cancel))
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        enabled = name.isNotBlank(),
                        onClick = {
                            onSave(
                                CharacterPreset(
                                    id = initialPreset?.id ?: 0L,
                                    name = name.trim(),
                                    systemPrompt = systemPrompt,
                                    temperature = temperature,
                                    minP = minP,
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
                                )
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.characters_save))
                    }
                }
            }
        }
    }
}
