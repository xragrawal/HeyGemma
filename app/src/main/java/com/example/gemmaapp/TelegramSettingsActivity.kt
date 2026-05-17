package com.example.gemmaapp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gemmaapp.databinding.ActivityTelegramSettingsBinding
import kotlinx.coroutines.launch

class TelegramSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTelegramSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch { TelegramRepository.init(this@TelegramSettingsActivity) }

        loadSavedToken()
        setupButtons()
    }

    private fun loadSavedToken() {
        val token = TelegramRepository.getToken()
        if (!token.isNullOrBlank()) {
            binding.etBotToken.setText(token)
            showStatus("Token saved", success = true)
        }
    }

    private fun setupButtons() {
        binding.btnSaveToken.setOnClickListener {
            val token = binding.etBotToken.text?.toString()?.trim() ?: ""
            if (token.isBlank()) {
                showStatus("Token cannot be empty", success = false)
                return@setOnClickListener
            }
            TelegramRepository.saveToken(token)
            showStatus("Token saved", success = true)
        }

        binding.btnPing.setOnClickListener {
            val token = binding.etBotToken.text?.toString()?.trim()
                ?: TelegramRepository.getToken()

            if (token.isNullOrBlank()) {
                showStatus("Enter a token first", success = false)
                return@setOnClickListener
            }

            binding.btnPing.isEnabled = false
            showStatus("Pinging...", success = true)

            lifecycleScope.launch {
                TelegramRepository.pingBot(token).fold(
                    onSuccess = { botName ->
                        showStatus("Connected: $botName", success = true)
                    },
                    onFailure = { err ->
                        showStatus("Failed: ${err.message ?: "Unknown error"}", success = false)
                    }
                )
                binding.btnPing.isEnabled = true
            }
        }
    }

    private fun showStatus(msg: String, success: Boolean) {
        binding.tvTokenStatus.text = msg
        binding.tvTokenStatus.setTextColor(
            getColor(if (success) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        binding.tvTokenStatus.visibility = View.VISIBLE
    }
}
