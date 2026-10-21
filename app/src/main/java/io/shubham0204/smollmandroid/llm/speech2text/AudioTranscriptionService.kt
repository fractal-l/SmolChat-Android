package io.shubham0204.smollmandroid.llm.speech2text

import ai.moonshine.voice.JNI
import ai.moonshine.voice.TranscriptEvent
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.shubham0204.smollmandroid.ui.screens.manage_asr.ASRModel
import io.shubham0204.smollmandroid.ui.screens.manage_asr.ASRModelArch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import java.io.File
import java.util.function.Consumer

@Single
class AudioTranscriptionService(private val context: Context) {

    private val micTranscriber = MyTranscriber()
    private val logTag = "[AudioTranscriptionService]"
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var model: ASRModel? = null
    private var listener: Consumer<TranscriptEvent>? = null
    private var owner: Any? = null
    private var isTranscribing = false

    @Synchronized
    fun startTranscription(
        asrModel: ASRModel,
        owner: Any,
        onLineComplete: (String) -> Unit,
    ): Error? {
        if (!checkIfAudioRecordingPermissionGranted()) {
            Log.e(logTag, "Permission to record audio was not granted.")
            return Error.AudioRecordingPermissionNotGranted(
                "Permission to record audio was not granted."
            )
        }

        if (isTranscribing) {
            stopTranscription()
        }

        if (model?.name != asrModel.name) {
            Log.d(logTag, "Loading model: $asrModel")
            val modelDir = File(context.filesDir, asrModel.name)
            if (!modelDir.isDirectory) {
                return Error.ModelLoadFailed("The selected speech model is not downloaded.")
            }
            try {
                micTranscriber.loadFromFiles(
                    modelDir.absolutePath,
                    when (asrModel.arch) {
                        ASRModelArch.TINY -> JNI.MOONSHINE_MODEL_ARCH_TINY
                        ASRModelArch.BASE -> JNI.MOONSHINE_MODEL_ARCH_BASE
                    },
                )
                model = asrModel
            } catch (e: RuntimeException) {
                Log.e(logTag, "Could not load ASR model", e)
                return Error.ModelLoadFailed("Could not load the selected speech model.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(logTag, "The speech model is not supported on this device", e)
                return Error.ModelLoadFailed("Speech recognition is not supported on this device.")
            }
        }

        listener?.let(micTranscriber::removeListener)
        val newListener =
            Consumer<TranscriptEvent> { event ->
                if (event is TranscriptEvent.LineCompleted &&
                    event.streamHandle == micTranscriber.activeStreamHandle
                ) {
                    Log.d(logTag, "ASR line completed: ${event.line.text}")
                    callbackScope.launch { onLineComplete(event.line.text) }
                }
            }
        listener = newListener
        micTranscriber.addListener(newListener)

        return try {
            Log.d(logTag, "Starting transcription...")
            micTranscriber.start()
            isTranscribing = true
            this.owner = owner
            null
        } catch (e: RuntimeException) {
            Log.e(logTag, "Could not start transcription", e)
            micTranscriber.removeListener(newListener)
            listener = null
            Error.TranscriptionFailed("Could not start speech recognition.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(logTag, "Speech recognition is not supported on this device", e)
            micTranscriber.removeListener(newListener)
            listener = null
            Error.TranscriptionFailed("Speech recognition is not supported on this device.")
        }
    }

    @Synchronized
    fun stopTranscription(owner: Any? = null, removeListener: Boolean = false) {
        if (owner != null && owner !== this.owner) return
        if (!isTranscribing) {
            if (removeListener) {
                listener?.let(micTranscriber::removeListener)
                listener = null
                this.owner = null
            }
            return
        }
        Log.d(logTag, "Stopping transcription...")
        // Keep the listener registered: the processing thread stops the native stream and emits
        // the final line asynchronously. It is replaced on the next start.
        try {
            micTranscriber.stop()
        } catch (e: RuntimeException) {
            Log.e(logTag, "Could not stop transcription", e)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(logTag, "Could not stop speech recognition", e)
        } finally {
            isTranscribing = false
            if (removeListener) {
                listener?.let(micTranscriber::removeListener)
                listener = null
                this.owner = null
            }
        }
    }

    private fun checkIfAudioRecordingPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    sealed interface Error {
        val message: String

        data class AudioRecordingPermissionNotGranted(override val message: String) : Error

        data class ModelLoadFailed(override val message: String) : Error

        data class TranscriptionFailed(override val message: String) : Error
    }
}
