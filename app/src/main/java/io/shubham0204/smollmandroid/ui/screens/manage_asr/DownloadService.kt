package io.shubham0204.smollmandroid.ui.screens.manage_asr

import android.content.Context
import com.ketch.Ketch
import com.ketch.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.koin.core.annotation.Single

@Single
class DownloadService(
    val context: Context
) {
    private class DownloadSlot {
        @Volatile var id: Int = -1
        @Volatile var superseded: Boolean = false
        @Volatile var job: Job? = null
    }

    private val ketch: Ketch =
        Ketch
            .builder()
            .setOkHttpClient(
                OkHttpClient
                    .Builder()
                    .connectTimeout(60L, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60L, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            ).build(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = mutableMapOf<String, DownloadSlot>()

    var defaultDownloadDir = context.filesDir.absolutePath

    /**
     * Starts a download and reports its lifecycle on the main thread. Starting the same destination
     * again supersedes the previous callback, cancels its Ketch download, and waits briefly for its
     * observer to stop before writing to the same file again.
     */
    @Synchronized
    fun startDownload(
        url: String,
        destDir: String,
        destFileName: String,
        onStart: () -> Unit,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val key = "$destDir/$destFileName"
        val previous = activeDownloads.remove(key)
        previous?.let { slot ->
            slot.superseded = true
            if (slot.id >= 0) {
                runCatching { ketch.cancel(slot.id) }
            }
        }

        val slot = DownloadSlot()
        activeDownloads[key] = slot
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Superseding a download is asynchronous in Ketch. Let its observer reach a
                    // terminal state before the replacement starts writing the same file.
                    previous?.job?.let { previousJob ->
                        withTimeoutOrNull(5_000L) { previousJob.join() }
                    }
                    if (slot.superseded) return@launch

                    val downloadId =
                        try {
                            ketch.download(url, destDir, destFileName)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                onFailure(e.message ?: "Download failed")
                            }
                            return@launch
                        }
                    slot.id = downloadId
                    if (slot.superseded) {
                        runCatching { ketch.cancel(downloadId) }
                        return@launch
                    }

                    var terminal = false
                    try {
                        ketch.observeDownloadById(downloadId).collect { download ->
                            if (terminal || slot.superseded || download == null) return@collect
                            withContext(Dispatchers.Main) {
                                when (download.status) {
                                    Status.QUEUED, Status.STARTED -> onStart()
                                    Status.PROGRESS -> onProgress(download.progress)
                                    Status.SUCCESS -> {
                                        terminal = true
                                        onSuccess()
                                    }
                                    Status.CANCELLED -> {
                                        terminal = true
                                        onFailure("Download cancelled")
                                    }
                                    Status.FAILED -> {
                                        terminal = true
                                        onFailure(download.failureReason)
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!terminal && !slot.superseded) {
                            withContext(Dispatchers.Main) {
                                onFailure(e.message ?: "Download failed")
                            }
                        }
                    }
                } finally {
                    synchronized(this@DownloadService) {
                        if (activeDownloads[key] === slot) {
                            activeDownloads.remove(key)
                        }
                    }
                }
            }
        slot.job = job
        job.start()
    }
}
