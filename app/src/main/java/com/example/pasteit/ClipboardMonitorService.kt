package com.example.pasteit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class ClipboardMonitorService : Service() {
    private val binder = LocalBinder()
    private lateinit var clipboardManager: ClipboardManager
    private var sherpaTts: SherpaOnnxTts? = null
    private var currentText = ""
    private var currentPosition = 0
    private var textChunks = listOf<String>()
    private var isPlaying = false
    private var ttsInitialized = false
    private var lastClipboardText = ""
    private var lastClipboardChangeTime = 0L
    /** Raw clipboard / pasted markdown for UI (TTS uses [currentText] which is sanitized). */
    private var lastSourceForDisplay = ""

    private var clipboardListener: ((String) -> Unit)? = null
    private var ttsListener: TTSListener? = null
    private lateinit var preferences: SharedPreferences

    private val mainHandler = Handler(Looper.getMainLooper())
    /** Posted after a chunk finishes so we never call [SherpaOnnxTts.speak] re-entrantly from [onSpeakDone]. */
    private val playNextChunkRunnable = Runnable { startPlayback() }

    interface TTSListener {
        fun onPlaybackStarted()
        fun onPlaybackFinished()
        fun onPlaybackError()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ClipboardMonitorService = this@ClipboardMonitorService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("PasteItService", "Service created")

        preferences = getSharedPreferences("PasteItSettings", MODE_PRIVATE)
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        
        initializeSherpaOnnxTts()

        setupClipboardListener()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun initializeSherpaOnnxTts() {
        sherpaTts = SherpaOnnxTts(this)
        sherpaTts?.setCallback(object : SherpaOnnxTts.TtsCallback {
            override fun onInitialized(success: Boolean) {
                Log.d("PasteItService", "Sherpa ONNX TTS initialized: $success")
                ttsInitialized = success
                if (success) {
                    val rate = preferences.getInt(
                        SherpaTtsEngineParams.PREF_SPEECH_RATE,
                        SherpaTtsEngineParams.DEFAULT_SPEECH_RATE_PERCENT,
                    ) / 100f
                    sherpaTts?.setSpeechRate(rate)
                }
            }

            override fun onSpeakStart(utteranceId: String) {
                Log.d("PasteItService", "TTS started: $utteranceId")
                isPlaying = true
                ttsListener?.onPlaybackStarted()
            }

            override fun onSpeakDone(utteranceId: String) {
                Log.d("PasteItService", "TTS finished: $utteranceId")
                isPlaying = false

                if (currentPosition < textChunks.size - 1) {
                    currentPosition++
                    // Defer: synchronous start here calls speak() → stop() → cancel while the
                    // finished chunk's coroutine is still unwinding; its CancellationException handler
                    // could clear isPlaying and abort the next chunk mid-generate.
                    mainHandler.removeCallbacks(playNextChunkRunnable)
                    mainHandler.post(playNextChunkRunnable)
                } else {
                    ttsListener?.onPlaybackFinished()
                }
            }

            override fun onSpeakError(utteranceId: String, error: String) {
                Log.e("PasteItService", "TTS error: $utteranceId - $error")
                isPlaying = false
                ttsListener?.onPlaybackError()
            }

            override fun onEngineReconfigured() {
                isPlaying = false
            }
        })
        
        sherpaTts?.initialize(preferences) { success ->
            Log.d("PasteItService", "Sherpa ONNX TTS initialization callback: $success")
        }
    }

    private fun setupClipboardListener() {
        // Get initial clipboard content
        checkClipboardContent()

        clipboardManager.addPrimaryClipChangedListener {
            Log.d("PasteItService", "Clipboard changed")
            checkClipboardContent()
        }
    }

    private fun checkClipboardContent() {
        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0).text?.toString() ?: ""
                Log.d("PasteItService", "Clipboard text: ${clipText.take(50)}...")

                // Process any non-empty text, even if it's the same as before
                // (user might want to re-read the same content)
                if (clipText.isNotEmpty()) {
                    // Only skip if it's exactly the same and very recent (within 1 second)
                    val currentTime = System.currentTimeMillis()
                    val timeDiff = currentTime - lastClipboardChangeTime

                    if (clipText != lastClipboardText || timeDiff > 1000) {
                        lastClipboardText = clipText
                        lastClipboardChangeTime = currentTime
                        handleClipboardChange(clipText)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PasteItService", "Error reading clipboard", e)
        }
    }

    private fun handleClipboardChange(text: String) {
        Log.d("PasteItService", "Handling clipboard change: ${text.take(50)}...")

        mainHandler.removeCallbacks(playNextChunkRunnable)
        // Stop any current playback
        if (isPlaying) {
            stopPlayback()
        }
        sherpaTts?.resetChunkCrossfade()

        lastSourceForDisplay = text

        currentText = SpeechFormattingPipeline.plainTextForTts(preferences, text)
        currentPosition = 0
        textChunks = splitTextIntoChunks(currentText)

        // Always notify the UI of the change
        clipboardListener?.invoke(text)

        Log.d("PasteItService", "New text loaded, chunks: ${textChunks.size}")
    }

    private fun splitTextIntoChunks(text: String): List<String> {
        val maxChunk = preferences.getInt(
            SherpaTtsEngineParams.PREF_CHUNK_MAX_CHARS,
            280,
        ).coerceIn(120, 520)

        // Paragraphs first — fewer awkward cuts mid-thought
        val paragraphs = text.split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        for (para in paragraphs) {
            chunks.addAll(splitParagraphIntoChunks(para, maxChunk))
        }

        return chunks.ifEmpty { listOf(text.trim()).filter { it.isNotEmpty() }.ifEmpty { listOf(text) } }
    }

    /**
     * Split on sentence boundaries; merge short sentences so chunks aren\'t tiny (less choppy playback).
     */
    private fun splitParagraphIntoChunks(paragraph: String, maxChunk: Int): List<String> {
        val sentences = paragraph.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) {
            return hardWrapLongText(paragraph.trim(), maxChunk).ifEmpty { listOf(paragraph) }
        }

        val chunks = mutableListOf<String>()
        var current = ""

        for (sentence in sentences) {
            val candidate = if (current.isEmpty()) sentence else "$current $sentence"
            if (candidate.length > maxChunk && current.isNotEmpty()) {
                chunks.addAll(hardWrapLongText(current.trim(), maxChunk))
                current = sentence
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) {
            chunks.addAll(hardWrapLongText(current.trim(), maxChunk))
        }
        return chunks
    }

    private fun hardWrapLongText(s: String, maxChunk: Int): List<String> {
        if (s.length <= maxChunk) return if (s.isNotEmpty()) listOf(s) else emptyList()
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > maxChunk) {
            val space = rest.lastIndexOf(' ', maxChunk)
            val cut = if (space > maxChunk / 2) space else maxChunk
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /**
     * Rebuilds [currentText] from [lastSourceForDisplay] using current formatting prefs (e.g. stock rules).
     * No-ops when the result matches [currentText] so [MainActivity.onResume] does not interrupt playback.
     */
    fun reapplySpeechFormatting() {
        if (lastSourceForDisplay.isEmpty()) return
        val next = SpeechFormattingPipeline.plainTextForTts(preferences, lastSourceForDisplay)
        if (next == currentText) return
        mainHandler.removeCallbacks(playNextChunkRunnable)
        if (isPlaying) stopPlayback()
        sherpaTts?.resetChunkCrossfade()
        currentText = next
        textChunks = splitTextIntoChunks(currentText)
        currentPosition = currentPosition.coerceIn(0, (textChunks.size - 1).coerceAtLeast(0))
    }

    fun updateTTSSettings(speechRate: Float, speechPitch: Float) {
        Log.d("PasteItService", "Updating TTS settings: rate=$speechRate, pitch=$speechPitch")
        sherpaTts?.applyPreferences(preferences, speechRateFromUi = speechRate, onComplete = null)
        // Re-split so chunk-size preference applies to text already loaded
        if (currentText.isNotEmpty()) {
            textChunks = splitTextIntoChunks(currentText)
            if (currentPosition >= textChunks.size) {
                currentPosition = (textChunks.size - 1).coerceAtLeast(0)
            }
        }
    }

    fun togglePlayback() {
        Log.d("PasteItService", "Toggle playback - currentText: ${currentText.take(50)}..., isPlaying: $isPlaying, ttsInitialized: $ttsInitialized")

        if (currentText.isEmpty()) {
            Log.w("PasteItService", "No text to play")
            return
        }

        if (!ttsInitialized) {
            Log.w("PasteItService", "TTS not initialized yet")
            return
        }

        if (isPlaying) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (currentPosition < textChunks.size && ttsInitialized) {
            val textToSpeak = textChunks[currentPosition]
            val prefetchNext =
                if (currentPosition + 1 < textChunks.size) textChunks[currentPosition + 1] else null
            Log.d("PasteItService", "Speaking chunk $currentPosition: ${textToSpeak.take(50)}...")

            sherpaTts?.speak(textToSpeak, "pasteit_$currentPosition", prefetchNext)
        } else {
            Log.w("PasteItService", "Cannot start playback - position: $currentPosition, chunks: ${textChunks.size}, tts: $ttsInitialized")
        }
    }

    private fun stopPlayback() {
        mainHandler.removeCallbacks(playNextChunkRunnable)
        Log.d("PasteItService", "Stopping playback")
        sherpaTts?.stop()
        isPlaying = false
    }

    fun rewind() {
        Log.d("PasteItService", "Rewind")
        stopPlayback()
        if (currentPosition > 0) {
            currentPosition--
        } else {
            currentPosition = 0
        }
        // Auto-resume playback if it was playing before
        if (isPlaying) {
            startPlayback()
        }
    }

    fun fastForward() {
        Log.d("PasteItService", "Fast forward")
        stopPlayback()
        if (currentPosition < textChunks.size - 1) {
            currentPosition++
        }
        // Auto-resume playback if it was playing before
        if (isPlaying) {
            startPlayback()
        }
    }

    fun setManualText(text: String) {
        Log.d("PasteItService", "Manual text set: ${text.take(50)}...")

        mainHandler.removeCallbacks(playNextChunkRunnable)
        // Stop any current playback
        if (isPlaying) {
            stopPlayback()
        }
        sherpaTts?.resetChunkCrossfade()

        lastSourceForDisplay = text

        currentText = SpeechFormattingPipeline.plainTextForTts(preferences, text)
        currentPosition = 0
        textChunks = splitTextIntoChunks(currentText)

        // Notify the UI listener as well
        clipboardListener?.invoke(text)

        Log.d("PasteItService", "Manual text loaded, chunks: ${textChunks.size}")
    }

    fun setClipboardListener(listener: (String) -> Unit) {
        clipboardListener = listener
        // Replay raw source so the UI still renders markdown / shows symbols
        if (lastSourceForDisplay.isNotEmpty()) {
            listener(lastSourceForDisplay)
        }
    }

    fun setTTSListener(listener: TTSListener) {
        ttsListener = listener
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PasteIt Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PasteIt running to monitor clipboard"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PasteIt is running")
            .setContentText("Monitoring clipboard for text to read")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d("PasteItService", "Service destroyed")
        sherpaTts?.release()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "PasteItService"
    }
}