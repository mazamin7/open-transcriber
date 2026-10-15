# Native Android App - Live Transcribe (100% Standalone Offline) 📱

This is the **native Android Kotlin project** for Live Transcribe.

## 🛡️ True Air-Gapped Privacy
- **`android.permission.INTERNET` is deliberately omitted** from `AndroidManifest.xml`.
- The Android operating system physically forbids this app from opening any network sockets.
- The app operates **100% standalone on the phone**:
  - No Wi-Fi required.
  - No cellular data / SIM card required.
  - No connection to any PC or server.
  - Airplane Mode supported.

---

## 🏗️ Architecture & On-Device Engine

1. **Embedded Kaldi / Vosk Neural Speech Engine (`VoskSpeechService.kt`):**
   - Transcribes 16kHz audio directly on the phone's CPU.
   - Sub-200ms streaming latency with real-time partial hypotheses.
2. **Iconic Decibel Meter Bubble (`AudioVisualizerView.kt`):**
   - Custom canvas view drawing the pulsing green circular volume indicator.
3. **Deaf Two-Way Communication & 180° Table-Flip (`MainActivity.kt`):**
   - Type-back reply tray.
   - **Flip 180° Button:** Flips the typed text upside-down so the hearing partner sitting across the table can read it instantly without passing the phone!
   - Offline Android Text-To-Speech (`android.speech.tts.TextToSpeech`).
   - Quick Response Chips (`"Yes"`, `"No"`, `"Please repeat that"`, `"I am deaf, please speak clearly"`).
4. **Haptic Vibration Feedback:**
   - Vibrates phone gently when someone resumes speaking after a pause.
5. **Screen Wake Lock:**
   - Keeps screen continuously awake during live conversations.

---

## 📦 How to Build the Standalone APK

### Option A: Open with Android Studio
1. Open **Android Studio** on your PC.
2. Select **Open** ➔ choose the `d:\Documents\projects\transcriber\android` folder.
3. Download the small English Vosk model:
   - Download [`vosk-model-small-en-us-0.15.zip`](https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip) (~40MB).
   - Unzip and rename the folder to `model-en-us`.
   - Place it inside `android/app/src/main/assets/model-en-us`.
4. Click **Build** ➔ **Build Bundle(s) / APK(s)** ➔ **Build APK(s)**.
5. Copy the generated `.apk` file to your girlfriend's phone (via USB cable or Bluetooth) and tap to install!

### Option B: Build via Command Line (Gradle)
```bash
cd android
./gradlew assembleDebug
```
The output APK will be at:
`android/app/build/outputs/apk/debug/app-debug.apk`

Transfer this `.apk` file to the phone, install it once, and she can use it anywhere in the world completely disconnected!
