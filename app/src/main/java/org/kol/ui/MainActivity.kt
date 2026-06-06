package org.kol.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Debug
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import org.kol.ModelConfig
import com.voiceassistant.R
import org.kol.ai.RuntimeProviders
import org.kol.VoiceAssistantEngine
import com.voiceassistant.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * Represents the main activity component.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val drawerLayout: DrawerLayout get() = binding.root as DrawerLayout
    private val vm: MainViewModel by viewModels()
    private val chatAdapter = ChatAdapter()
    private lateinit var chatsAdapter: ArrayAdapter<String>
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
        binding.btnSettings.setOnClickListener { showDeviceStatsDialog() }
        binding.btnLanguage.setOnClickListener { showLanguageDialog() }
        binding.btnVoice.setOnClickListener { showVoiceDialog() }
        binding.btnTtsQuality.setOnClickListener { showTtsQualityDialog() }
        vm.loadVoiceId(this)
        vm.loadTtsSteps(this)
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChat.adapter = chatAdapter
        chatsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.lvChats.adapter = chatsAdapter
        binding.lvChats.setOnItemClickListener { _, _, position, _ ->
            val conversationId = vm.conversations.value.getOrNull(position)?.id ?: return@setOnItemClickListener
            vm.selectConversation(conversationId)
            drawerLayout.closeDrawers()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawers()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        checkMicPermission()
        observeState()
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
                if (text.isNotBlank()) vm.recordUserMessage(text)
            }
        }
        lifecycleScope.launch {
            vm.response.collectLatest { text ->
                if (text.isNotBlank()) vm.updateAssistantMessage(text)
            }
        }
        lifecycleScope.launch {
            vm.state.collectLatest { state ->
                if (state is VoiceAssistantEngine.State.Listening) {
                    vm.finishAssistantMessage()
                }
            }
        }
        lifecycleScope.launch {
            vm.chatMessages.collectLatest { messages ->
                chatAdapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    binding.rvChat.scrollToPosition(messages.lastIndex)
                }
            }
        }
        lifecycleScope.launch {
            vm.conversations.collectLatest { conversations ->
                chatsAdapter.clear()
                chatsAdapter.addAll(conversations.map { it.title })
                chatsAdapter.notifyDataSetChanged()
            }
        }
        lifecycleScope.launch {
            vm.voiceId.collectLatest { id ->
                binding.btnVoice.text = vm.voiceBadge(id)
                binding.btnVoice.contentDescription = "${vm.voiceMeaning(id)}"
            }
        }
        lifecycleScope.launch {
            vm.ttsSteps.collectLatest { steps ->
                binding.btnTtsQuality.text = vm.ttsQualityLabel(steps)
                binding.btnTtsQuality.contentDescription = vm.ttsQualityMeaning(steps)
            }
        }
    }

    private fun showDeviceStatsDialog() {
        val stats = buildDeviceStats()
        AlertDialog.Builder(this)
            .setTitle("Device stats")
            .setMessage(stats)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showLanguageDialog() {
        val options = ModelConfig.LANGUAGE_OPTIONS
        val labels = options.map { "${it.flag} ${it.label}" }.toTypedArray()
        val currentId = vm.currentLanguage() ?: "default"
        val selectedIndex = options.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Assistant language")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val selected = options[which]
                vm.setLanguage(selected.languageCode)
                dialog.dismiss()
            }
            .setPositiveButton("Cancel", null)
            .show()
    }

    private fun showVoiceDialog() {
        val options = vm.voiceOptions()
        val labels = options.map { it.emoji }.toTypedArray()
        val selectedIndex = options.indexOfFirst { it.id == vm.voiceId.value }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Voice")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                vm.selectVoice(this, options[which].id)
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showTtsQualityDialog() {
        val options = vm.ttsQualityOptions()
        val labels = options.map { "${it.label}  ${it.meaning}" }.toTypedArray()
        val selectedIndex = options.indexOfFirst { it.steps == vm.ttsSteps.value }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("TTS quality")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                vm.selectTtsQuality(this, options[which].steps)
                dialog.dismiss()
            }
            .setPositiveButton("Cancel", null)
            .show()
    }

    private fun buildDeviceStats(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val heapUsedMb = ((Debug.getPss() / 1024f) * 1f).roundToInt()
        val availRamMb = (memoryInfo.availMem / 1_048_576L).toInt()
        val totalRamMb = (memoryInfo.totalMem / 1_048_576L).toInt()
        val cpuUsage = readCpuUsage()

        val llmProvider = (vm.engineGetGemmaProvider())
        val sttProvider = (vm.engineGetSttProvider())
        val vadProvider = (vm.engineGetVadProvider())
        val ttsProvider = (vm.engineGetTtsProvider())
        val gpuStatus = "Gemma ${RuntimeProviders.providerLabel(llmProvider)} / " +
            "Whisper ${RuntimeProviders.providerLabel(sttProvider)} / " +
            "VAD ${RuntimeProviders.providerLabel(vadProvider)} / " +
            "TTS ${RuntimeProviders.providerLabel(ttsProvider)}"

        return buildString {
            appendLine("CPU: ${Runtime.getRuntime().availableProcessors()} cores • ${cpuUsage}% active")
            appendLine("RAM: ${availRamMb} MB free of ${totalRamMb} MB • App PSS ${heapUsedMb} MB")
            appendLine("Backend: $gpuStatus")
        }.trim()
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
                binding.tvStatus.text = "⏳"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
            }
            is VoiceAssistantEngine.State.Listening -> {
                binding.tvStatus.text = "🎧"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
            }
            is VoiceAssistantEngine.State.Understanding -> {
                binding.tvStatus.text = "🤔"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
            }
            is VoiceAssistantEngine.State.Thinking -> {
                binding.tvStatus.text = "💬"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
            }
            is VoiceAssistantEngine.State.Speaking -> {
                binding.tvStatus.text = "🔊"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_background)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_blue_700))
            }
            is VoiceAssistantEngine.State.Error -> {
                binding.tvStatus.text = "⚠️"
                binding.tvStatus.setBackgroundResource(R.drawable.kol_chip_error)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.kol_error))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
