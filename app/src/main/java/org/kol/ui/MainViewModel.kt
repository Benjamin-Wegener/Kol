package com.voiceassistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceassistant.ModelConfig
import com.voiceassistant.ModelDownloader
import com.voiceassistant.VoiceAssistantEngine
import com.voiceassistant.ai.RuntimeProviders
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val engine = VoiceAssistantEngine(app)
    val state: StateFlow<VoiceAssistantEngine.State> = engine.state
    val transcript: StateFlow<String> = engine.transcript
    val response: StateFlow<String> = engine.response

    fun start() {
        engine.initialize()
    }

    fun clearHistory() {
        engine.clearHistory()
    }

    fun engineGetWhisperProvider(): String = engine.whisperProvider()
    fun engineGetVadProvider(): String = engine.vadProvider()
    fun engineGetTtsProvider(): String = engine.ttsProvider()

    override fun onCleared() {
        engine.release()
    }
}
