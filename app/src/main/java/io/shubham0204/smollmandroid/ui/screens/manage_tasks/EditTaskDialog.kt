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

package io.shubham0204.smollmandroid.ui.screens.manage_tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component1
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Edit2
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.LLMModel
import io.shubham0204.smollmandroid.data.Task
import io.shubham0204.smollmandroid.ui.components.SelectModelsList
import io.shubham0204.smollmandroid.ui.preview.dummyLLMModels
import io.shubham0204.smollmandroid.ui.preview.dummyTasksList
import kotlinx.collections.immutable.toImmutableList

@Preview
@Composable
private fun PreviewEditTaskDialog() {
    EditTaskDialog(
        selectedTask = dummyTasksList[0],
        selectedTaskModel = dummyLLMModels[0],
        availableModelsList = dummyLLMModels,
        onDismiss = {},
        onUpdateTask = {},
    )
}

@Composable
fun EditTaskDialog(
    selectedTask: Task,
    selectedTaskModel: LLMModel,
    availableModelsList: List<LLMModel>,
    onDismiss: () -> Unit,
    onUpdateTask: (Task) -> Unit,
) {
    var taskName by remember { mutableStateOf(selectedTask.name) }
    var systemPrompt by remember { mutableStateOf(selectedTask.systemPrompt) }
    var isModelListDialogVisible by remember { mutableStateOf(false) }
    val (focusRequestor) = FocusRequester.createRefs()
    var selectedModel by remember { mutableStateOf(selectedTaskModel) }
    val keyboardController = LocalSoftwareKeyboardController.current

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
                        .padding(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = FeatherIcons.Edit2,
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
                        text = stringResource(R.string.task_edit_task_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = taskName,
                    onValueChange = { taskName = it },
                    label = { Text(stringResource(R.string.task_create_task_name)) },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Next,
                        ),
                    keyboardActions = KeyboardActions(onNext = { focusRequestor.requestFocus() }),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequestor),
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.task_create_task_sys_prompt)) },
                    keyboardOptions =
                        KeyboardOptions.Default.copy(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    minLines = 3,
                    maxLines = 6,
                )

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { isModelListDialogVisible = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(FeatherIcons.Cpu, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = selectedModel.name,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(FeatherIcons.ChevronDown, contentDescription = null)
                }

                if (isModelListDialogVisible) {
                    SelectModelsList(
                        onDismissRequest = { isModelListDialogVisible = false },
                        modelsList = availableModelsList.toImmutableList(),
                        onModelListItemClick = { model ->
                            isModelListDialogVisible = false
                            selectedModel = model
                        },
                        onModelDeleteClick = { // Not applicable, as showModelDeleteIcon is set to
                            // false
                        },
                        showModelDeleteIcon = false,
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
                        enabled = taskName.isNotBlank() && systemPrompt.isNotBlank(),
                        onClick = {
                            onUpdateTask(
                                selectedTask.copy(
                                    name = taskName,
                                    systemPrompt = systemPrompt,
                                    modelId = selectedModel.id,
                                    modelName = selectedModel.name,
                                )
                            )
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.task_edit_task_update))
                    }
                }
            }
        }
    }
}
