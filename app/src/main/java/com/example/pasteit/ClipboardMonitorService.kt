package com.example.pasteit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

class ClipboardMonitorService : Service() {
    private val binder = LocalBinder()
    private lateinit var clipboardManager: ClipboardManager
    private var ttsEngine: PasteItTts? = null
    private var currentText = ""
    private var currentPosition = 0
    private var textChunks = listOf<String>()
    private var isPlaying = false
    private var isPaused = false
    private var ttsInitialized = false
    private var lastClipboardText = ""
    private var lastClipboardChangeTime = 0L
    /** Raw clipboard / pasted markdown for UI (TTS uses [currentText] which is sanitized). */
    private var lastSourceForDisplay = ""
    private var activeSavedItemId: String? = null
    private var activeContentSource: ActiveContentSource = ActiveContentSource.STANDARD
    /** Non-null when the active library item has a stitched merged.mp3 ready for continuous playback. */
    private var activeMergedFile: java.io.File? = null

    private var clipboardListener: ((String) -> Unit)? = null
    private var ttsListener: TTSListener? = null
    private lateinit var preferences: SharedPreferences
    private lateinit var mediaSession: MediaSessionCompat

    /** True while the user has pressed MAX — uses engine hard limits for chunk size. */
    private var isMaxChunkMode = false

    /** Set when a resume position > 0 is restored from persistence; consumed once by the UI. */
    private var pendingResumeHint: Pair<Int, Int>? = null
    private var averageChunkDurationMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    /** Posted after a chunk finishes so we never call speak() re-entrantly from onSpeakDone. */
    private val playNextChunkRunnable = Runnable { startPlayback() }

    interface TTSListener {
        fun onPlaybackBuffering()
        fun onPlaybackPaused()
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
        initializeMediaSession()

