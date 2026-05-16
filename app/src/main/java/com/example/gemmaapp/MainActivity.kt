package com.example.gemmaapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gemmaapp.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: ChatViewModel by viewModels()
    private val adapter = ChatAdapter()

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val gemmaPath = data.getStringExtra(ModelPickerActivity.EXTRA_MODEL_PATH)
            val whisperPath = data.getStringExtra(ModelPickerActivity.EXTRA_WHISPER_PATH)
            val voskPath = data.getStringExtra("vosk_path")
            
            if (gemmaPath != null) {
                vm.loadModel(gemmaPath)
            }
            if (whisperPath != null) {
                vm.loadWhisper(
                    "$whisperPath/encoder_model_quantized.onnx",
                    "$whisperPath/decoder_model_quantized.onnx",
                    "$whisperPath/tokenizer.json"
                )
            }
            if (voskPath != null) {
                vm.loadVosk(voskPath)
            }
            
            // Check permissions so Vosk can start listening
            checkPermissions()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            vm.startWakeWordListening() // Try to start wake word again
        } else {
            Toast.makeText(this, "Microphone permission required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        observeViewModel()
        setupInputBar()

        // Open model picker on first launch
        if (vm.loadState.value == LoadState.Idle) {
            launchModelPicker()
        } else {
            checkPermissions()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupRecyclerView() {
        val llm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvChat.layoutManager = llm
        binding.rvChat.adapter = adapter
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            vm.messages.collectLatest { msgs ->
                adapter.submitList(msgs) {
                    binding.rvChat.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }

        lifecycleScope.launch {
            vm.loadState.collectLatest { state ->
                when (state) {
                    LoadState.Idle    -> setStatus("No model loaded")
                    LoadState.Loading -> setStatus("Loading model…")
                    LoadState.Ready   -> setStatus("Ready")
                    is LoadState.Error -> {
                        setStatus("Error: ${state.msg}")
                        Toast.makeText(this@MainActivity, state.msg, Toast.LENGTH_LONG).show()
                    }
                }
                binding.btnSend.isEnabled = state == LoadState.Ready
                binding.etInput.isEnabled = state == LoadState.Ready
            }
        }

        lifecycleScope.launch {
            vm.isGenerating.collectLatest { generating ->
                binding.btnSend.text = if (generating) "Stop" else "Send"
                binding.progressBar.visibility = if (generating) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            vm.whisperState.collectLatest { state ->
                binding.btnMic.isEnabled = state == LoadState.Ready
                if (state is LoadState.Error) {
                    Toast.makeText(this@MainActivity, "Whisper: ${state.msg}", Toast.LENGTH_LONG).show()
                }
            }
        }

        lifecycleScope.launch {
            vm.voskState.collectLatest { state ->
                if (state is LoadState.Error) {
                    Toast.makeText(this@MainActivity, "Vosk: ${state.msg}", Toast.LENGTH_LONG).show()
                }
            }
        }

        lifecycleScope.launch {
            vm.isListeningForWakeWord.collectLatest { listening ->
                if (listening && !vm.isRecording.value) {
                    binding.etInput.hint = "Listening for 'Hey Gemma'..."
                    binding.btnMic.setIconTintResource(android.R.color.holo_blue_dark)
                } else if (!vm.isRecording.value) {
                    binding.etInput.hint = "Type a message..."
                    binding.btnMic.setIconTintResource(android.R.color.black)
                }
            }
        }

        lifecycleScope.launch {
            vm.isRecording.collectLatest { recording ->
                if (recording) {
                    binding.btnMic.setIconTintResource(android.R.color.holo_red_dark)
                    binding.etInput.hint = "Recording... (speak now)"
                } else if (vm.isListeningForWakeWord.value) {
                    binding.btnMic.setIconTintResource(android.R.color.holo_blue_dark)
                    binding.etInput.hint = "Listening for 'Hey Gemma'..."
                } else {
                    binding.btnMic.setIconTintResource(android.R.color.black)
                    binding.etInput.hint = "Type a message..."
                }
            }
        }
    }

    private fun setupInputBar() {
        binding.btnSend.setOnClickListener {
            if (vm.isGenerating.value) {
                vm.stopGeneration()
            } else {
                val text = binding.etInput.text.toString().trim()
                if (text.isEmpty()) return@setOnClickListener
                binding.etInput.setText("")
                vm.sendMessage(text)
            }
        }

        binding.btnMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                vm.toggleRecording()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun setStatus(msg: String) {
        supportActionBar?.subtitle = msg
    }

    private fun launchModelPicker() {
        pickModel.launch(Intent(this, ModelPickerActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_load_model -> { launchModelPicker(); true }
        R.id.action_clear_chat -> { vm.clearChat(); true }
        else -> super.onOptionsItemSelected(item)
    }
}
