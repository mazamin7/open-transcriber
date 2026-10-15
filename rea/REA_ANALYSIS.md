# REA Analysis & Reconstruction: Google Live Transcribe Clone

## 1. Executive Summary & Intent

The target of this reconstruction is Google's **Live Transcribe** (`com.google.audio.hearing.visualization.accessibility.scribe`), an accessibility tool created in collaboration with Gallaudet University for the deaf and hard-of-hearing community.

The user's objective is to build an uncompromising **100% offline, privacy-first clone** designed specifically for real-time daily communication with their deaf partner, removing all proprietary Google tracking, cloud streaming, and privacy hazards.

---

## 2. Reverse-Engineering Behavioral Analysis

Through behavioral dissection of Google Live Transcribe's mobile profile, five foundational pillars were isolated:

### A. Real-Time Streaming Speech Pipeline
* **Behavior:** Transcribes voice continuously with sub-300ms visual latency. Displays uncommitted interim words (with a subdued/italicized styling) before finalizing sentences with punctuation.
* **Challenge:** Google Live Transcribe typically relies on Google Play Services speech recognition or cloud streaming, leaking user audio to remote servers unless device-specific offline packs are downloaded.
* **Reconstruction Strategy:** 
  1. Client-side on-device Web Speech engine (leverages local Android on-device speech model when available).
  2. Local Neural Engine (in-browser Whisper via WebAssembly/WebGPU, cached completely offline).
  3. Optional air-gapped local Python Whisper WebSocket server for desktop/home LAN use.

### B. Ambient Sound & Volume Indicator Bubble
* **Behavior:** A distinct circular green bubble located in the top-right corner. It dynamically scales its outer glow ring in proportion to the input decibel level (RMS) to reassure the deaf user that speech is being received, and alerts them if the environment is too loud or silent.
* **Reconstruction Strategy:** Web Audio API (`AudioContext`, `AnalyserNode`, fast time-domain RMS decibel calculation) mapped directly to CSS transforms for a 60fps smooth pulsing bubble.

### C. Deaf Two-Way Communication (Type-Back & Flip Display)
* **Behavior:** In live face-to-face conversations across a table, the deaf partner needs to type back. Passing the phone back and forth is cumbersome.
* **Reconstruction Strategy:**
  * Quick-access Type-Back keyboard tray.
  * **180° Table-Flip Mode:** Flips the typed message upside down with giant high-contrast text so the hearing partner sitting opposite can read it immediately without the deaf partner letting go of their phone.
  * Local Speech Synthesis (offline TTS) to read the response out loud.
  * Quick response chips ("Yes", "No", "Please repeat", "Give me a moment to type").

### D. Sound Event Detection
* **Behavior:** Google Live Transcribe visualizes non-verbal sounds (e.g., laughter, clapping, music, loud bangs).
* **Reconstruction Strategy:** Acoustic frequency analysis and envelope detection triggering visual badge alerts (`[Applause]`, `[Music]`, `[Loud Noise]`, `[Speech]`).

### E. Privacy & Airgap Boundaries
* **Behavior:** The original app sends telemetry and can stream audio to Google servers.
* **Reconstruction Strategy:** Complete air-gap guarantee:
  * Strict Content Security Policy (`connect-src 'self' ws://localhost:*`).
  * Service Worker offline-first caching (`CacheStorage`).
  * IndexedDB local-only encrypted/scrubbable conversation history with instant shredding.
  * Zero third-party trackers, zero CDN calls at runtime, zero external APIs.

---

## 3. Obligation Ledger & Verification Map

| Obligation ID | Title | Layer | Verification Case | Status |
|---|---|---|---|---|
| `OBL-001` | Streaming STT Pipeline | Runtime | `tests/test-obligations.js::testStreamingSpeechPipeline` | Implemented |
| `OBL-002` | Volume Decibel Meter Bubble | Audio Processing | `tests/test-obligations.js::testAudioVisualizerRmsCalculation` | Implemented |
| `OBL-003` | Two-Way Deaf Comm & Flip Mode | Accessibility UI | `tests/test-obligations.js::testTwoWayCommunicationState` | Implemented |
| `OBL-004` | Sound Event Detection | Audio Processing | `tests/test-obligations.js::testSoundEventClassification` | Implemented |
| `OBL-005` | Offline Airgap & CSP | Security / Privacy | `tests/test-obligations.js::testOfflineAirgapAndCsp` | Implemented |
| `OBL-006` | Accessibility & Haptics | Accessibility | `tests/test-obligations.js::testAccessibilitySettings` | Implemented |
| `OBL-007` | Local Persistence & Shredding | Persistence | `tests/test-obligations.js::testLocalStorageAndScrubbing` | Implemented |
