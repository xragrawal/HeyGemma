# HeyGemma 🌟

**An on-device AI assistant with emergency alerting — powered by Gemma 4, Whisper, and Vosk.**

Built for the Google Gemma 4 Hackathon. All AI runs locally on your Android phone — no cloud, no latency, no data leaving your device.

---

## What it does

HeyGemma combines a conversational AI assistant with a life-safety emergency alert system:

- **Say "Hey Gemma"** → voice command is transcribed locally by Whisper and answered by Gemma 4 running on-device
- **Multi-agent routing** — automatically delegates your request to the right agent: general conversation, notes/todos, or Telegram messaging
- **Emergency alert** — say your custom keyword 3× in a row → GPS coordinates are sent to your emergency contact via Telegram instantly
- **Wake-word always listening** — Vosk runs continuously in the background waiting for "Hey Gemma" or your emergency phrase
- **Ambient sound detection** — always-on ONNX classifier detects emergency sounds (fire alarm, ambulance siren, baby crying, glass breaking) and triggers alerts automatically
- **Telegram action extraction** — incoming Telegram messages are parsed by Gemma to extract and execute todo actions automatically

---

## Demo

### Idea Pitch
https://github.com/user-attachments/assets/bcbb7c36-4067-4303-a6c1-f72a151fe831

### Full Demo
https://github.com/user-attachments/assets/40f92886-07b0-4042-b7d4-6feea269e01e

> *Say "Hey Gemma, send a message to Jayesh saying I'm running late"*
> → Gemma classifies intent → resolves contact → sends Telegram message → speaks confirmation

> *Say "Help … Help … Help"*
> → Haptic pulse on each detection → alert fires → emergency contact receives:
> ```
> 🆘 EMERGENCY ALERT
> From: Ravi
> 📍 https://maps.google.com/?q=18.52,73.85
> Need urgent help
> ```

> *Fire alarm goes off in the room*
> → SoundClassifier detects "Fire Alarm" → emergency screen shown → Telegram alert sent automatically

---

## Features

| Feature | Details |
|---|---|
| **On-device LLM** | Gemma 4 2B (Q4_K_M, ~1.5 GB) via llama.cpp JNI |
| **Voice transcription** | Whisper Small ONNX — 16kHz mono PCM → text |
| **Wake word** | Vosk offline model — "Hey Gemma" + custom emergency keyword |
| **Emergency alerts** | GPS + Telegram message, 3× keyword trigger, 60s anti-spam |
| **Sound detection** | Always-on ONNX classifier — 527 AudioSet classes; alerts on fire alarm, siren, baby crying, glass break |
| **Telegram agent** | Send messages to contacts via voice command |
| **Telegram action extraction** | Incoming messages parsed by Gemma to auto-execute todo actions |
| **Notes & Todos** | Voice-driven task management, persisted in SQLite |
| **Text-to-speech** | ElevenLabs (premium) or Android TTS (fallback) |
| **Onboarding** | Profile setup: name, emergency keyword, emergency contact |
| **Emergency screen** | Full-screen alert UI shown on sound/keyword trigger |

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                      MainActivity                         │
│  HOME → LISTENING → PROCESSING → RESULT → EMERGENCY      │
└────────────────────┬─────────────────────────────────────┘
                     │
            ┌────────▼────────┐
            │  ChatViewModel   │   StateFlows: messages, loadState,
            │  (MVVM core)     │   isRecording, currentTranscript
            └──┬───┬───┬──────┘
               │   │   │
    ┌──────────┘   │   └──────────────┐
    ▼              ▼                  ▼
LlamaEngine   WhisperEngine     WakeWordEngine ──── feedAudio() ────┐
(Gemma 4 JNI) (ONNX Runtime)   (Vosk + counter)                    ▼
                                      │                     SoundClassifier
                              "emergency" keyword          (ONNX AudioSet 527)
                                      │                    fire/siren/baby/glass
                                      └──────────┬─────────────────┘
                                                 ▼
                                        EmergencyManager
                                      (GPS + Telegram alert)
               │
               ▼
        AgentOrchestrator
        ┌──────┬──────┬──────┐
        ▼      ▼      ▼
     DIRECT   TODO  TELEGRAM
     (Gemma) (Room) (Bot API)
                       │
              TelegramActionExtractor
              (auto-execute todos from
               incoming TG messages)
