package com.example.pasteit

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.Locale

class MainActivity : BaseSwipeActivity() {

    override val swipeIndex = 0
    override fun isSwipeEnabled(): Boolean = !isInPipMode
    private lateinit var playButton: ImageButton
    private lateinit var rewindButton: ImageButton
    private lateinit var fastForwardButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var themeToggleButton: ImageButton
    private lateinit var pasteButton: ImageButton
    private lateinit var stockFormatButton: ImageButton
    private lateinit var bookmarkButton: ImageButton
    private lateinit var textDisplay: TextView
    private lateinit var textContainer: ScrollView
    private lateinit var ttsProgressBar: ProgressBar
    private lateinit var playbackStatusText: TextView
    private lateinit var cacheStatusText: TextView
    private lateinit var nowPlayingTitle: TextView
    private lateinit var chunkProgressBar: SeekBar
    private lateinit var chunkMetaText: TextView
    private lateinit var sourceMetaText: TextView
    private lateinit var timeEstimateText: TextView
    private lateinit var engineBadgeText: TextView
    private lateinit var documentMetaText: TextView
    private lateinit var statusDot: View
    private lateinit var startOverButton: TextView
    private lateinit var maxChunkButton: TextView
    private lateinit var textPanelCard: View
    private lateinit var mainContent: View
    private lateinit var navPlayerTab: LinearLayout
    private lateinit var navPlayerIcon: ImageView
    private lateinit var navPlayerLabel: TextView

    // PiP overlay views
    private lateinit var pipOverlay: View
    private lateinit var pipTitle: TextView
    private lateinit var pipChunkProgressBar: ProgressBar
    private lateinit var pipChunkText: TextView
    private lateinit var pipStatusDot: View
    private lateinit var pipStatusLabel: TextView

    private var clipboardService: ClipboardMonitorService? = null
    private var serviceBound = false
    private var isInPipMode = false
    private var isBuffering = false
    private var isSaved = false
    private var isSeeking = false
    private var pendingLibrarySelectionId: String? = null

    // Polls playback position every 300 ms so the SeekBar thumb animates smoothly within
    // the current chunk and the reading-band highlight advances alongside it.
    private val seekBarHandler = Handler(Looper.getMainLooper())
    private val seekBarRunnable = object : Runnable {
        override fun run() {
            val service = clipboardService
            if (!isSeeking && service != null && service.isPlayingNow()) {
                val chunkIndex0 = service.getCurrentChunkIndex()
                val totalChunks = service.getTotalChunkCount()
                if (totalChunks > 0) {
                    val posMs = service.getCurrentChunkPositionMs()
                    val durMs = service.getCurrentChunkDurationMs()
                    val within = if (durMs > 0) (posMs * SEEK_RESOLUTION / durMs).toInt() else 0
                    val maxProgress = (totalChunks * SEEK_RESOLUTION - 1).coerceAtLeast(SEEK_RESOLUTION)
                    chunkProgressBar.max = maxProgress
                    chunkProgressBar.progress = (chunkIndex0 * SEEK_RESOLUTION + within)
                        .coerceIn(0, maxProgress)
                }
                maybeRefreshReadingHighlight(service)
            }
            seekBarHandler.postDelayed(this, 300L)
        }
    }

    // Cached plain-text Spannable so the 300ms poll only re-applies the highlight span.
    private var cachedPlainSource: String = ""
    private var cachedPlainDisplay: SpannableStringBuilder? = null
    // Previously applied highlight span so we can swap it in-place without rebuilding the text.
    private var appliedHighlightSpan: BackgroundColorSpan? = null
    // Throttle state for the reading band refresh.
    private var lastAppliedRangeStart: Int = Int.MIN_VALUE
    private var lastHighlightUpdateMs: Long = 0L

    // Follow-mode state: auto-scroll the text view so the reading band stays visible.
    private var followModeEnabled: Boolean = true
    private var userTouchingTextContainer: Boolean = false
    private var lastScrollRequestY: Int = Int.MIN_VALUE

    // Tap-to-seek gesture tracking on the text display.
    private var tapDownX: Float = 0f
    private var tapDownY: Float = 0f
    private var tapDownTimeMs: Long = 0L
    private var tapMoved: Boolean = false

    private lateinit var preferences: SharedPreferences
    private lateinit var savedContentStore: SavedContentStore

