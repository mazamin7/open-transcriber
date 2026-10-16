# Open Transcriber 🎙️📱
> **App Android nativa per trascrizione vocale istantanea e sottotitoli in tempo reale 100% Offline & Air-Gapped**  
> Progettata per la comunicazione accessibile tra persone sorde e udenti senza alcuna connessione internet.

---

## 🌟 Caratteristiche Principali

| Funzionalità | Descrizione |
|---|---|
| **Zero Permessi Internet** | Nessun permesso di rete (`android.permission.INTERNET` assente). Privacy totale al 100%. |
| **Crittografia Hardware AES-256-GCM** | Persistenza sicura anche dopo il riavvio del telefono con chiavi generate e protette dal modulo hardware del dispositivo (**AndroidKeyStore / TEE**). Nessun testo memorizzato in chiaro. |
| **Modalità Incognito 🕶️** | Attivabile con un tocco: la trascrizione rimane unicamente nella RAM volatile (zero scritture su disco) e blocca screenshot e registrazioni dello schermo (`FLAG_SECURE`). |
| **Flusso Unificato App & Bolla** | Nessuna perdita di testo passando tra l'app a schermo intero e la bolla fluttuante: cronologia continua e sincronizzata bidirezionalmente in tempo reale (`TranscriptSessionManager`). |
| **Motore Vocale Neurale On-Device** | Sfrutta il motore neurale on-device di Android (`SpeechRecognizer.createOnDeviceSpeechRecognizer`). |
| **Selettore Lingua Dinamico** | Scelta rapida della lingua con pill dedicata in alto: Italiano 🇮🇹, English (US) 🇺🇸, English (UK) 🇬🇧, Español 🇪🇸, Français 🇫🇷, Deutsch 🇩🇪, Português 🇧🇷, Русский 🇷🇺, 中文 🇨🇳, 日本語 🇯🇵. |
| **Sottotitoli Fluttuanti Sovrapposti** | Finestra trasparente e trascinabile che mostra i sottotitoli in tempo reale sopra WhatsApp e qualsiasi altra app (`FloatingSubtitleService`). |
| **Doppio Comportamento Blocco Schermo** | Gestione intelligente a due livelli sul blocco schermo (App a schermo intero vs Bolla sottotitoli). |
| **Parole Personalizzate (Contextual Biasing)** | Dizionario personalizzato: i termini aggiunti vengono iniettati direttamente nel decoder neurale (`EXTRA_BIASING_STRINGS`), migliorando il riconoscimento di nomi propri e termini tecnici. |
| **Feedback Aptico per Parola (Tick)** | Vibrazione ultra-delicata (`EFFECT_TICK`) a ogni parola riconosciuta in tempo reale, per confermare la trascrizione anche senza guardare costantemente lo schermo. |
| **Modalità Tavolo 180° (Flip)** | Ruota la risposta capovolta a 180° per farla leggere comodamente all'interlocutore seduto di fronte. |
| **Sintesi Vocale Offline (TTS)** | Pronuncia vocalmente le risposte digitate utilizzando le voci offline del sistema, adattandosi automaticamente alla lingua selezionata. |

---

## 🔒 Gestione Intelligente del Blocco Schermo

L'applicazione gestisce il blocco schermo in modo ottimale a seconda della modalità in uso:

### 1. Se l'app principale era in primo piano (`MainActivity`):
- Se blocchi il telefono (o si spegne lo schermo) mentre usi l'app a tutto schermo, alla riaccensione dello schermo l'app **rimane immediatamente visibile sopra la schermata di blocco** (`setShowWhenLocked(true)`, `FLAG_SHOW_WHEN_LOCKED`, `FLAG_TURN_SCREEN_ON`).
- **Vantaggio:** Puoi continuare a leggere la trascrizione a schermo intero e usare la tastiera/risposte rapide senza dover inserire ogni volta PIN, sequenza o impronta digitale.
- **Per uscire / sbloccare:** Basta premere il tasto **Indietro** o scorrere verso l'alto per accedere normalmente al resto del telefono.

### 2. Se usavi la bolla fluttuante (`FloatingSubtitleService`):
- Se stavi usando altre app con la bolla attiva e blocchi il telefono, **la bolla NON compare nel blocco schermo** (viene nascosta per non essere invasiva e non bloccare i tasti).
- **Al suo posto compare la notifica in tempo reale nella schermata di blocco** (`NotificationCompat.BigTextStyle`, `VISIBILITY_PUBLIC`).
- **Comportamento:** 
  - La notifica si posiziona in cima alle notifiche sul blocco schermo e trascrive in tempo reale.
  - Le tue altre notifiche (WhatsApp, SMS, ecc.) rimangono perfettamente visibili subito sotto.
  - Dalla notifica del blocco schermo puoi anche premere "Apri App" o "Chiudi" con i pulsanti dedicati.

---

## 📦 Installazione Rapida

Il file APK già compilato e pronto all'uso è disponibile nella sezione **Releases** di GitHub o nella root del progetto:
- **`OpenTranscriber-v1.0.0.apk`**

Basta trasferirlo sul telefono Android e aprirlo per installarlo.

---

## 🛠️ Compilazione da Sorgenti

Per ricompilare l'APK da terminale:

```bash
cd android
.\gradlew.bat assembleDebug
```

L'APK generato si troverà in:
`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 📁 Struttura del Progetto

```
open-transcriber/
├── OpenTranscriber-v1.0.0.apk          # APK nativo compilato pronto all'uso
├── README.md                           # Documentazione completa
└── android/
    ├── app/
    │   └── src/main/
    │       ├── AndroidManifest.xml     # Zero permessi internet (100% offline)
    │       ├── java/com/transcribe/offline/
    │       │   ├── MainActivity.kt               # UI principale, selettore lingua, showWhenLocked, toggle Incognito
    │       │   ├── FloatingSubtitleService.kt    # Servizio overlay e notifica live su blocco schermo
    │       │   ├── TranscriptSessionManager.kt   # Gestore unificato flusso testo e persistenza sincrona
    │       │   ├── CryptoManager.kt              # Crittografia hardware AES-256-GCM (Android KeyStore / TEE)
    │       │   ├── OnDeviceSystemSpeechService.kt# Wrapper On-Device SpeechRecognizer
    │       │   ├── CustomWordManager.kt          # Gestione dizionario parole personalizzate
    │       │   └── AudioVisualizerView.kt        # Indicatore visivo decibel / volume
    │       └── res/
    │           ├── layout/activity_main.xml      # Layout principale con pill lingua e pill Incognito
    │           ├── layout/layout_floating_subtitle.xml # Layout overlay sottotitoli con scroll continuo
    │           └── layout/dialog_dictionary.xml  # Dialog aggiunta/rimozione parole
    ├── build.gradle
    └── gradlew.bat
```

---

## ⚖️ Note Legali e Trademark Disclaimer

Questo progetto open-source è indipendente e non è affiliato, sponsorizzato o approvato da Google LLC. "Live Transcribe" e "Trascrizione Istantanea" sono marchi di Google LLC.
