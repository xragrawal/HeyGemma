# Impactus — Backlog

## P2 Items (Post-Hackathon)

### [P2] False-positive protection on emergency trigger
**Context:** Currently the emergency alert fires immediately when the wake keyword is detected N times in a row. There is no cancellation window.
**Risk:** Any accidental utterance of the keyword (e.g. "I need help with this", child using phone, voice in media) sends a real Telegram alert to the emergency contact with no way to abort.
**Recommended fix:** Add a 3-second fullscreen countdown overlay after trigger: "🆘 Sending alert in 3… 2… 1… Say STOP or tap Cancel to abort." Only fires TelegramApi.sendMessage() after the countdown completes uncancelled.
**Effort estimate:** ~2 hours (new overlay Activity/Fragment + cancellation coroutine job)

### [P2] Background wake-word listening via Foreground Service
**Context:** WakeWordEngine runs on a background thread inside the app process. Android 10+ suspends mic access when the app is backgrounded or screen is locked.
**Risk:** Emergency keyword detection stops working the moment the user locks their phone — exactly when they'd need it most.
**Recommended fix:** Wrap WakeWordEngine in a `ForegroundService` with `foregroundServiceType="microphone"`. Show a persistent low-priority notification ("Impactus is listening for wake word"). This is fully supported in the current native Android setup.
**Effort estimate:** ~3 hours (new Service class + notification channel + lifecycle wiring)
**Manifest additions needed:**
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

### [P2] Encrypted storage for sensitive profile data
**Context:** Bot token (TelegramConfig) and emergency contact chat IDs (ProfilePrefs) are stored in plaintext SharedPreferences. Readable by any app with root access.
**Recommended fix:** Migrate to Jetpack `EncryptedSharedPreferences` (androidx.security:security-crypto). Drop-in replacement, same API.
**Effort estimate:** ~1 hour

### [P2] Two emergency contacts
**Context:** V1 (hackathon) supports one emergency contact for simplicity. Real-world safety requires at least two in case the primary is unreachable.
**Recommended fix:** Add `contact2Name` + `contact2ChatId` to ProfilePrefs, add a second contact picker row in UserProfileActivity, loop over both in EmergencyManager.sendAlert().
**Effort estimate:** ~1 hour
