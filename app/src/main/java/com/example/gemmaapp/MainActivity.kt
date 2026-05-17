package com.example.gemmaapp

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gemmaapp.databinding.ActivityMainBinding
import com.example.gemmaapp.databinding.ScreenHomeBinding
import com.example.gemmaapp.databinding.ScreenListeningBinding
import com.example.gemmaapp.databinding.ScreenProcessingBinding
import com.example.gemmaapp.databinding.ScreenResultBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppScreen { HOME, LISTENING, PROCESSING, RESULT }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeBinding: ScreenHomeBinding
    private lateinit var listeningBinding: ScreenListeningBinding
    private lateinit var processingBinding: ScreenProcessingBinding
    private lateinit var resultBinding: ScreenResultBinding

    private val vm: ChatViewModel by viewModels()
    private val todosAdapter = TodosAdapter(
        onToggle = { entity, checked ->
            lifecycleScope.launch {
                if (checked) TodoRepository.markDoneById(entity.id)
                else TodoRepository.markUndoneById(entity.id)
            }
        },
        onDelete = { /* not shown on home screen */ }
    )

    private var currentScreen = AppScreen.HOME
    private val resultHandler = Handler(Looper.getMainLooper())
    private var resultDismissRunnable: Runnable? = null

    private var waveAnimators = listOf<ObjectAnimator>()
    private var elapsedSeconds = 0
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val gemmaPath = data.getStringExtra(ModelPickerActivity.EXTRA_MODEL_PATH)
            val whisperPath = data.getStringExtra(ModelPickerActivity.EXTRA_WHISPER_PATH)
            val voskPath = data.getStringExtra("vosk_path")
            if (gemmaPath != null) vm.loadModel(gemmaPath)
            if (whisperPath != null) vm.loadWhisper(
                "$whisperPath/encoder_model_quantized.onnx",
                "$whisperPath/decoder_model_quantized.onnx",
                "$whisperPath/tokenizer.json"
            )
            if (voskPath != null) vm.loadVosk(voskPath)
            checkPermissions()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) vm.startWakeWordListening()
        else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        homeBinding = ScreenHomeBinding.bind(binding.home.root)
        listeningBinding = ScreenListeningBinding.bind(binding.listening.root)
        processingBinding = ScreenProcessingBinding.bind(binding.processing.root)
        resultBinding = ScreenResultBinding.bind(binding.result.root)

        setupHomeScreen()
        setupListeningScreen()
        setupResultScreen()
        observeViewModel()

        // Initial screen state
        showScreen(AppScreen.HOME)

        updateDateGreeting()

        if (vm.loadState.value == LoadState.Idle) {
            pickModel.launch(Intent(this, ModelPickerActivity::class.java))
        } else {
            checkPermissions()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupHomeScreen() {
        // Todos RecyclerView
        homeBinding.rvTodos.layoutManager = LinearLayoutManager(this)
        homeBinding.rvTodos.adapter = todosAdapter
        homeBinding.rvTodos.isNestedScrollingEnabled = false

        // Record FAB
        homeBinding.btnRecord.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                vm.toggleRecording()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // Telegram tile
        homeBinding.btnTelegram.setOnClickListener {
            startActivity(Intent(this, TelegramChatsActivity::class.java))
        }
    }

    private fun setupListeningScreen() {
        listeningBinding.btnListenClose.setOnClickListener {
            vm.toggleRecording() // stop recording
        }
        listeningBinding.btnListenSend.setOnClickListener {
            vm.toggleRecording() // stop and send
        }
    }

    private fun setupResultScreen() {
        resultBinding.btnGotIt.setOnClickListener { dismissResult() }
        resultBinding.btnResultClose.setOnClickListener { dismissResult() }
    }

    private fun dismissResult() {
        resultDismissRunnable?.let { resultHandler.removeCallbacks(it) }
        showScreen(AppScreen.HOME)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            vm.isRecording.collectLatest { recording ->
                if (recording && currentScreen == AppScreen.HOME) {
                    showScreen(AppScreen.LISTENING)
                    startTimer()
                    startWaveAnimation()
                } else if (!recording && currentScreen == AppScreen.LISTENING) {
                    stopTimer()
                    stopWaveAnimation()
                    // transition to PROCESSING when generation starts
                }
            }
        }

        lifecycleScope.launch {
            vm.isGenerating.collectLatest { generating ->
                if (generating && currentScreen != AppScreen.PROCESSING) {
                    showScreen(AppScreen.PROCESSING)
                } else if (!generating && currentScreen == AppScreen.PROCESSING) {
                    // Show result screen with last message
                    val lastMsg = vm.messages.value.lastOrNull { !it.isUser && !it.isStreaming }
                    val lastUserMsg = vm.messages.value.lastOrNull { it.isUser }
                    val summary = lastMsg?.text?.take(80) ?: "Done."
                    val transcript = lastUserMsg?.text ?: ""
                    resultBinding.tvResultSummary.text = summary
                    resultBinding.tvResultTranscript.text = "\"$transcript\""
                    showScreen(AppScreen.RESULT)
                    // Auto-dismiss after 5 seconds
                    resultDismissRunnable = Runnable { dismissResult() }
                    resultHandler.postDelayed(resultDismissRunnable!!, 5000L)
                }
            }
        }

        lifecycleScope.launch {
            vm.todos.collectLatest { todos ->
                todosAdapter.submitList(todos.sortedWith(compareBy({ it.isDone }, { it.createdAt })))
                val open = todos.count { !it.isDone }
                homeBinding.tvStats.text = "You have $open things on your plate."
                homeBinding.tvTodoCount.text = "$open / ${todos.size}"
            }
        }

        lifecycleScope.launch {
            TelegramRepository.observeInbox().collectLatest { chats ->
                // TelegramMessage doesn't expose unread count; just show total
                val msgCount = chats.size
                if (msgCount > 0) {
                    homeBinding.tvTelegramSub.text = "$msgCount chats · routed via Gemma"
                    homeBinding.tvTelegramUnread.text = msgCount.toString()
                    homeBinding.tvTelegramUnread.visibility = View.VISIBLE
                } else {
                    homeBinding.tvTelegramSub.text = "All caught up"
                    homeBinding.tvTelegramUnread.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            vm.loadState.collectLatest { state ->
                if (state is LoadState.Error) {
                    Toast.makeText(this@MainActivity, state.msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showScreen(screen: AppScreen) {
        currentScreen = screen
        binding.home.root.visibility = if (screen == AppScreen.HOME) View.VISIBLE else View.GONE
        binding.listening.root.visibility = if (screen == AppScreen.LISTENING) View.VISIBLE else View.GONE
        binding.processing.root.visibility = if (screen == AppScreen.PROCESSING) View.VISIBLE else View.GONE
        binding.result.root.visibility = if (screen == AppScreen.RESULT) View.VISIBLE else View.GONE
    }

    private fun updateDateGreeting() {
        val dateFmt = SimpleDateFormat("EEEE · MMM d", Locale.getDefault())
        homeBinding.tvDate.text = dateFmt.format(Date()).uppercase()

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when {
            hour < 12 -> "morning"
            hour < 17 -> "afternoon"
            else -> "evening"
        }
        homeBinding.tvGreeting.text = "Good $timeOfDay,\nJayesh."
        // Make "Jayesh." italic via SpannableString
        val greetText = homeBinding.tvGreeting.text.toString()
        val spannable = android.text.SpannableString(greetText)
        val nameStart = greetText.lastIndexOf('\n') + 1
        spannable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
            nameStart, greetText.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(getColor(R.color.gemma_lime)),
            nameStart, greetText.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        homeBinding.tvGreeting.text = spannable
    }

    private fun startTimer() {
        elapsedSeconds = 0
        timerRunnable = object : Runnable {
            override fun run() {
                elapsedSeconds++
                val mm = String.format("%02d", elapsedSeconds / 60)
                val ss = String.format("%02d", elapsedSeconds % 60)
                listeningBinding.tvRecTimer.text = "RECORDING · $mm:$ss"
                timerHandler.postDelayed(this, 1000L)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun startWaveAnimation() {
        val bars = listOf(
            listeningBinding.wbar1, listeningBinding.wbar2, listeningBinding.wbar3,
            listeningBinding.wbar4, listeningBinding.wbar5, listeningBinding.wbar6,
            listeningBinding.wbar7, listeningBinding.wbar8
        )
        val baseHeights = listOf(12, 24, 36, 48, 40, 28, 18, 10)
        waveAnimators = bars.mapIndexed { i, bar ->
            val base = baseHeights[i].toFloat()
            val target = (base * 1.6f).coerceAtMost(64f)
            ObjectAnimator.ofFloat(bar, "scaleY", 0.4f, 1.0f, 0.4f).apply {
                duration = 600L + (i % 5) * 120L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                startDelay = (i % 7) * 70L
                start()
            }
        }
        // Also pulse the outer rings
        val outerRing = listeningBinding.ringOuter
        val middleRing = listeningBinding.ringMiddle
        ObjectAnimator.ofFloat(outerRing, "alpha", 0.1f, 0.5f, 0.1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        ObjectAnimator.ofFloat(middleRing, "alpha", 0.3f, 0.7f, 0.3f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            startDelay = 300L
            start()
        }
    }

    private fun stopWaveAnimation() {
        waveAnimators.forEach { it.cancel() }
        waveAnimators = emptyList()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        stopWaveAnimation()
        resultDismissRunnable?.let { resultHandler.removeCallbacks(it) }
    }
}
