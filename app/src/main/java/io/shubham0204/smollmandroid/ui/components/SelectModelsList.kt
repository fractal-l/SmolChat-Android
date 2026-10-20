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

package io.shubham0204.smollmandroid.ui.components

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.Calendar
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash
import compose.icons.feathericons.Type
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.LLMModel
import io.shubham0204.smollmandroid.ui.preview.dummyLLMModels
import io.shubham0204.smollmandroid.ui.screens.model_download.DownloadModelActivity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.io.File

private enum class SortOrder {
    NAME,
    DATE_ADDED,
}

@Preview
@Composable
private fun PreviewSelectModelsList() {
    SelectModelsList(
        onDismissRequest = {},
        modelsList = dummyLLMModels.toImmutableList(),
        onModelListItemClick = {},
        onModelDeleteClick = {},
        onModelUpdate = {},
        showModelDeleteIcon = false,
        showModelEditIcon = false,
    )
}

@Composable
fun SelectModelsList(
    onDismissRequest: () -> Unit,
    modelsList: ImmutableList<LLMModel>,
    onModelListItemClick: (LLMModel) -> Unit,
    onModelDeleteClick: (LLMModel) -> Unit,
    onModelUpdate: (LLMModel) -> Unit = {},
    showModelDeleteIcon: Boolean = true,
    showModelEditIcon: Boolean = false,
) {
    val context = LocalContext.current
    var sortOrder by remember { mutableStateOf(SortOrder.NAME) }
    var editingModel by remember { mutableStateOf<LLMModel?>(null) }
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_model_list_screen_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.chat_model_list_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (editingModel != null) {
                    EditModelChatTemplate(
                        model = editingModel!!,
                        onCancel = { editingModel = null },
                        onSave = { updatedModel ->
                            onModelUpdate(updatedModel)
                            editingModel = null
                        },
                    )
                } else {
                // Animate switching between different types of content
                // See
                // https://developer.android.com/develop/ui/compose/animation/quick-guide#switch-different
                AnimatedContent(
                    sortOrder,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(100)) togetherWith
                                fadeOut(animationSpec = tween(100))
                    },
                    modifier =
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            sortOrder =
                                when (sortOrder) {
                                    SortOrder.NAME -> SortOrder.DATE_ADDED
                                    SortOrder.DATE_ADDED -> SortOrder.NAME
                                }
                        },
                    label = "change-sort-order-anim",
                ) { targetSortOrder: SortOrder ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .align(Alignment.End)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    sortOrder =
                                        if (sortOrder == SortOrder.NAME) SortOrder.DATE_ADDED
                                        else SortOrder.NAME
                                },
                    ) {
                        when (targetSortOrder) {
                            SortOrder.DATE_ADDED -> {
                                Icon(
                                    imageVector = FeatherIcons.Type,
                                    contentDescription = "Sort by Model Name",
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.chat_model_list_sort_name),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }

                            SortOrder.NAME -> {
                                Icon(
                                    imageVector = FeatherIcons.Calendar,
                                    contentDescription = "Sort by Date Added",
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.chat_model_list_sort_date),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    if (sortOrder == SortOrder.NAME) {
                        items(modelsList.sortedBy { it.name }) {
                            ModelListItem(
                                model = it,
                                onModelListItemClick,
                                onModelDeleteClick,
                                onModelEditClick = { model -> editingModel = model },
                                showModelDeleteIcon,
                                showModelEditIcon,
                            )
                        }
                    } else {
                        items(modelsList.reversed()) {
                            ModelListItem(
                                model = it,
                                onModelListItemClick,
                                onModelDeleteClick,
                                onModelEditClick = { model -> editingModel = model },
                                showModelDeleteIcon,
                                showModelEditIcon,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    onClick = {
                        Intent(context, DownloadModelActivity::class.java).also {
                            it.putExtra("openChatScreen", false)
                            context.startActivity(it)
                        }
                    },
                ) {
                    Icon(FeatherIcons.Plus, contentDescription = "New Model")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.chat_model_list_add_model))
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismissRequest,
                ) {
                    Text(stringResource(R.string.dialog_neg_cancel))
                }
                }
            }
        }
    }
}

@Composable
private fun EditModelChatTemplate(
    model: LLMModel,
    onCancel: () -> Unit,
    onSave: (LLMModel) -> Unit,
) {
    var chatTemplate by remember(model.id) { mutableStateOf(model.chatTemplate) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.chat_model_edit_template),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.chat_model_template_desc),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp),
            value = chatTemplate,
            onValueChange = { chatTemplate = it },
            label = { Text(stringResource(R.string.chat_model_template_label)) },
            maxLines = 10,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.dialog_neg_cancel))
            }
            TextButton(onClick = { onSave(model.copy(chatTemplate = chatTemplate)) }) {
                Text(stringResource(R.string.dialog_pos_save))
            }
        }
    }
}

@Composable
private fun ModelListItem(
    model: LLMModel,
    onModelListItemClick: (LLMModel) -> Unit,
    onModelDeleteClick: (LLMModel) -> Unit,
    onModelEditClick: (LLMModel) -> Unit,
    showModelDeleteIcon: Boolean,
    showModelEditIcon: Boolean,
) {
    val context = LocalContext.current
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .noRippleClickable { onModelListItemClick(model) },
        shape = RoundedCornerShape(14.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(38.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(12.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FeatherIcons.Cpu,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "%.1f GB".format(File(model.path).length() / (1e+9)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (showModelEditIcon) {
                IconButton(onClick = { onModelEditClick(model) }) {
                    Icon(
                        FeatherIcons.Edit2,
                        contentDescription = "Edit Chat Template",
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (showModelDeleteIcon) {
                IconButton(
                    onClick = {
                        createAlertDialog(
                            dialogTitle = context.getString(R.string.dialog_title_delete_chat),
                            dialogText =
                                context.getString(R.string.dialog_text_delete_chat).format(model.name),
                            dialogPositiveButtonText = context.getString(R.string.dialog_pos_delete),
                            dialogNegativeButtonText = context.getString(R.string.dialog_neg_cancel),
                            onPositiveButtonClick = { onModelDeleteClick(model) },
                            onNegativeButtonClick = {},
                        )
                    }
                ) {
                    Icon(
                        FeatherIcons.Trash,
                        contentDescription = "Delete Model",
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