```

### Key classes

| Class | Responsibility |
|---|---|
| `ChatViewModel` | Central state; owns all engines, manages model loading and message flow |
| `LlamaEngine` | Kotlin JNI wrapper around llama.cpp; streams tokens via callback |
| `WhisperEngine` | ONNX encoder-decoder loop; PCM audio → text transcript |
| `WakeWordEngine` | Vosk streaming recognition; rolling 8s time-window counter for emergency; feeds audio to SoundClassifier |
| `SoundClassifier` | Always-on ONNX model (527 AudioSet classes); detects fire alarms, sirens, baby crying, glass breaking; shares mic session with WakeWordEngine |
| `AgentOrchestrator` | Fast keyword classifier + LLM fallback; routes to TODO / TELEGRAM / DIRECT |
| `TelegramActionExtractor` | Parses incoming Telegram messages via Gemma; auto-executes add/cancel todo actions asynchronously |
| `EmergencyManager` | GPS fetch (5s timeout) → Telegram alert with 60s anti-spam cooldown |
| `ProfilePrefs` | SharedPreferences wrapper: name, keyword, repeat count, emergency contact |
| `TelegramApi` | OkHttp3 client: getMe, getUpdates, sendMessage, getChat |
| `TelegramRepository` | Room DB + in-memory cache for contacts and message threads |
| `AudioRecorder` | 16kHz mono PCM recording with Voice Activity Detection |
| `TtsManager` | ElevenLabs API if key present, else Android TTS fallback |

---

## Tech Stack

- **Language:** Kotlin + C++ (JNI)
- **AI Models:**
  - 🧠 **Gemma 4 2B** (Google) — on-device LLM for conversation, intent classification, agent routing; runs via llama.cpp JNI with Q4_K_M quantisation (~1.5 GB)
  - 🎙️ **Whisper Small** (OpenAI) — speech-to-text transcription; encoder-decoder ONNX inference at 16kHz mono PCM
  - 👂 **Vosk** (Alpha Cephei) — offline wake-word detection ("Hey Gemma") and custom emergency keyword recognition; streams 16kHz audio with rolling 8s window
  - 🔊 **YAMNet / AudioSet** — ambient sound classifier (527 classes); detects fire alarms, sirens, baby crying, glass breaking; bundled as ONNX asset
- **AI inference runtime:** [llama.cpp](https://github.com/ggerganov/llama.cpp) (git submodule), ONNX Runtime, Vosk Android
- **Architecture:** MVVM, Kotlin Coroutines, StateFlow
- **Database:** Room (SQLite) — todos, Telegram messages, contacts
- **Networking:** OkHttp3 (Telegram Bot API)
- **UI:** View Binding, Material 3
- **Min SDK:** 27 (Android 8.1) — Vulkan 1.1 baseline
- **Target SDK:** 35 (Android 15)
- **ABI:** arm64-v8a (NEON acceleration)

---

## Build Requirements

| Tool | Version |
|---|---|
| JDK | 17 (Temurin recommended) |
| Android SDK | API 35 |
| Android NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| Gradle | 8.11.1 |

### Install Android SDK + NDK (if not using Android Studio)

```bash
# Install command line tools
brew install --cask android-commandlinetools

# Install required SDK components
sdkmanager --sdk_root=$HOME/Library/Android/sdk \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
  "ndk;27.2.12479018" "cmake;3.22.1"

# Set local.properties
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

---

## Setup & Run

### 1. Clone with submodules

```bash
git clone https://github.com/xragrawal/HeyGemma.git
cd HeyGemma
git submodule update --init --recursive   # pulls llama.cpp
```

### 2. Download AI models

```bash
# Create models directory on device
adb shell mkdir -p /sdcard/Download/models

# Gemma 4 2B — download from HuggingFace (Q4_K_M recommended, ~1.5 GB)
# https://huggingface.co/bartowski/google_gemma-4-2b-it-GGUF
adb push gemma-4-2b-it-Q4_K_M.gguf /sdcard/Download/models/

# Whisper Small ONNX — encoder + decoder + tokenizer
# Download from: https://huggingface.co/onnx-community/whisper-small
adb push whisper-small-onnx/ /sdcard/Download/models/

# Vosk English model (~40 MB) — or download in-app
# https://alphacephei.com/vosk/models
adb push vosk-model-small-en-us-0.15/ /sdcard/Download/models/
```

> **Tip:** Gemma and Vosk can also be downloaded directly in the app via the model picker screen.

### 3. Build & install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> First build takes 5–15 minutes — llama.cpp C++ compilation is heavy.

### 4. Configure Telegram (optional, for messaging + emergency alerts)

