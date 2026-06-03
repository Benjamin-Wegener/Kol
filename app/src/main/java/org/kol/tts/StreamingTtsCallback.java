package com.voiceassistant.tts;

import android.util.Log;

import kotlin.jvm.functions.Function1;

public final class StreamingTtsCallback implements Function1<float[], Integer> {
    public interface Listener {
        boolean onSamples(float[] samples);
    }

    private final String tag;
    private final long startedAtMs;
    private final Listener listener;
    private boolean sawFirstChunk = false;
    private int callbackChunks = 0;
    private int callbackSamples = 0;

    public StreamingTtsCallback(String tag, long startedAtMs, Listener listener) {
        this.tag = tag;
        this.startedAtMs = startedAtMs;
        this.listener = listener;
    }

    @Override
    public Integer invoke(float[] samples) {
        if (samples == null || samples.length == 0) {
            return Integer.valueOf(1);
        }
        if (!sawFirstChunk) {
            sawFirstChunk = true;
            Log.d(tag, "First streaming TTS chunk in " + (System.currentTimeMillis() - startedAtMs) + "ms samples=" + samples.length);
        }
        callbackChunks++;
        callbackSamples += samples.length;
        return listener.onSamples(samples.clone()) ? Integer.valueOf(1) : Integer.valueOf(0);
    }

    public int getCallbackChunks() {
        return callbackChunks;
    }

    public int getCallbackSamples() {
        return callbackSamples;
    }
}
