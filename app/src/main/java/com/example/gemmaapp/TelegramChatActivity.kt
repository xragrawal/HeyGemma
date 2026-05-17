package com.example.gemmaapp

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gemmaapp.databinding.ActivityTelegramChatBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class TelegramChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_NAME = "chat_name"

        private val AVATAR_COLORS = listOf(
            "#B8FF5E", "#F5B450", "#FF7A6B", "#7DD3FC", "#B794F4",
            "#9BE84A", "#F97316", "#22D3EE"
        )

        fun avatarColorForName(name: String): Int {
            val idx = abs(name.hashCode()) % AVATAR_COLORS.size
            return Color.parseColor(AVATAR_COLORS[idx])
        }

        fun initialsForName(name: String): String {
            return name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
        }
    }

    private lateinit var binding: ActivityTelegramChatBinding
    private val adapter = TelegramMessagesAdapter()

    private var chatId: Long = 0L
    private var chatName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        chatName = intent.getStringExtra(EXTRA_CHAT_NAME) ?: ""

        // Header setup
        binding.tvChatName.text = chatName
        binding.tvChatInitials.text = initialsForName(chatName)
        val avatarColor = avatarColorForName(chatName)
        binding.ivChatAvatar.setBackgroundColor(avatarColor)

        // Set text color based on luminance
        val lum = (0.299 * Color.red(avatarColor) + 0.587 * Color.green(avatarColor) + 0.114 * Color.blue(avatarColor)) / 255.0
        binding.tvChatInitials.setTextColor(if (lum > 0.5) Color.parseColor("#0A0A0A") else Color.WHITE)

        binding.tvChatSubtitle.text = "IMPORTANT · last seen recently"
        binding.tvGemmaStrip.text = "Gemma is reading this thread aloud at IMPORTANT priority."

        binding.btnBack.setOnClickListener { finish() }

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