1. Open Telegram → search `@BotFather` → `/newbot` → copy your token
2. In the app: tap the **Telegram tile** → **⚙ Configure Account** → paste token → **Ping** to verify
3. Have your emergency contact send `/start` to your bot (so the bot can message them)

### 5. Set up emergency profile

1. On first launch the app opens the **Profile** screen automatically
2. Set your name, emergency keyword (default: "emergency"), and pick your emergency contact
3. Tap **Save Profile**

---

## Emergency Alert Flow

```
User says keyword 3× within 8 seconds
        ↓
Haptic pulse + toast "🆘 1/3 — keep going!" per detection
        ↓
On 3rd detection → EmergencyManager.sendAlert()
        ↓
GPS location fetched (5s timeout, falls back to "unavailable")
        ↓
Telegram message sent to emergency contact:
  "🆘 EMERGENCY ALERT
   From: [Name]
   📍 https://maps.google.com/?q=lat,lng
   Need urgent help"
        ↓
Toast: "✅ Alert sent to [contact]"
60-second cooldown before next alert
```

---

## Voice Commands

| Say | What happens |
|---|---|
| `"Hey Gemma, [question]"` | Answers via Gemma 4 LLM |
| `"Add buy groceries to my list"` | Saves to notes/todos |
| `"What's on my list?"` | Reads back open todos |
| `"Send [message] to [name] on Telegram"` | Routes to Telegram agent |
| `"[keyword] [keyword] [keyword]"` | Triggers emergency alert |

---

## Permissions

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Whisper transcription + Vosk wake-word |
| `INTERNET` | Model downloads, Telegram API |
| `ACCESS_FINE_LOCATION` | GPS coordinates in emergency alert |
| `ACCESS_COARSE_LOCATION` | GPS fallback |
| `VIBRATE` | Haptic feedback on emergency keyword detection |
| `READ_EXTERNAL_STORAGE` | Model file access (Android ≤ 12) |
| `READ_MEDIA_IMAGES` | Model file access (Android 13+) |

---

## Project Structure

```
HeyGemma/
├── app/
│   ├── src/main/
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt          # Native build config
│   │   │   ├── llama_jni.cpp           # JNI bridge to llama.cpp
│   │   │   └── llama.cpp/              # llama.cpp submodule
│   │   ├── assets/
│   │   │   ├── sound_classifier.onnx          # AudioSet 527-class sound model
│   │   │   └── sound_classifier_labels.txt    # Class index → label mapping
│   │   ├── java/com/example/gemmaapp/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ChatViewModel.kt
│   │   │   ├── LlamaEngine.kt
│   │   │   ├── WhisperEngine.kt
│   │   │   ├── WakeWordEngine.kt
│   │   │   ├── SoundClassifier.kt             # Ambient sound detection
│   │   │   ├── AgentOrchestrator.kt
│   │   │   ├── TelegramActionExtractor.kt     # Auto todo extraction from TG
│   │   │   ├── EmergencyManager.kt            # Emergency alert
│   │   │   ├── ProfilePrefs.kt                # User profile storage
│   │   │   ├── UserProfileActivity.kt         # Onboarding + settings
│   │   │   ├── TelegramApi.kt
│   │   │   ├── TelegramRepository.kt
│   │   │   └── ...
│   │   └── res/
│   │       ├── layout/                 # screen_home, screen_listening, screen_emergency, etc.
│   │       └── drawable/
│   └── build.gradle.kts
├── Demo/
│   └── HeyGemma_demo_v3.mp4           # Latest demo video
├── supporting-data-images-audio/       # Icons and audio clips for context
├── backlog.md                          # Known issues + P2 items
├── setup.sh                            # One-time bootstrap script
└── README.md
```

---

## Known Limitations / Backlog

See [backlog.md](backlog.md) for full details. Key items:

- **Background wake-word** — mic access suspended when screen is locked (Android 10+); requires Foreground Service to fix
- **No cancellation window** — emergency alert fires immediately; a 3-second abort countdown is planned
- **Single emergency contact** — v1 supports one contact; two recommended for real-world safety
- **Plaintext token storage** — bot token and contact ID stored in SharedPreferences; EncryptedSharedPreferences recommended before production

---

## Contributing

This project was built as a hackathon submission. PRs welcome for backlog items.

1. Fork the repo
2. Create a feature branch
3. Run `./gradlew assembleDebug` to verify build
4. Submit a PR against `main`

---

## License

MIT
