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
import android.os.SystemClock
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
    /** Document ranges (half-open) into [currentText] paired with [textChunks]. */
    private var chunkDocumentRanges = listOf<IntRange>()
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

    /** Notification / media session title: app name, or library item title when applicable (never body text). */
    private var notificationContentTitle: String? = null

    private var clipboardListener: ((String) -> Unit)? = null
    private var ttsListener: TTSListener? = null
    private lateinit var preferences: SharedPreferences
    private lateinit var mediaSession: MediaSessionCompat

    /** True while the user has pressed MAX — uses engine hard limits for chunk size. */
    private var isMaxChunkMode = false

    /** Set when a resume position > 0 is restored from persistence; consumed once by the UI. */
    private var pendingResumeHint: Pair<Int, Int>? = null
    private var averageChunkDurationMs: Long = 0L
    private var pendingResumeSeekMs: Long = 0L

    /** Wall-clock anchor for the Android-TTS synthetic position; 0 when the xAI engine is active. */
    private var syntheticUtteranceStartRealtimeMs: Long = 0L

    /** Reading schedule for the chunk currently being spoken, lazily (re)built when [currentPosition] changes. */
    private var currentChunkSchedule: ReadingSchedule? = null
    private var scheduleSourceChunk: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    /** Posted after a chunk finishes so we never call speak() re-entrantly from onSpeakDone. */
    private val playNextChunkRunnable = Runnable { startPlayback() }
    private val progressPersistRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                persistResumePosition(includePlaybackPosition = true)
                mainHandler.postDelayed(this, RESUME_PERSIST_INTERVAL_MS)
            }
        }
    }

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
        createNotificationChannel()
        restoreInitialTextState()
        setupClipboardListener()
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
                val resumeOffsetMs = pendingResumeSeekMs.coerceAtLeast(0L)
                if (pendingResumeSeekMs > 0L) {
                    ttsEngine?.seekWithinChunk(pendingResumeSeekMs)
                    pendingResumeSeekMs = 0L
                }
                syntheticUtteranceStartRealtimeMs =
                    if (ttsEngine?.isSeekable() == true) 0L
                    else SystemClock.elapsedRealtime() - resumeOffsetMs
                ensureReadingSchedule()
                mainHandler.removeCallbacks(progressPersistRunnable)
                mainHandler.postDelayed(progressPersistRunnable, RESUME_PERSIST_INTERVAL_MS)
                ttsListener?.onPlaybackStarted()
                updateMediaSession()
                updateNotification()
            }

            override fun onSpeakDone(utteranceId: String) {
                Log.d("PasteItService", "TTS finished: $utteranceId")
                isPlaying = false
                isPaused = false
                syntheticUtteranceStartRealtimeMs = 0L

                if (currentPosition < textChunks.size - 1) {
                    currentPosition++
                    invalidateReadingSchedule()
                    persistResumePosition(includePlaybackPosition = false)
                    // Defer: synchronous start here calls speak() → stop() → cancel while the
                    // finished chunk's coroutine is still unwinding; its CancellationException handler
                    // could clear isPlaying and abort the next chunk mid-generate.
                    mainHandler.removeCallbacks(playNextChunkRunnable)
                    mainHandler.post(playNextChunkRunnable)
                } else {
                    isMaxChunkMode = false  // auto-reset at end of document
                    persistResumePosition(includePlaybackPosition = false)
                    mainHandler.removeCallbacks(progressPersistRunnable)
                    updateMediaSession()
                    updateNotification()
                    ttsListener?.onPlaybackFinished()
                }
            }

            override fun onSpeakError(utteranceId: String, error: String) {
                Log.e("PasteItService", "TTS error: $utteranceId - $error")
                isPlaying = false
                isPaused = false
                syntheticUtteranceStartRealtimeMs = 0L
                mainHandler.removeCallbacks(progressPersistRunnable)
                updateMediaSession()
                updateNotification()
                ttsListener?.onPlaybackError()
            }

            override fun onEngineReconfigured() {
                // Android TTS applyPreferences() always runs (even when xAI is playing). Ignore
                // that callback for playback flags so ExoPlayer state stays in sync with isPlaying.
                if (ttsEngine?.isXaiEngineActive() == true) return
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
            Log.d("PasteItService", "Restoring persisted text")
            loadSourceTextIntoState(persisted.sourceText, savedItemId = persisted.savedItemId)
            return
        }

        checkClipboardContent()
    }

    private fun checkClipboardContent() {
        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0).text?.toString() ?: ""
                Log.d("PasteItService", "Clipboard text updated")

                // Process any non-empty text, even if it's the same as before
                // (user might want to re-read the same content)
                if (clipText.isNotEmpty()) {
                    // While a library item is open, only ignore clipboard updates that duplicate the
                    // current document — otherwise copying new text must switch to a session load.
                    if (activeContentSource == ActiveContentSource.LIBRARY && clipText == lastSourceForDisplay) {
                        return
                    }
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
        Log.d("PasteItService", "Direct process-text load")
        loadSourceTextIntoState(directText)
    }

    fun loadLibraryItem(savedItemId: String): Boolean {
        val store = SavedContentStore(this)
        val document = store.load(savedItemId) ?: return false

        if (document.sourceText.isNotBlank()) {
            loadSourceTextIntoState(
                document.sourceText,
                savedItemId = savedItemId,
                libraryDisplayTitle = document.item.title,
            )
            return true
        }

        val playable = SavedContentStore.findPlayableAudioFile(this, savedItemId) ?: return false
        val label = "Audio file available: ${playable.name}"
        loadSourceTextIntoState(
            label,
            savedItemId = savedItemId,
            libraryDisplayTitle = document.item.title,
        )
        return true
    }

    private fun loadSourceTextIntoState(
        text: String,
        savedItemId: String? = null,
        libraryDisplayTitle: String? = null,
    ) {
        val previousSourceText = lastSourceForDisplay
        val previousSavedItemId = activeSavedItemId
        val nextContentSource =
            if (savedItemId != null) ActiveContentSource.LIBRARY else ActiveContentSource.STANDARD

        mainHandler.removeCallbacks(playNextChunkRunnable)
        // Always tear down active/paused engine state before swapping source text.
        // Otherwise a paused library ExoPlayer session can resume after we load new clipboard text.
        if (isPlaying || isPaused) {
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
        notificationContentTitle = resolveNotificationTitle(savedItemId, libraryDisplayTitle)
        isMaxChunkMode = false  // always reset to standard mode on new text
        // Keep clipboard debounce aligned with the active document (paste, library load, etc.).
        lastClipboardText = text
        lastClipboardChangeTime = System.currentTimeMillis()

        // Only use the stitched merged.mp3 for single-file playback — never individual chunk files.
        // Chunk files are audio fragments, not full-document audio; routing them here collapses
        // the chunk list to 1 and breaks chunk-level resume for non-merged items.
        activeMergedFile = savedItemId?.let { id ->
            PasteItStoragePaths.mergedMp3File(this, id).takeIf { it.exists() }
        }

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
        applyChunks(
            if (activeMergedFile != null) listOf(currentText) else splitTextIntoChunks(currentText),
        )
        refreshAverageChunkDurationEstimate()
        val restored = restoreResumeState()
        currentPosition = restored.chunkIndex.coerceIn(0, (textChunks.size - 1).coerceAtLeast(0))
        pendingResumeSeekMs = restored.chunkPositionMs
        if (currentPosition > 0 || pendingResumeSeekMs > 0L) {
            pendingResumeHint = Pair(currentPosition + 1, textChunks.size)
        }

        clipboardListener?.invoke(text)
        Log.d("PasteItService", "Text loaded, chunks: ${textChunks.size}")

        // Media session was initialized before restore; keep metadata in sync with [notificationContentTitle].
        updateMediaSession()
        updateNotification()
    }

    private fun splitTextIntoChunks(text: String): List<String> {
        if (isMaxChunkMode) {
            // Bypass the manifest so the larger chunks don't overwrite the standard chunk split.
            val maxChars = if (TextChunkingPreferences.shouldUseCloudChunking(this, preferences)) {
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
            TextChunker.splitText(text, TextChunkingPreferences.activeChunkMax(this, preferences))
        }
    }

    /**
     * Updates [textChunks] and rebuilds [chunkDocumentRanges] so downstream code always sees
     * the two in lockstep. Ranges are derived by locating each chunk inside [currentText]; the
     * merged-file single-chunk path collapses to `[0, currentText.length)` automatically.
     */
    private fun applyChunks(chunks: List<String>) {
        textChunks = chunks
        chunkDocumentRanges = computeChunkDocumentRanges(currentText, chunks)
    }

    private fun computeChunkDocumentRanges(text: String, chunks: List<String>): List<IntRange> {
        if (chunks.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>(chunks.size)
        var cursor = 0
        for (chunk in chunks) {
            if (chunk.isEmpty()) {
                ranges += cursor until cursor
                continue
            }
            val idx = text.indexOf(chunk, cursor)
            if (idx < 0) {
                val start = cursor.coerceAtMost(text.length)
                val end = (start + chunk.length).coerceAtMost(text.length)
                ranges += start until end
                cursor = end
            } else {
                val end = idx + chunk.length
                ranges += idx until end
                cursor = end
            }
        }
        return ranges
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
        applyChunks(splitTextIntoChunks(currentText))
        refreshAverageChunkDurationEstimate()
        currentPosition = currentPosition.coerceIn(0, (textChunks.size - 1).coerceAtLeast(0))
        persistResumePosition(
            includePlaybackPosition = false,
            explicitChunkPositionMs = pendingResumeSeekMs.takeIf { it > 0L },
        )
    }

    fun updateTTSSettings(speechRate: Float, speechPitch: Float) {
        Log.d("PasteItService", "Updating TTS settings: rate=$speechRate, pitch=$speechPitch")
        ttsEngine?.applyPreferences(preferences, speechRateFromUi = speechRate, onComplete = null)
        // Re-split so chunk-size preference applies to text already loaded
        if (currentText.isNotEmpty()) {
            applyChunks(splitTextIntoChunks(currentText))
            refreshAverageChunkDurationEstimate()
            if (currentPosition >= textChunks.size) {
                currentPosition = (textChunks.size - 1).coerceAtLeast(0)
            }
            persistResumePosition(
                includePlaybackPosition = isPlaying || isPaused,
                explicitChunkPositionMs = pendingResumeSeekMs.takeIf { it > 0L },
            )
        }
    }

    fun togglePlayback() {
        Log.d("PasteItService", "Toggle playback (isPlaying=$isPlaying, ttsInitialized=$ttsInitialized)")

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
                Log.d("PasteItService", "Speaking chunk $currentPosition of ${textChunks.size}")
                ttsListener?.onPlaybackBuffering()
                ttsEngine?.speak(textToSpeak, "pasteit_$currentPosition", prefetchNext)
            }
        } else {
            Log.w("PasteItService", "Cannot start playback - position: $currentPosition, chunks: ${textChunks.size}, tts: $ttsInitialized")
        }
    }

    private fun stopPlayback() {
        mainHandler.removeCallbacks(playNextChunkRunnable)
        mainHandler.removeCallbacks(progressPersistRunnable)
        Log.d("PasteItService", "Stopping playback")
        ttsEngine?.stop()
        isPlaying = false
        isPaused = false
        syntheticUtteranceStartRealtimeMs = 0L
    }

    private fun pausePlayback() {
        Log.d("PasteItService", "Pausing playback")
        if (ttsEngine?.pause() == true) {
            isPlaying = false
            isPaused = true
            persistResumePosition(includePlaybackPosition = true)
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
        persistResumePosition(includePlaybackPosition = false)
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
        persistResumePosition(includePlaybackPosition = false)
    }

    fun seekToChunk(index: Int) {
        if (textChunks.isEmpty()) return
        val target = index.coerceIn(0, textChunks.size - 1)
        Log.d("PasteItService", "Seek to chunk $target")
        val wasPlaying = isPlaying || isPaused
        stopPlayback()
        currentPosition = target
        pendingResumeSeekMs = 0L
        persistResumePosition(includePlaybackPosition = false)
        if (wasPlaying) startPlayback() else { updateMediaSession(); updateNotification() }
    }

    fun seekToProgress(progress: Int, resolutionPerChunk: Int) {
        if (textChunks.isEmpty() || resolutionPerChunk <= 0) return

        val maxProgress = (textChunks.size * resolutionPerChunk).coerceAtLeast(resolutionPerChunk)
        val bounded = progress.coerceIn(0, maxProgress)
        val targetChunk = (bounded / resolutionPerChunk).coerceIn(0, textChunks.size - 1)
        val withinUnits = (bounded % resolutionPerChunk).coerceIn(0, resolutionPerChunk - 1)
        val withinFraction = withinUnits.toFloat() / resolutionPerChunk.toFloat()

        val targetDuration = estimateChunkDurationMs(targetChunk)
        val targetSeekMs = (targetDuration * withinFraction).toLong().coerceAtLeast(0L)

        val wasPlaying = isPlaying || isPaused
        stopPlayback()
        currentPosition = targetChunk
        pendingResumeSeekMs = targetSeekMs
        persistResumePosition(includePlaybackPosition = false, explicitChunkPositionMs = targetSeekMs)
        if (wasPlaying) startPlayback() else { updateMediaSession(); updateNotification() }
    }

    fun getCurrentChunkPositionMs(): Long = effectiveChunkPositionMs()
    fun getCurrentChunkDurationMs(): Long = effectiveChunkDurationMs()
    fun getCurrentChunkText(): String =
        textChunks.getOrNull(currentPosition).orEmpty()

    /**
     * Returns a character range in [getCurrentChunkText] that approximates what the TTS engine
     * is currently reading. Uses the [PlaybackReadingEstimator] schedule cached for the active
     * chunk; returns [IntRange.EMPTY] when no chunk is active or timing is unavailable.
     */
    fun getEstimatedReadingRangeInChunk(
        marginChars: Int = PlaybackReadingEstimator.DEFAULT_MARGIN_CHARS,
    ): IntRange {
        val chunkText = textChunks.getOrNull(currentPosition).orEmpty()
        if (chunkText.isEmpty()) return IntRange.EMPTY
        val schedule = ensureReadingScheduleFor(chunkText)
        val durationMs = effectiveChunkDurationMs()
        val positionMs = positionMsForReadingEstimate()
        return PlaybackReadingEstimator.rangeForTime(schedule, positionMs, durationMs, marginChars)
    }

    /** TTS-aligned plain text; this is the exact string the reader should display. */
    fun getPlaybackPlainText(): String = currentText

    /**
     * Maps [getEstimatedReadingRangeInChunk] into [currentText] coordinates so the reader can
     * highlight across chunk boundaries. Returns [IntRange.EMPTY] when no chunk is active.
     */
    fun getEstimatedReadingRangeInDocument(
        marginChars: Int = PlaybackReadingEstimator.DEFAULT_MARGIN_CHARS,
    ): IntRange {
        val chunkRange = chunkDocumentRanges.getOrNull(currentPosition) ?: return IntRange.EMPTY
        if (chunkRange.isEmpty()) return IntRange.EMPTY
        val inChunk = getEstimatedReadingRangeInChunk(marginChars)
        if (inChunk.isEmpty()) return IntRange.EMPTY
        val start = (chunkRange.first + inChunk.first).coerceIn(chunkRange.first, chunkRange.last)
        val endInclusive = (chunkRange.first + inChunk.last).coerceIn(chunkRange.first, chunkRange.last)
        return if (endInclusive < start) IntRange.EMPTY else start..endInclusive
    }

    /**
     * Seeks playback to the chunk containing [offset] in [currentText], with an in-chunk
     * position approximated from the character fraction. Mirrors [seekToProgress] semantics.
     */
    fun seekToDocumentPlainOffset(offset: Int) {
        if (chunkDocumentRanges.isEmpty() || currentText.isEmpty()) return
        val clamped = offset.coerceIn(0, currentText.length)
        val targetChunk = chunkDocumentRanges
            .indexOfFirst { range: IntRange -> clamped < range.last + 1 }
            .let { idx: Int -> if (idx < 0) chunkDocumentRanges.lastIndex else idx }
        val range = chunkDocumentRanges[targetChunk]
        val chunkLength = (range.last + 1 - range.first).coerceAtLeast(1)
        val withinChars = (clamped - range.first).coerceIn(0, chunkLength)
        val fraction = withinChars.toFloat() / chunkLength.toFloat()
        val duration = estimateChunkDurationMs(targetChunk).takeIf { it > 0L }
            ?: fallbackChunkDurationMs(targetChunk)
        val seekMs = (duration * fraction).toLong().coerceAtLeast(0L)

        val wasPlaying = isPlaying || isPaused
        stopPlayback()
        currentPosition = targetChunk.coerceIn(0, (textChunks.size - 1).coerceAtLeast(0))
        pendingResumeSeekMs = seekMs
        invalidateReadingSchedule()
        persistResumePosition(includePlaybackPosition = false, explicitChunkPositionMs = seekMs)
        if (wasPlaying) startPlayback() else { updateMediaSession(); updateNotification() }
    }

    private fun effectiveChunkPositionMs(): Long {
        val engine = ttsEngine
        if (engine?.isSeekable() == true) {
            return engine.getCurrentPositionMs().coerceAtLeast(0L)
        }
        if (isPlaying && syntheticUtteranceStartRealtimeMs > 0L) {
            val raw = SystemClock.elapsedRealtime() - syntheticUtteranceStartRealtimeMs
            val ceiling = effectiveChunkDurationMs()
            return if (ceiling > 0L) raw.coerceIn(0L, ceiling) else raw.coerceAtLeast(0L)
        }
        return pendingResumeSeekMs.coerceAtLeast(0L)
    }

    /**
     * Position used only for follow-along highlighting. Lags [effectiveChunkPositionMs] by
     * [READING_ESTIMATE_START_LAG_MS] so the band does not run ahead at chunk start (TTS often
     * begins audibly after callbacks / ExoPlayer reports 0 ms).
     */
    private fun positionMsForReadingEstimate(): Long {
        val raw = effectiveChunkPositionMs()
        return (raw - READING_ESTIMATE_START_LAG_MS).coerceAtLeast(0L)
    }

    private fun effectiveChunkDurationMs(): Long {
        val engine = ttsEngine
        if (engine?.isSeekable() == true) {
            val live = engine.getDurationMs()
            if (live > 0L) return live
        }
        val cached = estimateChunkDurationMs(currentPosition)
        if (cached > 0L) return cached
        return fallbackChunkDurationMs(currentPosition)
    }

    /**
     * Character-based duration estimate used when neither ExoPlayer nor a cached audio file can
     * provide a real duration. Calibrated loosely against typical Android TTS output at the
     * user's preferred speech rate (~15 chars/sec at 1.0x).
     */
    private fun fallbackChunkDurationMs(chunkIndex: Int): Long {
        val chunk = textChunks.getOrNull(chunkIndex) ?: return 0L
        if (chunk.isEmpty()) return 0L
        val ratePercent = preferences.getInt(
            AndroidTtsEngineParams.PREF_SPEECH_RATE,
            AndroidTtsEngineParams.DEFAULT_SPEECH_RATE_PERCENT,
        )
        val rate = (ratePercent / 100f).coerceIn(0.25f, 4.0f)
        val charsPerSec = (15.0f * rate).coerceAtLeast(3.0f)
        return ((chunk.length / charsPerSec) * 1000f).toLong().coerceAtLeast(0L)
    }

    private fun ensureReadingSchedule() {
        val chunkText = textChunks.getOrNull(currentPosition).orEmpty()
        if (chunkText.isEmpty()) {
            invalidateReadingSchedule()
            return
        }
        ensureReadingScheduleFor(chunkText)
    }

    private fun ensureReadingScheduleFor(chunkText: String): ReadingSchedule {
        val cached = currentChunkSchedule
        if (cached != null && scheduleSourceChunk == chunkText) return cached
        val built = PlaybackReadingEstimator.buildSchedule(chunkText)
        currentChunkSchedule = built
        scheduleSourceChunk = chunkText
        return built
    }

    private fun invalidateReadingSchedule() {
        currentChunkSchedule = null
        scheduleSourceChunk = ""
    }

    fun setMaxChunkMode(enabled: Boolean) {
        if (isMaxChunkMode == enabled) return
        isMaxChunkMode = enabled
        if (currentText.isNotEmpty()) {
            val wasPlaying = isPlaying || isPaused
            stopPlayback()
            ttsEngine?.resetChunkCrossfade()
            applyChunks(splitTextIntoChunks(currentText))
            refreshAverageChunkDurationEstimate()
            currentPosition = currentPosition.coerceIn(0, (textChunks.size - 1).coerceAtLeast(0))
            persistResumePosition(includePlaybackPosition = false)
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
        persistResumePosition(includePlaybackPosition = false)
    }

    fun setManualText(text: String) {
        Log.d("PasteItService", "Manual text set")
        loadSourceTextIntoState(text)
    }

    fun setSavedText(text: String, savedItemId: String) {
        Log.d("PasteItService", "Saved text loaded (savedItemId=$savedItemId)")
        val title = SavedContentStore(this).load(savedItemId)?.item?.title
        loadSourceTextIntoState(text, savedItemId = savedItemId, libraryDisplayTitle = title)
    }

    fun getCurrentSourceText(): String = lastSourceForDisplay

    /** In-app / PiP header: app name for non-library content, or the saved library title (never body text). */
    fun getNowPlayingDisplayTitle(): String =
        notificationContentTitle ?: getString(R.string.app_name)
    fun getActiveSavedItemId(): String? = activeSavedItemId
    fun isLibraryItemActive(): Boolean = activeContentSource == ActiveContentSource.LIBRARY

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

    private fun restoreResumeState(): PlaybackResumeStore.ResumeState {
        val source = lastSourceForDisplay.ifBlank {
            return PlaybackResumeStore.ResumeState(
                sourceHash = "",
                chunkIndex = 0,
                chunkPositionMs = 0L,
            )
        }
        return PlaybackResumeStore.load(
            context = this,
            sourceText = source,
            savedItemId = activeSavedItemId,
        ) ?: PlaybackResumeStore.ResumeState(
            sourceHash = SavedContentStore.hashKeyMaterial(source),
            chunkIndex = 0,
            chunkPositionMs = 0L,
        )
    }

    private fun persistResumePosition(
        includePlaybackPosition: Boolean = false,
        explicitChunkPositionMs: Long? = null,
    ) {
        val source = lastSourceForDisplay.ifBlank { return }
        val chunkPositionMs =
            when {
                explicitChunkPositionMs != null -> explicitChunkPositionMs.coerceAtLeast(0L)
                includePlaybackPosition -> {
                    val livePosition = (ttsEngine?.getCurrentPositionMs() ?: 0L).coerceAtLeast(0L)
                    if (livePosition > 0L) {
                        livePosition
                    } else {
                        pendingResumeSeekMs.coerceAtLeast(0L)
                    }
                }
                else -> 0L
            }
        PlaybackResumeStore.save(
            context = this,
            sourceText = source,
            savedItemId = activeSavedItemId,
            chunkIndex = currentPosition,
            chunkPositionMs = chunkPositionMs,
        )
    }

    private fun estimateChunkDurationMs(chunkIndex: Int): Long {
        if (chunkIndex !in textChunks.indices) return 0L
        val savedItemId = activeSavedItemId
        if (activeMergedFile != null) {
            val duration = ttsEngine?.getDurationMs()?.takeIf { it > 0L }
                ?: activeMergedFile?.let { readDurationMs(it) } ?: 0L
            return duration
        }
        if (savedItemId == null) return averageChunkDurationMs
        val voice = XaiVoiceOption.fromPreferences(preferences)
        val keyMaterial = listOf(
            "provider=xai",
            "voice=${voice.prefValue}",
            "language=en",
            "codec=mp3",
            "sample_rate=44100",
            "bit_rate=128000",
            "text=${textChunks[chunkIndex]}",
        ).joinToString("|")
        val file = SavedContentStore.audioCacheFile(this, savedItemId, keyMaterial)
        val exact = if (file.exists()) readDurationMs(file) else 0L
        return if (exact > 0L) exact else averageChunkDurationMs
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
        val callback: MediaSessionCompat.Callback = object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                togglePlayback()
            }

            override fun onPause() {
                togglePlayback()
            }

            override fun onSkipToNext() {
                fastForward()
            }

            override fun onSkipToPrevious() {
                rewind()
            }

            override fun onStop() {
                stopPlayback()
            }
        }
        val session = MediaSessionCompat(this, "PasteItSession")
        session.setCallback(callback)
        session.isActive = true
        mediaSession = session
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
        val title = notificationContentTitle ?: getString(R.string.app_name)
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                // Leave artist empty — the system already shows the app name; "PasteIt" here duplicated it on the lock screen.
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "")
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (currentPosition + 1).toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, textChunks.size.toLong())
                .build()
        )
    }

    private fun resolveNotificationTitle(savedItemId: String?, libraryDisplayTitle: String?): String =
        when {
            savedItemId == null -> getString(R.string.app_name)
            else -> {
                libraryDisplayTitle?.trim()?.takeIf { it.isNotBlank() }
                    ?: SavedContentStore(this).load(savedItemId)?.item?.title?.trim()?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.app_name)
            }
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

        val title = notificationContentTitle ?: getString(R.string.app_name)
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
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
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
        persistResumePosition(
            includePlaybackPosition = true,
            explicitChunkPositionMs = pendingResumeSeekMs.takeIf { it > 0L },
        )
        mainHandler.removeCallbacks(progressPersistRunnable)
        ttsEngine?.release()
        mediaSession.isActive = false
        mediaSession.release()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val SEEK_STEP_MS = 15_000L
        /** Shifts the reading band slightly late vs raw playback time (see [positionMsForReadingEstimate]). */
        private const val READING_ESTIMATE_START_LAG_MS = 300L
        private const val RESUME_PERSIST_INTERVAL_MS = 2_000L
        private const val CHANNEL_ID = "PasteItService"
        const val ACTION_TOGGLE_PLAYBACK = "com.example.pasteit.action.TOGGLE_PLAYBACK"
        const val ACTION_PASTE_CLIPBOARD = "com.example.pasteit.action.PASTE_CLIPBOARD"
        const val ACTION_REWIND = "com.example.pasteit.action.REWIND"
        const val ACTION_FAST_FORWARD = "com.example.pasteit.action.FAST_FORWARD"
        const val ACTION_LOAD_TEXT_DIRECTLY = "com.example.pasteit.action.LOAD_TEXT_DIRECTLY"
        const val EXTRA_DIRECT_TEXT = "com.example.pasteit.extra.DIRECT_TEXT"
    }
}
