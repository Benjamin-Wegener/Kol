package org.kol.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import org.kol.ModelDownloader
import org.kol.ModelDownloader.FileState
import com.voiceassistant.R
import com.voiceassistant.databinding.ActivitySetupBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Represents the setup activity component.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var downloader: ModelDownloader

    // Map fileName -> row views
    private data class FileRow(
        val icon: TextView,
        val name: TextView,
        val size: TextView,
        val progress: ProgressBar
    )
    private val rows = mutableMapOf<String, FileRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloader = ModelDownloader(this)
        startDownload()

        binding.btnRetry.setOnClickListener {
            binding.btnRetry.visibility = View.GONE
            binding.tvStatus.text = "Retrying\u2026"
            rows.clear()
            binding.llFiles.removeAllViews()
            startDownload()
        }
    }

    private fun startDownload() {
        // Observe progress
        lifecycleScope.launch {
            downloader.progress.collectLatest { overall ->
                // Build rows on first emission
                if (rows.isEmpty() && overall.files.isNotEmpty()) {
                    overall.files.forEach { fp ->
                        val row = inflateFileRow(fp.fileName)
                        rows[fp.fileName] = row
                    }
                }

                // Update overall bar
                binding.progressOverall.progress = overall.percent
                binding.tvOverallPercent.text = "${overall.percent}%"
                binding.tvDownloaded.text = "%s / %s".format(
                    fmtMB(overall.downloadedMB),
                    fmtMB(overall.totalMB)
                )
                binding.tvSpeed.text = overall.speedStr()
                binding.tvEta.text = overall.etaStr()

                // Update per-file rows
                overall.files.forEach { fp ->
                    val row = rows[fp.fileName] ?: return@forEach
                    when (fp.state) {
                        FileState.PENDING -> {
                            row.icon.text = "\u25cb"
                            row.icon.setTextColor(0xFF444444.toInt())
                            row.name.setTextColor(0xFF666666.toInt())
                            row.progress.visibility = View.GONE
                            row.size.text = ""
                        }
                        FileState.DOWNLOADING -> {
                            row.icon.text = "\u2193"
                            row.icon.setTextColor(0xFF58A6FF.toInt())
                            row.name.setTextColor(0xFFCCCCCC.toInt())
                            row.progress.visibility = View.VISIBLE
                            row.progress.progress = fp.percent
                            row.size.text = if (fp.total > 0)
                                "%s / %s".format(fmtMB(fp.downloadedMB), fmtMB(fp.totalMB))
                            else
                                fmtMB(fp.downloadedMB)
                        }
                        FileState.DONE -> {
                            row.icon.text = "\u2713"
                            row.icon.setTextColor(0xFF3FB950.toInt())
                            row.name.setTextColor(0xFF888888.toInt())
                            row.progress.visibility = View.GONE
                            row.size.text = fmtMB(fp.totalMB)
                        }
                        FileState.SKIPPED -> {
                            row.icon.text = "\u2713"
                            row.icon.setTextColor(0xFF3FB950.toInt())
                            row.name.setTextColor(0xFF555555.toInt())
                            row.progress.visibility = View.GONE
                            row.size.text = "cached"
                        }
                        FileState.ERROR -> {
                            row.icon.text = "\u2717"
                            row.icon.setTextColor(0xFFF85149.toInt())
                            row.name.setTextColor(0xFFF85149.toInt())
                            row.progress.visibility = View.GONE
                            row.size.text = "error"
                        }
                    }
                }

                // Handle terminal states
                when {
                    overall.done -> {
                        binding.tvStatus.text = "All models ready"
                        binding.tvOverallLabel.text = "Ready"
                        binding.progressOverall.progress = 100
                        binding.tvOverallPercent.text = "100%"
                        binding.tvSpeed.text = ""
                        binding.tvEta.text = ""
                        // Small delay then launch main
                        binding.root.postDelayed({
                            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                            finish()
                        }, 800)
                    }
                    overall.error != null -> {
                        binding.tvStatus.text = overall.error
                        binding.tvStatus.setTextColor(0xFFF85149.toInt())
                        binding.btnRetry.visibility = View.VISIBLE
                    }
                    else -> {
                        binding.tvStatus.text = when {
                            overall.currentFile.isNotEmpty() && overall.currentFileIsResuming ->
                                "Resuming ${overall.currentFile}\u2026"
                            overall.currentFile.isNotEmpty() ->
                                "Downloading ${overall.currentFile}\u2026"
                            else ->
                                "Preparing\u2026"
                        }
                    }
                }
            }
        }

        // Start download
        lifecycleScope.launch {
            downloader.downloadAll()
        }
    }

    private fun inflateFileRow(fileName: String): FileRow {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_file_progress, binding.llFiles, false)
        view.findViewById<TextView>(R.id.tvFileName).text = fileName
        binding.llFiles.addView(view)
        return FileRow(
            icon = view.findViewById(R.id.tvFileIcon),
            name = view.findViewById(R.id.tvFileName),
            size = view.findViewById(R.id.tvFileSize),
            progress = view.findViewById(R.id.progressFile)
        )
    }

    private fun fmtMB(mb: Float): String = when {
        mb >= 1024 -> "%.1f GB".format(mb / 1024)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(mb * 1024)
    }
}
