package com.example.gemmaapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gemmaapp.databinding.ActivityUserProfileBinding
import com.example.gemmaapp.databinding.ItemContactPickerBinding
import kotlinx.coroutines.launch

class UserProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserProfileBinding

    // Chat ID resolved either via getChat() or tapped from contacts list
    private var resolvedChatId: Long = 0L
    private var resolvedName: String = ""

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(
            this,
            "Location permission denied — alert will send without GPS coordinates.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ProfilePrefs.init(this)
        requestLocationIfNeeded()
        loadExistingProfile()
        populateBotContacts()
        setupClickListeners()
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun requestLocationIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ── Load existing values + demo pre-fill ───────────────────────────────────

    private fun loadExistingProfile() {
        if (ProfilePrefs.onboardingComplete) {
            binding.etName.setText(ProfilePrefs.userName)
            binding.etKeyword.setText(ProfilePrefs.emergencyKeyword)
            binding.etContactUsername.setText(ProfilePrefs.contactUsername)
            binding.etManualName.setText(ProfilePrefs.contactName)
            if (ProfilePrefs.contactChatId != 0L) {
                resolvedChatId = ProfilePrefs.contactChatId
                resolvedName   = ProfilePrefs.contactName
                showVerifiedBadge(resolvedName)
            }
            updateStatusBadge(configured = true)
        } else {
            // Demo pre-fill for first run
            binding.etName.setText("Ravi")
            binding.etKeyword.setText("help")
            binding.etContactUsername.setText("@jayeshnshete15")
            binding.etManualName.setText("Jayesh")
            updateStatusBadge(configured = false)
        }
    }

    // ── Bot contacts list (anyone who has messaged the bot) ────────────────────

    private fun populateBotContacts() {
        lifecycleScope.launch {
            // Refresh from API so list is current
            TelegramRepository.refreshContacts()
            renderContactRows()
        }
    }

    private fun renderContactRows() {
        val contacts = TelegramRepository.contacts.value
        binding.llContacts.removeAllViews()

        if (contacts.isEmpty()) {
            binding.tvContactsEmpty.visibility = View.VISIBLE
            return
        }

        binding.tvContactsEmpty.visibility = View.GONE
        contacts.forEach { contact ->
            val row = ItemContactPickerBinding.inflate(
                LayoutInflater.from(this), binding.llContacts, false
            )
            row.tvContactName.text = contact.name
            row.tvContactType.text = contact.displayType.uppercase()

            val isSelected = contact.chatId == resolvedChatId
            applyRowHighlight(row, isSelected)

            row.root.setOnClickListener {
                resolvedChatId = contact.chatId
                resolvedName   = contact.name
                // Sync name field so it stays in sync
                binding.etManualName.setText(contact.name)
                renderContactRows()
                showVerifiedBadge(contact.name)
            }

            binding.llContacts.addView(row.root)
        }
    }

    private fun applyRowHighlight(row: ItemContactPickerBinding, selected: Boolean) {
        row.root.setBackgroundResource(
            if (selected) R.drawable.bg_lime_surface else R.drawable.bg_surface_border_14
        )
        row.tvContactName.setTextColor(
            getColor(if (selected) R.color.gemma_lime else R.color.gemma_ink)
        )
    }

    // ── Click listeners ────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnTestAlert.setOnClickListener {
            lifecycleScope.launch {
                if (prepareContact()) {
                    EmergencyManager.sendAlert(this@UserProfileActivity)
                }
            }
        }

        binding.btnSave.setOnClickListener {
            lifecycleScope.launch {
                // Save doesn't block on contact resolution — name + keyword are enough
                // Contact can be set later or is already resolved via row tap
                val name = binding.etName.text.toString().trim()
                if (name.isBlank()) { binding.etName.error = "Name is required"; return@launch }
                val keyword = binding.etKeyword.text.toString().trim()
                if (keyword.isBlank()) { binding.etKeyword.error = "Keyword is required"; return@launch }
                // Try to resolve contact if not yet done, but don't block save on failure
                if (resolvedChatId == 0L) prepareContact()
                saveProfile()
            }
        }
    }

    // ── Contact resolution ─────────────────────────────────────────────────────

    /**
     * Ensures a valid chat ID is ready in [resolvedChatId].
     * Priority:
     *   1. Already resolved via row tap — use directly.
     *   2. etContactUsername looks numeric — parse as chat ID.
     *   3. etContactUsername starts with @ — try getChat() API call.
     * Returns true if a valid chat ID is ready, false otherwise.
     */
    private suspend fun prepareContact(): Boolean {
        val name = binding.etName.text.toString().trim()
        if (name.isBlank()) { binding.etName.error = "Name is required"; return false }

        val keyword = binding.etKeyword.text.toString().trim()
        if (keyword.isBlank()) { binding.etKeyword.error = "Keyword is required"; return false }

        // Already selected from contacts list — nothing more to do
        if (resolvedChatId != 0L) return true

        val raw = binding.etContactUsername.text.toString().trim()
        if (raw.isBlank()) {
            binding.etContactUsername.error = "Enter @username or pick from contacts below"
            return false
        }

        // Numeric chat ID entered directly
        val numeric = raw.toLongOrNull()
        if (numeric != null) {
            resolvedChatId = numeric
            resolvedName   = binding.etManualName.text.toString().trim().ifBlank { "Contact" }
            showVerifiedBadge(resolvedName)
            return true
        }

        // @username — try Telegram API resolution
        val token = TelegramConfig.getToken(this)
        if (token.isNullOrBlank()) {
            Toast.makeText(this,
                "⚠️ Set your Telegram bot token first (Telegram → ⚙ Configure Account).",
                Toast.LENGTH_LONG).show()
            return false
        }

        Toast.makeText(this, "Verifying @username…", Toast.LENGTH_SHORT).show()

        val result = TelegramApi.getChat(token, raw)
        return result.fold(
            onSuccess = { chatId ->
                resolvedChatId = chatId
                resolvedName   = binding.etManualName.text.toString().trim().ifBlank { "Contact" }
                ProfilePrefs.contactChatId   = chatId
                ProfilePrefs.contactName     = resolvedName
                ProfilePrefs.contactUsername = raw
                showVerifiedBadge(resolvedName)
                true
            },
            onFailure = { err ->
                // getChat failed for private user — guide toward contacts list
                binding.etContactUsername.error = "Could not resolve: ${err.message}"
                Toast.makeText(this,
                    "❌ Lookup failed. Tap the contact below instead.",
                    Toast.LENGTH_LONG).show()
                false
            }
        )
    }

    // ── Save ───────────────────────────────────────────────────────────────────

    private fun saveProfile() {
        ProfilePrefs.userName           = binding.etName.text.toString().trim()
        ProfilePrefs.emergencyKeyword   = binding.etKeyword.text.toString().trim().lowercase()
        ProfilePrefs.contactChatId      = resolvedChatId
        ProfilePrefs.contactName        = resolvedName
        ProfilePrefs.contactUsername    = binding.etContactUsername.text.toString().trim()
        ProfilePrefs.onboardingComplete = true
        updateStatusBadge(configured = true)
        Toast.makeText(this, "Profile saved ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private fun showVerifiedBadge(name: String) {
        binding.llSelectedContact.visibility = View.VISIBLE
        binding.tvSelectedName.text = name
    }

    private fun updateStatusBadge(configured: Boolean) {
        binding.tvProfileBadge.text = if (configured) "SET" else "NOT SET"
        binding.tvProfileBadge.setTextColor(
            getColor(if (configured) R.color.gemma_lime else R.color.gemma_rose)
        )
        binding.tvProfileBadge.setBackgroundResource(
            if (configured) R.drawable.bg_lime_surface else R.drawable.bg_rose_pill
        )
    }
}
