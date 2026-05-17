package com.example.gemmaapp

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.gemmaapp.databinding.ActivityElevenlabsBinding

class ElevenLabsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityElevenlabsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityElevenlabsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Pre-fill saved values
        ElevenLabsConfig.getApiKey(this)?.let  { binding.etApiKey.setText(it) }
        binding.etVoiceId.setText(ElevenLabsConfig.getVoiceId(this))

        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                showKeyStatus("API key cannot be empty", success = false)
                return@setOnClickListener
            }
            ElevenLabsConfig.setApiKey(this, key)
            showKeyStatus("Key saved", success = true)
            Toast.makeText(this, "ElevenLabs key saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveVoice.setOnClickListener {
            val id = binding.etVoiceId.text?.toString()?.trim() ?: ""
            if (id.isBlank()) {
                Toast.makeText(this, "Voice ID cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ElevenLabsConfig.setVoiceId(this, id)
            Toast.makeText(this, "Voice saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showKeyStatus(msg: String, success: Boolean) {
        binding.tvKeyStatus.text = msg
        binding.tvKeyStatus.setTextColor(
            getColor(if (success) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
        binding.tvKeyStatus.visibility = View.VISIBLE
    }
}
