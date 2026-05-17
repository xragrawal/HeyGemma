package com.example.gemmaapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
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

    // Serialises classification so concurrent Gemma calls never happen
    private val classifyQueue = Channel<TelegramMessage>(Channel.UNLIMITED)

    private val adapter = TelegramChatsAdapter { lastMessage ->
        startActivity(
            Intent(this, TelegramChatActivity::class.java).apply {
                putExtra(TelegramChatActivity.EXTRA_CHAT_ID,   lastMessage.chatId)
                putExtra(TelegramChatActivity.EXTRA_CHAT_NAME, lastMessage.chatName)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch { TelegramRepository.init(this@TelegramChatsActivity) }
        TtsManager.init(this)   // safe to call multiple times — no-ops if already ready

        setupList()
        setupFilterButtons()
        observeInbox()
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
        binding.rvChats.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.rvChats.adapter = adapter
    }

    private fun setupFilterButtons() {
        // Default selection
        binding.toggleFilter.check(R.id.btnFilterEmergency)

        binding.toggleFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentFilter = when (checkedId) {
                R.id.btnFilterEmergency -> InboxFilter.EMERGENCY
                R.id.btnFilterImportant -> InboxFilter.IMPORTANT
                else                    -> InboxFilter.ALL
            }
        }
    }

    private fun observeInbox() {
        lifecycleScope.launch {
            TelegramRepository.observeInbox().collectLatest { chats ->
                adapter.submitList(chats)
                binding.tvEmpty.visibility  = if (chats.isEmpty()) View.VISIBLE else View.GONE
                binding.rvChats.visibility  = if (chats.isEmpty()) View.GONE   else View.VISIBLE
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

    // Single coroutine processes messages one-at-a-time — no concurrent Gemma calls
    private fun startClassifier() {
        lifecycleScope.launch {
            for (msg in classifyQueue) {
                val shouldRead = when (currentFilter) {
                    InboxFilter.ALL       -> true
                    InboxFilter.IMPORTANT -> TelegramMessageClassifier.isImportant(msg.text)
                    InboxFilter.EMERGENCY -> TelegramMessageClassifier.isEmergency(msg.text)
                }
                if (shouldRead) {
                    TtsManager.speak("Message from ${msg.senderName}: ${msg.text}")
                }
            }
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
