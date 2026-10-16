package com.transcribe.offline

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Floating Subtitle Overlay Foreground Service.
 * Renders a transparent, draggable caption window above all other Android apps.
 * Deaf users can browse, use WhatsApp or read articles while live speech is transcribed in real-time.
 * 100% offline, air-gapped (zero network permissions).
 */
class FloatingSubtitleService : Service(), TranscriptSessionManager.Listener {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var systemSpeechService: OnDeviceSystemSpeechService? = null
    private lateinit var customWordManager: CustomWordManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPartialWordCount = 0
    private var lastPendingPartial = ""
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var pendingIntent: PendingIntent? = null
    private var lastNotificationTime = 0L
    private var screenStateReceiver: BroadcastReceiver? = null
    private var isBubbleVisibleByUser = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_BUBBLE -> {
                isBubbleVisibleByUser = true
                isBubbleVisible = true
                mainHandler.post {
                    if (!isInAppForeground) {
                        floatingView?.visibility = View.VISIBLE
                    }
                }
                updateNotificationText(TranscriptSessionManager.getFullTranscript().ifEmpty { "In ascolto... Parla vicino al telefono" })
                Toast.makeText(this, "Bolla sottotitoli ripristinata", Toast.LENGTH_SHORT).show()
                return START_STICKY
            }
            ACTION_HIDE_BUBBLE -> {
                isBubbleVisibleByUser = false
                isBubbleVisible = false
                mainHandler.post {
                    floatingView?.visibility = View.GONE
                }
                updateNotificationText(TranscriptSessionManager.getFullTranscript().ifEmpty { "In ascolto... Parla vicino al telefono" })
                Toast.makeText(this, "Bolla nascosta. Trascrizione sempre attiva nella notifica!", Toast.LENGTH_SHORT).show()
                return START_STICKY
            }
            ACTION_SET_IN_APP_FOREGROUND -> {
                val inApp = intent.getBooleanExtra(EXTRA_IN_APP, false)
                isInAppForeground = inApp
                mainHandler.post {
                    if (inApp) {
                        floatingView?.visibility = View.GONE
                    } else {
                        if (isBubbleVisibleByUser && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@FloatingSubtitleService))) {
                            floatingView?.visibility = View.VISIBLE
                        }
                    }
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isBubbleVisibleByUser = true
        isBubbleVisible = true
        customWordManager = CustomWordManager(this)

        // Initialize shared unified transcript session and register for real-time updates
        TranscriptSessionManager.init(this)
        TranscriptSessionManager.registerListener(this)

        // Keep CPU active for continuous speech recognition even when screen locks
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "OpenTranscribe::FloatingWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {}

