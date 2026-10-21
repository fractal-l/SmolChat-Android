package io.shubham0204.smollmandroid.llm.speech2text;

import android.util.Log;

import ai.moonshine.voice.MicCaptureProcessor;
import ai.moonshine.voice.Transcriber;

/**
 * Connects Moonshine's microphone capture to a streaming transcriber.
 *
 * <p>Each call to {@link #start()} owns one microphone thread and one audio-processing thread.
 * {@link #stop()} asks both to finish, and the processing thread stops the native stream so the
 * final transcript is delivered exactly once.
 */
public class MyTranscriber extends Transcriber {
    private static final String LOG_TAG = "MyTranscriber";
    private static final int SAMPLE_RATE = 16000;
    private static final long IDLE_SLEEP_MS = 10L;
    private static final Object STREAM_LOCK = new Object();

    private volatile boolean isRunning = false;
    private volatile int generation = 0;
    private volatile int activeStreamHandle = -1;
    private Thread micThread;
    private Thread audioProcessingThread;

    @Override
    public synchronized void start() {
        if (isRunning) {
            return;
        }
        isRunning = true;
        int currentGeneration = ++generation;

        MicCaptureProcessor captureProcessor = new MicCaptureProcessor();
        micThread = new Thread(captureProcessor, "moonshine-mic");
        audioProcessingThread =
                new Thread(
                        () -> audioProcessingLoop(currentGeneration, captureProcessor),
                        "moonshine-audio");
        micThread.start();
        audioProcessingThread.start();
    }

    @Override
    public synchronized void stop() {
        isRunning = false;
        if (micThread != null) {
            micThread.interrupt();
        }
        // Give the processing thread a bounded chance to stop and free its stream so a prompt
        // restart cannot overlap native stream teardown or deliver the old final line to a new
        // listener. The bounded wait avoids hanging the UI if the native layer stalls.
        Thread processingThread = audioProcessingThread;
        if (processingThread != null && processingThread != Thread.currentThread()) {
            try {
                processingThread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Stream currently accepting audio, or {@code -1} while no stream is active. */
    public int getActiveStreamHandle() {
        return activeStreamHandle;
    }

    private void audioProcessingLoop(
            int loopGeneration, MicCaptureProcessor captureProcessor) {
        int streamHandle = -1;
        try {
            synchronized (STREAM_LOCK) {
                streamHandle = createStream();
                activeStreamHandle = streamHandle;
                startStream(streamHandle);
            }
            while (isRunning && loopGeneration == generation) {
                float[] audioData = captureProcessor.consumeAudio();
                if (audioData.length > 0) {
                    synchronized (STREAM_LOCK) {
                        addAudioToStream(streamHandle, audioData, SAMPLE_RATE);
                    }
                } else {
                    try {
                        Thread.sleep(IDLE_SLEEP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Audio processing failed", e);
        } finally {
            if (loopGeneration == generation) {
                isRunning = false;
                if (micThread != null) {
                    micThread.interrupt();
                    micThread = null;
                }
                audioProcessingThread = null;
            }
            if (streamHandle >= 0) {
                synchronized (STREAM_LOCK) {
                    try {
                        stopStream(streamHandle);
                    } catch (RuntimeException e) {
                        Log.e(LOG_TAG, "Could not finalize the transcription stream", e);
                    }
                    try {
                        freeStream(streamHandle);
                    } catch (RuntimeException e) {
                        Log.e(LOG_TAG, "Could not free the transcription stream", e);
                    }
                    if (activeStreamHandle == streamHandle) {
                        activeStreamHandle = -1;
                    }
                }
            }
        }
    }
}
