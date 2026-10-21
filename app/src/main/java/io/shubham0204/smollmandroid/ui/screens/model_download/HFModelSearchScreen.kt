/*
 * Copyright (C) 2025 Shubham Panchal
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

package io.shubham0204.smollmandroid.ui.screens.model_download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Search
import compose.icons.feathericons.User
import io.shubham0204.hf_model_hub_api.HFModelSearch
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.ui.components.AppBarTitleText
import io.shubham0204.smollmandroid.ui.components.AppProgressDialog
import io.shubham0204.smollmandroid.ui.components.EmptyState
import io.shubham0204.smollmandroid.ui.components.SectionHeader
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HFModelDownloadScreen(
    viewModel: DownloadModelsViewModel,
    onBackClicked: () -> Unit,
    onModelClick: (String) -> Unit,
) {
    SmolLMAndroidTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {
                        AppBarTitleText(stringResource(R.string.download_model_hf_screen_title))
                    },
                    navigationIcon = {
                        IconButton(onClick = { onBackClicked() }) {
                            Icon(
                                FeatherIcons.ArrowLeft,
                                contentDescription = "Navigate Back",
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.surface)
                        .fillMaxSize()
                        .imePadding()
            ) {
                var query by rememberSaveable { mutableStateOf("") }
                var debouncedQuery by rememberSaveable { mutableStateOf("") }
                LaunchedEffect(query) {
                    delay(300)
                    debouncedQuery = query
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = query,
                        onValueChange = { query = it },
                        shape = RoundedCornerShape(28.dp),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor =
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                        leadingIcon = {
                            Icon(
                                imageVector = FeatherIcons.Search,
                                contentDescription = "Search for models",
                            )
                        },
                        placeholder = {
                            Text(text = stringResource(R.string.download_model_hf_search_label))
                        },
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Search,
                            ),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    SectionHeader(
                        title = "Hugging Face models",
                        description = "Tap a model to inspect its GGUF files.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                ModelList(
                    query = debouncedQuery,
                    viewModel = viewModel,
                    onModelClick = onModelClick,
                    modifier = Modifier.weight(1f),
                )
                AppProgressDialog()
            }
        }
    }
}

@Composable
private fun ModelList(
    query: String,
    viewModel: DownloadModelsViewModel,
    onModelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modelsFlow = remember(query) { viewModel.getModels(query) }
    val models = modelsFlow.collectAsLazyPagingItems()
    val refreshState = models.loadState.refresh

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            refreshState is LoadState.Loading && models.itemCount == 0 -> {
                item {
                    ModelListLoading(modifier = Modifier.fillParentMaxSize())
                }
            }

            refreshState is LoadState.Error && models.itemCount == 0 -> {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            icon = FeatherIcons.Globe,
                            title = stringResource(R.string.model_search_offline_title),
                            description = stringResource(R.string.model_search_offline_desc),
                            actionLabel = stringResource(R.string.action_try_again),
                            onAction = { models.retry() },
                        )
                    }
                }
            }

            refreshState is LoadState.NotLoading && models.itemCount == 0 -> {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            icon = FeatherIcons.Search,
                            title = stringResource(R.string.model_search_empty_title),
                            description = stringResource(R.string.model_search_empty_desc),
                        )
                    }
                }
            }
        }

        items(
            count = models.itemCount,
            key = { index -> models.peek(index)?.id ?: index },
            contentType = { "hf_model" },
        ) { index ->
            models[index]?.let { model ->
                ModelListItem(
                    model = model,
                    onModelClick = onModelClick,
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (models.loadState.append is LoadState.Loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        if (models.loadState.append is LoadState.Error) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(onClick = { models.retry() }) {
                        Text(stringResource(R.string.action_try_again))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelListLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.model_search_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelListItem(
    model: HFModelSearch.ModelSearchResult,
    onModelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parts = model.id.split("/")
    val modelAuthor = if (parts.size > 1) parts[0] else "Hugging Face"
    val modelName = if (parts.size > 1) parts[1] else parts[0]

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onModelClick(model.id) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        FeatherIcons.User,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = modelAuthor,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    model.tags.filter { !listOf("GGUF", "conversational").contains(it) }.take(4)
                        .forEach { tag ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                }
            }
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
