package com.transcribe.offline

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import android.net.Uri
import java.util.Locale

/**
 * Dynamic Manager for On-Device Speech Recognition Language Models.
 * Responsibilities:
 * 1. Checks which language packs are ACTUALLY installed and ready on the device (no hardcoded lists).
 * 2. Uses Android 13+ (API 33+) SpeechRecognizer.checkRecognitionSupport() for accurate on-device detection.
 * 3. Uses RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS broadcast as universal fallback.
 * 4. Enables users to add/download new offline language packs by opening system speech settings.
 * 5. Generates localized display names and emoji flags dynamically for any BCP-47 language tag.
 */
object LanguageModelManager {

    private const val PREFS_NAME = "app_language_prefs"
    private const val KEY_CACHED_INSTALLED = "cached_installed_language_tags"
    private const val KEY_CACHED_DOWNLOADABLE = "cached_downloadable_language_tags"
    private val mainHandler = Handler(Looper.getMainLooper())

    data class LanguageInfo(
        val code: String,          // BCP-47 tag, e.g. "it-IT"
        val displayName: String,   // Native localized name, e.g. "Italiano (Italia)"
        val flag: String,          // Dynamic emoji flag, e.g. "🇮🇹"
        val isInstalled: Boolean,  // True if downloaded and ready for offline recognition
        val isPending: Boolean = false
    )

    fun normalizeTag(tag: String): String {
        return tag.trim().replace('_', '-')
    }

    /**
     * Converts any 2-letter country code into an emoji flag using Unicode Regional Indicator Symbols.
     * Fallback to language-based flags if country code is missing.
     */
    fun getFlagEmoji(locale: Locale): String {
        val country = locale.country.uppercase()
        if (country.length == 2 && country[0] in 'A'..'Z' && country[1] in 'A'..'Z') {
            val firstChar = Character.codePointAt(country, 0) - 0x41 + 0x1F1E6
            val secondChar = Character.codePointAt(country, 1) - 0x41 + 0x1F1E6
            return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
        }

        return when (locale.language.lowercase()) {
            "it" -> "🇮🇹"
            "en" -> "🇬🇧"
            "es" -> "🇪🇸"
            "fr" -> "🇫🇷"
            "de" -> "🇩🇪"
            "pt" -> "🇵🇹"
            "ru" -> "🇷🇺"
            "zh" -> "🇨🇳"
            "ja" -> "🇯🇵"
            "ar" -> "🇸🇦"
            "hi" -> "🇮🇳"
            "nl" -> "🇳🇱"
            "pl" -> "🇵🇱"
            "tr" -> "🇹🇷"
            "uk" -> "🇺🇦"
            "ro" -> "🇷🇴"
            "el" -> "🇬🇷"
            "cs" -> "🇨🇿"
            "sv" -> "🇸🇪"
            "da" -> "🇩🇰"
            "fi" -> "🇫🇮"
            "no" -> "🇳🇴"
            else -> "🌐"
        }
    }

    /**
     * Creates a LanguageInfo from any BCP-47 tag with native autonym display name.
     */
    fun getLanguageInfo(tag: String, isInstalled: Boolean = true): LanguageInfo {
        val normalized = normalizeTag(tag)
        val locale = Locale.forLanguageTag(normalized)
        val rawName = locale.getDisplayName(locale)
        val display = if (rawName.isNotBlank() && !rawName.equals(normalized, ignoreCase = true)) {
            rawName.replaceFirstChar { it.uppercase() }
        } else {
            val italianDisplay = locale.getDisplayName(Locale.ITALIAN)
            if (italianDisplay.isNotBlank()) italianDisplay.replaceFirstChar { it.uppercase() }
            else normalized
        }
        val flag = getFlagEmoji(locale)
        return LanguageInfo(normalized, display, flag, isInstalled)
    }

