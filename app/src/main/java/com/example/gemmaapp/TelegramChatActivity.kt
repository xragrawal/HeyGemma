package com.example.gemmaapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gemmaapp.databinding.ActivityTelegramChatBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TelegramChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID   = "chat_id"
        const val EXTRA_CHAT_NAME = "chat_name"
    }

    private lateinit var binding: ActivityTelegramChatBinding
    private val adapter = TelegramMessagesAdapter()

    private var chatId:   Long   = 0L
    private var chatName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId   = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        chatName = intent.getStringExtra(EXTRA_CHAT_NAME) ?: ""

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = chatName
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch { TelegramRepository.init(this@TelegramChatActivity) }

        val llm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.layoutManager = llm
        binding.rvMessages.adapter = adapter

        lifecycleScope.launch {
            TelegramRepository.observeThread(chatId).collectLatest { messages ->
                adapter.submitList(messages) {
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isBlank()) return@setOnClickListener
            binding.etMessage.setText("")
            binding.btnSend.isEnabled = false

            lifecycleScope.launch {
                val error = TelegramRepository.sendMessageToChat(chatId, chatName, text)
                binding.btnSend.isEnabled = true
                if (error.isNotBlank()) {
                    Toast.makeText(this@TelegramChatActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
