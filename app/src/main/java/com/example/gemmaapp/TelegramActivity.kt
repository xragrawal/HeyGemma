package com.example.gemmaapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gemmaapp.databinding.ActivityTelegramBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TelegramActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTelegramBinding
    private val adapter = TelegramContactsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch { TelegramRepository.init(this@TelegramActivity) }

        setupContacts()
        loadSavedToken()
        observeContacts()
        setupButtons()
    }

    private fun setupContacts() {
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.rvContacts.adapter = adapter
    }

    private fun loadSavedToken() {
        val token = TelegramRepository.getToken()
        if (!token.isNullOrBlank()) {
            binding.etBotToken.setText(token)
            showTokenStatus("Token saved", success = true)
        }
    }

    private fun observeContacts() {
        lifecycleScope.launch {
            TelegramRepository.observeContacts().collectLatest { entities ->
                val contacts = entities.map { it.toContact() }
                adapter.submitList(contacts)
                binding.rvContacts.visibility      = if (contacts.isEmpty()) View.GONE else View.VISIBLE
                binding.tvEmptyContacts.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupButtons() {
        binding.btnViewChats.setOnClickListener {
            startActivity(Intent(this, TelegramChatsActivity::class.java))
        }

        binding.btnSaveToken.setOnClickListener {
            val token = binding.etBotToken.text?.toString()?.trim() ?: ""
            if (token.isBlank()) {
                showTokenStatus("Token cannot be empty", success = false)
                return@setOnClickListener
            }
            TelegramRepository.saveToken(token)
            showTokenStatus("Token saved", success = true)
            Toast.makeText(this, "Bot token saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnRefresh.setOnClickListener {
            val token = TelegramRepository.getToken()
            if (token.isNullOrBlank()) {
                showRefreshStatus("Save a bot token first.", success = false)
                return@setOnClickListener
            }

            binding.btnRefresh.isEnabled = false
            lifecycleScope.launch {
                val msg = TelegramRepository.refreshContacts()
                showRefreshStatus(msg, success = !msg.startsWith("Error"))
                binding.btnRefresh.isEnabled = true
            }
        }
    }

    private fun showTokenStatus(msg: String, success: Boolean) {
        binding.tvTokenStatus.text = msg
        binding.tvTokenStatus.setTextColor(
            getColor(if (success) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        binding.tvTokenStatus.visibility = View.VISIBLE
    }

    private fun showRefreshStatus(msg: String, success: Boolean) {
        binding.tvRefreshStatus.text = msg
        binding.tvRefreshStatus.setTextColor(
            getColor(if (success) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        binding.tvRefreshStatus.visibility = View.VISIBLE
    }
}
