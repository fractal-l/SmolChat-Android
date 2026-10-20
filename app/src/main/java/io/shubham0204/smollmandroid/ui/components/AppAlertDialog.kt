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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private data class AlertDialogState(
    val title: String,
    val text: String,
    val positiveButtonText: String,
    val onPositiveButtonClick: () -> Unit,
    val negativeButtonText: String? = null,
    val onNegativeButtonClick: (() -> Unit)? = null,
)

private var alertDialogState by mutableStateOf<AlertDialogState?>(null)

@Composable
fun AppAlertDialog() {
    val state = alertDialogState
    if (state != null) {
        AlertDialog(
            title = { Text(text = state.title) },
            text = { Text(text = state.text) },
            onDismissRequest = { /* All alert dialogs are non-cancellable */ },
            confirmButton = {
                TextButton(
                    onClick = {
                        alertDialogState = null
                        state.onPositiveButtonClick()
                    }
                ) {
                    Text(text = state.positiveButtonText)
                }
            },
            dismissButton = {
                state.negativeButtonText?.let { negativeText ->
                    TextButton(
                        onClick = {
                            alertDialogState = null
                            state.onNegativeButtonClick?.invoke()
                        }
                    ) {
                        Text(text = negativeText)
                    }
                }
            },
        )
    }
}

fun createAlertDialog(
    dialogTitle: String,
    dialogText: String,
    dialogPositiveButtonText: String,
    dialogNegativeButtonText: String?,
    onPositiveButtonClick: (() -> Unit),
    onNegativeButtonClick: (() -> Unit)?,
) {
    alertDialogState =
        AlertDialogState(
            title = dialogTitle,
            text = dialogText,
            positiveButtonText = dialogPositiveButtonText,
            onPositiveButtonClick = onPositiveButtonClick,
            negativeButtonText = dialogNegativeButtonText?.takeIf { it.isNotBlank() },
            onNegativeButtonClick = onNegativeButtonClick,
        )
}
