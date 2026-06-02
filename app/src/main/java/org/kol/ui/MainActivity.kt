package com.voiceassistant.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.voiceassistant.ModelConfig
import com.voiceassistant.R
import com.voiceassistant.ai.RuntimeProviders
import com.voiceassistant.VoiceAssistantEngine
import com.voiceassistant.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.RandomAccessFile
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null
    private var lastTotalCpu: Long = 0L
    private var lastIdleCpu: Long = 0L

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) checkModelsAndStart() else showPermissionError()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClear.setOnClickListener { vm.clearHistory() }

        checkMicPermission()
        observeState()
        startStatsUpdates()
    }

    private fun checkMicPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> checkModelsAndStart()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkModelsAndStart() {
        if (!ModelConfig.allModelsPresent(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        vm.start()
    }

    private fun showPermissionError() {
        binding.tvStatus.text = "Microphone permission required"
        binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_error)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_error))
    }

    private fun observeState() {
        lifecycleScope.launch {
            vm.state.collectLatest { state ->
                updateUI(state)
            }
        }
        lifecycleScope.launch {
            vm.transcript.collectLatest { text ->
                if (text.isNotBlank()) binding.tvTranscript.text = "You: $text"
            }
        }
        lifecycleScope.launch {
            vm.response.collectLatest { text ->
                if (text.isNotBlank()) binding.tvResponse.text = text
            }
        }
    }

    private fun startStatsUpdates() {
        statsRunnable = object : Runnable {
            override fun run() {
                updateStats()
                statsHandler.postDelayed(this, 1000L)
            }
        }
        statsHandler.post(statsRunnable!!)
    }

    private fun updateStats() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val heapUsedMb = ((Debug.getPss() / 1024f) * 1f).roundToInt()
        val availRamMb = (memoryInfo.availMem / 1_048_576L).toInt()
        val totalRamMb = (memoryInfo.totalMem / 1_048_576L).toInt()
        val cpuUsage = readCpuUsage()

        val sttProvider = (vm.engineGetWhisperProvider())
        val vadProvider = (vm.engineGetVadProvider())
        val ttsProvider = (vm.engineGetTtsProvider())
        val gpuStatus = listOf(sttProvider, vadProvider, ttsProvider)
            .joinToString(" / ") { RuntimeProviders.providerLabel(it) }

        binding.tvStatsCpu.text = "CPU: ${Runtime.getRuntime().availableProcessors()} cores • ${cpuUsage}% active"
        binding.tvStatsRam.text = "RAM: ${availRamMb} MB free of ${totalRamMb} MB • App PSS ${heapUsedMb} MB"
        binding.tvStatsGpu.text = "Backend: $gpuStatus"
    }

    private fun readCpuUsage(): Int {
        return try {
            val cpuParts = RandomAccessFile("/proc/stat", "r").use { file ->
                val parts = file.readLine().trim().split(Regex("\\s+"))
                parts.drop(1).mapNotNull { it.toLongOrNull() }
            }
            val idle = (cpuParts.getOrNull(3) ?: 0L) + (cpuParts.getOrNull(4) ?: 0L)
            val total = cpuParts.sum()

            val totalDiff = total - lastTotalCpu
            val idleDiff = idle - lastIdleCpu

            lastTotalCpu = total
            lastIdleCpu = idle

            val active = if (totalDiff > 0) ((totalDiff - idleDiff).toDouble() / totalDiff * 100.0).roundToInt() else 0
            active.coerceIn(0, 100)
        } catch (_: Exception) {
            0
        }
    }

    private fun updateUI(state: VoiceAssistantEngine.State) {
        when (state) {
            is VoiceAssistantEngine.State.Idle -> {
                binding.tvStatus.text = "Loading…"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
                binding.orbView.setState(OrbView.OrbState.IDLE)
            }
            is VoiceAssistantEngine.State.Listening -> {
                binding.tvStatus.text = "Listening"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
                binding.orbView.setState(OrbView.OrbState.LISTENING)
            }
            is VoiceAssistantEngine.State.Transcribing -> {
                binding.tvStatus.text = "Understanding…"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
                binding.orbView.setState(OrbView.OrbState.PROCESSING)
            }
            is VoiceAssistantEngine.State.Thinking -> {
                binding.tvStatus.text = "Thinking…"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
                binding.orbView.setState(OrbView.OrbState.THINKING)
            }
            is VoiceAssistantEngine.State.Speaking -> {
                binding.tvStatus.text = "Speaking"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
                binding.orbView.setState(OrbView.OrbState.SPEAKING)
            }
            is VoiceAssistantEngine.State.Error -> {
                binding.tvStatus.text = "Error: ${state.message}"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_error)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_error))
                binding.orbView.setState(OrbView.OrbState.IDLE)
            }
        }
    }

    override fun onDestroy() {
        statsRunnable?.let { statsHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}
