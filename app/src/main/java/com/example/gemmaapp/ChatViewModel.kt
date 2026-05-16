package com.example.gemmaapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class LoadState {
    data object Idle       : LoadState()
    data object Loading    : LoadState()
    data object Ready      : LoadState()
    data class  Error(val msg: String) : LoadState()
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _messages    = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _loadState   = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _whisperState = MutableStateFlow<LoadState>(LoadState.Idle)
    val whisperState: StateFlow<LoadState> = _whisperState.asStateFlow()

    private val _voskState = MutableStateFlow<LoadState>(LoadState.Idle)
    val voskState: StateFlow<LoadState> = _voskState.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isListeningForWakeWord = MutableStateFlow(false)
    val isListeningForWakeWord: StateFlow<Boolean> = _isListeningForWakeWord.asStateFlow()

    private val audioRecorder = AudioRecorder()

    // ── Model management ──────────────────────────────────────────────────────

    fun loadModel(modelPath: String) {
        viewModelScope.launch {
            _loadState.value = LoadState.Loading
            val err = LlamaEngine.load(modelPath)
            _loadState.value = if (err == null) LoadState.Ready else LoadState.Error(err)
        }
    }

    fun loadWhisper(encoderPath: String, decoderPath: String, vocabPath: String) {
        viewModelScope.launch {
            _whisperState.value = LoadState.Loading
            val err = WhisperEngine.load(encoderPath, decoderPath, vocabPath)
            _whisperState.value = if (err == null) LoadState.Ready else LoadState.Error(err)
        }
    }

    fun loadVosk(modelPath: String) {
        viewModelScope.launch {
            _voskState.value = LoadState.Loading
            val err = WakeWordEngine.load(modelPath)
            if (err == null) {
                // Set Ready FIRST so startWakeWordListening() passes its guard check
                _voskState.value = LoadState.Ready
                startWakeWordListening()
            } else {
                _voskState.value = LoadState.Error(err)
            }
        }
    }

    // ── Wake Word ─────────────────────────────────────────────────────────────

    fun startWakeWordListening() {
        if (_voskState.value !is LoadState.Ready) return
        if (_isRecording.value) return // Don't listen if currently recording
        
        val success = WakeWordEngine.startListening { wakeWord ->
            _isListeningForWakeWord.value = false
            // Wake word detected, trigger recording
            startRecording()
        }
        
        _isListeningForWakeWord.value = success
    }

    fun stopWakeWordListening() {
        WakeWordEngine.stopListening()
        _isListeningForWakeWord.value = false
    }

    // ── Audio Recording ───────────────────────────────────────────────────────

    fun toggleRecording() {
        if (_isRecording.value) {
            audioRecorder.stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (_whisperState.value !is LoadState.Ready) return
        
        // Ensure Vosk releases the mic
        stopWakeWordListening()

        viewModelScope.launch {
            val pcmData = audioRecorder.recordWithVad { recording ->
                _isRecording.value = recording
            }

            if (pcmData.isNotEmpty()) {
                val transcript = WhisperEngine.transcribe(pcmData)
                if (!transcript.isNullOrBlank()) {
                    // Auto-send to Gemma
                    sendMessage(transcript)
                } else {
                    // Start listening again if transcription failed/empty
                    startWakeWordListening()
                }
            } else {
                // Restart listening if no audio was captured
                startWakeWordListening()
            }
        }
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    fun sendMessage(userText: String) {
        if (_isGenerating.value || _loadState.value !is LoadState.Ready) return

        val userMsg = ChatMessage(userText, isUser = true)
        val streamingPlaceholder = ChatMessage("", isUser = false, isStreaming = true)

        _messages.update { it + userMsg + streamingPlaceholder }
        _isGenerating.value = true

        viewModelScope.launch {
            val history = _messages.value.filter { !it.isStreaming }
            val prompt  = LlamaEngine.buildGemmaPrompt(history)

            var accumulated = ""

            val err = LlamaEngine.generate(
                prompt = prompt,
                maxTokens = 1024,
                temperature = 0.7f,
                topP = 0.9f
            ) { piece ->
                accumulated += piece
                val updated = accumulated
                // Replace the streaming placeholder in-place
                _messages.update { msgs ->
                    msgs.dropLast(1) + ChatMessage(updated, isUser = false, isStreaming = true)
                }
            }

            // Finalise: mark no longer streaming
            _messages.update { msgs ->
                val finalText = if (err != null) "Error: $err" else accumulated
                msgs.dropLast(1) + ChatMessage(finalText, isUser = false, isStreaming = false)
            }
            _isGenerating.value = false
            
            // Resume listening for the next wake word
            startWakeWordListening()
        }
    }

    fun stopGeneration() = LlamaEngine.stop()

    fun clearChat() {
        _messages.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        stopWakeWordListening()
        viewModelScope.launch {
            LlamaEngine.release()
            WhisperEngine.release()
            WakeWordEngine.release()
        }
    }
}