        startAsForegroundService()
        registerScreenStateReceiver()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e("FloatingService", "SYSTEM_ALERT_WINDOW permission not granted - running foreground notification only")
            isBubbleVisibleByUser = false
            isBubbleVisible = false
        } else {
            initOverlayWindow()
        }

        initSpeechRecognition()
    }

    private fun buildNotification(text: String): Notification {
        val clean = text.trim()
        val lines = if (clean.isEmpty()) emptyList() else clean.split("\n").filter { it.isNotBlank() }
        val latestLine = lines.lastOrNull() ?: "In ascolto... Parla vicino al telefono"
        val recentText = if (lines.isEmpty()) {
            "In ascolto... Parla vicino al telefono"
        } else {
            lines.takeLast(6).joinToString("\n")
        }

        val isIncognito = TranscriptSessionManager.isIncognitoMode()
        val title = if (isIncognito) "🎙️ Open Transcriber 🕶️ (Incognito)" else "🎙️ Open Transcriber (Live)"

        val stopIntent = Intent(this, FloatingSubtitleService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleBubbleIntent = Intent(this, FloatingSubtitleService::class.java).apply {
            action = if (isBubbleVisibleByUser) ACTION_HIDE_BUBBLE else ACTION_SHOW_BUBBLE
        }
        val toggleBubblePending = PendingIntent.getService(
            this,
            2,
            toggleBubbleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleActionText = if (isBubbleVisibleByUser) "Nascondi Bolla" else "Mostra Bolla"
        val toggleActionIcon = if (isBubbleVisibleByUser) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_input_add

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(latestLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(recentText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(Color.parseColor("#00E676"))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSortKey("0_live_transcribe")
            .addAction(toggleActionIcon, toggleActionText, toggleBubblePending)
            .addAction(android.R.drawable.ic_menu_view, "Apri App", pendingIntent)
            .addAction(android.R.drawable.ic_menu_delete, "Termina", stopPending)

        val notification = builder.build()
        notification.flags = notification.flags or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_NO_CLEAR or
                Notification.FLAG_FOREGROUND_SERVICE
        return notification
    }

    private fun startAsForegroundService() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Open Transcriber - Trascrizione Live",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Mostra la trascrizione vocale in tempo reale sempre in alto nella barra notifiche e nel blocco schermo"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            val pending = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            this.pendingIntent = pending

            val notification = buildNotification("In ascolto... Parla vicino al telefono")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNotificationText(text: String, isFinal: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!isFinal && now - lastNotificationTime < 350L) return
        lastNotificationTime = now

        try {
            val notification = buildNotification(text)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Notification update failed or interrupted
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOverlayWindow() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val themedContext = ContextThemeWrapper(this, R.style.Theme_LiveTranscribeOffline)
            val inflater = LayoutInflater.from(themedContext)
            floatingView = inflater.inflate(R.layout.layout_floating_subtitle, null)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val displayWidth = resources.displayMetrics.widthPixels
            val windowWidth = (displayWidth * 0.92).toInt()

            params = WindowManager.LayoutParams(
                windowWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        @Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 120
            }

            val btnClose = floatingView?.findViewById<ImageButton>(R.id.btnCloseOverlay)
            btnClose?.setOnClickListener {
                isBubbleVisibleByUser = false
                isBubbleVisible = false
                floatingView?.visibility = View.GONE
                updateNotificationText(TranscriptSessionManager.getFullTranscript().ifEmpty { "In ascolto... Parla vicino al telefono" })
                Toast.makeText(this, "Bolla nascosta. Trascrizione sempre attiva nella notifica!", Toast.LENGTH_SHORT).show()
            }

            val btnMaximize = floatingView?.findViewById<ImageButton>(R.id.btnMaximizeApp)
            btnMaximize?.setOnClickListener {
                maximizeToFullApp()
            }

            val tvFloatingText = floatingView?.findViewById<TextView>(R.id.tvFloatingText)
            val tvFloatingInterim = floatingView?.findViewById<TextView>(R.id.tvFloatingInterim)
            val floatingScrollView = floatingView?.findViewById<ScrollView>(R.id.floatingScrollView)

            val full = TranscriptSessionManager.getFullTranscript()
            val interim = TranscriptSessionManager.getInterim()
            if (full.isNotEmpty() || interim.isNotEmpty()) {
                tvFloatingText?.text = full
                tvFloatingInterim?.text = interim
            } else {
                tvFloatingText?.text = "In attesa di voce..."
                tvFloatingInterim?.text = ""
            }

            val tvFloatingStatus = floatingView?.findViewById<TextView>(R.id.tvFloatingStatus)
            if (TranscriptSessionManager.isIncognitoMode()) {
                tvFloatingStatus?.text = "🕶️ Incognito"
                tvFloatingStatus?.setTextColor(Color.parseColor("#FFD54F"))
            } else {
                tvFloatingStatus?.text = "In ascolto..."
                tvFloatingStatus?.setTextColor(Color.parseColor("#00E676"))
            }

            val maxBubbleHeight = (resources.displayMetrics.density * 135).toInt()
            floatingScrollView?.let { sv ->
                sv.viewTreeObserver.addOnGlobalLayoutListener {
                    if (sv.height > maxBubbleHeight) {
                        val lp = sv.layoutParams
                        if (lp != null && lp.height != maxBubbleHeight) {
                            lp.height = maxBubbleHeight
                            sv.layoutParams = lp
                        }
                    }
                }
            }

            floatingScrollView?.post {
                floatingScrollView.fullScroll(View.FOCUS_DOWN)
            }

            tvFloatingText?.setOnClickListener {
                maximizeToFullApp()
            }
            tvFloatingInterim?.setOnClickListener {
                maximizeToFullApp()
            }

            val dragHandle = floatingView?.findViewById<View>(R.id.dragHandle)
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            dragHandle?.setOnTouchListener { _, event ->
                val p = params ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x
                        initialY = p.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initialX + (event.rawX - initialTouchX).toInt()
                        p.y = initialY + (event.rawY - initialTouchY).toInt()
                        if (floatingView != null) {
                            windowManager.updateViewLayout(floatingView, p)
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("FloatingService", "Failed to add overlay window", e)
            Toast.makeText(this, "Errore apertura sottotitoli: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initSpeechRecognition() {
        systemSpeechService?.stop()
        systemSpeechService = null

        val service = OnDeviceSystemSpeechService(this, object : OnDeviceSystemSpeechService.Listener {
            override fun onPartialResult(hypothesis: String) {
                if (hypothesis.isNotEmpty()) {
                    lastPendingPartial = hypothesis
                    TranscriptSessionManager.setInterim(hypothesis)
                    checkAndTriggerWordHaptic(hypothesis, isFinal = false)
                }
            }

            override fun onFinalResult(sentence: String) {
                if (sentence.isNotEmpty()) {
                    lastPendingPartial = ""
                    TranscriptSessionManager.appendSentence(sentence, this@FloatingSubtitleService)
                    checkAndTriggerWordHaptic(sentence, isFinal = true)
                } else {
                    lastPartialWordCount = 0
                }
            }

            override fun onAudioLevel(level: Float) {
                TranscriptSessionManager.notifyAudioLevel(level)
            }

            override fun onAcousticEvent(event: String) {
                TranscriptSessionManager.notifyAcousticEvent(event)
            }

            override fun onError(error: String, isOfflineUnavailable: Boolean) {
                if (isOfflineUnavailable) {
                    mainHandler.post {
                        Toast.makeText(this@FloatingSubtitleService, "Pacchetto vocale offline mancante nelle impostazioni Android.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })

        val prefs = getSharedPreferences("app_language_prefs", Context.MODE_PRIVATE)
        val selectedLanguage = prefs.getString("selected_language", "it-IT") ?: "it-IT"
        service.setLanguage(selectedLanguage)
        service.setBiasingWords(customWordManager.getWords())
        service.start()
        systemSpeechService = service
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        mainHandler.post {
                            floatingView?.visibility = View.GONE
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        mainHandler.post {
                            if (isBubbleVisibleByUser && !isInAppForeground) {
                                floatingView?.visibility = View.VISIBLE
                            } else {
                                floatingView?.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
        registerReceiver(screenStateReceiver, filter)
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

    private fun maximizeToFullApp() {
        if (lastPendingPartial.isNotBlank()) {
            TranscriptSessionManager.commitInterimIfPresent(this)
            lastPendingPartial = ""
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
        // Keep service and persistent notification active!
    }

    override fun onTranscriptChanged(fullText: String, interimText: String) {
        mainHandler.post {
            val tvFloatingText = floatingView?.findViewById<TextView>(R.id.tvFloatingText)
            val tvFloatingInterim = floatingView?.findViewById<TextView>(R.id.tvFloatingInterim)
            val floatingScrollView = floatingView?.findViewById<ScrollView>(R.id.floatingScrollView)

            if (fullText.isEmpty() && interimText.isEmpty()) {
                val isIncognito = TranscriptSessionManager.isIncognitoMode()
                tvFloatingText?.text = if (isIncognito) "🕶️ Incognito (In ascolto...)" else "In attesa di voce..."
                tvFloatingInterim?.text = ""
            } else {
                tvFloatingText?.text = fullText
                tvFloatingInterim?.text = interimText
            }

            floatingScrollView?.post {
                floatingScrollView.fullScroll(View.FOCUS_DOWN)
            }

            val displayText = if (interimText.isNotEmpty()) {
                if (fullText.isNotEmpty()) "$fullText\n▶ $interimText" else "▶ $interimText"
            } else {
                fullText
            }
            if (displayText.isNotEmpty()) {
                updateNotificationText(displayText)
            }
        }
    }

    override fun onIncognitoChanged(isIncognito: Boolean) {
        mainHandler.post {
            val tvFloatingStatus = floatingView?.findViewById<TextView>(R.id.tvFloatingStatus)
            val tvFloatingIncognitoWatermark = floatingView?.findViewById<TextView>(R.id.tvFloatingIncognitoWatermark)
            if (isIncognito) {
                tvFloatingStatus?.text = "🕶️ Incognito"
                tvFloatingStatus?.setTextColor(Color.parseColor("#FFD54F"))
                tvFloatingIncognitoWatermark?.visibility = View.VISIBLE
                params?.let { p ->
                    p.flags = p.flags or WindowManager.LayoutParams.FLAG_SECURE
                    if (floatingView != null) {
                        try { windowManager.updateViewLayout(floatingView, p) } catch (e: Exception) {}
                    }
                }
            } else {
                tvFloatingStatus?.text = "In ascolto..."
                tvFloatingStatus?.setTextColor(Color.parseColor("#00E676"))
                tvFloatingIncognitoWatermark?.visibility = View.GONE
                params?.let { p ->
                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    if (floatingView != null) {
                        try { windowManager.updateViewLayout(floatingView, p) } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isBubbleVisible = false
        isInAppForeground = false
        TranscriptSessionManager.unregisterListener(this)
        if (lastPendingPartial.isNotBlank()) {
            TranscriptSessionManager.commitInterimIfPresent(this)
            lastPendingPartial = ""
        }
        systemSpeechService?.stop()
        systemSpeechService = null
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        wakeLock = null
        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {}
        }
        screenStateReceiver = null

        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        floatingView = null
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        const val ACTION_STOP_SERVICE = "com.transcribe.offline.STOP_SERVICE"
        const val ACTION_SHOW_BUBBLE = "com.transcribe.offline.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.transcribe.offline.HIDE_BUBBLE"
        const val ACTION_SET_IN_APP_FOREGROUND = "com.transcribe.offline.SET_IN_APP_FOREGROUND"
        const val EXTRA_IN_APP = "extra_in_app"
        const val CHANNEL_ID = "live_transcript_permanent_v6"
        var currentTranscript = ""
        var isRunning = false
        var isBubbleVisible = true
        var isInAppForeground = false
    }
}
