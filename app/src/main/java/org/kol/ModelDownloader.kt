package com.voiceassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloader(private val context: Context) {

    enum class FileState { PENDING, DOWNLOADING, DONE, SKIPPED, ERROR }

    data class FileProgress(
        val fileName: String,
        val state: FileState = FileState.PENDING,
        val downloaded: Long = 0L,
        val total: Long = 0L,
        val error: String? = null
    ) {
        val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val downloadedMB: Float get() = downloaded / 1_048_576f
        val totalMB: Float get() = total / 1_048_576f
    }

    data class OverallProgress(
        val files: List<FileProgress>,
        val totalBytesDownloaded: Long,   // across all files this session
        val totalBytesExpected: Long,
        val currentFile: String = "",
        val currentFileIsResuming: Boolean = false,
        val speedBytesPerSec: Long = 0L,
        val etaSeconds: Long = -1L,
        val done: Boolean = false,
        val error: String? = null
    ) {
        val percent: Int get() = if (totalBytesExpected > 0)
            ((totalBytesDownloaded * 100) / totalBytesExpected).toInt() else 0

        val downloadedMB: Float get() = totalBytesDownloaded / 1_048_576f
        val totalMB: Float get() = totalBytesExpected / 1_048_576f

        fun speedStr(): String {
            if (speedBytesPerSec <= 0) return ""
            return when {
                speedBytesPerSec >= 1_048_576 -> "%.1f MB/s".format(speedBytesPerSec / 1_048_576f)
                else -> "%.0f KB/s".format(speedBytesPerSec / 1024f)
            }
        }

        fun etaStr(): String {
            if (etaSeconds < 0) return ""
            val m = etaSeconds / 60
            val s = etaSeconds % 60
            return if (m > 0) "ETA ${m}m ${s}s" else "ETA ${s}s"
        }
    }

    private val TAG = "ModelDownloader"

    private val fileList: List<String> = ModelConfig.DOWNLOAD_URLS.keys.toList()

    private val _progress = MutableStateFlow(
        OverallProgress(
            files = fileList.map { FileProgress(it) },
            totalBytesDownloaded = 0L,
            totalBytesExpected = 0L
        )
    )
    val progress: StateFlow<OverallProgress> = _progress

    // Legacy single-file flow kept for compatibility
    data class Progress(
        val fileName: String,
        val downloaded: Long,
        val total: Long,
        val isDone: Boolean = false,
        val error: String? = null
    ) {
        val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt() else 0
    }

    suspend fun downloadCore(): Boolean = withContext(Dispatchers.IO) {
        downloadFiles(ModelConfig.DOWNLOAD_URLS.entries.toList())
    }

    suspend fun downloadAll(): Boolean = withContext(Dispatchers.IO) {
        downloadFiles(ModelConfig.DOWNLOAD_URLS.entries.toList())
    }

    private suspend fun downloadFiles(urls: List<Map.Entry<String, String>>): Boolean {

        // Build initial state with all files PENDING
        val fileStates = fileList.map { FileProgress(it) }.toMutableList()

        // First pass: compute how many bytes we actually need to download
        var totalExpected = 0L
        val toDownload = mutableListOf<Pair<String, String>>() // fileName -> url
        for ((fileName, url) in urls) {
            val file = ModelConfig.modelFile(context, fileName)
            if (fileName == ModelConfig.SUPERTONIC_ARCHIVE) {
                val supertonicDir = File(ModelConfig.modelsDir(context), ModelConfig.SUPERTONIC_DIR)
                if (supertonicDir.resolve("duration_predictor.int8.onnx").exists() &&
                    supertonicDir.resolve("text_encoder.int8.onnx").exists() &&
                    supertonicDir.resolve("vector_estimator.int8.onnx").exists() &&
                    supertonicDir.resolve("vocoder.int8.onnx").exists() &&
                    supertonicDir.resolve("tts.json").exists() &&
                    supertonicDir.resolve("unicode_indexer.bin").exists() &&
                    supertonicDir.resolve("voice.bin").exists()
                ) {
                    Log.d(TAG, "Skipping Supertonic archive; extracted files already present at ${supertonicDir.absolutePath}")
                    val idx = fileStates.indexOfFirst { it.fileName == fileName }
                    if (idx >= 0) fileStates[idx] = fileStates[idx].copy(
                        state = FileState.SKIPPED,
                        downloaded = file.length(),
                        total = file.length()
                    )
                    continue
                }
            }
            if (file.exists() && file.length() > 1024) {
                val idx = fileStates.indexOfFirst { it.fileName == fileName }
                if (idx >= 0) fileStates[idx] = fileStates[idx].copy(
                    state = FileState.SKIPPED,
                    downloaded = file.length(),
                    total = file.length()
                )
            } else {
                toDownload.add(fileName to url)
            }
        }

        // HEAD requests to get sizes
        for ((fileName, url) in toDownload) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 15_000
                conn.connect()
                val size = conn.contentLengthLong
                conn.disconnect()
                if (size > 0) totalExpected += size
                val idx = fileStates.indexOfFirst { it.fileName == fileName }
                if (idx >= 0) fileStates[idx] = fileStates[idx].copy(total = size.coerceAtLeast(0))
            } catch (e: Exception) {
                Log.w(TAG, "HEAD failed for $fileName")
            }
        }

        _progress.value = OverallProgress(
            files = fileStates.toList(),
            totalBytesDownloaded = 0L,
            totalBytesExpected = totalExpected
        )

        var sessionDownloaded = 0L
        val sessionStart = System.currentTimeMillis()

        for ((fileName, url) in toDownload) {
            val file = ModelConfig.modelFile(context, fileName)
            val idx = fileStates.indexOfFirst { it.fileName == fileName }

            fileStates[idx] = fileStates[idx].copy(state = FileState.DOWNLOADING)
            _progress.value = _progress.value.copy(
                files = fileStates.toList(),
                currentFile = fileName,
                currentFileIsResuming = false
            )

            val success = downloadFile(
                url = url,
                dest = file,
                name = fileName,
                knownTotal = fileStates[idx].total,
                onProgress = { dlBytes, total ->
                    fileStates[idx] = fileStates[idx].copy(
                        downloaded = dlBytes,
                        total = total,
                        state = FileState.DOWNLOADING
                    )
                    val elapsedMs = (System.currentTimeMillis() - sessionStart).coerceAtLeast(1)
                    val speed = (sessionDownloaded + dlBytes) * 1000L / elapsedMs
                    val remaining = (totalExpected - sessionDownloaded - dlBytes).coerceAtLeast(0)
                    val eta = if (speed > 0) remaining / speed else -1L
                    _progress.value = OverallProgress(
                        files = fileStates.toList(),
                        totalBytesDownloaded = sessionDownloaded + dlBytes,
                        totalBytesExpected = totalExpected,
                        currentFile = fileName,
                        currentFileIsResuming = dlBytes > 0L,
                        speedBytesPerSec = speed,
                        etaSeconds = eta
                    )
                }
            )

            if (success) {
                sessionDownloaded += file.length()
                fileStates[idx] = fileStates[idx].copy(
                    state = FileState.DONE,
                    downloaded = file.length(),
                    total = file.length()
                )
                if (fileName == ModelConfig.SUPERTONIC_ARCHIVE) {
                    if (!extractSupertonicArchive(file)) {
                        fileStates[idx] = fileStates[idx].copy(
                            state = FileState.ERROR,
                            error = "Failed to unpack multilingual TTS archive"
                        )
                        _progress.value = _progress.value.copy(
                            files = fileStates.toList(),
                            error = "Failed to unpack multilingual TTS archive"
                        )
                        return false
                    }
                }
                _progress.value = _progress.value.copy(files = fileStates.toList())
            } else {
                fileStates[idx] = fileStates[idx].copy(
                    state = FileState.ERROR,
                    error = "Download failed"
                )
                _progress.value = _progress.value.copy(
                    files = fileStates.toList(),
                    error = "Failed to download $fileName"
                )
                return false
            }
        }

        _progress.value = _progress.value.copy(
            totalBytesDownloaded = _progress.value.totalBytesExpected,
            done = true,
            speedBytesPerSec = 0,
            etaSeconds = 0
        )
        return true
    }

    private fun downloadFile(
        url: String,
        dest: File,
        name: String,
        knownTotal: Long,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        return try {
            val tmp = File(dest.parent, "${dest.name}.tmp")
            val existingBytes = if (tmp.exists()) tmp.length() else 0L

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            if (existingBytes > 0L) {
                conn.setRequestProperty("Range", "bytes=$existingBytes-")
                Log.d(TAG, "Resuming $name from byte $existingBytes")
            }
            conn.connect()

            val responseCode = conn.responseCode
            val total = when {
                responseCode == HttpURLConnection.HTTP_PARTIAL -> {
                    val rangeHeader = conn.getHeaderField("Content-Range")
                    val slash = rangeHeader?.lastIndexOf('/') ?: -1
                    if (slash >= 0) rangeHeader.substring(slash + 1).toLongOrNull() ?: knownTotal else knownTotal
                }
                conn.contentLengthLong > 0 -> conn.contentLengthLong + existingBytes
                else -> knownTotal
            }
            var downloaded = existingBytes

            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    if (existingBytes > 0L) {
                        output.channel.position(existingBytes)
                    }
                    val buf = ByteArray(32_768)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }

            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                throw IOException("Failed to move ${tmp.absolutePath} to ${dest.absolutePath}")
            }
            Log.d(TAG, "Downloaded $name (${"%,.1f".format(downloaded / 1_048_576f)} MB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed: $name", e)
            false
        }
    }

    private fun extractSupertonicArchive(archive: File): Boolean {
        val targetDir = ModelConfig.modelsDir(context)
        val supertonicDir = targetDir.resolve(ModelConfig.SUPERTONIC_DIR)
        if (supertonicDir.resolve("duration_predictor.int8.onnx").exists() &&
            supertonicDir.resolve("text_encoder.int8.onnx").exists() &&
            supertonicDir.resolve("vector_estimator.int8.onnx").exists() &&
            supertonicDir.resolve("vocoder.int8.onnx").exists() &&
            supertonicDir.resolve("tts.json").exists() &&
            supertonicDir.resolve("unicode_indexer.bin").exists() &&
            supertonicDir.resolve("voice.bin").exists()
        ) {
            Log.d(TAG, "Supertonic archive already extracted at ${supertonicDir.absolutePath}")
            return true
        }

        val command = listOf("tar", "-xjf", archive.absolutePath, "-C", targetDir.absolutePath)
        return try {
            Log.d(TAG, "Extracting Supertonic archive ${archive.absolutePath} -> ${targetDir.absolutePath}")
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e(TAG, "Failed to extract multilingual TTS archive, exitCode=$exitCode")
                false
            } else {
                Log.d(
                    TAG,
                    "Extracted Supertonic archive; duration=${describeFile(supertonicDir.resolve("duration_predictor.int8.onnx"))} textEncoder=${describeFile(supertonicDir.resolve("text_encoder.int8.onnx"))} vector=${describeFile(supertonicDir.resolve("vector_estimator.int8.onnx"))} vocoder=${describeFile(supertonicDir.resolve("vocoder.int8.onnx"))} ttsJson=${describeFile(supertonicDir.resolve("tts.json"))} unicode=${describeFile(supertonicDir.resolve("unicode_indexer.bin"))} voice=${describeFile(supertonicDir.resolve("voice.bin"))}"
                )
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Multilingual TTS extraction unavailable", e)
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun describeFile(file: File): String {
        return if (!file.exists()) {
            "missing"
        } else {
            "exists,size=${file.length()}"
        }
    }

    fun totalSizeMB(): String = "~650 MB"
}
