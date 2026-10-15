# Live Transcribe (Offline) 🎙️📱
> **A 100% Offline, Privacy-Guaranteed Google Live Transcribe Clone**  
> Designed specifically for deaf and hard-of-hearing communication.

---

## 🌟 Overview & Purpose

This application is an offline, air-gapped reconstruction of Google's **Live Transcribe** accessibility app, engineered using the **Reverse Engineer Anything (REA)** reconstruction framework.

It was created to solve a vital real-world need: **allowing deaf and hearing partners to communicate seamlessly and privately in person without any internet connection, cloud audio streaming, or telemetry.**

---

## ✨ Features (Google Live Transcribe Parity)

| Feature | Description |
|---|---|
| **Continuous Streaming Speech-to-Text** | Instantaneous streaming transcription with live interim hypotheses and finalized sentences. |
| **Acoustic Decibel Meter Bubble** | Iconic circular decibel indicator pulsing with input audio amplitude to visualize volume levels. |
| **Acoustic Sound Event Badges** | Visual badges detecting environmental sounds (`[Speech]`, `[Applause]`, `[Music]`, `[Loud Noise]`, `[Silence]`). |
| **Two-Way Type-Back Communication** | Quick keyboard reply tray for the deaf partner to type responses. |
| **180° Table-Flip Mode** | In one tap, rotates the typed reply upside down with giant high-contrast text so a hearing partner sitting across the table can read it instantly without passing the phone! |
| **Offline Text-To-Speech (TTS)** | Reads out the deaf partner's typed message out loud using the device's built-in offline voices. |
| **Quick Response Chips** | One-tap customizable phrases (`"Yes"`, `"No"`, `"I am deaf, please speak clearly"`, `"Give me a moment to type"`). |
| **Haptic Speech-Onset Vibration** | Vibrates the phone gently when someone resumes speaking after a silence pause, alerting the deaf partner to look at the screen. |
| **Screen Wake Lock** | Keeps the phone screen awake continuously during conversations. |
| **Scalable Typography (18px - 64px)** | Giant, high-legibility text designed for effortless reading. |
| **AMOLED & Accessibility Themes** | OLED Pure Black (`#000000`), High-Contrast Yellow-on-Black, Slate Dark, and High-Contrast White. |
| **Private Local History & Shredder** | IndexedDB conversation log with search, text/JSON export, and one-tap permanent memory wipe. |

---

## 🔒 100% Offline & Privacy Guarantee

- **Air-Gapped Content Security Policy (CSP):** Blocks all third-party analytics, tracking scripts, and external connections.
- **Service Worker Cache-First:** All assets (HTML, CSS, JS, icons) are cached in `CacheStorage`. Once loaded once, it works completely offline in airplane mode.
- **Audio Never Leaves Device:** Speech is processed strictly on-device in RAM.

---

## 🚀 Quick Start (Running on Android & PC)

### 1. Start the Local Server
From this project directory:
```bash
npm start
```
This will start the local server on port 3000 and display your local network IP addresses:
```text
======================================================
  Live Transcribe (Offline) Local Server Running
======================================================
Local Access:      http://localhost:3000
Android Access:    http://192.168.1.X:3000
======================================================
```

### 2. Install on Android Phone (Takes 10 Seconds)
1. Connect your Android phone to the same Wi-Fi as your PC.
2. Open Chrome (or Samsung Internet / Edge / Firefox) on your phone and navigate to `http://<your-pc-ip>:3000`.
3. In Chrome, tap the **three dots menu (`...`)** in the top right.
4. Tap **"Add to Home screen"** or **"Install App"**.
5. **Done!** The app is now installed on your Android phone as a standalone, full-screen offline app with its own icon. You can now disconnect from Wi-Fi or turn on Airplane Mode!

---

## 🎙️ Enabling Offline Italian Speech Recognition on Android

Android includes built-in offline Italian speech recognition models so transcription works completely offline without internet:

1. Open your Android phone's **Settings** (*Impostazioni*).
2. Go to **System** (*Sistema*) ➔ **Languages & input** (*Lingue e inserimento*) ➔ **Voice input** (*Digitazione vocale* / *Riconoscimento vocale*).
3. Tap **Offline speech recognition** (*Riconoscimento vocale offline*).
4. In the **"All"** (*Tutte*) tab, tap **Italiano (Italia)** to download the offline language pack (~50MB).
5. Once downloaded, your phone will transcribe speech in Italian 100% offline with zero internet access!

---

## 🖥️ Optional: Local Faster-Whisper WebSocket Server

If you want to use OpenAI's high-accuracy Whisper model running locally on your laptop over private Wi-Fi:

1. Install Python dependencies:
   ```bash
   pip install -r server/requirements.txt
   ```
2. Start the local Whisper server:
   ```bash
   python server/local_whisper_server.py
   ```
3. In the Transcribe app, tap the **Settings icon (⚙️)** ➔ change **Speech Engine** to `"Local Private WebSocket Server"` ➔ enter `ws://<your-pc-ip>:8765`.

---

## 🧪 REA Reconstruction Verification Suite

The project includes an automated test suite verifying all 7 REA obligations:
```bash
npm test
```
Outputs:
```text
  [PASS] OBL-001: SpeechEngine Mock Streaming & Finalization
  [PASS] OBL-002: AudioAnalyzer RMS & Decibel Calculations
  [PASS] OBL-003: TwoWayCommunication Quick Chips & Flip State
  [PASS] OBL-004: Acoustic Sound Event Detection
  [PASS] OBL-005: Strict Airgap Security & CSP Verification
  [PASS] OBL-006: Accessibility Typography & Haptic Intervals
  [PASS] OBL-007: History Export Formatting & Shredding Verification

  Tests completed: 7 passed, 0 failed.
```

---

## 📁 Project Architecture

```
transcriber/
├── public/
│   ├── index.html            # Main mobile-first PWA interface & Airgap CSP
│   ├── manifest.json         # Standalone Android PWA configuration
│   ├── sw.js                 # Offline Cache-first Service Worker
│   ├── css/
│   │   └── app.css           # OLED themes, 180° flip transform, decibel bubble
│   ├── js/
│   │   ├── app.js            # Main application orchestrator
│   │   ├── audio-analyzer.js # RMS decibel meter & acoustic event classifier
│   │   ├── speech-engine.js  # WebSpeech & Local WebSocket multi-engine STT
│   │   ├── two-way-comm.js   # Type-back, 180° table-flip, and offline TTS
│   │   ├── accessibility.js  # Font scaling, Wake Lock API, haptics
│   │   └── storage.js        # IndexedDB local history, export & privacy shredder
│   └── icons/
│       └── icon.svg          # Accessibility & microphone app icon
├── rea/
│   ├── reconstruction-ledger.json # REA formal obligation ledger
│   └── REA_ANALYSIS.md       # Target analysis & behavioral mappings
├── server/
│   ├── local_whisper_server.py # Airgapped Python Faster-Whisper server
│   └── requirements.txt      # Python dependencies
├── tests/
│   └── test-obligations.js   # Automated REA verification test suite
├── server.js                 # Zero-dependency local Node.js server
└── package.json
```
