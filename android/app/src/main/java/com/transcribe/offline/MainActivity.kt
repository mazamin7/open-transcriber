package com.transcribe.offline

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.transcribe.offline.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, TranscriptSessionManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var customWordManager: CustomWordManager
    private var systemSpeechService: OnDeviceSystemSpeechService? = null
    private var isListening = false
    private var isFlipped = false
    private var isLaunchingFloating = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private var hasCheckedNotificationsOnStartup = false
    private var isAwaitingNotificationPermission = false
    private var wasRequestingNotificationsForFloating = false

    private var tts: TextToSpeech? = null
    private var lastSpeechTime = 0L
    private val silenceGapThresholdMs = 2500L

    private val RECORD_AUDIO_REQUEST_CODE = 101
    private var lastPartialWordCount = 0

    private var installedLanguages = mutableListOf<LanguageModelManager.LanguageInfo>()
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

        // Initialize shared transcript session and register for real-time updates
        TranscriptSessionManager.init(this)
        TranscriptSessionManager.registerListener(this)
        syncTranscriptUi()

        // Ensure notification channel is registered in system settings
        createNotificationChannelIfNeeded()

        // Load detected installed languages from cache and start background scan
        installedLanguages = LanguageModelManager.getCachedInstalledLanguages(this).toMutableList()
        val prefs = getSharedPreferences("app_language_prefs", Context.MODE_PRIVATE)
        selectedLanguage = prefs.getString("selected_language", "it-IT") ?: "it-IT"
        updateLanguageBadge()
        refreshInstalledLanguages()

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

        binding.btnIncognito.setOnClickListener {
            showIncognitoToggleDialog()
        }

        binding.btnDictionary.setOnClickListener {
            showDictionaryDialog()
        }

        binding.btnFloating.setOnClickListener {
            toggleFloatingSubtitles()
        }

        binding.btnFloating.setOnLongClickListener {
            if (!areNotificationsEnabled()) {
                promptNotificationPermissionDialog(forFloating = false)
            } else {
                checkBatteryOptimizationPermission()
            }
            true
        }

        binding.fabMic.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.btnClear.setOnClickListener {
            TranscriptSessionManager.clear(this)
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
            checkNotificationPermissionOnStartup()
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
                checkNotificationPermissionOnStartup()
            } else {
                Toast.makeText(this, "Permesso microfono necessario per la trascrizione.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startListening() {
        if (FloatingSubtitleService.isRunning) {
            isListening = true
            binding.fabMic.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
            binding.audioVisualizer.setListening(true)
            val inAppIntent = Intent(this, FloatingSubtitleService::class.java).apply {
                action = FloatingSubtitleService.ACTION_SET_IN_APP_FOREGROUND
                putExtra(FloatingSubtitleService.EXTRA_IN_APP, true)
            }
            startService(inAppIntent)
            return
        }

        if (areNotificationsEnabled()) {
            val serviceIntent = Intent(this, FloatingSubtitleService::class.java).apply {
                action = FloatingSubtitleService.ACTION_SET_IN_APP_FOREGROUND
                putExtra(FloatingSubtitleService.EXTRA_IN_APP, true)
            }
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
                isListening = true
                binding.fabMic.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
                binding.audioVisualizer.setListening(true)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        startSystemSpeechListening()
    }

    private fun startSystemSpeechListening() {
        systemSpeechService?.stop()
        systemSpeechService = null

        val service = OnDeviceSystemSpeechService(this, object : OnDeviceSystemSpeechService.Listener {
            override fun onPartialResult(hypothesis: String) {
                if (hypothesis.isNotEmpty()) {
                    checkAndTriggerWordHaptic(hypothesis, isFinal = false)
                    TranscriptSessionManager.setInterim(hypothesis)
                    binding.tvEmptyPrompt.visibility = View.GONE
                    binding.tvInterim.text = hypothesis
                    scrollToBottom()
                }
            }

            override fun onFinalResult(sentence: String) {
                if (sentence.isNotEmpty()) {
                    checkAndTriggerWordHaptic(sentence, isFinal = true)
                    TranscriptSessionManager.appendSentence(sentence, this@MainActivity)
                    binding.tvEmptyPrompt.visibility = View.GONE
                    binding.tvTranscript.text = TranscriptSessionManager.getFullTranscript()
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
                wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "OpenTranscribe::MainWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {}
    }

    private fun stopListening() {
        if (FloatingSubtitleService.isRunning) {
            val stopIntent = Intent(this, FloatingSubtitleService::class.java).apply {
                action = FloatingSubtitleService.ACTION_STOP_SERVICE
            }
            startService(stopIntent)
        }
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
        val current = installedLanguages.find { it.code.equals(selectedLanguage, ignoreCase = true) }
            ?: LanguageModelManager.getLanguageInfo(selectedLanguage)
        val note = if (errorMsg.isNotBlank()) "\n\n$errorMsg" else ""
        AlertDialog.Builder(this)
            .setTitle("Modulo Vocale Offline Mancante")
            .setMessage("Il pacchetto offline per ${current.flag} ${current.displayName} (${current.code}) non è ancora installato sul dispositivo.$note\n\nPer usarlo senza connessione internet, scarica il modulo nelle impostazioni vocali del telefono.")
            .setPositiveButton("Scarica Pacchetto") { _, _ ->
                val opened = LanguageModelManager.openOfflineSpeechSettings(this)
                if (!opened) {
                    Toast.makeText(this, "Apri Impostazioni di sistema > Voce > Riconoscimento vocale offline", Toast.LENGTH_LONG).show()
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
        TranscriptSessionManager.appendReply(text, this)
        binding.tvEmptyPrompt.visibility = View.GONE
        binding.tvTranscript.text = TranscriptSessionManager.getFullTranscript()
        scrollToBottom()
    }

    private fun speakText(text: String) {
        systemSpeechService?.temporaryUnmuteForTts()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "OpenTranscribeReply")
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "OpenTranscribeReply")
    }

    private fun scrollToBottom() {
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateLanguageBadge() {
        val current = installedLanguages.find { it.code.equals(selectedLanguage, ignoreCase = true) }
            ?: LanguageModelManager.getLanguageInfo(selectedLanguage)
        val shortName = Locale.forLanguageTag(current.code).displayLanguage.replaceFirstChar { it.uppercase() }
        binding.btnLanguageSelector.text = "${current.flag} ${shortName.ifBlank { current.displayName }}"
    }

    private fun refreshInstalledLanguages() {
        LanguageModelManager.detectInstalledLanguages(this) { installed, _ ->
            if (installed.isNotEmpty()) {
                installedLanguages = installed.toMutableList()
                updateLanguageBadge()
            }
        }
    }

    private fun showLanguageSelectorDialog() {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_language_selector, null)
        dialog.setContentView(dialogView)

        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnCloseLanguageDialog)
        val btnOpenSettings = dialogView.findViewById<MaterialButton>(R.id.btnOpenSpeechSettings)
        val layoutScanning = dialogView.findViewById<android.widget.LinearLayout>(R.id.layoutScanning)
        val containerInstalled = dialogView.findViewById<android.widget.LinearLayout>(R.id.installedLanguagesContainer)
        val containerDownloadable = dialogView.findViewById<android.widget.LinearLayout>(R.id.downloadableLanguagesContainer)

        fun renderInstalled(installed: List<LanguageModelManager.LanguageInfo>) {
            containerInstalled?.removeAllViews()
            if (installed.isEmpty()) {
                val tvEmpty = android.widget.TextView(this).apply {
                    text = "Nessun modulo offline installato."
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 13f
                    setPadding(16, 20, 16, 20)
                }
                containerInstalled?.addView(tvEmpty)
                return
            }

            for (lang in installed) {
                val isSelected = lang.code.equals(selectedLanguage, ignoreCase = true)
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(20, 16, 20, 16)
                    background = ContextCompat.getDrawable(
                        context,
                        if (isSelected) R.drawable.bg_event_pill else android.R.color.transparent
                    )
                    isClickable = true
                    isFocusable = true
                }

                val tvFlag = android.widget.TextView(this).apply {
                    text = lang.flag
                    textSize = 22f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 14 }
                }
                row.addView(tvFlag)

                val col = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                val tvName = android.widget.TextView(this).apply {
                    text = lang.displayName
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvCode = android.widget.TextView(this).apply {
                    text = "${lang.code} • Modulo Offline Pronto"
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 11f
                }
                col.addView(tvName)
                col.addView(tvCode)
                row.addView(col)

                val tvBadge = android.widget.TextView(this).apply {
                    text = if (isSelected) "ATTIVA ✓" else "Attiva"
                    setTextColor(
                        if (isSelected) ContextCompat.getColor(context, R.color.accent_green)
                        else ContextCompat.getColor(context, R.color.white)
                    )
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(10, 6, 10, 6)
                }
                row.addView(tvBadge)

                row.setOnClickListener {
                    selectedLanguage = lang.code
                    getSharedPreferences("app_language_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("selected_language", selectedLanguage)
                        .apply()

                    updateLanguageBadge()
                    updateTtsLanguage(selectedLanguage)
                    systemSpeechService?.setLanguage(selectedLanguage)

                    if (isListening) {
                        stopListening()
                        binding.root.postDelayed({ startListening() }, 150)
                    }

                    Toast.makeText(this@MainActivity, "Lingua attiva: ${lang.flag} ${lang.displayName}", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }

                containerInstalled?.addView(row)

                val divider = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply { setMargins(16, 0, 16, 0) }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.surface_border))
                }
                containerInstalled?.addView(divider)
            }
        }

        fun renderDownloadable(downloadable: List<LanguageModelManager.LanguageInfo>) {
            containerDownloadable?.removeAllViews()
            if (downloadable.isEmpty()) {
                val tvEmpty = android.widget.TextView(this).apply {
                    text = "Tutte le lingue supportate risultano già installate sul dispositivo."
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 13f
                    setPadding(16, 20, 16, 20)
                }
                containerDownloadable?.addView(tvEmpty)
                return
            }

            for (lang in downloadable) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(20, 14, 20, 14)
                }

                val tvFlag = android.widget.TextView(this).apply {
                    text = lang.flag
                    textSize = 22f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 14 }
                }
                row.addView(tvFlag)

                val col = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                val tvName = android.widget.TextView(this).apply {
                    text = lang.displayName
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val tvCode = android.widget.TextView(this).apply {
                    text = "${lang.code} • Non ancora scaricata"
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 11f
                }
                col.addView(tvName)
                col.addView(tvCode)
                row.addView(col)

                val btnDownload = com.google.android.material.button.MaterialButton(this).apply {
                    text = "Scarica 📥"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.black))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.accent_green))
                    cornerRadius = 8
                    setPadding(12, 4, 12, 4)
                }

                btnDownload.setOnClickListener {
                    btnDownload.isEnabled = false
                    btnDownload.text = "Avvio... ⏳"
                    LanguageModelManager.triggerModelDownload(
                        this@MainActivity,
                        lang.code,
                        onProgress = { percent ->
                            runOnUiThread {
                                btnDownload.text = "$percent% ⏳"
                            }
                        },
                        onComplete = { success, message ->
                            runOnUiThread {
                                if (success) {
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                    LanguageModelManager.detectInstalledLanguages(this@MainActivity) { newInstalled, newDownloadable ->
                                        installedLanguages = newInstalled.toMutableList()
                                        renderInstalled(installedLanguages)
                                        renderDownloadable(newDownloadable)
                                        updateLanguageBadge()
                                    }
                                } else {
                                    btnDownload.isEnabled = true
                                    btnDownload.text = "Scarica 📥"
                                    showManualDownloadGuideDialog(lang)
                                }
                            }
                        }
                    )
                }

                row.addView(btnDownload)
                containerDownloadable?.addView(row)

                val divider = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply { setMargins(16, 0, 16, 0) }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.surface_border))
                }
                containerDownloadable?.addView(divider)
            }
        }

        renderInstalled(installedLanguages)

        layoutScanning?.visibility = View.VISIBLE
        LanguageModelManager.detectInstalledLanguages(this) { installed, downloadable ->
            layoutScanning?.visibility = View.GONE
            if (installed.isNotEmpty()) {
                installedLanguages = installed.toMutableList()
                renderInstalled(installedLanguages)
                updateLanguageBadge()
            }
            renderDownloadable(downloadable)
        }

        btnOpenSettings?.setOnClickListener {
            showManualDownloadGuideDialog(LanguageModelManager.getLanguageInfo(selectedLanguage))
        }

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showManualDownloadGuideDialog(lang: LanguageModelManager.LanguageInfo) {
        AlertDialog.Builder(this)
            .setTitle("Come Scaricare il Modulo: ${lang.flag} ${lang.displayName}")
            .setMessage("Nelle impostazioni di sistema Android:\n\n1. Tocca 'Apri Impostazioni'.\n2. Nella schermata 'Tastiera su schermo', seleziona 'Gboard' o 'Digitazione vocale Google' (icona ⚙️).\n3. Tocca 'Voce' o 'Riconoscimento vocale offline'.\n4. Nella scheda 'TUTTO', cerca '${lang.displayName}' e tocca Scarica.")
            .setPositiveButton("Apri Impostazioni") { _, _ ->
                val opened = LanguageModelManager.openOfflineSpeechSettings(this)
                if (!opened) {
                    Toast.makeText(this, "Apri Impostazioni > Sistema > Lingue e tastiere > Riconoscimento vocale offline", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Chiudi", null)
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

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                FloatingSubtitleService.CHANNEL_ID,
                "Open Transcriber - Trascrizione Live",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Mostra la trascrizione vocale in tempo reale sempre in alto nella barra notifiche e nel blocco schermo"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun areNotificationsEnabled(): Boolean {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = manager?.getNotificationChannel(FloatingSubtitleService.CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    private fun checkNotificationPermissionOnStartup() {
        if (hasCheckedNotificationsOnStartup) return
        hasCheckedNotificationsOnStartup = true

        if (!areNotificationsEnabled()) {
            promptNotificationPermissionDialog(forFloating = false)
        } else {
            checkBatteryOptimizationPermission()
        }
    }

    private fun checkBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Notifica Permanente e Continua 🔋")
                    .setMessage("Per rendere la notifica fissa e permanente al 100% (evitando che Android cancelli la notifica o sospenda l'ascolto quando blocchi lo schermo o cambi app), imposta la batteria su 'Senza restrizioni'.")
                    .setPositiveButton("Imposta Senza Restrizioni") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (e2: Exception) {}
                        }
                    }
                    .setNegativeButton("Più tardi", null)
                    .show()
            }
        }
    }

    private fun promptNotificationPermissionDialog(forFloating: Boolean) {
        if (forFloating) {
            wasRequestingNotificationsForFloating = true
        }
        isAwaitingNotificationPermission = true

        AlertDialog.Builder(this)
            .setTitle("Autorizzazione Notifiche")
            .setMessage("Per mostrare la trascrizione vocale e i sottotitoli in tempo reale sulla schermata di blocco o quando l'app è in background, è necessario autorizzare le notifiche dalle Impostazioni di sistema.\n\nPremi 'Apri Impostazioni' e attiva l'interruttore delle notifiche per continuare.")
            .setPositiveButton("Apri Impostazioni") { _, _ ->
                openNotificationSettings()
            }
            .setNegativeButton("Più tardi") { dialog, _ ->
                isAwaitingNotificationPermission = false
                wasRequestingNotificationsForFloating = false
                dialog.dismiss()
            }
            .show()
    }

    private fun openNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Impossibile aprire le impostazioni: ${ex.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

        if (!areNotificationsEnabled()) {
            promptNotificationPermissionDialog(forFloating = true)
            return
        }

        startFloatingSubtitles()
    }

    private fun startFloatingSubtitles() {
        isLaunchingFloating = true
        stopListening()
        val intent = Intent(this, FloatingSubtitleService::class.java).apply {
            action = FloatingSubtitleService.ACTION_SHOW_BUBBLE
        }
        try {
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Sottotitoli flottanti attivati sopra lo schermo!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            isLaunchingFloating = false
            Toast.makeText(this, "Errore avvio sottotitoli: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onTranscriptChanged(fullText: String, interimText: String) {
        runOnUiThread {
            val isIncognito = TranscriptSessionManager.isIncognitoMode()
            if (fullText.isNotEmpty()) {
                binding.tvEmptyPrompt.visibility = View.GONE
                binding.tvTranscript.text = fullText
            } else {
                binding.tvTranscript.text = ""
                binding.tvEmptyPrompt.visibility = if (interimText.isEmpty() && !isIncognito) View.VISIBLE else View.GONE
            }
            binding.tvInterim.text = interimText
            scrollToBottom()
        }
    }

    override fun onIncognitoChanged(isIncognito: Boolean) {
        runOnUiThread {
            if (isIncognito) {
                binding.btnIncognito.text = "🕶️ Incognito"
                binding.btnIncognito.setTextColor(Color.parseColor("#FFD54F"))
                binding.incognitoWatermark.visibility = View.VISIBLE
                window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                binding.btnIncognito.text = "🔒 Criptato"
                binding.btnIncognito.setTextColor(ContextCompat.getColor(this, R.color.white))
                binding.incognitoWatermark.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    override fun onAudioLevel(level: Float) {
        runOnUiThread {
            binding.audioVisualizer.setLevel(level)
        }
    }

    override fun onAcousticEvent(event: String) {
        runOnUiThread {
            binding.tvSoundEvent.text = event
        }
    }

    private fun showIncognitoToggleDialog() {
        val currentlyIncognito = TranscriptSessionManager.isIncognitoMode()
        if (!currentlyIncognito) {
            AlertDialog.Builder(this)
                .setTitle("Attiva Modalità Incognito 🕶️")
                .setMessage("In Modalità Incognito:\n\n• La conversazione persistente viene nascosta e protetta.\n• Le nuove trascrizioni rimangono SOLO nella memoria volatile (RAM) e non vengono salvate su disco.\n• Vengono bloccati screenshot e registrazioni dello schermo.\n• Viene mostrato il simbolo incognito in trasparenza.")
                .setPositiveButton("Attiva Incognito") { _, _ ->
                    TranscriptSessionManager.setIncognitoMode(true, this)
                    Toast.makeText(this, "Modalità Incognito ATTIVA", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annulla", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Disattiva Modalità Incognito 🔒")
                .setMessage("Vuoi tornare alla Modalità Criptata?\n\nLa sessione incognito temporanea svanirà e verrà ripristinata la tua cronologia salvata con crittografia hardware AES-256 (Android KeyStore).")
                .setPositiveButton("Ripristina Cronologia Criptata") { _, _ ->
                    TranscriptSessionManager.setIncognitoMode(false, this)
                    Toast.makeText(this, "Cronologia Criptata AES-256 RIPRISTINATA", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }

    private fun syncTranscriptUi() {
        TranscriptSessionManager.init(this)
        onTranscriptChanged(
            TranscriptSessionManager.getFullTranscript(),
            TranscriptSessionManager.getInterim()
        )
        onIncognitoChanged(TranscriptSessionManager.isIncognitoMode())
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        syncTranscriptUi()
    }

    override fun onResume() {
        super.onResume()
        refreshInstalledLanguages()
        if (isLaunchingFloating) {
            isLaunchingFloating = false
            return
        }

        // Synchronize and restore live transcript from floating subtitles session
        syncTranscriptUi()

        if (isAwaitingNotificationPermission) {
            isAwaitingNotificationPermission = false
            if (areNotificationsEnabled()) {
                Toast.makeText(this, "Notifiche autorizzate con successo!", Toast.LENGTH_SHORT).show()
                checkBatteryOptimizationPermission()
                if (wasRequestingNotificationsForFloating) {
                    wasRequestingNotificationsForFloating = false
                    startFloatingSubtitles()
                    return
                }
            } else {
                if (wasRequestingNotificationsForFloating) {
                    Toast.makeText(this, "Notifiche non autorizzate: i sottotitoli sul blocco schermo non saranno visibili.", Toast.LENGTH_LONG).show()
                }
            }
            wasRequestingNotificationsForFloating = false
        }

        if (FloatingSubtitleService.isRunning) {
            val inAppIntent = Intent(this, FloatingSubtitleService::class.java).apply {
                action = FloatingSubtitleService.ACTION_SET_IN_APP_FOREGROUND
                putExtra(FloatingSubtitleService.EXTRA_IN_APP, true)
            }
            startService(inAppIntent)
            isListening = true
            binding.fabMic.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
            binding.audioVisualizer.setListening(true)
        } else {
            binding.root.postDelayed({
                if (!isListening && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startListening()
                }
            }, 200)
        }
    }

    override fun onPause() {
        super.onPause()
        if (FloatingSubtitleService.isRunning) {
            val outOfAppIntent = Intent(this, FloatingSubtitleService::class.java).apply {
                action = FloatingSubtitleService.ACTION_SET_IN_APP_FOREGROUND
                putExtra(FloatingSubtitleService.EXTRA_IN_APP, false)
            }
            startService(outOfAppIntent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                if (!areNotificationsEnabled()) {
                    promptNotificationPermissionDialog(forFloating = true)
                } else {
                    startFloatingSubtitles()
                }
            } else {
                Toast.makeText(this, "Permesso non concesso: impossibile mostrare i sottotitoli sopra le app.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        TranscriptSessionManager.unregisterListener(this)
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
