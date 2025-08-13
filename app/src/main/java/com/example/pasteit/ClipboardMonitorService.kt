// Replace the existing ClipboardMonitorService.kt with this fixed version:
package com.example.pasteit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class ClipboardMonitorService : Service(), TextToSpeech.OnInitListener {
    private val binder = LocalBinder()
    private lateinit var clipboardManager: ClipboardManager
    private var textToSpeech: TextToSpeech? = null
    private var currentText = ""
    private var currentPosition = 0
    private var textChunks = listOf<String>()
    private var isPlaying = false
    private var ttsInitialized = false
    private var lastClipboardText = ""
    private var lastClipboardChangeTime = 0L

    private var clipboardListener: ((String) -> Unit)? = null
    private var ttsListener: TTSListener? = null
    private lateinit var preferences: SharedPreferences

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

        preferences = getSharedPreferences("PasteItSettings", Context.MODE_PRIVATE)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        textToSpeech = TextToSpeech(this, this)

        setupClipboardListener()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
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

        // Stop any current playback
        if (isPlaying) {
            stopPlayback()
        }

        // Update with new content
        currentText = text
        currentPosition = 0
        textChunks = splitTextIntoChunks(text)

        // Always notify the UI of the change
        clipboardListener?.invoke(text)

        Log.d("PasteItService", "New text loaded, chunks: ${textChunks.size}")
    }

    private fun splitTextIntoChunks(text: String): List<String> {
        // Split text into sentences or chunks of ~150 characters for better TTS
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val chunks = mutableListOf<String>()
        var currentChunk = ""

        for (sentence in sentences) {
            if (currentChunk.length + sentence.length > 150 && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.trim())
                currentChunk = sentence
            } else {
                currentChunk += if (currentChunk.isEmpty()) sentence else " $sentence"
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.trim())
        }

        return chunks.ifEmpty { listOf(text) }
    }

    fun updateTTSSettings(speechRate: Float, speechPitch: Float) {
        Log.d("PasteItService", "Updating TTS settings: rate=$speechRate, pitch=$speechPitch")
        textToSpeech?.let { tts ->
            tts.setSpeechRate(speechRate)
            tts.setPitch(speechPitch)

            // Apply saved voice if available
            val savedVoiceName = preferences.getString("selected_voice", "")
            if (!savedVoiceName.isNullOrEmpty()) {
                val voice = tts.voices?.find { it.name == savedVoiceName }
                if (voice != null) {
                    tts.voice = voice
                }
            }
        }
    }

    override fun onInit(status: Int) {
        Log.d("PasteItService", "TTS init status: $status")
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                val result = tts.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("PasteItService", "Language not supported")
                    tts.setLanguage(Locale.US) // Fallback to US English
                }

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("PasteItService", "TTS started: $utteranceId")
                        isPlaying = true
                        ttsListener?.onPlaybackStarted()
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d("PasteItService", "TTS finished: $utteranceId")
                        isPlaying = false

                        // Auto-advance to next chunk if available
                        if (currentPosition < textChunks.size - 1) {
                            currentPosition++
                            startPlayback()
                        } else {
                            ttsListener?.onPlaybackFinished()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e("PasteItService", "TTS error: $utteranceId")
                        isPlaying = false
                        ttsListener?.onPlaybackError()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e("PasteItService", "TTS error: $utteranceId, code: $errorCode")
                        isPlaying = false
                        ttsListener?.onPlaybackError()
                    }
                })

                ttsInitialized = true

                // Apply current settings
                val rate = preferences.getInt("speech_rate", 100) / 100f
                val pitch = preferences.getInt("speech_pitch", 100) / 100f
                updateTTSSettings(rate, pitch)
            }
        } else {
            Log.e("PasteItService", "TTS initialization failed")
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
            Log.d("PasteItService", "Speaking chunk $currentPosition: ${textToSpeak.take(50)}...")

            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "pasteit_$currentPosition")

            val result = textToSpeech?.speak(
                textToSpeak,
                TextToSpeech.QUEUE_FLUSH,
                params,
                "pasteit_$currentPosition"
            )

            Log.d("PasteItService", "TTS speak result: $result")
        } else {
            Log.w("PasteItService", "Cannot start playback - position: $currentPosition, chunks: ${textChunks.size}, tts: $ttsInitialized")
        }
    }

    private fun stopPlayback() {
        Log.d("PasteItService", "Stopping playback")
        textToSpeech?.stop()
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

        // Stop any current playback
        if (isPlaying) {
            stopPlayback()
        }

        // Update with new content - same logic as handleClipboardChange
        currentText = text
        currentPosition = 0
        textChunks = splitTextIntoChunks(text)

        // Notify the UI listener as well
        clipboardListener?.invoke(text)

        Log.d("PasteItService", "Manual text loaded, chunks: ${textChunks.size}")
    }

    fun setClipboardListener(listener: (String) -> Unit) {
        clipboardListener = listener
        // Send current clipboard content if available
        if (currentText.isNotEmpty()) {
            listener(currentText)
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
        textToSpeech?.shutdown()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "PasteItService"
    }
}