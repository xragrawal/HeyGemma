package com.example.gemmaapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
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

    init {
        TodoRepository.init(app)
        viewModelScope.launch { TelegramRepository.init(app) }
    }

    private val _messages    = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    val todos: Flow<List<TodoEntity>> get() = TodoRepository.todos

    private val _activeAgent = MutableStateFlow<AgentType?>(null)
    val activeAgent: StateFlow<AgentType?> = _activeAgent.asStateFlow()

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

            val result = AgentOrchestrator.process(
                userMessage  = userText,
                chatHistory  = history,
                onClassified = { classified, agentType ->
                    _activeAgent.value = agentType
                    val noteText = buildRoutingNote(classified, agentType)
                    // Insert routing note; remove the blank streaming placeholder first, re-add after
                    _messages.update { msgs ->
                        val placeholder = msgs.last()
                        msgs.dropLast(1) +
                            ChatMessage(noteText, isUser = false, isAgentNote = true) +
                            placeholder
                    }
                },
                onToken = { piece ->
                    _messages.update { msgs ->
                        val current = (msgs.lastOrNull()?.text ?: "") + piece
                        msgs.dropLast(1) + ChatMessage(current, isUser = false, isStreaming = true)
                    }
                }
            )

            _messages.update { msgs ->
                val finalText = if (result.error != null) "Error: ${result.error}" else result.displayText
                msgs.dropLast(1) + ChatMessage(finalText, isUser = false, isStreaming = false, agentType = result.agentType)
            }
            _activeAgent.value = null
            _isGenerating.value = false

            startWakeWordListening()
        }
    }

    private fun buildRoutingNote(
        classified: AgentOrchestrator.ClassifierResult,
        agentType: AgentType
    ): String = buildString {
        append("Classifier → ${agentType.label} Agent")
        if (classified.task.isNotBlank() && classified.task != "ANSWER") {
            append(" · ${classified.task}")
            if (classified.recipient.isNotBlank()) append(" → ${classified.recipient}")
            if (classified.content.isNotBlank()) append(": \"${classified.content}\"")
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
