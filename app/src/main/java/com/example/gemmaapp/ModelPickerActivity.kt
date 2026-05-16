package com.example.gemmaapp

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lets the user pick a GGUF model file from storage.
 *
 * On Android 10+ the system file picker returns a content:// URI — we cannot
 * pass that directly to llama.cpp's file loader. Instead we copy the file into
 * the app's private cache directory (no special permissions needed) and hand
 * that absolute path to the engine.
 *
 * Auto-detect also checks /sdcard/Download/models/ via direct File access,
 * which works as long as the file is placed there via ADB push.
 */
class ModelPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_WHISPER_PATH = "whisper_path"

        /** ADB-push destination shown in the hint. */
        val DEFAULT_DOWNLOAD_DIR: File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "models"
        )
        val DEFAULT_WHISPER_DIR: File = File(DEFAULT_DOWNLOAD_DIR, "whisper")
    }

    private lateinit var tvHint: TextView
    private lateinit var btnPickFile: Button
    private lateinit var btnDownloadGemma: Button
    private lateinit var btnDownloadWhisper: Button
    private lateinit var btnDownloadVosk: Button
    private lateinit var progressContainer: LinearLayout
    private lateinit var tvProgressLabel: TextView
    private lateinit var progressBar: ProgressBar
    
    private var detectedWhisperPath: String? = null
    private var detectedVoskPath: String? = null
    
    private val gemmaDownloadUrl = "https://huggingface.co/bartowski/google_gemma-4-2b-it-GGUF/resolve/main/gemma-4-2b-it-Q4_K_M.gguf?download=true"
    
    private val whisperUrls = mapOf(
        "encoder_model_quantized.onnx" to "https://huggingface.co/onnx-community/whisper-small/resolve/main/onnx/encoder_model_quantized.onnx?download=true",
        "decoder_model_quantized.onnx" to "https://huggingface.co/onnx-community/whisper-small/resolve/main/onnx/decoder_model_quantized.onnx?download=true",
        "tokenizer.json" to "https://huggingface.co/onnx-community/whisper-small/resolve/main/tokenizer.json?download=true"
    )

    private val voskDownloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    // ── File picker (SAF) ─────────────────────────────────────────────────────

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        copyAndReturn(uri)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_picker)

        tvHint = findViewById(R.id.tvHint)
        tvHint.text = "Checking for models..."

        btnPickFile = findViewById(R.id.btnPickFile)
        btnPickFile.visibility = android.view.View.GONE // Hide SAF picker, enforce downloads
        
        btnDownloadGemma = findViewById(R.id.btnDownloadGemma)
        btnDownloadWhisper = findViewById(R.id.btnDownloadWhisper)
        btnDownloadVosk = findViewById(R.id.btnDownloadVosk)
        progressContainer = findViewById(R.id.progressContainer)
        tvProgressLabel = findViewById(R.id.tvProgressLabel)
        progressBar = findViewById(R.id.progressBar)

        btnPickFile.setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }
        
        btnDownloadGemma.setOnClickListener { downloadGemma() }
        btnDownloadWhisper.setOnClickListener { downloadWhisper() }
        btnDownloadVosk.setOnClickListener { downloadVosk() }

        // Auto-detect: scan the well-known ADB-push directory and app private dir
        refreshAutoDetect()
    }

    // ── Auto-detect ───────────────────────────────────────────────────────────

    private fun refreshAutoDetect() {
        val appDownloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val appWhisperDir = File(appDownloadsDir, "whisper")
        val appVoskDir = File(appDownloadsDir, "vosk-model-small-en-us-0.15")
        
        val gemmaFile = listOf(cacheDir, DEFAULT_DOWNLOAD_DIR, appDownloadsDir)
            .mapNotNull { it?.takeIf { d -> d.exists() && d.isDirectory } }
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .firstOrNull { it.extension.equals("gguf", ignoreCase = true) }

        // Check if Whisper files exist in either location
        var whisperDir: File? = null
        for (dir in listOf(DEFAULT_WHISPER_DIR, appWhisperDir)) {
            if (dir != null && dir.exists() && dir.isDirectory) {
                val files = dir.listFiles()?.map { it.name } ?: emptyList()
                if (files.contains("encoder_model_quantized.onnx") && 
                    files.contains("decoder_model_quantized.onnx")) {
                    whisperDir = dir
                    break
                }
            }
        }
            
        if (whisperDir != null) {
            detectedWhisperPath = whisperDir.absolutePath
        }

        // Check for Vosk
        var voskDir: File? = null
        if (appVoskDir.exists() && appVoskDir.isDirectory && appVoskDir.listFiles()?.isNotEmpty() == true) {
            voskDir = appVoskDir
            detectedVoskPath = voskDir.absolutePath
        }

        val btn = findViewById<Button>(R.id.btnAutoDetect)
        
        // Hide download buttons if models exist
        btnDownloadGemma.visibility = if (gemmaFile == null) android.view.View.VISIBLE else android.view.View.GONE
        btnDownloadWhisper.visibility = if (whisperDir == null) android.view.View.VISIBLE else android.view.View.GONE
        btnDownloadVosk.visibility = if (voskDir == null) android.view.View.VISIBLE else android.view.View.GONE
        
        tvHint.text = ""
        if (gemmaFile != null) tvHint.append("✅ Gemma: ${gemmaFile.name}\n")
        else tvHint.append("❌ Gemma model missing\n")
        
        if (whisperDir != null) tvHint.append("✅ Whisper ONNX found\n")
        else tvHint.append("❌ Whisper models missing\n")

        if (voskDir != null) tvHint.append("✅ Vosk model found")
        else tvHint.append("❌ Vosk model missing")

        // Only allow proceed if ALL are installed
        if (gemmaFile != null && whisperDir != null && voskDir != null) {
            btn.visibility = android.view.View.VISIBLE
            btn.text = "Start Voice Assistant"
            btn.setOnClickListener { returnPath(gemmaFile.absolutePath) }
        } else {
            btn.visibility = android.view.View.GONE
        }
    }

    // ── Copy content:// URI → private cache ───────────────────────────────────

    @Suppress("DEPRECATION")
    private fun copyAndReturn(uri: Uri) {
        // Resolve a display name for the destination file
        val displayName = resolveDisplayName(uri) ?: "model.gguf"
        val dest = File(cacheDir, displayName)

        // If already copied with matching size, reuse it
        val srcSize = resolveSize(uri)
        if (dest.exists() && srcSize > 0 && dest.length() == srcSize) {
            tvHint.text = "Using cached copy:\n${dest.absolutePath}"
            returnPath(dest.absolutePath)
            return
        }

        // Show progress while copying (can be several GB)
        val progress = ProgressDialog(this).apply {
            setTitle("Preparing model…")
            setMessage("Copying ${displayName}\nThis may take a minute for large files.")
            isIndeterminate = true
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    dest.absolutePath
                }
            }

            progress.dismiss()

            result.fold(
                onSuccess = { path ->
                    tvHint.text = "Model ready:\n$path"
                    returnPath(path)
                },
                onFailure = { err ->
                    tvHint.text = "❌ Copy failed: ${err.message}\n\n" +
                        "Try using ADB push instead:\n" +
                        "adb push model.gguf /sdcard/Download/models/"
                }
            )
        }
    }

    // ── Downloading ───────────────────────────────────────────────────────────
    
    private fun setDownloadUiActive(active: Boolean) {
        btnDownloadGemma.isEnabled = !active
        btnDownloadWhisper.isEnabled = !active
        btnDownloadVosk.isEnabled = !active
        progressContainer.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
    }
    
    private fun downloadGemma() {
        val destFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "gemma-4-2b-it-Q4_K_M.gguf")
        if (destFile.exists() && destFile.length() > 1_000_000_000L) {
            Toast.makeText(this, "Gemma model already downloaded", Toast.LENGTH_SHORT).show()
            refreshAutoDetect()
            return
        }
        
        setDownloadUiActive(true)
        tvProgressLabel.text = "Downloading Gemma (1.5GB)... Please keep app open."
        progressBar.progress = 0
        
        lifecycleScope.launch {
            val result = ModelDownloader.downloadFile(gemmaDownloadUrl, destFile) { progress ->
                progressBar.progress = progress
            }
            
            setDownloadUiActive(false)
            result.onSuccess {
                Toast.makeText(this@ModelPickerActivity, "Gemma downloaded successfully", Toast.LENGTH_SHORT).show()
                refreshAutoDetect()
            }.onFailure { err ->
                Toast.makeText(this@ModelPickerActivity, "Download failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun downloadWhisper() {
        val whisperDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "whisper")
        whisperDir.mkdirs()
        
        setDownloadUiActive(true)
        progressBar.progress = 0
        
        lifecycleScope.launch {
            var allSuccess = true
            val totalFiles = whisperUrls.size
            var currentFileIndex = 0
            
            for ((filename, url) in whisperUrls) {
                currentFileIndex++
                tvProgressLabel.text = "Downloading Whisper ($currentFileIndex/$totalFiles): $filename"
                progressBar.progress = 0
                
                val destFile = File(whisperDir, filename)
                val result = ModelDownloader.downloadFile(url, destFile) { progress ->
                    progressBar.progress = progress
                }
                
                if (result.isFailure) {
                    allSuccess = false
                    Toast.makeText(this@ModelPickerActivity, "Failed to download $filename", Toast.LENGTH_LONG).show()
                    break
                }
            }
            
            setDownloadUiActive(false)
            if (allSuccess) {
                Toast.makeText(this@ModelPickerActivity, "Whisper models downloaded", Toast.LENGTH_SHORT).show()
                refreshAutoDetect()
            }
        }
    }

    private fun downloadVosk() {
        val zipFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "vosk-model.zip")
        val extractDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        
        setDownloadUiActive(true)
        tvProgressLabel.text = "Downloading Vosk model (40MB)..."
        progressBar.progress = 0
        
        lifecycleScope.launch {
            val result = ModelDownloader.downloadFile(voskDownloadUrl, zipFile) { progress ->
                progressBar.progress = progress
            }
            
            if (result.isSuccess) {
                tvProgressLabel.text = "Extracting Vosk..."
                val unzipSuccess = withContext(Dispatchers.IO) {
                    UnzipUtils.unzip(zipFile, extractDir!!)
                }
                zipFile.delete() // Cleanup
                
                if (unzipSuccess) {
                    Toast.makeText(this@ModelPickerActivity, "Vosk model downloaded", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ModelPickerActivity, "Failed to extract Vosk", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this@ModelPickerActivity, "Failed to download Vosk", Toast.LENGTH_LONG).show()
            }
            
            setDownloadUiActive(false)
            refreshAutoDetect()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return File(uri.path!!).name
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }

    private fun resolveSize(uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path!!).length()
        return contentResolver.query(uri, arrayOf(OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) cursor.getLong(idx) else -1L
            } else -1L
        } ?: -1L
    }

    private fun returnPath(path: String) {
        val intent = Intent().putExtra(EXTRA_MODEL_PATH, path)
        if (detectedWhisperPath != null) {
            intent.putExtra(EXTRA_WHISPER_PATH, detectedWhisperPath)
        }
        if (detectedVoskPath != null) {
            intent.putExtra("vosk_path", detectedVoskPath)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}
