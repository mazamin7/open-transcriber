package com.transcribe.offline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.transcribe.offline.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var customWordManager: CustomWordManager
    private var systemSpeechService: OnDeviceSystemSpeechService? = null
    private var isListening = false
    private var isFlipped = false
    private var isLaunchingFloating = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private var tts: TextToSpeech? = null
    private var lastSpeechTime = 0L
    private val silenceGapThresholdMs = 2500L

    private val fullTranscript = StringBuilder()
    private val RECORD_AUDIO_REQUEST_CODE = 101
    private var lastPartialWordCount = 0

    data class LanguageOption(val code: String, val displayName: String, val flag: String)

    private val supportedLanguages = listOf(
        LanguageOption("it-IT", "Italiano", "🇮🇹"),
        LanguageOption("en-US", "English (US)", "🇺🇸"),
        LanguageOption("en-GB", "English (UK)", "🇬🇧"),
        LanguageOption("es-ES", "Español", "🇪🇸"),
        LanguageOption("fr-FR", "Français", "🇫🇷"),
        LanguageOption("de-DE", "Deutsch", "🇩🇪"),
        LanguageOption("pt-BR", "Português", "🇧🇷"),
        LanguageOption("ru-RU", "Русский", "🇷🇺"),
        LanguageOption("zh-CN", "中文", "🇨🇳"),
        LanguageOption("ja-JP", "日本語", "🇯🇵")
    )
    private var selectedLanguage = "it-IT"

    // Quick Response Chips for Deaf Communication in Italian
    private val quickChips = listOf(
        "Sì",
        "No",
        "Grazie!",
        "Puoi ripetere?",
        "Sono sorda, parla chiaro",
        "Dammi un attimo per scrivere",
        "Ho capito",
        "Puoi parlare più lentamente?"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize local custom words manager
        customWordManager = CustomWordManager(this)

        // Load saved language or default to it-IT
        val prefs = getSharedPreferences("app_language_prefs", Context.MODE_PRIVATE)
        selectedLanguage = prefs.getString("selected_language", "it-IT") ?: "it-IT"
        updateLanguageBadge()

        // Keep screen awake continuously during live conversations
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Allow app to display directly on the lock screen without unlocking
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Initialize Text-To-Speech with selected language voice
        tts = TextToSpeech(this, this)

        // Setup Quick Response Chips
        setupQuickChips()

        // Setup Click Listeners
        setupListeners()

        // Request audio permissions and start listening
        checkPermissionsAndStart()
    }

    private fun setupListeners() {
        binding.btnLanguageSelector.setOnClickListener {
            showLanguageSelectorDialog()
        }

        binding.btnDictionary.setOnClickListener {
            showDictionaryDialog()
        }

        binding.btnFloating.setOnClickListener {
            toggleFloatingSubtitles()
        }

        binding.fabMic.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.btnClear.setOnClickListener {
            fullTranscript.clear()
            binding.tvTranscript.text = ""
            binding.tvInterim.text = ""
            binding.tvEmptyPrompt.visibility = View.VISIBLE
        }

        binding.btnKeyboard.setOnClickListener {
            val isVisible = binding.typeBackCard.visibility == View.VISIBLE
            binding.typeBackCard.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                binding.etTypeBack.requestFocus()
            }
        }

        binding.btnCloseTypeBack.setOnClickListener {
            binding.typeBackCard.visibility = View.GONE
        }

        // 180° Table-Flip Mode: Rotates response upside down for partner facing her across the table
        binding.btnFlip180.setOnClickListener {
            isFlipped = !isFlipped
            binding.flipContainer.rotation = if (isFlipped) 180f else 0f
            binding.btnFlip180.setTextColor(
                if (isFlipped) ContextCompat.getColor(this, R.color.accent_green)
                else ContextCompat.getColor(this, R.color.white)
            )
        }

        // Offline Text-To-Speech Button (Italian)
        binding.btnSpeakTts.setOnClickListener {
            val text = binding.tvTranscript.text.toString().trim()
            if (text.isNotEmpty()) {
                val lastSentence = text.split(".", "?", "!").filter { it.isNotBlank() }.lastOrNull() ?: text
                speakText(lastSentence)
            } else {
                Toast.makeText(this, "Nessun testo da pronunciare.", Toast.LENGTH_SHORT).show()
            }
        }

        // Send reply to transcript stream
        binding.btnSendReply.setOnClickListener {
            val text = binding.etTypeBack.text.toString().trim()
            if (text.isNotEmpty()) {
                appendReplyToTranscript(text)
                speakText(text)
                binding.etTypeBack.setText("")
                binding.typeBackCard.visibility = View.GONE
            }
        }
    }

    private fun setupQuickChips() {
        binding.chipsContainer.removeAllViews()
        for (chipText in quickChips) {
            val button = MaterialButton(this).apply {
                text = chipText
                setTextColor(ContextCompat.getColor(context, R.color.white))
                setBackgroundColor(ContextCompat.getColor(context, R.color.chip_bg))
                strokeColor = ContextCompat.getColorStateList(context, R.color.chip_stroke)
                strokeWidth = 2
                cornerRadius = 40
                textSize = 14f
                setOnClickListener {
                    binding.etTypeBack.setText(chipText)
                    binding.typeBackCard.visibility = View.VISIBLE
                }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 16 }
            binding.chipsContainer.addView(button, params)
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_REQUEST_CODE
            )
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                Toast.makeText(this, "Permesso microfono necessario per la trascrizione.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startListening() {
        startSystemSpeechListening()
    }

    private fun startSystemSpeechListening() {
        systemSpeechService?.stop()
        systemSpeechService = null

        val service = OnDeviceSystemSpeechService(this, object : OnDeviceSystemSpeechService.Listener {
            override fun onPartialResult(hypothesis: String) {
                if (hypothesis.isNotEmpty()) {
                    checkAndTriggerWordHaptic(hypothesis, isFinal = false)
                    binding.tvEmptyPrompt.visibility = View.GONE
                    binding.tvInterim.text = hypothesis
                    scrollToBottom()
                }
            }

            override fun onFinalResult(sentence: String) {
                if (sentence.isNotEmpty()) {
                    checkAndTriggerWordHaptic(sentence, isFinal = true)
                    binding.tvEmptyPrompt.visibility = View.GONE
                    fullTranscript.append(sentence).append(" ")
                    binding.tvTranscript.text = fullTranscript.toString()
                    binding.tvInterim.text = ""
                    scrollToBottom()
                } else {
                    lastPartialWordCount = 0
                }
            }

            override fun onAudioLevel(level: Float) {
                binding.audioVisualizer.setLevel(level)
            }

            override fun onAcousticEvent(event: String) {
                binding.tvSoundEvent.text = event
            }

            override fun onError(error: String, isOfflineUnavailable: Boolean) {
                if (isOfflineUnavailable) {
                    showOfflineSetupDialog(error)
                } else {
                    binding.tvSoundEvent.text = error
                }
            }
        })

        // Set selected language
        service.setLanguage(selectedLanguage)

        // Feed custom vocabulary directly as native hotwords (Contextual Biasing)
        val words = customWordManager.getWords()
        service.setBiasingWords(words)
        service.start()
        systemSpeechService = service

        isListening = true
        binding.fabMic.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        binding.audioVisualizer.setListening(true)

        // Keep CPU awake while transcribing even if screen locks
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "LiveTranscribe::MainWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {}
    }

    private fun stopListening() {
        systemSpeechService?.stop()
        systemSpeechService = null
        isListening = false
        binding.fabMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
        binding.audioVisualizer.setListening(false)
        binding.audioVisualizer.setLevel(0f)
        binding.tvSoundEvent.text = getString(R.string.sound_silence)

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
    }

    private fun showOfflineSetupDialog(errorMsg: String) {
        val currentLangName = supportedLanguages.find { it.code.equals(selectedLanguage, ignoreCase = true) }?.displayName ?: selectedLanguage
        AlertDialog.Builder(this)
            .setTitle("Pacchetto Vocale Offline Google")
            .setMessage("$errorMsg\n\nPer usare il riconoscimento offline senza connessione internet, scarica il pacchetto '$currentLangName' ($selectedLanguage) nelle impostazioni vocali del telefono.\n\nVuoi aprire le impostazioni?")
            .setPositiveButton("Impostazioni Voce") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent("com.google.android.voicesearch.intent.action.OFFLINE_RECOGNITION"))
                    } catch (e2: Exception) {
                        Toast.makeText(this, "Apri Impostazioni > Google > Voce > Riconoscimento vocale offline", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun checkAndTriggerWordHaptic(currentText: String, isFinal: Boolean = false) {
        val trimmed = currentText.trim()
        if (trimmed.isEmpty()) {
            if (isFinal) lastPartialWordCount = 0
            return
        }

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val currentWordCount = words.size

        if (isFinal) {
            if (currentWordCount > lastPartialWordCount) {
                vibrateWordTick()
            }
            lastPartialWordCount = 0
        } else {
            if (currentWordCount > lastPartialWordCount) {
                lastPartialWordCount = currentWordCount
                vibrateWordTick()
            }
        }
    }

    private fun vibrateWordTick() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } catch (e: Exception) {
                    vibrator.vibrate(VibrationEffect.createOneShot(15L, 120))
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(15L, 120))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(15L)
            }
        } catch (e: Exception) {
            // Hardware without vibrator or permission restricted
        }
    }

    private fun appendReplyToTranscript(text: String) {
        binding.tvEmptyPrompt.visibility = View.GONE
        fullTranscript.append("\n[Risposta]: ").append(text).append("\n\n")
        binding.tvTranscript.text = fullTranscript.toString()
        scrollToBottom()
    }

    private fun speakText(text: String) {
        systemSpeechService?.temporaryUnmuteForTts()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "LiveTranscribeReply")
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "LiveTranscribeReply")
    }

    private fun scrollToBottom() {
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateLanguageBadge() {
        val current = supportedLanguages.find { it.code.equals(selectedLanguage, ignoreCase = true) }
            ?: LanguageOption(selectedLanguage, selectedLanguage, "🌐")
        binding.btnLanguageSelector.text = "${current.flag} ${current.displayName}"
    }

    private fun showLanguageSelectorDialog() {
        val options = supportedLanguages.map { "${it.flag}  ${it.displayName} (${it.code})" }.toTypedArray()
        val currentIndex = supportedLanguages.indexOfFirst { it.code.equals(selectedLanguage, ignoreCase = true) }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Scegli Lingua / Language")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val chosen = supportedLanguages[which]
                selectedLanguage = chosen.code
                getSharedPreferences("app_language_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("selected_language", selectedLanguage)
                    .apply()

                updateLanguageBadge()
                updateTtsLanguage(selectedLanguage)
                systemSpeechService?.setLanguage(selectedLanguage)
                Toast.makeText(this, "Lingua: ${chosen.flag} ${chosen.displayName}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun updateTtsLanguage(langTag: String) {
        try {
            val locale = Locale.forLanguageTag(langTag)
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale(locale.language))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            updateTtsLanguage(selectedLanguage)
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    systemSpeechService?.muteAgainAfterTts()
                }
                override fun onError(utteranceId: String?) {
                    systemSpeechService?.muteAgainAfterTts()
                }
            })
        }
    }

    private fun showDictionaryDialog() {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_dictionary, null)
        dialog.setContentView(dialogView)

        val entriesContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.entriesContainer)
        val etWord = dialogView.findViewById<android.widget.EditText>(R.id.etWord)
        val btnAddEntry = dialogView.findViewById<MaterialButton>(R.id.btnAddEntry)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnCloseDialog)

        fun refreshList() {
            entriesContainer.removeAllViews()
            val words = customWordManager.getWords()
            if (words.isEmpty()) {
                val emptyTv = android.widget.TextView(this).apply {
                    text = "Nessuna parola salvata."
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 14f
                    setPadding(16, 24, 16, 24)
                }
                entriesContainer.addView(emptyTv)
                return
            }

            for (word in words) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(16, 12, 16, 12)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val wordTv = android.widget.TextView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = word
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                row.addView(wordTv)

                val btnDelete = android.widget.ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    background = null
                    imageTintList = ContextCompat.getColorStateList(context, R.color.text_secondary)
                    setPadding(12, 12, 12, 12)
                    setOnClickListener {
                        customWordManager.removeWord(word)
                        refreshList()
                        systemSpeechService?.setBiasingWords(customWordManager.getWords())
                    }
                }
                row.addView(btnDelete)
                entriesContainer.addView(row)
            }
        }

        btnAddEntry.setOnClickListener {
            val word = etWord.text.toString().trim()
            if (word.isNotEmpty()) {
                customWordManager.addWord(word)
                etWord.setText("")
                refreshList()
                systemSpeechService?.setBiasingWords(customWordManager.getWords())
                Toast.makeText(this, "'$word' aggiunta!", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        refreshList()
        dialog.show()
    }

    private val OVERLAY_PERMISSION_REQUEST_CODE = 202

    private fun toggleFloatingSubtitles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permesso Sottotitoli Flottanti")
                .setMessage("Per mostrare i sottotitoli in tempo reale sopra WhatsApp e le altre app mentre usi lo smartphone, abilita 'Consenti visualizzazione sopra altre app' nella schermata successiva.")
                .setPositiveButton("Apri Impostazioni") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                }
                .setNegativeButton("Annulla", null)
                .show()
            return
        }

        startFloatingSubtitles()
    }

    private fun startFloatingSubtitles() {
        isLaunchingFloating = true
        stopListening()
        val intent = Intent(this, FloatingSubtitleService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Sottotitoli flottanti attivati sopra lo schermo!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            isLaunchingFloating = false
            Toast.makeText(this, "Errore avvio sottotitoli: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isLaunchingFloating) {
            isLaunchingFloating = false
            return
        }
        stopService(Intent(this, FloatingSubtitleService::class.java))
        binding.root.postDelayed({
            if (!isListening && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListening()
            }
        }, 200)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingSubtitles()
            } else {
                Toast.makeText(this, "Permesso non concesso: impossibile mostrare i sottotitoli sopra le app.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        wakeLock = null
        tts?.stop()
        tts?.shutdown()
    }
}
