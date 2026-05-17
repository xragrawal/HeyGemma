package com.example.gemmaapp

import android.content.Intent
import android.os.Bundle
import android.view.View
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
        binding.toolbar.inflateMenu(R.menu.telegram_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_telegram_settings) {
                startActivity(Intent(this, TelegramSettingsActivity::class.java))
                true
            } else false
        }

        lifecycleScope.launch { TelegramRepository.init(this@TelegramActivity) }

        setupContacts()
        observeContacts()
        setupButtons()
    }

    private fun setupContacts() {
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.rvContacts.adapter = adapter
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

        binding.btnRefresh.setOnClickListener {
            val token = TelegramRepository.getToken()
            if (token.isNullOrBlank()) {
                showRefreshStatus("No bot token set. Tap the gear icon to configure.", success = false)
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

    private fun showRefreshStatus(msg: String, success: Boolean) {
        binding.tvRefreshStatus.text = msg
        binding.tvRefreshStatus.setTextColor(
            getColor(if (success) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        binding.tvRefreshStatus.visibility = View.VISIBLE
    }
}
