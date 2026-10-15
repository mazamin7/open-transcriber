package com.transcribe.offline

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.sqrt

/**
 * Air-Gapped On-Device Speech Recognition Engine using Vosk / Kaldi.
 * Runs 100% locally on the phone's CPU with zero internet or server connectivity.
 */
class VoskSpeechService(
    private val model: Model,
    private val listener: Listener
) {

    interface Listener {
        fun onPartialResult(hypothesis: String)
        fun onFinalResult(sentence: String)
        fun onAudioLevel(level: Float)
        fun onAcousticEvent(event: String)
        fun onError(error: Exception)
    }

    private var audioRecord: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRunning: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sampleRate = 16000

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        try {
            recognizer = Recognizer(model, sampleRate.toFloat())

            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()
            isRunning = true

            recordingThread = Thread({
                val buffer = ShortArray(1024)
                while (isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // Calculate RMS level
                        var sum = 0.0
                        for (i in 0 until read) {
                            val sample = buffer[i] / 32768.0
                            sum += sample * sample
                        }
                        val rms = sqrt(sum / read).toFloat()
                        val normalizedLevel = (rms * 2.5f).coerceIn(0f, 1f)

                        // Sound classification
                        val event = when {
                            rms < 0.02f -> "Silence"
                            rms > 0.70f -> "Loud Noise"
                            else -> "Speech"
                        }

                        mainHandler.post {
                            listener.onAudioLevel(normalizedLevel)
                            listener.onAcousticEvent(event)
                        }

                        // Feed to Vosk recognizer
                        val rec = recognizer ?: break
                        if (rec.acceptWaveForm(buffer, read)) {
                            val jsonResult = rec.result
                            val text = parseTextFromJson(jsonResult)
                            if (text.isNotEmpty()) {
                                mainHandler.post { listener.onFinalResult(text) }
                            }
                        } else {
                            val jsonPartial = rec.partialResult
                            val partialText = parsePartialFromJson(jsonPartial)
                            mainHandler.post { listener.onPartialResult(partialText) }
                        }
                    }
                }
            }, "VoskAudioThread").apply { start() }

        } catch (e: Exception) {
            stop()
            listener.onError(e)
        }
    }

    fun stop() {
        isRunning = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        recognizer?.close()
        recognizer = null

        mainHandler.post {
            listener.onAudioLevel(0f)
            listener.onAcousticEvent("Silence")
        }
    }

    private fun parseTextFromJson(jsonStr: String): String {
        return try {
            JSONObject(jsonStr).optString("text", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun parsePartialFromJson(jsonStr: String): String {
        return try {
            JSONObject(jsonStr).optString("partial", "").trim()
        } catch (e: Exception) {
            ""
        }
    }
}