    // Selection is now handled via onResume + SharedPreferences (PREF_PENDING_SELECTION),
    // so the launcher just needs to open the activity.
    private fun openLibrary() {
        startActivity(
            Intent(this, LibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ClipboardMonitorService.LocalBinder
            clipboardService = binder.getService()
            serviceBound = true
            applyPendingLibrarySelection()

            applyTTSSettings()
            clipboardService?.reapplySpeechFormatting()
            updateStockFormatButtonUi()
            updatePipActions()
            refreshUi()

            // Show resume toast once if playback position was restored from persistence.
            clipboardService?.consumeResumeHint()?.let { (chunk, total) ->
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.resume_indicator, chunk, total),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            clipboardService?.setClipboardListener { clipText ->
                runOnUiThread {
                    Log.d("PasteItMain", "Received clipboard text: ${clipText.take(50)}...")
                    updateTextDisplay(clipText)
                    isSaved = clipboardService?.isLibraryItemActive() == true
                    refreshUi()
                }
            }

            clipboardService?.setTTSListener(object : ClipboardMonitorService.TTSListener {
                override fun onPlaybackBuffering() {
                    runOnUiThread {
                        isBuffering = true
                        renderPlaybackState()
                        updatePipActions()
                    }
                }

                override fun onPlaybackPaused() {
                    runOnUiThread {
                        isBuffering = false
                        seekBarHandler.removeCallbacks(seekBarRunnable)
                        playButton.setImageResource(android.R.drawable.ic_media_play)
                        renderPlaybackState()
                        updatePipActions()
                    }
                }

                override fun onPlaybackStarted() {
                    runOnUiThread {
                        isBuffering = false
                        playButton.setImageResource(android.R.drawable.ic_media_pause)
                        renderPlaybackState()
                        updatePipActions()
                        seekBarHandler.removeCallbacks(seekBarRunnable)
                        seekBarHandler.postDelayed(seekBarRunnable, 300L)
                    }
                }

                override fun onPlaybackFinished() {
                    runOnUiThread {
                        isBuffering = false
                        seekBarHandler.removeCallbacks(seekBarRunnable)
                        playButton.setImageResource(android.R.drawable.ic_media_play)
                        renderPlaybackState()
                        updatePipActions()
                    }
                }

                override fun onPlaybackError() {
                    runOnUiThread {
                        isBuffering = false
                        seekBarHandler.removeCallbacks(seekBarRunnable)
                        Toast.makeText(this@MainActivity, getString(R.string.tts_init_failed), Toast.LENGTH_SHORT).show()
                        playButton.setImageResource(android.R.drawable.ic_media_play)
                        renderPlaybackState()
                        updatePipActions()
                    }
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clipboardService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before super.onCreate so AppCompat inflates with the right theme
        val prefs = getSharedPreferences("PasteItSettings", MODE_PRIVATE)
        val savedMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = prefs
        savedContentStore = SavedContentStore(this)

        initializeViews()
        setupClickListeners()
        setupNavBar()
        startAndBindService()
        setProcessTextComponentEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        setProcessTextComponentEnabled(isInPipMode)

        // Pick up any item tapped in LibraryActivity, regardless of how it was launched.
        val pendingId = preferences.getString(LibraryActivity.PREF_PENDING_SELECTION, null)
        if (pendingId != null) {
            preferences.edit().remove(LibraryActivity.PREF_PENDING_SELECTION).apply()
            pendingLibrarySelectionId = pendingId
        }
        applyPendingLibrarySelection()

        if (serviceBound) {
            applyTTSSettings()
            isSaved = clipboardService?.isLibraryItemActive() == true
            refreshUi()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipMode) {
            setProcessTextComponentEnabled(false)
        }
    }

    private fun applyPendingLibrarySelection() {
        val pendingId = pendingLibrarySelectionId ?: return
        if (!serviceBound) return
        Log.d("PasteItMain", "Applying pending library selection: $pendingId")
        val loaded = clipboardService?.loadLibraryItem(pendingId) == true
        pendingLibrarySelectionId = null
        if (loaded) {
            isSaved = true
            Toast.makeText(this, getString(R.string.saved_item_loaded_generic), Toast.LENGTH_SHORT).show()
            refreshUi()
        } else {
            Toast.makeText(this, getString(R.string.saved_item_load_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeViews() {
        mainContent = findViewById(R.id.mainContent)
        textPanelCard = findViewById(R.id.textPanelCard)

        pipOverlay = findViewById(R.id.pipOverlay)
        pipTitle = findViewById(R.id.pipTitle)
        pipChunkProgressBar = findViewById(R.id.pipChunkProgressBar)
        pipChunkText = findViewById(R.id.pipChunkText)
        pipStatusDot = findViewById(R.id.pipStatusDot)
        pipStatusLabel = findViewById(R.id.pipStatusLabel)

        playButton = findViewById(R.id.playButton)
        rewindButton = findViewById(R.id.rewindButton)
        fastForwardButton = findViewById(R.id.fastForwardButton)
        settingsButton = findViewById(R.id.settingsButton)
        themeToggleButton = findViewById(R.id.themeToggleButton)
        pasteButton = findViewById(R.id.pasteButton)
        stockFormatButton = findViewById(R.id.stockFormatButton)
        bookmarkButton = findViewById(R.id.bookmarkButton)
        textDisplay = findViewById(R.id.textDisplay)
        textContainer = findViewById(R.id.textContainer)
        installReaderInteractionHandlers()
        ttsProgressBar = findViewById(R.id.ttsProgressBar)
        playbackStatusText = findViewById(R.id.playbackStatusText)
        cacheStatusText = findViewById(R.id.cacheStatusText)
        nowPlayingTitle = findViewById(R.id.nowPlayingTitle)
        chunkProgressBar = findViewById(R.id.chunkProgressBar)
        chunkProgressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isSeeking = true
                seekBarHandler.removeCallbacks(seekBarRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeeking = false
                clipboardService?.seekToProgress(seekBar.progress, SEEK_RESOLUTION)
                refreshUi()
            }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
        })
        chunkMetaText = findViewById(R.id.chunkMetaText)
        sourceMetaText = findViewById(R.id.sourceMetaText)
        timeEstimateText = findViewById(R.id.timeEstimateText)
        engineBadgeText = findViewById(R.id.engineBadgeText)
        documentMetaText = findViewById(R.id.documentMetaText)
        statusDot = findViewById(R.id.statusDot)
        startOverButton = findViewById(R.id.startOverButton)
        maxChunkButton = findViewById(R.id.maxChunkButton)
        navPlayerTab = findViewById(R.id.navPlayerTab)
        navPlayerIcon = findViewById(R.id.navPlayerIcon)
        navPlayerLabel = findViewById(R.id.navPlayerLabel)

        updateLayoutForPipMode(false)
        updateStockFormatButtonUi()
        refreshUi()
    }

    private fun setupClickListeners() {
        playButton.setOnClickListener { clipboardService?.togglePlayback() }
        rewindButton.setOnClickListener { clipboardService?.rewind() }
        fastForwardButton.setOnClickListener { clipboardService?.fastForward() }
        rewindButton.setOnLongClickListener {
            clipboardService?.startOver()
            true
        }
        fastForwardButton.setOnLongClickListener {
            openChapterNavigator()
            true
        }

        settingsButton.setOnClickListener {
            if (!isInPipMode) startActivity(Intent(this, SettingsActivity::class.java))
        }

        themeToggleButton.setOnClickListener { toggleTheme() }

        stockFormatButton.setOnClickListener {
            val next = !preferences.getBoolean(SpeechFormattingPreferences.PREF_QUICK_STOCK, false)
            preferences.edit().putBoolean(SpeechFormattingPreferences.PREF_QUICK_STOCK, next).apply()
            updateStockFormatButtonUi()
            clipboardService?.reapplySpeechFormatting()
            refreshUi()
        }

        pasteButton.setOnClickListener { pasteFromClipboard() }

        bookmarkButton.setOnClickListener { promptSaveCurrentText() }

        startOverButton.setOnClickListener { clipboardService?.startOver() }

        maxChunkButton.setOnClickListener { toggleMaxChunkMode() }

        playButton.setOnLongClickListener {
            enterPipMode()
            true
        }
    }

    private fun setupNavBar() {
        val amber = ContextCompat.getColor(this, R.color.pasteit_accent)
        val dim = ContextCompat.getColor(this, R.color.pasteit_text_dim)

        // Player tab is active
        navPlayerIcon.setColorFilter(amber)
        navPlayerLabel.setTextColor(amber)
        navPlayerTab.alpha = 1.0f

        // Dim other tabs
        val libraryTab = findViewById<LinearLayout>(R.id.navLibraryTab)
        val settingsTab = findViewById<LinearLayout>(R.id.navSettingsTab)
        libraryTab.alpha = 0.4f
        settingsTab.alpha = 0.4f

        // Nav click listeners
        navPlayerTab.setOnClickListener { /* already here */ }
        libraryTab.setOnClickListener { openLibrary() }
        settingsTab.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun toggleTheme() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val newMode = if (isNight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        preferences.edit().putInt("theme_mode", newMode).apply()
        AppCompatDelegate.setDefaultNightMode(newMode)
        // uiMode is in android:configChanges (needed for PiP), so setDefaultNightMode won't
        // trigger an automatic recreation — we must do it manually.
        recreate()
    }

    private fun applyTTSSettings() {
        val rate = preferences.getInt(
            AndroidTtsEngineParams.PREF_SPEECH_RATE,
            AndroidTtsEngineParams.DEFAULT_SPEECH_RATE_PERCENT,
        ) / 100f
        val pitch = preferences.getInt(
            AndroidTtsEngineParams.PREF_SPEECH_PITCH,
            AndroidTtsEngineParams.DEFAULT_SPEECH_PITCH_PERCENT,
        ) / 100f
        clipboardService?.updateTTSSettings(rate, pitch)
    }

    private fun pasteFromClipboard(): Boolean {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isNotBlank()) {
                clipboardService?.setManualText(text)
                isSaved = false
                updateBookmarkButton()
                Toast.makeText(this, "Text pasted and ready to read", Toast.LENGTH_SHORT).show()
                refreshUi()
                return true
            }
        }
        Toast.makeText(this, "Nothing on clipboard", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun promptSaveCurrentText() {
        val sourceText = clipboardService?.getCurrentSourceText().orEmpty().trim()
        if (sourceText.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_text_to_save), Toast.LENGTH_SHORT).show()
            return
        }

        // If already saved as a library item, nothing to do
        if (isSaved && clipboardService?.isLibraryItemActive() == true) {
            Toast.makeText(this, "Already saved to library", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            setSingleLine()
            hint = getString(R.string.saved_item_title_hint)
            setText(sourceText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40).orEmpty())
            setSelection(text?.length ?: 0)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.save_current_text)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = input.text?.toString().orEmpty()
                val document = savedContentStore.save(title, sourceText)
                clipboardService?.setSavedText(document.sourceText, document.item.id)
                isSaved = true
                updateBookmarkButton()
                Toast.makeText(
                    this,
                    getString(R.string.saved_item_saved, document.item.title),
                    Toast.LENGTH_SHORT,
                ).show()
                refreshUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updatePlayButtonState() {
        val hasText = clipboardService?.getCurrentSourceText().orEmpty().isNotBlank()

        playButton.isEnabled = hasText
        rewindButton.isEnabled = hasText
        fastForwardButton.isEnabled = hasText
        bookmarkButton.isEnabled = hasText

        if (!hasText) {
            playButton.setImageResource(android.R.drawable.ic_media_play)
            isBuffering = false
        }

        updateBookmarkButton()
    }

    private fun updateBookmarkButton() {
        val hasText = clipboardService?.getCurrentSourceText().orEmpty().isNotBlank()
        val saved = isSaved && clipboardService?.isLibraryItemActive() == true

        if (saved) {
            bookmarkButton.setImageResource(R.drawable.ic_bookmark_filled)
            bookmarkButton.setColorFilter(ContextCompat.getColor(this, R.color.pasteit_accent))
            bookmarkButton.contentDescription = getString(R.string.bookmark_saved_cd)
        } else {
            bookmarkButton.setImageResource(R.drawable.ic_bookmark_border)
            bookmarkButton.setColorFilter(
                ContextCompat.getColor(
                    this,
                    if (hasText) R.color.pasteit_text_dim else R.color.pasteit_text_soft,
                ),
            )
            bookmarkButton.contentDescription = getString(R.string.bookmark_save_cd)
        }
    }

    private fun renderPlaybackState() {
        val hasText = clipboardService?.getCurrentSourceText().orEmpty().isNotBlank()
        val isPlayingNow = clipboardService?.isPlayingNow() == true
        val isPausedNow = clipboardService?.isPausedNow() == true

        playButton.setImageResource(
            if (isPlayingNow) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )

        when {
            isBuffering -> {
                ttsProgressBar.visibility = View.VISIBLE
                playbackStatusText.visibility = View.VISIBLE
                playbackStatusText.text = getString(R.string.buffering_tts)
            }
            hasText -> {
                ttsProgressBar.visibility = View.GONE
                playbackStatusText.visibility = View.VISIBLE
                playbackStatusText.text = when {
                    isPlayingNow -> getString(R.string.player_status_playing)
                    isPausedNow -> getString(R.string.player_status_paused)
                    else -> getString(R.string.ready_to_read)
                }
            }
            else -> {
                ttsProgressBar.visibility = View.GONE
                playbackStatusText.visibility = View.VISIBLE
                playbackStatusText.text = getString(R.string.player_status_waiting)
            }
        }

        val dotDrawable = statusDot.background.mutate() as? GradientDrawable
        val dotColor = when {
            isBuffering -> ContextCompat.getColor(this, R.color.pasteit_accent)
            isPlayingNow -> ContextCompat.getColor(this, R.color.pasteit_success)
            hasText -> ContextCompat.getColor(this, R.color.pasteit_text_dim)
            else -> ContextCompat.getColor(this, R.color.pasteit_text_soft)
        }
        dotDrawable?.setColor(dotColor)

        renderNowPlayingCard()
        renderCacheStatus()
        if (isInPipMode) updatePipOverlay()
    }

    private fun toggleMaxChunkMode() {
        val service = clipboardService ?: return
        service.setMaxChunkMode(!service.isMaxChunkModeActive())
        updateMaxChunkButton()
        refreshUi()
    }

    private fun updateMaxChunkButton() {
        val active = clipboardService?.isMaxChunkModeActive() == true
        val color = ContextCompat.getColor(
            this,
            if (active) R.color.pasteit_accent else R.color.pasteit_text_dim,
        )
        maxChunkButton.setTextColor(color)
    }

    private fun updateStartOverButton() {
        val hasText = clipboardService?.getCurrentSourceText().orEmpty().isNotBlank()
        val pastChunkZero = (clipboardService?.getCurrentChunkIndex() ?: 0) > 0
        startOverButton.visibility = if (hasText && pastChunkZero) View.VISIBLE else View.GONE
    }

    private fun renderNowPlayingCard() {
        val sourceText = clipboardService?.getCurrentSourceText().orEmpty()
        val hasText = sourceText.isNotBlank()
        val chunkIndex0 = clipboardService?.getCurrentChunkIndex() ?: 0  // 0-based
        val totalChunks = clipboardService?.getTotalChunkCount() ?: 0

        nowPlayingTitle.text = if (hasText) buildDocumentTitle(sourceText) else getString(R.string.player_idle_title)
        chunkMetaText.text = if (totalChunks > 0) {
            getString(R.string.chunk_of_total, (chunkIndex0 + 1).coerceAtLeast(1), totalChunks)
        } else {
            getString(R.string.chunk_not_ready)
        }
        sourceMetaText.text = if (clipboardService?.isLibraryItemActive() == true) {
            getString(R.string.saved_text)
        } else {
            getString(R.string.clipboard_text)
        }
        engineBadgeText.text = clipboardService?.getCurrentEngineLabel().takeUnless { it.isNullOrBlank() }
            ?: fallbackEngineLabel()
        timeEstimateText.text = buildTimeEstimateText()

        // Only update bar if user isn't dragging; polling handler handles live position.
        if (!isSeeking) {
            val maxProgress = (totalChunks * SEEK_RESOLUTION - 1).coerceAtLeast(SEEK_RESOLUTION)
            chunkProgressBar.max = maxProgress
            chunkProgressBar.progress = if (hasText && totalChunks > 0) {
                (chunkIndex0 * SEEK_RESOLUTION).coerceIn(0, maxProgress)
            } else {
                0
            }
        }

        updateStartOverButton()
        updateMaxChunkButton()
        updateTextDisplay(sourceText)
    }

    /**
     * Renders the reader body using the TTS-aligned plain text from the service. The body is
     * cached and reused across polls; only the [BackgroundColorSpan] tracking the estimated
     * reading band is swapped in place. [sourceText] is kept in the signature for compatibility
     * with existing callers but is ignored when the service is bound — the service's
     * [ClipboardMonitorService.getPlaybackPlainText] is authoritative.
     */
    private fun updateTextDisplay(@Suppress("UNUSED_PARAMETER") sourceText: String) {
        val service = clipboardService
        val plain = service?.getPlaybackPlainText().orEmpty()
        if (plain.isBlank()) {
            textDisplay.text = ""
            cachedPlainDisplay = null
            cachedPlainSource = ""
            appliedHighlightSpan = null
            lastAppliedRangeStart = Int.MIN_VALUE
            return
        }

        val display = plainDisplayFor(plain)
        val range = service?.getEstimatedReadingRangeInDocument() ?: IntRange.EMPTY
        applyHighlightSpan(display, range)
        textDisplay.text = display

        lastAppliedRangeStart = if (range.isEmpty()) Int.MIN_VALUE else range.first
        lastHighlightUpdateMs = SystemClock.elapsedRealtime()
        scrollToReadingBandIfFollowing(range)
    }

    private fun plainDisplayFor(plainText: String): SpannableStringBuilder {
        val cached = cachedPlainDisplay
        if (cached != null && cachedPlainSource == plainText) return cached
        val fresh = SpannableStringBuilder(plainText)
        cachedPlainSource = plainText
        cachedPlainDisplay = fresh
        appliedHighlightSpan = null
        followModeEnabled = true
        return fresh
    }

    private fun applyHighlightSpan(builder: SpannableStringBuilder, range: IntRange) {
        appliedHighlightSpan?.let { builder.removeSpan(it) }
        if (range.isEmpty()) {
            appliedHighlightSpan = null
            return
        }
        val start = range.first.coerceIn(0, builder.length)
        val end = (range.last + 1).coerceIn(start, builder.length)
        if (end <= start) {
            appliedHighlightSpan = null
            return
        }
        val span = BackgroundColorSpan(ContextCompat.getColor(this, R.color.pasteit_accent_soft))
        builder.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        appliedHighlightSpan = span
    }

    private fun maybeRefreshReadingHighlight(service: ClipboardMonitorService) {
        val range = service.getEstimatedReadingRangeInDocument()
        val start = if (range.isEmpty()) Int.MIN_VALUE else range.first
        val now = SystemClock.elapsedRealtime()
        val moved = start != Int.MIN_VALUE &&
            kotlin.math.abs(start - lastAppliedRangeStart) >= HIGHLIGHT_MOVE_THRESHOLD_CHARS
        val stale = now - lastHighlightUpdateMs >= HIGHLIGHT_MIN_REFRESH_MS
        if (!moved && !stale) return
        updateTextDisplay(service.getCurrentSourceText())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installReaderInteractionHandlers() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val tapTimeoutMs = ViewConfiguration.getTapTimeout().toLong() * 2L

        textDisplay.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapDownX = event.x
                    tapDownY = event.y
                    tapDownTimeMs = SystemClock.elapsedRealtime()
                    tapMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tapMoved &&
                        (kotlin.math.abs(event.x - tapDownX) > touchSlop ||
                            kotlin.math.abs(event.y - tapDownY) > touchSlop)
                    ) {
                        tapMoved = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = SystemClock.elapsedRealtime() - tapDownTimeMs
                    if (!tapMoved && elapsed <= tapTimeoutMs) {
                        handleReaderTap(event.x, event.y)
                    }
                }
            }
            false
        }

        textContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> userTouchingTextContainer = true
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> userTouchingTextContainer = false
            }
            false
        }

        textContainer.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (!followModeEnabled) return@setOnScrollChangeListener
            val isLikelyUser = userTouchingTextContainer &&
                kotlin.math.abs(scrollY - lastScrollRequestY) > AUTO_SCROLL_TOLERANCE_PX
            val delta = kotlin.math.abs(scrollY - oldScrollY)
            if (isLikelyUser && delta > AUTO_SCROLL_TOLERANCE_PX) {
                followModeEnabled = false
            }
        }
    }

    private fun handleReaderTap(x: Float, y: Float) {
        val service = clipboardService ?: return
        if (service.getPlaybackPlainText().isBlank()) return
        val offset = textDisplay.getOffsetForPosition(x, y)
        if (offset < 0) return
        service.seekToDocumentPlainOffset(offset)
        followModeEnabled = true
        maybeRefreshReadingHighlight(service)
    }

    /**
     * Keeps the reading band roughly one-third down the viewport while [followModeEnabled] is
     * true. The scroll request is posted so it runs after layout, which is required for
     * [android.text.Layout.getLineForOffset] to have valid metrics.
     */
    private fun scrollToReadingBandIfFollowing(range: IntRange) {
        if (!followModeEnabled || range.isEmpty()) return
        val focus = range.first
        textDisplay.post {
            val layout = textDisplay.layout ?: return@post
            val boundedOffset = focus.coerceIn(0, textDisplay.text.length)
            val line = layout.getLineForOffset(boundedOffset)
            val lineTop = layout.getLineTop(line)
            val viewportHeight = textContainer.height
            val viewportThird = viewportHeight / 3
            val targetY = (lineTop + textDisplay.paddingTop - viewportThird).coerceAtLeast(0)
            val maxScroll = (textDisplay.height - viewportHeight).coerceAtLeast(0)
            val clampedY = targetY.coerceAtMost(maxScroll)
            if (kotlin.math.abs(clampedY - textContainer.scrollY) < FOLLOW_SCROLL_EPSILON_PX) return@post
            lastScrollRequestY = clampedY
            textContainer.smoothScrollTo(0, clampedY)
        }
    }

    private fun renderDocumentMeta() {
        val sourceText = clipboardService?.getCurrentSourceText().orEmpty()
        val count = NumberFormat.getIntegerInstance().format(sourceText.length)
        val kind = if (clipboardService?.isLibraryItemActive() == true) {
            getString(R.string.saved_text)
        } else {
            getString(R.string.clipboard_text)
        }
        documentMetaText.text = if (sourceText.isBlank()) "0 chars" else "$count chars · $kind"
    }

    private fun renderCacheStatus() {
        val sourceText = clipboardService?.getCurrentSourceText().orEmpty()
        if (sourceText.isBlank()) {
            cacheStatusText.visibility = View.GONE
            return
        }

        val savedItemId = clipboardService?.getActiveSavedItemId()
        cacheStatusText.visibility = View.VISIBLE
        cacheStatusText.text = XaiCacheInspector.buildStatusText(
            context = this,
            preferences = preferences,
            sourceText = sourceText,
            savedItemId = savedItemId,
        )
    }

    private fun refreshUi() {
        updatePlayButtonState()
        renderPlaybackState()
        renderDocumentMeta()
        updateStockFormatButtonUi()
        updatePipActions()
    }

    private fun fallbackEngineLabel(): String {
        return when (TtsProviderMode.fromPreferences(preferences)) {
            TtsProviderMode.XAI -> "xAI"
            TtsProviderMode.ANDROID -> "Android"
            TtsProviderMode.AUTO -> {
                val apiKey = preferences.getString(XaiVoiceOption.PREF_XAI_API_KEY, "").orEmpty()
                if (apiKey.isBlank()) "Auto: Android" else "Auto: xAI"
            }
        }
    }

    private fun buildDocumentTitle(sourceText: String): String {
        return sourceText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(120)
            ?: getString(R.string.player_idle_title)
    }

    private fun buildTimeEstimateText(): String {
        val service = clipboardService ?: return getString(R.string.time_estimate_unavailable)
        val averageMs = service.getAverageChunkDurationMs()
        if (averageMs <= 0L) return getString(R.string.time_estimate_unavailable)
        val elapsed = service.getEstimatedElapsedMs()
        val remaining = service.getEstimatedRemainingMs()
        return getString(
            R.string.time_estimate_format,
            formatClock(elapsed),
            formatClock(remaining),
        )
    }

    private fun formatClock(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    private fun openChapterNavigator() {
        val service = clipboardService ?: return
        val titles = service.getChapterTitles()
        if (titles.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_chapters_available), Toast.LENGTH_SHORT).show()
            return
        }
        val current = service.getCurrentChunkIndex().coerceAtLeast(0)
        val labels = titles.mapIndexed { idx, title ->
            val prefix = if (idx == current) "• " else ""
            "$prefix${idx + 1}. $title"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.chapter_navigation_title)
            .setItems(labels) { _, which ->
                service.seekToChunk(which)
                refreshUi()
            }
            .show()
    }

