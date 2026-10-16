package com.transcribe.offline

import android.content.Context

/**
 * Shared in-memory and hardware-encrypted disk-persistent conversation transcript session manager.
 * Single source of truth for the entire conversation history.
 * Ensures the app and the floating bubble share the EXACT same continuous text stream.
 * Persists across app closures and phone reboots using AES-256-GCM hardware encryption.
 * Supports Incognito Mode (zero disk writes, volatile RAM only).
 */
object TranscriptSessionManager {
    private val transcriptBuilder = StringBuilder()
    private var lastInterimText = ""
    private const val PREFS_NAME = "transcript_persistent_storage"
    private const val KEY_ENCRYPTED_TRANSCRIPT = "encrypted_transcript_aes256"
    private const val KEY_INCOGNITO_MODE = "is_incognito_mode_active"
    private var appContext: Context? = null
    private var isInitialized = false
    private var isIncognito = false

    interface Listener {
        fun onTranscriptChanged(fullText: String, interimText: String)
        fun onIncognitoChanged(isIncognito: Boolean) {}
    }

    private val listeners = mutableListOf<Listener>()

    @Synchronized
    fun registerListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.onTranscriptChanged(transcriptBuilder.toString(), lastInterimText)
        listener.onIncognitoChanged(isIncognito)
    }

    @Synchronized
    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (!isInitialized) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                isIncognito = prefs.getBoolean(KEY_INCOGNITO_MODE, false)

                // Only load saved transcript from disk if not in incognito mode
                if (!isIncognito) {
                    val encrypted = prefs.getString(KEY_ENCRYPTED_TRANSCRIPT, "") ?: ""
                    if (encrypted.isNotEmpty() && transcriptBuilder.isEmpty()) {
                        val decrypted = CryptoManager.decrypt(encrypted)
                        if (decrypted.isNotEmpty()) {
                            transcriptBuilder.append(decrypted)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isInitialized = true
        }
    }

    @Synchronized
    fun isIncognitoMode(): Boolean = isIncognito

    @Synchronized
    fun setIncognitoMode(active: Boolean, context: Context? = null) {
        isIncognito = active
        val ctx = context?.applicationContext ?: appContext
        ctx?.let {
            try {
                it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_INCOGNITO_MODE, active)
                    .commit()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (!active) {
            // Persist current memory buffer when leaving incognito mode
            persist()
        }
        notifyIncognitoChanged()
    }

    @Synchronized
    fun getFullTranscript(): String {
        return transcriptBuilder.toString()
    }

    @Synchronized
    fun appendSentence(sentence: String, context: Context? = null) {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return

        if (context != null && appContext == null) {
            appContext = context.applicationContext
        }

        if (transcriptBuilder.isNotEmpty() && !transcriptBuilder.endsWith(" ") && !transcriptBuilder.endsWith("\n")) {
            transcriptBuilder.append(" ")
        }
        transcriptBuilder.append(trimmed).append(" ")
        lastInterimText = ""

        persist()
        notifyListeners()
    }

    @Synchronized
    fun appendReply(replyText: String, context: Context? = null) {
        val trimmed = replyText.trim()
        if (trimmed.isEmpty()) return

        if (context != null && appContext == null) {
            appContext = context.applicationContext
        }

        if (transcriptBuilder.isNotEmpty() && !transcriptBuilder.endsWith("\n")) {
            transcriptBuilder.append("\n")
        }
        transcriptBuilder.append("[Risposta]: ").append(trimmed).append("\n\n")
        lastInterimText = ""

        persist()
        notifyListeners()
    }

    @Synchronized
    fun setInterim(text: String) {
        val trimmed = text.trim()
        if (lastInterimText != trimmed) {
            lastInterimText = trimmed
            notifyListeners()
        }
    }

    @Synchronized
    fun getInterim(): String {
        return lastInterimText
    }

    @Synchronized
    fun commitInterimIfPresent(context: Context? = null) {
        if (lastInterimText.isNotBlank()) {
            val pending = lastInterimText
            lastInterimText = ""
            appendSentence(pending, context)
        }
    }

    @Synchronized
    fun clear(context: Context? = null) {
        transcriptBuilder.clear()
        lastInterimText = ""
        val ctx = context?.applicationContext ?: appContext
        ctx?.let {
            try {
                it.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_ENCRYPTED_TRANSCRIPT)
                    .commit()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        notifyListeners()
    }

    private fun persist() {
        // In Incognito Mode, NEVER write to disk! Volatile RAM only.
        if (isIncognito) return

        appContext?.let { ctx ->
            try {
                val plain = transcriptBuilder.toString()
                if (plain.isEmpty()) {
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(KEY_ENCRYPTED_TRANSCRIPT)
                        .commit()
                } else {
                    // Encrypt using hardware-backed AES-256-GCM before writing to storage
                    val cipherTextBase64 = CryptoManager.encrypt(plain)
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_ENCRYPTED_TRANSCRIPT, cipherTextBase64)
                        .commit()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun notifyListeners() {
        val full = transcriptBuilder.toString()
        val interim = lastInterimText
        val copy = ArrayList(listeners)
        for (listener in copy) {
            try {
                listener.onTranscriptChanged(full, interim)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun notifyIncognitoChanged() {
        val active = isIncognito
        val copy = ArrayList(listeners)
        for (listener in copy) {
            try {
                listener.onIncognitoChanged(active)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
