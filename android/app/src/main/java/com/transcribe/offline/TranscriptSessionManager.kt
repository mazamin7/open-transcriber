package com.transcribe.offline

import android.content.Context

/**
 * Shared in-memory and persistent conversation transcript session manager.
 * Synchronizes transcription history seamlessly between MainActivity and FloatingSubtitleService,
 * ensuring users never lose spoken text when switching between the floating bubble and the full app.
 */
object TranscriptSessionManager {
    private val transcriptBuilder = StringBuilder()
    private var lastInterimText = ""
    private const val PREFS_NAME = "transcript_session_prefs"
    private const val KEY_TRANSCRIPT = "saved_full_transcript"
    private var isInitialized = false

    @Synchronized
    fun init(context: Context) {
        if (!isInitialized) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val saved = prefs.getString(KEY_TRANSCRIPT, "") ?: ""
                if (saved.isNotEmpty() && transcriptBuilder.isEmpty()) {
                    transcriptBuilder.append(saved)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isInitialized = true
        }
    }

    @Synchronized
    fun getFullTranscript(): String {
        return transcriptBuilder.toString()
    }

    @Synchronized
    fun getRecentSnippet(maxLength: Int = 200): String {
        val full = transcriptBuilder.toString().trim()
        if (full.length <= maxLength) return full
        val trimmed = full.takeLast(maxLength)
        val spaceIndex = trimmed.indexOf(' ')
        return if (spaceIndex != -1) trimmed.substring(spaceIndex + 1) else trimmed
    }

    @Synchronized
    fun appendSentence(sentence: String, context: Context? = null) {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return

        val current = transcriptBuilder.toString().trimEnd()
        // Prevent duplicate appending if the sentence is already the end
        if (current.endsWith(trimmed)) {
            lastInterimText = ""
            return
        }

        if (transcriptBuilder.isNotEmpty() && !transcriptBuilder.endsWith(" ") && !transcriptBuilder.endsWith("\n")) {
            transcriptBuilder.append(" ")
        }
        transcriptBuilder.append(trimmed).append(" ")
        lastInterimText = ""

        persist(context)
    }

    @Synchronized
    fun appendReply(replyText: String, context: Context? = null) {
        val trimmed = replyText.trim()
        if (trimmed.isEmpty()) return

        if (transcriptBuilder.isNotEmpty() && !transcriptBuilder.endsWith("\n")) {
            transcriptBuilder.append("\n")
        }
        transcriptBuilder.append("[Risposta]: ").append(trimmed).append("\n\n")
        lastInterimText = ""

        persist(context)
    }

    @Synchronized
    fun setInterim(text: String) {
        lastInterimText = text.trim()
    }

    @Synchronized
    fun getInterim(): String {
        return lastInterimText
    }

    @Synchronized
    fun commitInterimIfPresent(context: Context? = null) {
        if (lastInterimText.isNotEmpty()) {
            val pending = lastInterimText
            lastInterimText = ""
            appendSentence(pending, context)
        }
    }

    @Synchronized
    fun clear(context: Context? = null) {
        transcriptBuilder.clear()
        lastInterimText = ""
        context?.let { ctx ->
            try {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_TRANSCRIPT)
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun persist(context: Context?) {
        context?.let { ctx ->
            try {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_TRANSCRIPT, transcriptBuilder.toString())
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
