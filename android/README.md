# App Android Nativa - Trascrizione Istantanea (100% Standalone Offline in Italiano) 📱🇮🇹

Questa è l'applicazione **nativa Android in Kotlin** per la Trascrizione Istantanea, progettata specificamente per comunicare offline in **lingua italiana**.

---

## 🛡️ Massima Privacy & Air-Gap Garantito
- **`android.permission.INTERNET` è deliberatamente OMESSO** dal file `AndroidManifest.xml`.
- Il sistema operativo Android impedisce fisicamente a questa applicazione di aprire qualsiasi connessione di rete o trasmettere dati all'esterno.
- L'app funziona **100% autonoma sul telefono**:
  - Nessuna connessione Wi-Fi necessaria.
  - Nessun dato cellulare o scheda SIM necessaria.
  - Nessun collegamento con il computer.
  - Funziona perfettamente in **Modalità Aereo**.

---

## ✨ Funzionalità per la Comunicazione con la tua Ragazza

1. **Motore Neurale Vocale Offline Italiano (`VoskSpeechService.kt`):**
   - Trascrive l'audio a 16kHz direttamente sul processore del telefono con il modello italiano `vosk-model-small-it-0.22`.
   - Latenza minima (inferiore a 200ms) con anteprima in tempo reale delle parole mentre parli.
2. **Bolla dei Decibel Verde di Google Live Transcribe (`AudioVisualizerView.kt`):**
   - Cerchio verde reattivo che pulsa con il volume della tua voce per confermarle visivamente che stai parlando, e avvisa in giallo se c'è troppo rumore di fondo.
3. **Comunicazione a Due Vie & Modalità Capovolgimento 180° (`MainActivity.kt`):**
   - Cassetto tastiera integrato per permettere alla tua ragazza di scrivere la sua risposta.
   - **Tasto "Capovolgi 180°":** Con un solo tocco, capovolge il testo scritto a testa in giù a caratteri giganti ad alto contrasto. Tu, seduto di fronte a lei al tavolo, puoi leggere immediatamente la sua risposta senza che debba passarti il telefono!
   - **Sintesi Vocale Offline (TTS):** Può leggere la risposta ad alta voce con la voce italiana offline di sistema.
   - **Frasi Rapide in Italiano:** Tasti rapidi a un tocco:
     - *"Sì"*
     - *"No"*
     - *"Grazie!"*
     - *"Puoi ripetere?"*
     - *"Sono sorda, parla chiaro"*
     - *"Dammi un attimo per scrivere"*
     - *"Ho capito"*
4. **Vibrazione Tattile all'Inizio del Parlato:**
   - Il telefono vibra delicatamente appena ricominci a parlare dopo una pausa di silenzio, avvisandola sulla mano di guardare lo schermo.
5. **Schermo Sempre Attivo:**
   - Mantiene il display costantemente acceso durante la conversazione.

---

## 📦 Come Compilare l'APK con il Modello Italiano

### Passo 1: Scarica il modello vocale italiano Vosk (~45MB)
1. Scarica il file zip del modello italiano: [vosk-model-small-it-0.22.zip](https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip).
2. Estrai la cartella zippata.
3. Rinomina la cartella estratta in `model-it`.
4. Copia la cartella `model-it` all'interno di:
   `android/app/src/main/assets/model-it`

*(Nota: La cartella conterrà file come `am`, `conf`, `graph`, `ivector`)*

### Passo 2: Compila l'APK
- **Con Android Studio:**
  1. Apri la cartella `d:\Documents\projects\transcriber\android`.
  2. Clicca su **Build** ➔ **Build Bundle(s) / APK(s)** ➔ **Build APK(s)**.
- **Oppure da terminale (Gradle):**
  ```bash
  cd android
  ./gradlew assembleDebug
  ```

L'APK compilato si troverà in:
`android/app/build/outputs/apk/debug/app-debug.apk`

### Passo 3: Installa sul telefono della tua ragazza
1. Collega il telefono al PC via USB (o invialo tramite Bluetooth).
2. Copia il file `app-debug.apk` sul telefono e toccalo per installarlo.
3. **Fatto!** L'applicazione è ora sul suo telefono, pronta all'uso ovunque senza internet!
