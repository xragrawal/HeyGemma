package com.example.gemmaapp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gemmaapp.databinding.ActivityTelegramChatsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TelegramChatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTelegramChatsBinding

    private var currentFilter = InboxFilter.EMERGENCY
    private var pollingJob: Job? = null

    private val classifyQueue = Channel<TelegramMessage>(Channel.UNLIMITED)

    private val adapter = TelegramChatsAdapter { lastMessage ->
        startActivity(
            Intent(this, TelegramChatActivity::class.java).apply {
                putExtra(TelegramChatActivity.EXTRA_CHAT_ID, lastMessage.chatId)
                putExtra(TelegramChatActivity.EXTRA_CHAT_NAME, lastMessage.chatName)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch { TelegramRepository.init(this@TelegramChatsActivity) }
        TtsManager.init(this)

        setupList()
        setupFilterButtons()
        observeInbox()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, TelegramSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        TelegramRepository.resetSessionOffset()
        startPolling()
        startClassifier()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    private fun setupList() {
        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter
    }

    private fun setupFilterButtons() {
        // Default selection: EMERGENCY
        updateFilterSelection(InboxFilter.EMERGENCY)

        binding.btnFilterEmergency.setOnClickListener {
            currentFilter = InboxFilter.EMERGENCY
            adapter.currentFilter = currentFilter
            adapter.notifyDataSetChanged()
            updateFilterSelection(InboxFilter.EMERGENCY)
        }

        binding.btnFilterImportant.setOnClickListener {
            currentFilter = InboxFilter.IMPORTANT
            adapter.currentFilter = currentFilter
            adapter.notifyDataSetChanged()
            updateFilterSelection(InboxFilter.IMPORTANT)
        }

        binding.btnFilterAll.setOnClickListener {
            currentFilter = InboxFilter.ALL
            adapter.currentFilter = currentFilter
            adapter.notifyDataSetChanged()
            updateFilterSelection(InboxFilter.ALL)
        }
    }

    private fun updateFilterSelection(filter: InboxFilter) {
        // Reset all to inactive
        binding.btnFilterEmergency.background = ContextCompat.getDrawable(this, R.drawable.bg_filter_btn_inactive)
        binding.tvFilterEmergencyLabel.setTextColor(getColor(R.color.gemma_ink_soft))
        binding.btnFilterImportant.background = ContextCompat.getDrawable(this, R.drawable.bg_filter_btn_inactive)
        binding.tvFilterImportantLabel.setTextColor(getColor(R.color.gemma_ink_soft))
        binding.btnFilterAll.background = ContextCompat.getDrawable(this, R.drawable.bg_filter_btn_inactive)
        binding.tvFilterAllLabel.setTextColor(getColor(R.color.gemma_ink_soft))

        // Activate selected
        when (filter) {
            InboxFilter.EMERGENCY -> {
                binding.btnFilterEmergency.background = ContextCompat.getDrawable(this, R.drawable.bg_filter_btn_active_emergency)
                binding.tvFilterEmergencyLabel.setTextColor(getColor(R.color.gemma_rose))
                binding.tvFilterDesc.text = "Only urgent contacts & alerts"
            }
            InboxFilter.IMPORTANT -> {
                binding.btnFilterImportant.background = ContextCompat.getDrawable(this, R.drawable.bg_filter_btn_active_important)
                binding.tvFilterImportantLabel.setTextColor(getColor(R.color.gemma_amber))
                binding.tvFilterDesc.text = "Family, work, bots you trust"
            }
            InboxFilter.ALL -> {
                binding.btnFilterAll.background = ContextCompat.getDrawable(this, R.drawable.bg_filter_btn_active_all)
                binding.tvFilterAllLabel.setTextColor(getColor(R.color.gemma_lime))
                binding.tvFilterDesc.text = "Read every message aloud"
            }
        }
    }

    private fun observeInbox() {
        lifecycleScope.launch {
            TelegramRepository.observeInbox().collectLatest { chats ->
                adapter.currentFilter = currentFilter
                adapter.submitList(chats)
                binding.tvEmpty.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
                binding.rvChats.visibility = if (chats.isEmpty()) View.GONE else View.VISIBLE

                val total = chats.size
                binding.tvChatsEditorial.text = "${total} chats"
            }
        }
    }

    private fun startPolling() {
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                val newMessages = TelegramRepository.pollNewMessages()
                newMessages.forEach { classifyQueue.send(it) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun startClassifier() {
        lifecycleScope.launch {
            for (msg in classifyQueue) {
                val shouldRead = when (currentFilter) {
                    InboxFilter.ALL -> true
                    InboxFilter.IMPORTANT -> TelegramMessageClassifier.isImportant(msg.text)
                    InboxFilter.EMERGENCY -> TelegramMessageClassifier.isEmergency(msg.text)
                }
                if (shouldRead) {
                    vibrateNotification()
                    TtsManager.speak("Message from ${msg.senderName}: ${msg.text}")
                }
            }
        }
    }

    private fun vibrateNotification() {
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(400)
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 6_000L
    }
}
