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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Play
import io.shubham0204.smollmandroid.ui.components.AppBarTitleText
import io.shubham0204.smollmandroid.ui.components.PageIntro
import io.shubham0204.smollmandroid.ui.components.SectionHeader
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme

@Preview
@Composable
private fun PreviewBenchmarkModelScreen() {
    BenchmarkModelScreen(onBackClicked = {}, onEvent = {})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkModelScreen(onBackClicked: () -> Unit, onEvent: (ChatScreenUIEvent) -> Unit) {
    var benchmarkResult by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    val ppValue =
        remember(benchmarkResult) {
            benchmarkResult?.let { result ->
                val lines = result.split("\n")
                lines.find { it.contains("| pp ") }?.split("|")?.getOrNull(6)?.trim()
            } ?: "N/A"
        }
    val tgValue =
        remember(benchmarkResult) {
            benchmarkResult?.let { result ->
                val lines = result.split("\n")
                lines.find { it.contains("| tg ") }?.split("|")?.getOrNull(6)?.trim()
            } ?: "N/A"
        }

    SmolLMAndroidTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { AppBarTitleText("Model Benchmark") },
                    navigationIcon = {
                        IconButton(onClick = onBackClicked) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                        }
                    },
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                PageIntro(
                    text =
                        "Measure how quickly this device processes the prompt and generates new tokens with the loaded model.",
                    icon = FeatherIcons.Cpu,
                )

                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    onClick = {
                        isRunning = true
                        onEvent(
                            ChatScreenUIEvent.ChatEvents.StartBenchmark(
                                onResult = {
                                    benchmarkResult = it
                                    isRunning = false
                                }
                            )
                        )
                    },
                    enabled = !isRunning,
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Benchmarking...")
                    } else {
                        Icon(FeatherIcons.Play, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start benchmark")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(
                    title = "Results",
                    description = "Tokens processed or generated per second.",
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BenchmarkMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = FeatherIcons.Cpu,
                        label = "Prompt processing",
                        value = ppValue,
                    )
                    BenchmarkMetricCard(
                        modifier = Modifier.weight(1f),
                        icon = FeatherIcons.Play,
                        label = "Token generation",
                        value = tgValue,
                    )
                }

                if (benchmarkResult != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Prompt: 512 tokens  ·  Generation: 128 tokens",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchmarkMetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "tokens/s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