    private fun updateStockFormatButtonUi() {
        val on = preferences.getBoolean(SpeechFormattingPreferences.PREF_QUICK_STOCK, false)
        stockFormatButton.isSelected = on
        stockFormatButton.alpha = if (on) 1f else 0.6f
        stockFormatButton.contentDescription = getString(
            if (on) R.string.stock_format_button_cd_on else R.string.stock_format_button_cd_off,
        )
        stockFormatButton.setColorFilter(
            ContextCompat.getColor(
                this,
                if (on) R.color.pasteit_accent else R.color.pasteit_text_dim,
            ),
        )
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, ClipboardMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(buildPipActions())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            enterPictureInPictureMode(builder.build())
        }
    }

    private fun updatePipActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(buildPipActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }
        setPictureInPictureParams(builder.build())
    }

    private fun buildPipActions(): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        val hasText = clipboardService?.getCurrentSourceText().orEmpty().isNotBlank()
        val isPlayingNow = clipboardService?.isPlayingNow() == true
        val isPausedNow = clipboardService?.isPausedNow() == true

        fun serviceIntent(action: String) =
            Intent(this, ClipboardMonitorService::class.java).apply { this.action = action }

        fun pendingService(requestCode: Int, action: String) = PendingIntent.getService(
            this, requestCode, serviceIntent(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val rewindAction = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_media_rew),
            getString(R.string.rewind), getString(R.string.rewind),
            pendingService(2000, ClipboardMonitorService.ACTION_REWIND),
        ).apply { isEnabled = hasText }

        val toggleAction = RemoteAction(
            Icon.createWithResource(
                this,
                if (isPlayingNow) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            ),
            getString(if (isPlayingNow) R.string.pause else R.string.play),
            getString(if (isPlayingNow) R.string.pause else R.string.play),
            pendingService(2001, ClipboardMonitorService.ACTION_TOGGLE_PLAYBACK),
        ).apply { isEnabled = hasText || isPausedNow }

        val fastForwardAction = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_media_ff),
            getString(R.string.fast_forward), getString(R.string.fast_forward),
            pendingService(2002, ClipboardMonitorService.ACTION_FAST_FORWARD),
        ).apply { isEnabled = hasText }

        val pasteAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.baseline_content_paste_24),
            getString(R.string.paste), getString(R.string.paste),
            pendingService(2003, ClipboardMonitorService.ACTION_PASTE_CLIPBOARD),
        )

        return listOf(rewindAction, toggleAction, fastForwardAction, pasteAction)
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isInPipMode = isInPictureInPictureMode
        setProcessTextComponentEnabled(isInPictureInPictureMode)
        updateLayoutForPipMode(isInPictureInPictureMode)
        refreshUi()
    }

    private fun setProcessTextComponentEnabled(enabled: Boolean) {
        val component = ComponentName(this, ProcessTextActivity::class.java)
        val targetState =
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(
            component,
            targetState,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun updateLayoutForPipMode(isPipMode: Boolean) {
        if (isPipMode) {
            mainContent.visibility = View.GONE
            pipOverlay.visibility = View.VISIBLE
            updatePipOverlay()
        } else {
            mainContent.visibility = View.VISIBLE
            pipOverlay.visibility = View.GONE
        }
    }

    private fun updatePipOverlay() {
        val sourceText = clipboardService?.getCurrentSourceText().orEmpty()
        val hasText = sourceText.isNotBlank()
        val isPlayingNow = clipboardService?.isPlayingNow() == true
        val isPausedNow = clipboardService?.isPausedNow() == true
        val chunkIndex = (clipboardService?.getCurrentChunkIndex() ?: 0) + 1
        val totalChunks = clipboardService?.getTotalChunkCount() ?: 0

        // Title
        pipTitle.text = if (hasText) buildDocumentTitle(sourceText) else getString(R.string.player_idle_title)

        // Progress
        pipChunkProgressBar.max = totalChunks.coerceAtLeast(1)
        pipChunkProgressBar.progress = if (hasText && totalChunks > 0) chunkIndex.coerceAtLeast(1) else 0
        pipChunkText.text = when {
            totalChunks <= 0 -> ""
            isPlayingNow || isPausedNow -> buildPipReadingLabel(chunkIndex, totalChunks)
            else -> getString(R.string.chunk_of_total, chunkIndex.coerceAtLeast(1), totalChunks)
        }

        // Status dot + label
        val (statusColor, statusString) = when {
            isBuffering -> Pair(R.color.pasteit_accent, R.string.player_status_loading)
            isPlayingNow -> Pair(R.color.pasteit_success, R.string.player_status_playing)
            isPausedNow -> Pair(R.color.pasteit_text_dim, R.string.player_status_paused)
            hasText -> Pair(R.color.pasteit_text_dim, R.string.player_status_ready)
            else -> Pair(R.color.pasteit_text_soft, R.string.player_status_waiting)
        }
        val dotDrawable = pipStatusDot.background.mutate() as? GradientDrawable
        dotDrawable?.setColor(ContextCompat.getColor(this, statusColor))
        pipStatusLabel.text = getString(statusString)
        pipStatusLabel.setTextColor(ContextCompat.getColor(this, statusColor))
    }

    private fun buildPipReadingLabel(chunkIndex: Int, totalChunks: Int): String {
        val service = clipboardService
        val chunkLabel = getString(R.string.chunk_of_total, chunkIndex.coerceAtLeast(1), totalChunks)
        val plain = service?.getPlaybackPlainText().orEmpty()
        val range = service?.getEstimatedReadingRangeInDocument() ?: IntRange.EMPTY
        if (plain.isEmpty() || range.isEmpty()) return chunkLabel

        val center = (range.first + range.last) / 2
        val halfWidth = EXCERPT_FALLBACK_CHARS / 2
        val rawStart = (center - halfWidth).coerceAtLeast(0)
        val rawEnd = (center + halfWidth).coerceAtMost(plain.length)
        var s = rawStart
        while (s > 0 && !plain[s - 1].isWhitespace() && rawStart - s < EXCERPT_SNAP_CHARS) s--
        var e = rawEnd
        while (e < plain.length && !plain[e].isWhitespace() && e - rawEnd < EXCERPT_SNAP_CHARS) e++
        val prefix = if (s > 0) "…" else ""
        val suffix = if (e < plain.length) "…" else ""
        val middle = plain.substring(s, e).replace(Regex("\\s+"), " ").trim()
        if (middle.isEmpty()) return chunkLabel
        return "$chunkLabel · $prefix$middle$suffix"
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
    }

    override fun onDestroy() {
        setProcessTextComponentEnabled(false)
        seekBarHandler.removeCallbacks(seekBarRunnable)
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    companion object {
        /** Sub-units per chunk — gives smooth within-chunk animation without rebuilding chunks. */
        private const val SEEK_RESOLUTION = 100

        /** Minimum jump in band-start (chunk chars) that justifies rebuilding the text display. */
        private const val HIGHLIGHT_MOVE_THRESHOLD_CHARS = 40

        /** Maximum time between band refreshes while playing, even if the start index is stable. */
        private const val HIGHLIGHT_MIN_REFRESH_MS = 800L

        /** Head-of-chunk excerpt length used before the estimator has a usable range. */
        private const val EXCERPT_FALLBACK_CHARS = 96

        /** How far the excerpt builder will search for a word boundary past the band edges. */
        private const val EXCERPT_SNAP_CHARS = 20

        /** Suppress follow-mode scrolls smaller than this (px) — avoids jitter from tiny shifts. */
        private const val FOLLOW_SCROLL_EPSILON_PX = 12

        /** Scroll delta (px) during an auto-scroll that is still considered programmatic. */
        private const val AUTO_SCROLL_TOLERANCE_PX = 24
    }
}
