package com.example.gemmaapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gemmaapp.databinding.ActivityTelegramChatsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TelegramChatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTelegramChatsBinding

    private val adapter = TelegramChatsAdapter { lastMessage ->
        val intent = Intent(this, TelegramChatActivity::class.java).apply {
            putExtra(TelegramChatActivity.EXTRA_CHAT_ID,   lastMessage.chatId)
            putExtra(TelegramChatActivity.EXTRA_CHAT_NAME, lastMessage.chatName)
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch { TelegramRepository.init(this@TelegramChatsActivity) }

        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.rvChats.adapter = adapter

        lifecycleScope.launch {
            TelegramRepository.observeInbox().collectLatest { chats ->
                adapter.submitList(chats)
                binding.tvEmpty.visibility  = if (chats.isEmpty()) View.VISIBLE else View.GONE
                binding.rvChats.visibility  = if (chats.isEmpty()) View.GONE  else View.VISIBLE
            }
        }
    }
}
