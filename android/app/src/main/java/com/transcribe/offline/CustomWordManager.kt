package com.transcribe.offline

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 100% Offline Custom Word Manager.
 * Stores user-defined special words, names, and vocabulary in local SharedPreferences.
 * Words are fed directly to Android's On-Device SpeechRecognizer via EXTRA_BIASING_STRINGS.
 * No phonetics, no heuristics, no text replacement needed.
 */
class CustomWordManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("custom_words_prefs", Context.MODE_PRIVATE)
    private val words = mutableListOf<String>()

    init {
        loadWords()
    }

    @Synchronized
    fun getWords(): List<String> = words.toList()

    @Synchronized
    fun addWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        if (!words.any { it.equals(trimmed, ignoreCase = true) }) {
            words.add(trimmed)
            saveWords()
        }
    }

    @Synchronized
    fun removeWord(word: String) {
        words.removeAll { it.equals(word, ignoreCase = true) }
        saveWords()
    }

    private fun loadWords() {
        words.clear()
        val jsonString = prefs.getString(KEY_WORDS, null) ?: return
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val w = jsonArray.optString(i)?.trim()
                if (!w.isNullOrBlank() && !words.contains(w)) {
                    words.add(w)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveWords() {
        try {
            val jsonArray = JSONArray()
            for (w in words) {
                jsonArray.put(w)
            }
            prefs.edit().putString(KEY_WORDS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val KEY_WORDS = "custom_words_list"
    }
}