        preferences = getSharedPreferences("PasteItSettings", MODE_PRIVATE)
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        initializeTts()
        restoreInitialTextState()
        setupClipboardListener()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAYBACK -> togglePlayback()
            ACTION_PASTE_CLIPBOARD -> pasteClipboardContent()
            ACTION_REWIND -> rewind()
            ACTION_FAST_FORWARD -> fastForward()
            ACTION_LOAD_TEXT_DIRECTLY -> loadTextDirectly(intent.getStringExtra(EXTRA_DIRECT_TEXT))
        }
        return START_STICKY
    }

    private fun initializeTts() {
        ttsEngine = PasteItTts(this)
        ttsEngine?.setCallback(object : AppTtsCallback {
            override fun onInitialized(success: Boolean) {
                Log.d("PasteItService", "TTS initialized: $success")
                ttsInitialized = success
                if (success) {
                    val rate = preferences.getInt(
                        AndroidTtsEngineParams.PREF_SPEECH_RATE,
                        AndroidTtsEngineParams.DEFAULT_SPEECH_RATE_PERCENT,
                    ) / 100f
                    ttsEngine?.setSpeechRate(rate)
                }
            }

            override fun onSpeakStart(utteranceId: String) {
                Log.d("PasteItService", "TTS started: $utteranceId")
                isPlaying = true
                isPaused = false
                ttsListener?.onPlaybackStarted()
                updateMediaSession()
                updateNotification()
            }

            override fun onSpeakDone(utteranceId: String) {
                Log.d("PasteItService", "TTS finished: $utteranceId")
                isPlaying = false
                isPaused = false

                if (currentPosition < textChunks.size - 1) {
                    currentPosition++
                    persistResumePosition()
                    // Defer: synchronous start here calls speak() → stop() → cancel while the
                    // finished chunk's coroutine is still unwinding; its CancellationException handler
                    // could clear isPlaying and abort the next chunk mid-generate.
                    mainHandler.removeCallbacks(playNextChunkRunnable)
                    mainHandler.post(playNextChunkRunnable)
                } else {
                    isMaxChunkMode = false  // auto-reset at end of document
                    persistResumePosition()
                    updateMediaSession()
                    updateNotification()
                    ttsListener?.onPlaybackFinished()
                }
            }

            override fun onSpeakError(utteranceId: String, error: String) {
                Log.e("PasteItService", "TTS error: $utteranceId - $error")
                isPlaying = false
                isPaused = false
                updateMediaSession()
                updateNotification()
                ttsListener?.onPlaybackError()
            }

            override fun onEngineReconfigured() {
                isPlaying = false
                isPaused = false
            }
        })
        
        ttsEngine?.initialize(preferences) { success ->
            Log.d("PasteItService", "TTS initialization callback: $success")
        }
    }

    private fun setupClipboardListener() {
        clipboardManager.addPrimaryClipChangedListener {
            Log.d("PasteItService", "Clipboard changed")
            checkClipboardContent()
        }
    }

    private fun restoreInitialTextState() {
        val savedContentStore = SavedContentStore(this)
        val persisted = CurrentTextPersistence.load(this, preferences, savedContentStore)
        if (persisted != null) {
            activeSavedItemId = persisted.savedItemId
            activeContentSource = persisted.contentSource
            Log.d("PasteItService", "Restoring persisted text: ${persisted.sourceText.take(50)}...")
            loadSourceTextIntoState(persisted.sourceText, savedItemId = persisted.savedItemId)
            return
        }

        checkClipboardContent()
    }

    private fun checkClipboardContent() {
        if (activeContentSource == ActiveContentSource.LIBRARY) {
            Log.d("PasteItService", "Ignoring clipboard change while library item is active")
            return
        }
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
                        loadSourceTextIntoState(clipText)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PasteItService", "Error reading clipboard", e)
        }
    }

    private fun pasteClipboardContent() {
        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text.isNotBlank()) {
                    lastClipboardText = text
                    lastClipboardChangeTime = System.currentTimeMillis()
                    loadSourceTextIntoState(text)
                }
            }
        } catch (e: Exception) {
            Log.e("PasteItService", "Error pasting clipboard content", e)
        }
    }

    private fun loadTextDirectly(text: String?) {
        val directText = text?.trim().orEmpty()
        if (directText.isBlank()) return
        Log.d("PasteItService", "Direct process-text load: ${directText.take(50)}...")
        loadSourceTextIntoState(directText)
    }

    fun loadLibraryItem(savedItemId: String): Boolean {
        val store = SavedContentStore(this)
        val document = store.load(savedItemId) ?: return false

        if (document.sourceText.isNotBlank()) {
            loadSourceTextIntoState(document.sourceText, savedItemId = savedItemId)
            return true
        }

        val playable = SavedContentStore.findPlayableAudioFile(this, savedItemId) ?: return false
        val label = "Audio file available: ${playable.name}"
        loadSourceTextIntoState(label, savedItemId = savedItemId)
        return true
    }

    private fun loadSourceTextIntoState(text: String, savedItemId: String? = null) {
        val previousSourceText = lastSourceForDisplay
        val previousSavedItemId = activeSavedItemId
        val nextContentSource =
            if (savedItemId != null) ActiveContentSource.LIBRARY else ActiveContentSource.STANDARD

        mainHandler.removeCallbacks(playNextChunkRunnable)
        if (isPlaying) {
            stopPlayback()
        }
        ttsEngine?.resetChunkCrossfade()

        when {
            previousSavedItemId == null && savedItemId != null && previousSourceText == text -> {
                SessionAudioCacheStore.moveIntoSavedItem(this, savedItemId)
                ChunkManifestStore.moveSessionIntoSavedItem(this, savedItemId)
            }
            previousSavedItemId == null && previousSourceText.isNotEmpty() && previousSourceText != text -> {
                SessionAudioCacheStore.clear(this)
            }
            previousSavedItemId == null && savedItemId != null -> {
                SessionAudioCacheStore.clear(this)
            }
            previousSavedItemId != null && savedItemId == null -> {
                SessionAudioCacheStore.clear(this)
            }
        }

        lastSourceForDisplay = text
        activeSavedItemId = savedItemId
        activeContentSource = nextContentSource
        isMaxChunkMode = false  // always reset to standard mode on new text

        // Detect playable single-file MP3 for library items (merged export preferred).
        activeMergedFile = savedItemId?.let { id -> SavedContentStore.findPlayableAudioFile(this, id) }

        CurrentTextPersistence.save(
            this,
            preferences,
            text,
            savedItemId,
            contentSource = nextContentSource,
        )

        currentText = SpeechFormattingPipeline.plainTextForTts(preferences, text)
        // When a merged file is available, treat the whole document as one chunk so the seekbar
        // maps directly to real audio position within the single file.
        textChunks = if (activeMergedFile != null) listOf(currentText) else splitTextIntoChunks(currentText)
        refreshAverageChunkDurationEstimate()
        currentPosition = restoreResumePosition().coerceIn(0, (textChunks.size - 1).coerceAtLeast(0))
        persistResumePosition()
        if (currentPosition > 0) {
            pendingResumeHint = Pair(currentPosition + 1, textChunks.size)
        }

        clipboardListener?.invoke(text)
        Log.d("PasteItService", "Text loaded, chunks: ${textChunks.size}")
    }

    private fun splitTextIntoChunks(text: String): List<String> {
        if (isMaxChunkMode) {
            // Bypass the manifest so the larger chunks don't overwrite the standard chunk split.
            val maxChars = if (TextChunkingPreferences.shouldUseCloudChunking(preferences)) {
                TextChunkingPreferences.MAX_CLOUD_CHUNK_MAX_CHARS
            } else {
                AndroidTtsEngineParams.MAX_CHUNK_MAX_CHARS
            }
            return TextChunker.splitText(text, maxChars)
        }
        return ChunkManifestStore.ensureManifest(
            context = this,
            sourceText = text,
            savedItemId = activeSavedItemId,
        ) {
            TextChunker.splitText(text, TextChunkingPreferences.activeChunkMax(preferences))
        }
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
        ttsEngine?.resetChunkCrossfade()
        currentText = next
        textChunks = splitTextIntoChunks(currentText)
        refreshAverageChunkDurationEstimate()
        currentPosition = currentPosition.coerceIn(0, (textChunks.size - 1).coerceAtLeast(0))
        persistResumePosition()
    }

    fun updateTTSSettings(speechRate: Float, speechPitch: Float) {
        Log.d("PasteItService", "Updating TTS settings: rate=$speechRate, pitch=$speechPitch")
        ttsEngine?.applyPreferences(preferences, speechRateFromUi = speechRate, onComplete = null)
        // Re-split so chunk-size preference applies to text already loaded
        if (currentText.isNotEmpty()) {
            textChunks = splitTextIntoChunks(currentText)
            refreshAverageChunkDurationEstimate()
            if (currentPosition >= textChunks.size) {
                currentPosition = (textChunks.size - 1).coerceAtLeast(0)
            }
            persistResumePosition()
        }
    }

    fun togglePlayback() {
        Log.d("PasteItService", "Toggle playback - currentText: ${currentText.take(50)}..., isPlaying: $isPlaying, ttsInitialized: $ttsInitialized")

        if (currentText.isEmpty() && activeMergedFile == null) {
            Log.w("PasteItService", "No text to play")
            return
        }

        if (!ttsInitialized) {
            Log.w("PasteItService", "TTS not initialized yet")
            return
        }

        if (isPlaying) {
            pausePlayback()
        } else if (isPaused) {
            resumePlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (currentPosition < textChunks.size && ttsInitialized) {
            val merged = activeMergedFile
            if (merged != null && merged.exists()) {
                Log.d("PasteItService", "Playing merged MP3: ${merged.name}")
                ttsListener?.onPlaybackBuffering()
                ttsEngine?.playLocalFile(merged, "pasteit_merged")
            } else {
                val textToSpeak = textChunks[currentPosition]
                val prefetchNext =
                    if (currentPosition + 1 < textChunks.size) textChunks[currentPosition + 1] else null
                Log.d("PasteItService", "Speaking chunk $currentPosition: ${textToSpeak.take(50)}...")
                ttsListener?.onPlaybackBuffering()
                ttsEngine?.speak(textToSpeak, "pasteit_$currentPosition", prefetchNext)
            }
        } else {
            Log.w("PasteItService", "Cannot start playback - position: $currentPosition, chunks: ${textChunks.size}, tts: $ttsInitialized")
        }
    }

    private fun stopPlayback() {
        mainHandler.removeCallbacks(playNextChunkRunnable)
        Log.d("PasteItService", "Stopping playback")
        ttsEngine?.stop()
        isPlaying = false
        isPaused = false
    }

    private fun pausePlayback() {
        Log.d("PasteItService", "Pausing playback")
        if (ttsEngine?.pause() == true) {
            isPlaying = false
            isPaused = true
            updateMediaSession()
            updateNotification()
            ttsListener?.onPlaybackPaused()
        } else {
            stopPlayback()
        }
    }

    private fun resumePlayback() {
        Log.d("PasteItService", "Resuming playback")
        if (ttsEngine?.resume() == true) {
            isPlaying = true
            isPaused = false
            updateMediaSession()
            updateNotification()
            ttsListener?.onPlaybackStarted()
        } else {
            isPaused = false
            startPlayback()
        }
    }

    fun rewind() {
        Log.d("PasteItService", "Rewind")
        // If xAI is active and we're more than 15 s into the current chunk, seek back within it.
        if (isPlaying || isPaused) {
            val posMs = ttsEngine?.getCurrentPositionMs() ?: 0L
            if (ttsEngine?.isSeekable() == true && posMs > SEEK_STEP_MS) {
                ttsEngine?.seekWithinChunk(posMs - SEEK_STEP_MS)
                return
            }
        }
        // Otherwise jump to the previous chunk.
        val wasPlaying = isPlaying || isPaused
        stopPlayback()
        if (currentPosition > 0) currentPosition--
        if (wasPlaying) startPlayback() else { updateMediaSession(); updateNotification() }
        persistResumePosition()
    }

    fun fastForward() {
        Log.d("PasteItService", "Fast forward")
        // If xAI is active and there are more than 15 s left in the current chunk, seek forward.
        if (isPlaying || isPaused) {
            val posMs = ttsEngine?.getCurrentPositionMs() ?: 0L
            val durMs = ttsEngine?.getDurationMs() ?: 0L
            if (ttsEngine?.isSeekable() == true && durMs > 0 && posMs + SEEK_STEP_MS < durMs) {
                ttsEngine?.seekWithinChunk(posMs + SEEK_STEP_MS)
                return
            }
        }
        // Otherwise jump to the next chunk.
        val wasPlaying = isPlaying || isPaused
        stopPlayback()
        if (currentPosition < textChunks.size - 1) currentPosition++
        if (wasPlaying) startPlayback() else { updateMediaSession(); updateNotification() }
        persistResumePosition()
    }

    fun seekToChunk(index: Int) {
        if (textChunks.isEmpty()) return
        val target = index.coerceIn(0, textChunks.size - 1)
        Log.d("PasteItService", "Seek to chunk $target")
        val wasPlaying = isPlaying || isPaused
        stopPlayback()
        currentPosition = target
        persistResumePosition()
        if (wasPlaying) startPlayback() else { updateMediaSession(); updateNotification() }
    }

    fun getCurrentChunkPositionMs(): Long = ttsEngine?.getCurrentPositionMs() ?: 0L
    fun getCurrentChunkDurationMs(): Long = ttsEngine?.getDurationMs() ?: 0L

    fun setMaxChunkMode(enabled: Boolean) {
        if (isMaxChunkMode == enabled) return
        isMaxChunkMode = enabled
        if (currentText.isNotEmpty()) {
            val wasPlaying = isPlaying || isPaused
            stopPlayback()
            ttsEngine?.resetChunkCrossfade()
            textChunks = splitTextIntoChunks(currentText)
            refreshAverageChunkDurationEstimate()
            currentPosition = currentPosition.coerceIn(0, (textChunks.size - 1).coerceAtLeast(0))
            persistResumePosition()
            if (wasPlaying) startPlayback()
        }
    }

    fun isMaxChunkModeActive(): Boolean = isMaxChunkMode

    /** Returns (1-based chunkIndex, total) if a resume was just applied, null otherwise. Clears on read. */
    fun consumeResumeHint(): Pair<Int, Int>? {
        val hint = pendingResumeHint
        pendingResumeHint = null
        return hint
    }

    fun startOver() {
        Log.d("PasteItService", "Start over")
        val wasPlaying = isPlaying || isPaused
        stopPlayback()
        currentPosition = 0
        if (wasPlaying) {
            startPlayback()
        } else {
            updateMediaSession()
            updateNotification()
        }
        persistResumePosition()
    }

    fun setManualText(text: String) {
        Log.d("PasteItService", "Manual text set: ${text.take(50)}...")
        loadSourceTextIntoState(text)
    }

    fun setSavedText(text: String, savedItemId: String) {
        Log.d("PasteItService", "Saved text loaded: ${text.take(50)}... ($savedItemId)")
        loadSourceTextIntoState(text, savedItemId = savedItemId)
    }

    fun getCurrentSourceText(): String = lastSourceForDisplay
    fun getActiveSavedItemId(): String? = activeSavedItemId
    fun isLibraryItemActive(): Boolean = activeContentSource == ActiveContentSource.LIBRARY
    fun isMergedMp3Active(): Boolean = activeMergedFile?.exists() == true
    fun isPlayingNow(): Boolean = isPlaying
    fun isPausedNow(): Boolean = isPaused
    fun getCurrentChunkIndex(): Int = currentPosition
    fun getTotalChunkCount(): Int = textChunks.size
    fun getCurrentEngineLabel(): String = ttsEngine?.getUiEngineLabel().orEmpty()
    fun getAverageChunkDurationMs(): Long = averageChunkDurationMs
    fun getEstimatedElapsedMs(): Long = (currentPosition.toLong() * averageChunkDurationMs).coerceAtLeast(0L)
    fun getEstimatedRemainingMs(): Long {
        val remainingChunks = (textChunks.size - currentPosition - 1).coerceAtLeast(0)
        return (remainingChunks.toLong() * averageChunkDurationMs).coerceAtLeast(0L)
    }
    fun getChapterTitles(): List<String> =
        textChunks.mapIndexed { index, chunk ->
            val normalized = chunk.replace(Regex("\\s+"), " ").trim()
            if (normalized.isBlank()) return@mapIndexed "Part ${index + 1}"
            val sentence = normalized.split(Regex("(?<=[.!?])\\s+"), limit = 2).firstOrNull().orEmpty().trim()
            val base = sentence.ifBlank { normalized }.take(60).trim()
            if (base.isBlank()) "Part ${index + 1}" else base
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

    private fun restoreResumePosition(): Int {
        val source = lastSourceForDisplay.ifBlank { return 0 }
        return PlaybackResumeStore.load(
            context = this,
            sourceText = source,
            savedItemId = activeSavedItemId,
        )?.chunkIndex ?: 0
    }

    private fun persistResumePosition() {
        val source = lastSourceForDisplay.ifBlank { return }
        PlaybackResumeStore.save(
            context = this,
            sourceText = source,
            savedItemId = activeSavedItemId,
            chunkIndex = currentPosition,
        )
    }

    private fun refreshAverageChunkDurationEstimate() {
        averageChunkDurationMs = calculateAverageChunkDurationMs()
    }

    private fun calculateAverageChunkDurationMs(): Long {
        if (textChunks.isEmpty()) return 0L
        val savedItemId = activeSavedItemId ?: return 0L
        val voice = XaiVoiceOption.fromPreferences(preferences)

        var totalMs = 0L
        var count = 0
        textChunks.forEach { chunk ->
            val keyMaterial = listOf(
                "provider=xai",
                "voice=${voice.prefValue}",
                "language=en",
                "codec=mp3",
                "sample_rate=44100",
                "bit_rate=128000",
                "text=$chunk",
            ).joinToString("|")
            val file = SavedContentStore.audioCacheFile(this, savedItemId, keyMaterial)
            if (!file.exists()) return@forEach
            val duration = readDurationMs(file)
            if (duration > 0L) {
                totalMs += duration
                count += 1
            }
        }
        return if (count > 0) totalMs / count else 0L
    }

    private fun readDurationMs(file: java.io.File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "PasteItSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { togglePlayback() }
                override fun onPause() { togglePlayback() }
                override fun onSkipToNext() { fastForward() }
                override fun onSkipToPrevious() { rewind() }
                override fun onStop() { stopPlayback() }
            })
            isActive = true
        }
        updateMediaSession()
    }

    private fun updateMediaSession() {
        val state = when {
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            isPaused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(actions)
                .build()
        )
        val title = lastSourceForDisplay
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(80)
            ?: "PasteIt"
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "PasteIt")
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (currentPosition + 1).toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, textChunks.size.toLong())
                .build()
        )
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
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun serviceIntent(action: String) = PendingIntent.getService(
            this, action.hashCode(), Intent(this, ClipboardMonitorService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val rewindPi = serviceIntent(ACTION_REWIND)
        val togglePi = serviceIntent(ACTION_TOGGLE_PLAYBACK)
        val ffPi = serviceIntent(ACTION_FAST_FORWARD)

        val toggleIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val toggleLabel = if (isPlaying) "Pause" else "Play"

        val title = lastSourceForDisplay
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(60)
            ?: "PasteIt"
        val subtitle = when {
            isPlaying -> "Reading · chunk ${currentPosition + 1} of ${textChunks.size}"
            isPaused -> "Paused · chunk ${currentPosition + 1} of ${textChunks.size}"
            else -> "Ready to read"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_rew, "Rewind", rewindPi)
            .addAction(toggleIcon, toggleLabel, togglePi)
            .addAction(android.R.drawable.ic_media_ff, "Forward", ffPi)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d("PasteItService", "Service destroyed")
        ttsEngine?.release()
        mediaSession.isActive = false
        mediaSession.release()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val SEEK_STEP_MS = 15_000L
        private const val CHANNEL_ID = "PasteItService"
        const val ACTION_TOGGLE_PLAYBACK = "com.example.pasteit.action.TOGGLE_PLAYBACK"
        const val ACTION_PASTE_CLIPBOARD = "com.example.pasteit.action.PASTE_CLIPBOARD"
        const val ACTION_REWIND = "com.example.pasteit.action.REWIND"
        const val ACTION_FAST_FORWARD = "com.example.pasteit.action.FAST_FORWARD"
        const val ACTION_LOAD_TEXT_DIRECTLY = "com.example.pasteit.action.LOAD_TEXT_DIRECTLY"
        const val EXTRA_DIRECT_TEXT = "com.example.pasteit.extra.DIRECT_TEXT"
    }
}
