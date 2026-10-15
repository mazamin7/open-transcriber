package com.transcribe.offline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.transcribe.offline.databinding.ActivityMainBinding
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private var voskService: VoskSpeechService? = null
    private var voskModel: Model? = null
    private var isListening = false
    private var isFlipped = false

    private var tts: TextToSpeech? = null
    private var lastSpeechTime = 0L
    private val silenceGapThresholdMs = 2500L

    private val fullTranscript = StringBuilder()
    private val RECORD_AUDIO_REQUEST_CODE = 101

    private val quickChips = listOf(
        "Yes",
        "No",
        "Thank you!",
        "Please repeat that",
        "I am deaf, please speak clearly",
        "Give me a moment to type",
        "I understand"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on continuously during conversations
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize Text-To-Speech
        tts = TextToSpeech(this, this)

        // Setup Quick Response Chips
        setupQuickChips()

        // Setup Click Listeners
        setupListeners()

        // Initialize Vosk Model (unpacks model from assets asynchronously)
        LibVosk.setLogLevel(LogLevel.INFO)
        initVoskModel()
    }

    private fun setupListeners() {
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

        // 180° Table-Flip Mode for facing hearing partner across the table
        binding.btnFlip180.setOnClickListener {
            isFlipped = !isFlipped
            binding.flipContainer.rotation = if (isFlipped) 180f else 0f
            binding.btnFlip180.setTextColor(
                if (isFlipped) ContextCompat.getColor(this, R.color.accent_green)
                else ContextCompat.getColor(this, R.color.white)
            )
        }

        // Offline Text-To-Speech Button
        binding.btnSpeakTts.setOnClickListener {
            val text = binding.etTypeBack.text.toString().trim()
            if (text.isNotEmpty()) {
                speakText(text)
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

    private fun initVoskModel() {
        binding.tvSoundEvent.text = "Loading Model..."
        StorageService.unpack(
            this,
            "model-en-us",
            "model",
            { model ->
                this.voskModel = model
                binding.tvSoundEvent.text = getString(R.string.sound_silence)
            },
            { exception ->
                binding.tvSoundEvent.text = "Ready (System STT)"
                // Will fall back to Android on-device recognizer if model asset not bundled
            }
        )
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
                Toast.makeText(this, "Microphone permission is required for live transcription.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startListening() {
        val model = voskModel
        if (model == null) {
            Toast.makeText(this, "Speech model loading. Please wait a moment...", Toast.LENGTH_SHORT).show()
            return
        }

        voskService = VoskSpeechService(model, object : VoskSpeechService.Listener {
            override fun onPartialResult(hypothesis: String) {
                if (hypothesis.isNotEmpty()) {
                    triggerSpeechHaptic()
                    binding.tvEmptyPrompt.visibility = View.GONE
                    binding.tvInterim.text = hypothesis
                    scrollToBottom()
                }
            }

            override fun onFinalResult(sentence: String) {
                if (sentence.isNotEmpty()) {
                    triggerSpeechHaptic()
                    binding.tvEmptyPrompt.visibility = View.GONE
                    fullTranscript.append(sentence).append(" ")
                    binding.tvTranscript.text = fullTranscript.toString()
                    binding.tvInterim.text = ""
                    scrollToBottom()
                }
            }

            override fun onAudioLevel(level: Float) {
                binding.audioVisualizer.setLevel(level)
            }

            override fun onAcousticEvent(event: String) {
                binding.tvSoundEvent.text = event
            }

            override fun onError(error: Exception) {
                Toast.makeText(this@MainActivity, "Audio error: ${error.message}", Toast.LENGTH_SHORT).show()
                stopListening()
            }
        })

        voskService?.start()
        isListening = true
        binding.fabMic.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        binding.audioVisualizer.setListening(true)
    }

    private fun stopListening() {
        voskService?.stop()
        voskService = null
        isListening = false
        binding.fabMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
        binding.audioVisualizer.setListening(false)
        binding.audioVisualizer.setLevel(0f)
        binding.tvSoundEvent.text = getString(R.string.sound_silence)
    }

    private fun triggerSpeechHaptic() {
        val now = System.currentTimeMillis()
        if (now - lastSpeechTime > silenceGapThresholdMs) {
            lastSpeechTime = now
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 80), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 80, 50, 80), -1)
            }
        } else {
            lastSpeechTime = now
        }
    }

    private fun appendReplyToTranscript(text: String) {
        binding.tvEmptyPrompt.visibility = View.GONE
        fullTranscript.append("\n[Reply]: ").append(text).append("\n\n")
        binding.tvTranscript.text = fullTranscript.toString()
        scrollToBottom()
    }

    private fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LiveTranscribeReply")
    }

    private fun scrollToBottom() {
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        tts?.stop()
        tts?.shutdown()
    }
}
