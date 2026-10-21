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

package io.shubham0204.smollmandroid.ui.screens.model_download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.shubham0204.hf_model_hub_api.HFModelInfo
import io.shubham0204.hf_model_hub_api.HFModelSearch
import io.shubham0204.hf_model_hub_api.HFModelTree
import io.shubham0204.smollm.GGUFReader
import io.shubham0204.smollm.SmolLM
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.AppDB
import io.shubham0204.smollmandroid.data.HFModelsAPI
import io.shubham0204.smollmandroid.ui.components.hideProgressDialog
import io.shubham0204.smollmandroid.ui.components.setProgressDialogText
import io.shubham0204.smollmandroid.ui.components.setProgressDialogTitle
import io.shubham0204.smollmandroid.ui.components.showProgressDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Single
class DownloadModelsViewModel(
    val context: Context,
    val appDB: AppDB,
    val hfModelsAPI: HFModelsAPI,
) : ViewModel() {
    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun downloadModelFromIndex(selectedPopularModelIndex: Int) {
        // Downloading files in Android with the DownloadManager API
        // Ref: https://youtu.be/4t8EevQSYK4?feature=shared
        val modelUrl = getPopularModel(selectedPopularModelIndex)?.url
        if (modelUrl == null) {
            Toast.makeText(context, R.string.toast_invalid_file, Toast.LENGTH_SHORT).show()
            return
        }
        downloadModelFromUrl(modelUrl)
    }

    fun downloadModelFromUrl(modelUrl: String) {
        val fileName = modelUrl.substring(modelUrl.lastIndexOf('/') + 1)
        val request =
            DownloadManager.Request(modelUrl.toUri())
                .setTitle(fileName)
                .setDescription(
                    "The GGUF model will be downloaded on your device for use with SmolChat."
                )
                .setMimeType("application/octet-stream")
                .setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                )
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        downloadManager.enqueue(request)
    }

    fun getModels(query: String): Flow<PagingData<HFModelSearch.ModelSearchResult>> =
        hfModelsAPI.getModelsList(query).cachedIn(viewModelScope)

    /**
     * Given the model file URI, copy the model file to the app's internal directory. Once copied,
     * add a new LLMModel entity with modelName=fileName where fileName is the name of the model
     * file.
     */
    fun copyModelFile(uri: Uri, onComplete: () -> Unit) {
        val fileName =
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    } else {
                        null
                    }
                }
            }
                .getOrNull()
                ?.let { File(it).name }
                ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        if (fileName != null && fileName.endsWith(".gguf", ignoreCase = true)) {
            setProgressDialogTitle(context.getString(R.string.dialog_progress_copy_model_title))
            setProgressDialogText(
                context.getString(R.string.dialog_progress_copy_model_text, fileName)
            )
            showProgressDialog()
            viewModelScope.launch(Dispatchers.IO) {
                val targetFile = File(context.filesDir, fileName)
                val temporaryFile = File(context.filesDir, "${fileName}.part")
                val targetExisted = targetFile.exists()
                var moved = false
                var persisted = false
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        temporaryFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: throw IllegalStateException("Could not open the selected file.")

                    val ggufReader = GGUFReader()
                    ggufReader.load(temporaryFile.absolutePath)
                    val contextSize =
                        ggufReader.getContextSize() ?: SmolLM.DefaultInferenceParams.contextSize
                    val chatTemplate =
                        ggufReader.getChatTemplate() ?: SmolLM.DefaultInferenceParams.chatTemplate
                    Files.move(
                        temporaryFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    moved = true

                    val targetPath = targetFile.canonicalPath
                    val existingModel =
                        appDB.getModelsList().firstOrNull {
                            File(it.path).canonicalPath == targetPath
                        }
                    if (existingModel == null) {
                        appDB.addModel(
                            fileName,
                            "",
                            Paths.get(context.filesDir.absolutePath, fileName).toString(),
                            contextSize.toInt(),
                            chatTemplate,
                        )
                    } else {
                        appDB.updateModel(
                            existingModel.copy(
                                name = fileName,
                                path = targetFile.absolutePath,
                                contextSize = contextSize.toInt(),
                                chatTemplate = chatTemplate,
                            )
                        )
                    }
                    persisted = true

                    withContext(Dispatchers.Main) { onComplete() }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        temporaryFile.delete()
                        if (moved && !targetExisted && !persisted) {
                            targetFile.delete()
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    temporaryFile.delete()
                    // If this was a new model, don't leave an untracked file behind when
                    // persisting it fails. An existing model's file is valid and is kept.
                    if (moved && !targetExisted) {
                        targetFile.delete()
                    }
                    withContext(NonCancellable + Dispatchers.Main) {
                        Toast.makeText(
                                context,
                                R.string.toast_model_import_failed,
                                Toast.LENGTH_LONG,
                        )
                            .show()
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) { hideProgressDialog() }
                }
            }
        } else {
            Toast.makeText(
                    context,
                    context.getString(R.string.toast_invalid_file),
                    Toast.LENGTH_SHORT,
            )
                .show()
        }
    }

    fun fetchModelInfoAndTree(
        modelId: String,
        onResult: (HFModelInfo.ModelInfo, List<HFModelTree.HFModelFile>) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelInfo = hfModelsAPI.getModelInfo(modelId)
                val modelTree =
                    hfModelsAPI.getModelTree(modelId).filter { modelFile ->
                        modelFile.path.endsWith(".gguf")
                    }
                withContext(Dispatchers.Main) { onResult(modelInfo, modelTree) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Could not load the model.") }
            }
        }
    }
}
