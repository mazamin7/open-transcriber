package com.transcribe.offline

import android.content.Context

/**
 * Shared in-memory and hardware-encrypted disk-persistent conversation transcript session manager.
 * Single source of truth for the entire conversation history.
 * Ensures the app and the floating bubble share the EXACT same continuous text stream.
 * Persists across app closures and phone reboots using AES-256-GCM hardware encryption.
 * Supports strictly isolated Incognito Mode (zero disk writes, volatile RAM only).
 * Switching between encrypted and incognito mode cleanly separates and hides the respective buffers.
 */
object TranscriptSessionManager {
    // Persistent disk-backed buffer (AES-256 encrypted)
    private val persistentBuilder = StringBuilder()
    // Volatile RAM-only incognito buffer (zero disk writes, wiped on exit/reboot)
    private val incognitoBuilder = StringBuilder()

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
        fun onAudioLevel(level: Float) {}
        fun onAcousticEvent(event: String) {}
    }

    private val listeners = mutableListOf<Listener>()

    private fun getActiveBuilder(): StringBuilder {
        return if (isIncognito) incognitoBuilder else persistentBuilder
    }

    @Synchronized
    fun registerListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.onTranscriptChanged(getActiveBuilder().toString(), lastInterimText)
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

                // Always restore the persistent encrypted transcript from disk
                val encrypted = prefs.getString(KEY_ENCRYPTED_TRANSCRIPT, "") ?: ""
                if (encrypted.isNotEmpty() && persistentBuilder.isEmpty()) {
                    val decrypted = CryptoManager.decrypt(encrypted)
                    if (decrypted.isNotEmpty()) {
                        persistentBuilder.append(decrypted)
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
        if (isIncognito == active) return

        val ctx = context?.applicationContext ?: appContext
        isIncognito = active

        if (active) {
            // ENTERING INCOGNITO:
            // Do NOT show what was in persistent!
            // Start fresh with an empty volatile buffer.
            incognitoBuilder.clear()
            lastInterimText = ""
        } else {
            // EXITING INCOGNITO BACK TO PERSISTENT:
            // Do NOT show what was in incognito!
            // Wipe volatile incognito buffer completely.
            incognitoBuilder.clear()
            lastInterimText = ""

            // Ensure persistentBuilder has the saved encrypted transcript from disk
            if (persistentBuilder.isEmpty() && ctx != null) {
                try {
                    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val encrypted = prefs.getString(KEY_ENCRYPTED_TRANSCRIPT, "") ?: ""
                    if (encrypted.isNotEmpty()) {
                        val decrypted = CryptoManager.decrypt(encrypted)
                        if (decrypted.isNotEmpty()) {
                            persistentBuilder.append(decrypted)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

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

        // Notify all UI listeners of the buffer change and incognito status
        notifyListeners()
        notifyIncognitoChanged()
    }

    @Synchronized
    fun getFullTranscript(): String {
        return getActiveBuilder().toString()
    }

    @Synchronized
    fun appendSentence(sentence: String, context: Context? = null) {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return

        if (context != null && appContext == null) {
            appContext = context.applicationContext
        }

        val formatted = trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        val builder = getActiveBuilder()
        if (builder.isNotEmpty() && !builder.endsWith("\n")) {
            builder.append("\n")
        }
        builder.append(formatted)
        lastInterimText = ""

        if (!isIncognito) {
            persist()
        }
        notifyListeners()
    }

    @Synchronized
    fun appendReply(replyText: String, context: Context? = null) {
        val trimmed = replyText.trim()
        if (trimmed.isEmpty()) return

        if (context != null && appContext == null) {
            appContext = context.applicationContext
        }

        val builder = getActiveBuilder()
        if (builder.isNotEmpty() && !builder.endsWith("\n")) {
            builder.append("\n")
        }
        builder.append("[Risposta]: ").append(trimmed)
        lastInterimText = ""

        if (!isIncognito) {
            persist()
        }
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
        if (isIncognito) {
            incognitoBuilder.clear()
            lastInterimText = ""
        } else {
            persistentBuilder.clear()
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
        }
        notifyListeners()
    }

    private fun persist() {
        // In Incognito Mode, NEVER write to disk! Volatile RAM only.
        if (isIncognito) return

        appContext?.let { ctx ->
            try {
                val plain = persistentBuilder.toString()
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
        val full = getActiveBuilder().toString()
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

    @Synchronized
    fun notifyAudioLevel(level: Float) {
        val copy = ArrayList(listeners)
        for (listener in copy) {
            try {
                listener.onAudioLevel(level)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    fun notifyAcousticEvent(event: String) {
        val copy = ArrayList(listeners)
        for (listener in copy) {
            try {
                listener.onAcousticEvent(event)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
