package com.example.gemmaapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object EmergencyManager {

    private const val TAG          = "EmergencyManager"
    private const val COOLDOWN_MS  = 60_000L   // 60-second anti-spam window
    private const val LOCATION_TIMEOUT_MS = 5_000L

    @Volatile private var lastAlertMs = 0L

    // ── Public entry point ─────────────────────────────────────────────────────

    suspend fun sendAlert(context: Context) {
        val now = System.currentTimeMillis()

        // Anti-spam cooldown
        if (now - lastAlertMs < COOLDOWN_MS) {
            Log.w(TAG, "Alert suppressed — within 60s cooldown")
            showToast(context, "⚠️ Alert already sent recently (60s cooldown)")
            return
        }

        // Guard: profile must be set up
        if (!ProfilePrefs.isConfigured()) {
            showToast(context, "⚠️ Emergency contact not set. Open Profile to configure.")
            return
        }

        // Guard: Telegram token must exist
        val token = TelegramConfig.getToken(context)
        if (token.isNullOrBlank()) {
            showToast(context, "⚠️ Telegram bot token missing. Configure in Telegram settings.")
            return
        }

        // Lock cooldown before async work so rapid re-triggers are blocked
        lastAlertMs = now

        // Attempt GPS fix with timeout; fall back gracefully
        val location = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { getLocation(context) }

        val message = buildMessage(ProfilePrefs.userName, location)
        val chatId  = ProfilePrefs.contactChatId

        Log.i(TAG, "Sending emergency alert → chatId=$chatId")

        val result = TelegramApi.sendMessage(token, chatId, message)
        result.fold(
            onSuccess = {
                val name = ProfilePrefs.contactName.ifBlank { "emergency contact" }
                showToast(context, "✅ Emergency alert sent to $name")
                Log.i(TAG, "Alert delivered successfully")
            },
            onFailure = { err ->
                // Reset cooldown so user can retry immediately after a delivery failure
                lastAlertMs = 0L
                showToast(context, "❌ Alert failed — ${err.message}. Check Telegram setup.")
                Log.e(TAG, "Alert delivery failed", err)
            }
        )
    }

    // ── Location ───────────────────────────────────────────────────────────────

    private suspend fun getLocation(context: Context): Pair<Double, Double>? {
        val hasFine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permission not granted")
            return null
        }

        return withContext(Dispatchers.IO) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // 1. Try last-known from all providers first (instant, no battery cost)
            val lastKnown = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).firstNotNullOfOrNull { provider ->
                try {
                    if (lm.isProviderEnabled(provider)) {
                        @Suppress("MissingPermission")
                        lm.getLastKnownLocation(provider)
                    } else null
                } catch (_: Exception) { null }
            }

            if (lastKnown != null) {
                Log.i(TAG, "Last-known location: ${lastKnown.latitude}, ${lastKnown.longitude}")
                return@withContext Pair(lastKnown.latitude, lastKnown.longitude)
            }

            // 2. Request a fresh fix (used within withTimeoutOrNull so it auto-cancels)
            Log.i(TAG, "No last-known location — requesting fresh fix")
            suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                try {
                    val provider = when {
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)     -> LocationManager.GPS_PROVIDER
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                        else -> null
                    }
                    if (provider == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            lm.removeUpdates(this)
                            if (cont.isActive) cont.resume(Pair(loc.latitude, loc.longitude))
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    @Suppress("MissingPermission")
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }

                } catch (e: Exception) {
                    Log.e(TAG, "Fresh location fix failed", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    // ── Message builder ────────────────────────────────────────────────────────

    private fun buildMessage(name: String, location: Pair<Double, Double>?): String {
        val locationLine = if (location != null)
            "📍 https://maps.google.com/?q=${location.first},${location.second}"
        else
            "📍 Location unavailable"
        return "🆘 EMERGENCY ALERT\nFrom: ${name.ifBlank { "User" }}\n$locationLine\nNeed urgent help"
    }

    // ── UI helper ──────────────────────────────────────────────────────────────

    private fun showToast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }
}