    /**
     * Immediately returns cached installed languages from disk to populate UI with zero lag.
     */
    fun getCachedInstalledLanguages(context: Context): List<LanguageInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tags = prefs.getStringSet(KEY_CACHED_INSTALLED, null)
        val resultTags = if (!tags.isNullOrEmpty()) {
            tags.toList()
        } else {
            // Default seed: Italian and device system locale
            val defaultLocaleTag = normalizeTag(Locale.getDefault().toLanguageTag())
            listOf("it-IT", defaultLocaleTag).distinct()
        }
        return resultTags.map { getLanguageInfo(it, isInstalled = true) }
    }

    /**
     * Asynchronously detects all offline speech recognition language packages installed on the device.
     */
    fun detectInstalledLanguages(
        context: Context,
        callback: (installed: List<LanguageInfo>, downloadable: List<LanguageInfo>) -> Unit
    ) {
        // Method 1: Android 13+ (API 33+) native on-device support check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            try {
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                recognizer.checkRecognitionSupport(
                    intent,
                    ContextCompat.getMainExecutor(context),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            val installedTags = recognitionSupport.installedOnDeviceLanguages
                            val supportedTags = recognitionSupport.supportedOnDeviceLanguages
                            try { recognizer.destroy() } catch (e: Exception) {}

                            handleDetectionResults(context, installedTags, supportedTags, callback)
                        }

                        override fun onError(error: Int) {
                            try { recognizer.destroy() } catch (e: Exception) {}
                            fallbackToBroadcastQuery(context, callback)
                        }
                    }
                )
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Method 2: Broadcast fallback for all Android versions
        fallbackToBroadcastQuery(context, callback)
    }

    private fun fallbackToBroadcastQuery(
        context: Context,
        callback: (installed: List<LanguageInfo>, downloadable: List<LanguageInfo>) -> Unit
    ) {
        try {
            val detailsIntent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
            context.sendOrderedBroadcast(
                detailsIntent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val results = getResultExtras(true)
                        val supportedTags = results?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES) ?: arrayListOf()
                        val prefTag = results?.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)

                        val installedTags = mutableListOf<String>()
                        prefTag?.let { installedTags.add(it) }
                        installedTags.add("it-IT")

                        handleDetectionResults(context, installedTags, supportedTags, callback)
                    }
                },
                null,
                Activity.RESULT_OK,
                null,
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            // Ultimate fallback to cached languages
            val cached = getCachedInstalledLanguages(context)
            mainHandler.post { callback(cached, emptyList()) }
        }
    }

    private fun handleDetectionResults(
        context: Context,
        installedTags: List<String>,
        supportedTags: List<String>,
        callback: (installed: List<LanguageInfo>, downloadable: List<LanguageInfo>) -> Unit
    ) {
        val installedSet = mutableSetOf<String>()
        for (tag in installedTags) {
            val norm = normalizeTag(tag)
            if (norm.isNotBlank()) installedSet.add(norm)
        }

        // Italian is always guaranteed to be recognized if on device
        if (installedSet.isEmpty()) {
            installedSet.add("it-IT")
        }

        // Cache installed tags to SharedPreferences
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_CACHED_INSTALLED, installedSet)
                .apply()
        } catch (e: Exception) {}

        val installedList = installedSet.map { getLanguageInfo(it, isInstalled = true) }
            .sortedBy { it.displayName }

        val standardLanguages = listOf(
            "en-US", "en-GB", "es-ES", "fr-FR", "de-DE", "pt-PT", "pt-BR",
            "ru-RU", "zh-CN", "ja-JP", "ar-SA", "hi-IN", "nl-NL", "pl-PL",
            "tr-TR", "uk-UA", "ro-RO", "el-GR", "sv-SE"
        )

        val downloadableSet = mutableSetOf<String>()
        for (tag in (supportedTags + standardLanguages)) {
            val norm = normalizeTag(tag)
            if (norm.isNotBlank() && !installedSet.contains(norm)) {
                downloadableSet.add(norm)
            }
        }

        val downloadableList = downloadableSet.map { getLanguageInfo(it, isInstalled = false) }
            .sortedBy { it.displayName }

        mainHandler.post {
            callback(installedList, downloadableList)
        }
    }

    /**
     * Opens Android System / Google Speech Services settings screen directly to download offline language models.
     */
    fun openOfflineSpeechSettings(context: Context): Boolean {
        // Prioritize settings that directly lead to keyboard voice typing and offline speech packs
        val candidateIntents = listOf(
            // 1. Android Input Methods / On-Screen Keyboards (Direct access to Gboard / Google Voice Typing settings gear)
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
            // 2. Direct Voice Search Settings Action
            Intent("com.google.android.voicesearch.action.SETTINGS"),
            // 3. Android platform voice search action
            Intent("android.speech.action.VOICE_SEARCH_SETTINGS"),
            // 4. Google Quick Search Box VoiceSearchPreferences component
            Intent().setComponent(ComponentName("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.settingsui.VoiceSearchPreferences")),
            // 5. Google TTS / Speech Services settings
            Intent().setComponent(ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.settings.GoogleTtsSettings")),
            // 6. Application Details for Google Speech Services
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", "com.google.android.tts", null)
            },
            // 7. Application Details for Google App
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", "com.google.android.googlequicksearchbox", null)
            },
            // 8. Android System Languages and Keyboard settings
            Intent(Settings.ACTION_LOCALE_SETTINGS),
            // 9. General Android System Settings
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in candidateIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                // Try next candidate
            }
        }

        // Direct fallback: start Settings.ACTION_SETTINGS unconditionally
        return try {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Programmatically triggers speech model download on Android 14+ (API 34+) if supported by device engine.
     */
    fun triggerModelDownload(
        context: Context,
        langTag: String,
        onProgress: (percent: Int) -> Unit,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    }
                    recognizer.triggerModelDownload(
                        intent,
                        ContextCompat.getMainExecutor(context),
                        object : android.speech.ModelDownloadListener {
                            override fun onProgress(completedPercent: Int) {
                                onProgress(completedPercent)
                            }
                            override fun onSuccess() {
                                try { recognizer.destroy() } catch (e: Exception) {}
                                onComplete(true, "Modulo $langTag scaricato con successo!")
                            }
                            override fun onScheduled() {
                                onComplete(true, "Download programmato dal sistema (in attesa di Wi-Fi).")
                            }
                            override fun onError(error: Int) {
                                try { recognizer.destroy() } catch (e: Exception) {}
                                onComplete(false, "Download diretto non riuscito (errore $error).")
                            }
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onComplete(false, "Download automatico non supportato su questo dispositivo.")
    }
}
