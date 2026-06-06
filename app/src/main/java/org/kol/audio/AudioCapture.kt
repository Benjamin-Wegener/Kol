package org.kol.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import org.kol.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Continuously captures mic audio, applies AEC + NS, runs Silero VAD.
 * Emits utterance-complete float arrays ready for STT.
 *
 * Uses VOICE_COMMUNICATION audio source → hardware AEC so TTS playback
 * doesn't trigger VAD (barge-in without echo artifacts).
 */
class AudioCapture(
    private val vad: VadDetector,
    private val onUtterance: (FloatArray) -> Unit,
    private val onUtteranceSegment: (FloatArray) -> Unit,
    private val onSpeechStart: () -> Unit,
    private val onSpeechEnd: () -> Unit
) {
    private val tag = "AudioCapture"
    private val sampleRate = ModelConfig.STT_SAMPLE_RATE
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        .coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var captureJob: Job? = null

    private val isRunning = AtomicBoolean(false)
    private val suspendedUntilMs = AtomicLong(0L)
    private val bufferLock = Any()

    // Ring buffer: ~500ms pre-roll at 16kHz = 8000 shorts
    private val preRollCapacity = (sampleRate * 0.5).toInt()
    private val preRollBuffer = ArrayDeque<Short>(preRollCapacity)

    // Accumulated speech samples for current utterance
    private val speechBuffer = mutableListOf<Short>()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var utteranceCounter = 0L

    @SuppressLint("MissingPermission")
    /**
     * Handles start.
     */
    fun start() {
        if (isRunning.getAndSet(true)) return

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            encoding,
            bufferSize * 4
        )

        // Hardware echo cancellation — essential for barge-in
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true }
        }
        // Noise suppression
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true }
        }

        audioRecord = record
        record.startRecording()

        captureJob = scope.launch {
            val chunk = ShortArray(ModelConfig.STT_CHUNK_SIZE)
            var inSpeech = false
            var silenceFrames = 0
            var segmentEmitted = false
            val silenceThresholdFrames = (ModelConfig.VAD_MIN_SILENCE_MS /
                (1000.0 * ModelConfig.STT_CHUNK_SIZE / sampleRate)).toInt()
            val segmentThresholdFrames = (300.0 /
                (1000.0 * ModelConfig.STT_CHUNK_SIZE / sampleRate)).toInt().coerceAtLeast(1)

            while (isActive && isRunning.get()) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                if (System.currentTimeMillis() < suspendedUntilMs.get()) {
                    inSpeech = false
                    silenceFrames = 0
                    synchronized(bufferLock) {
                        speechBuffer.clear()
                        preRollBuffer.clear()
                    }
                    continue
                }

                val floatChunk = chunk.take(read).map { it / 32768f }.toFloatArray()

                // Update pre-roll ring buffer (always)
                synchronized(bufferLock) {
                    for (i in 0 until read) {
                        if (preRollBuffer.size >= preRollCapacity) preRollBuffer.removeFirst()
                        preRollBuffer.addLast(chunk[i])
                    }
                }

                val isSpeech = vad.isSpeech(floatChunk)

                when {
                    isSpeech && !inSpeech -> {
                        // Speech started — prepend pre-roll
                        utteranceCounter += 1
                        inSpeech = true
                        silenceFrames = 0
                        segmentEmitted = false
                        synchronized(bufferLock) {
                            speechBuffer.clear()
                            speechBuffer.addAll(preRollBuffer)
                            for (i in 0 until read) speechBuffer.add(chunk[i])
                        }
                        onSpeechStart()
                    }
                    isSpeech && inSpeech -> {
                        silenceFrames = 0
                        synchronized(bufferLock) {
                            for (i in 0 until read) speechBuffer.add(chunk[i])
                        }
                    }
                    !isSpeech && inSpeech -> {
                        silenceFrames++
                        synchronized(bufferLock) {
                            for (i in 0 until read) speechBuffer.add(chunk[i])
                        }

                        if (!segmentEmitted && silenceFrames >= segmentThresholdFrames) {
                            segmentEmitted = true
                            val segment = synchronized(bufferLock) {
                                speechBuffer.map { it / 32768f }.toFloatArray()
                            }
                            if (segment.isNotEmpty()) {
                                onUtteranceSegment(segment)
                            }
                        }

                        if (silenceFrames >= silenceThresholdFrames) {
                            // Utterance ended
                            inSpeech = false
                            val utterance = synchronized(bufferLock) {
                                val res = speechBuffer.map { it / 32768f }.toFloatArray()
                                speechBuffer.clear()
                                res
                            }
                            onSpeechEnd()
                            onUtterance(utterance)
                        }
                    }
                }
            }
        }
    }

    /**
     * Handles suspend processing.
     * @param durationMs Supplies the duration ms value.
     */
    fun suspendProcessing(durationMs: Long) {
        val untilMs = System.currentTimeMillis() + durationMs.coerceAtLeast(0L)
        suspendedUntilMs.set(untilMs)
        synchronized(bufferLock) {
            speechBuffer.clear()
            preRollBuffer.clear()
        }
    }

    /**
     * Handles stop.
     */
    fun stop() {
        isRunning.set(false)
        captureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        aec?.release()
        ns?.release()
    }
}
