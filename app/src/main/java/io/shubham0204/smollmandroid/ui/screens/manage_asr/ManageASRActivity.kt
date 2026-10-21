package io.shubham0204.smollmandroid.ui.screens.manage_asr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.Download
import compose.icons.feathericons.Mic
import compose.icons.feathericons.Play
import io.shubham0204.smollmandroid.ui.components.AppBarTitleText
import io.shubham0204.smollmandroid.ui.components.PageIntro
import io.shubham0204.smollmandroid.ui.components.SectionHeader
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme
import org.koin.androidx.compose.koinViewModel

class ManageASRActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: ManageASRViewModel = koinViewModel()
            val uiState by model.uiState.collectAsStateWithLifecycle()
            ScreenUI(uiState, model::onEvent)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ScreenUI(uiState: ManageASRUIState, onEvent: (ManageASRUIEvent) -> Unit) {
        SmolLMAndroidTheme {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    TopAppBar(
                        title = { AppBarTitleText("Manage Speech-to-Text") },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(FeatherIcons.ArrowLeft, contentDescription = "Back")
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier =
                        Modifier
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp)
                            .fillMaxSize()
                ) {
                    val launcher =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) {
                            if (it) {
                                onEvent(ManageASRUIEvent.EnableSpeech2Text)
                            } else {
                                Toast.makeText(
                                    this@ManageASRActivity,
                                    "You need permission to record audio to enable speech-to-text",
                                    Toast.LENGTH_LONG,
                                )
                                    .show()
                            }
                        }
                    Spacer(modifier = Modifier.height(8.dp))
                    PageIntro(
                        text =
                            "Turn spoken words into text in the chat composer. Everything runs locally on your device.",
                        icon = FeatherIcons.Mic,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Speech to text",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Enable or disable transcription globally.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = uiState.isEnabled,
                                onCheckedChange = {
                                    if (it) {
                                        if (checkAudioRecordingPermission()) {
                                            onEvent(ManageASRUIEvent.EnableSpeech2Text)
                                        } else {
                                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    } else {
                                        onEvent(ManageASRUIEvent.DisableSpeech2Text)
                                    }
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    SectionHeader(
                        title = "Available models",
                        description = "Download one model to use on-device speech recognition.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(
                            items = uiState.modelsList,
                            key = { it.model.name },
                            contentType = { "asr_model" },
                        ) { uiASRModel ->
                            ASRModelItem(
                                modifier = Modifier.animateItem(),
                                asrModel = uiASRModel,
                                onEvent = onEvent,
                            )
                        }
                    }

                    uiState.downloadingModelErrorMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }

                    if (uiState.downloadingModelProgress != null) {
                        DownloadProgressDialog(uiState.downloadingModelProgress)
                    }
                }
            }
        }
    }

    @Composable
    private fun ASRModelItem(
        modifier: Modifier = Modifier,
        asrModel: UIASRModel,
        onEvent: (ManageASRUIEvent) -> Unit,
    ) {
        val containerColor by
            animateColorAsState(
                targetValue =
                    if (asrModel.isEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                animationSpec = tween(durationMillis = 180),
                label = "asr_model_container",
            )
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
        ) {
            Row(
                modifier = Modifier
                    .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(42.dp)
                            .background(
                                if (asrModel.isEnabled) {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                RoundedCornerShape(14.dp),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = FeatherIcons.Mic,
                        contentDescription = null,
                        tint =
                            if (asrModel.isEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        asrModel.model.description,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        when {
                            asrModel.isEnabled -> "Currently active"
                            asrModel.isDownloaded -> "Downloaded"
                            else -> "Not downloaded"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (asrModel.isEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }

                if (asrModel.isDownloaded) {
                    if (asrModel.isEnabled) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    } else {
                        IconButton(
                            onClick = {
                                onEvent(ManageASRUIEvent.EnableSpeech2TextModel(asrModel.model))
                            }
                        ) {
                            Icon(FeatherIcons.Play, contentDescription = "Use Model")
                        }
                    }
                } else {
                    IconButton(
                        onClick = {
                            onEvent(ManageASRUIEvent.DownloadSpeech2TextModel(asrModel.model))
                        }
                    ) {
                        Icon(FeatherIcons.Download, contentDescription = "Download Model")
                    }
                }
            }
        }
    }

    @Composable
    private fun DownloadProgressDialog(progress: Int) {
        val animatedProgress by
            animateFloatAsState(
                targetValue = progress / 100f,
                animationSpec = tween(durationMillis = 220),
                label = "download_progress",
            )
        Dialog(onDismissRequest = {}) {
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = FeatherIcons.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Downloading model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Keep the app open while the model is saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "$progress%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    private fun checkAudioRecordingPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Preview
    @Composable
    private fun PreviewScreenUI() {
        ScreenUI(
            uiState =
                ManageASRUIState(
                    isEnabled = true,
                    modelsList =
                        listOf(
                            UIASRModel(
                                model =
                                    ASRModel(
                                        "tiny-en",
                                        "Tiny Model - English",
                                        "",
                                        "",
                                        ASRModelArch.TINY,
                                    ),
                                isEnabled = true,
                                isDownloaded = true,
                            ),
                            UIASRModel(
                                model =
                                    ASRModel(
                                        "base-zh",
                                        "Base Model - Chinese",
                                        "",
                                        "",
                                        ASRModelArch.BASE,
                                    ),
                                isEnabled = false,
                                isDownloaded = false,
                            ),
                        ),
                ),
            onEvent = {},
        )
    }
}
