package com.voiceassistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.voiceassistant.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams PCM audio chunks to AudioTrack.
 * flush() immediately stops playback and clears the queue — used for barge-in.
 */
class AudioPlayer {

    private val tag = "AudioPlayer"

    private val sampleRate = ModelConfig.TTS_SAMPLE_RATE
    private val audioQueue = Channel<FloatArray>(capacity = Channel.UNLIMITED)
    private val isFlushing = AtomicBoolean(false)
    private val hasStarted = AtomicBoolean(false)
    private var playJob: Job? = null
    private var audioTrack: AudioTrack? = null
    val isPlaying = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096) * 4

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        Log.d(tag, "AudioTrack created")

        playJob = scope.launch {
            for (chunk in audioQueue) {
                if (isFlushing.get()) continue   // drain without playing
                isPlaying.set(true)
                val pcm16 = chunk.toPcm16()
                val wrote = audioTrack?.write(pcm16, 0, pcm16.size, AudioTrack.WRITE_BLOCKING) ?: 0
                if (wrote < 0) {
                    Log.e(tag, "AudioTrack write failed code=$wrote chunkSize=${pcm16.size}")
                } else if (hasStarted.compareAndSet(false, true)) {
                    audioTrack?.play()
                    Log.d(tag, "AudioTrack started after first buffered chunk")
                }
            }
            isPlaying.set(false)
        }
    }

    /** Enqueue a synthesized audio chunk for playback */
    fun enqueue(samples: FloatArray) {
        if (!isFlushing.get() && samples.isNotEmpty()) {
            audioQueue.trySend(samples)
        }
    }

    /**
     * Stop current playback immediately and discard queued audio.
     * Called on barge-in (user starts speaking while assistant is talking).
     */
    fun flush() {
        isFlushing.set(true)
        audioTrack?.pause()
        audioTrack?.flush()
        hasStarted.set(false)
        // Drain the queue
        while (audioQueue.tryReceive().isSuccess) { /* discard */ }
        isFlushing.set(false)
        isPlaying.set(false)
    }

    fun stop() {
        playJob?.cancel()
        audioTrack?.let {
            Log.d(tag, "Stopping AudioTrack")
            it.stop()
            it.release()
        }
        audioTrack = null
    }

    private fun FloatArray.toPcm16(): ShortArray {
        val out = ShortArray(size)
        for (i in indices) {
            val sample = this[i].coerceIn(-1f, 1f)
            out[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
