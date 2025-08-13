// MainActivity.kt - Clean Enhanced Version
package com.example.pasteit

import android.app.*
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.util.Rational
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var playButton: ImageButton
    private lateinit var rewindButton: ImageButton
    private lateinit var fastForwardButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var textDisplay: TextView
    private lateinit var expandButton: Button
    private lateinit var textContainer: ScrollView
    private lateinit var pasteButton: ImageButton
    private var clipboardService: ClipboardMonitorService? = null
    private var serviceBound = false
    private var isInPipMode = false
    private var isTextExpanded = false
    private lateinit var preferences: SharedPreferences

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ClipboardMonitorService.LocalBinder
            clipboardService = binder.getService()
            serviceBound = true

            // Apply TTS settings from preferences
            applyTTSSettings()

            // Set up clipboard listener
            clipboardService?.setClipboardListener { clipText ->
                runOnUiThread {
                    Log.d("PasteItMain", "Received clipboard text: ${clipText.take(50)}...")

                    // Always update the text display with new content
                    textDisplay.text = clipText

                    // Reset play button state
                    playButton.setImageResource(android.R.drawable.ic_media_play)

                    // Paste button: always tries to paste
                    pasteButton.setOnClickListener { pasteFromClipboard() }


                    // Update button states
                    updatePlayButtonState()

                    // Show a brief indication that new text was loaded
                    if (clipText.length > 50) {
                        Toast.makeText(this@MainActivity, "New text loaded (${clipText.length} characters)", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Set up TTS listener
            clipboardService?.setTTSListener(object : ClipboardMonitorService.TTSListener {
                override fun onPlaybackStarted() {
                    runOnUiThread {
                        playButton.setImageResource(android.R.drawable.ic_media_pause)
                    }
                }

                override fun onPlaybackFinished() {
                    runOnUiThread {
                        playButton.setImageResource(android.R.drawable.ic_media_play)
                    }
                }

                override fun onPlaybackError() {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "TTS Error occurred", Toast.LENGTH_SHORT).show()
                        playButton.setImageResource(android.R.drawable.ic_media_play)
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = getSharedPreferences("PasteItSettings", Context.MODE_PRIVATE)

        initializeViews()
        setupClickListeners()
        startAndBindService()
    }

    override fun onResume() {
        super.onResume()
        // Reapply TTS settings in case they changed in settings
        if (serviceBound) {
            applyTTSSettings()
        }
    }


    private fun applyTTSSettings() {
        val rate = preferences.getInt("speech_rate", 100) / 100f
        val pitch = preferences.getInt("speech_pitch", 100) / 100f

        clipboardService?.updateTTSSettings(rate, pitch)
    }

    private fun initializeViews() {
        playButton = findViewById(R.id.playButton)
        rewindButton = findViewById(R.id.rewindButton)
        fastForwardButton = findViewById(R.id.fastForwardButton)
        settingsButton = findViewById(R.id.settingsButton)
        textDisplay = findViewById(R.id.textDisplay)
        expandButton = findViewById(R.id.expandButton)
        textContainer = findViewById(R.id.textContainer)
        pasteButton = findViewById(R.id.pasteButton)

        updateLayoutForPipMode(false)
    }

    private fun setupClickListeners() {
        playButton.setOnClickListener {
            clipboardService?.togglePlayback()
        }

        rewindButton.setOnClickListener {
            clipboardService?.rewind()
        }

        fastForwardButton.setOnClickListener {
            clipboardService?.fastForward()
        }

        settingsButton.setOnClickListener {
            if (!isInPipMode) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        expandButton.setOnClickListener {
            toggleTextExpansion()
        }

        pasteButton.setOnClickListener {
            pasteFromClipboard()
        }

        // Long press to enter PiP mode manually
        playButton.setOnLongClickListener {
            enterPipMode()
            true
        }
    }

    private fun pasteFromClipboard(): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isNotBlank()) {
                // Tell the service about the new text
                clipboardService?.setManualText(text)

                Toast.makeText(this, "Text pasted and ready to read", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        Toast.makeText(this, "Nothing on clipboard", Toast.LENGTH_SHORT).show()
        return false
    }


    private fun updatePlayButtonState() {
        val defaultText = getString(R.string.copy_some_text_to_get_started)
        val hasText = textDisplay.text.isNotEmpty() &&
                textDisplay.text.toString() != defaultText &&
                textDisplay.text.toString().trim().isNotEmpty()

        playButton.isEnabled = hasText
        rewindButton.isEnabled = hasText
        fastForwardButton.isEnabled = hasText

        if (!hasText) {
            playButton.setImageResource(android.R.drawable.ic_media_play)
        }

        // Debug log
        Log.d("PasteItMain", "updatePlayButtonState - hasText: $hasText, text: '${textDisplay.text}'")
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, ClipboardMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }


    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(params)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isInPipMode = isInPictureInPictureMode
        updateLayoutForPipMode(isInPictureInPictureMode)
    }

    private fun updateLayoutForPipMode(isPipMode: Boolean) {
        if (isPipMode) {
            // Hide unnecessary elements in PiP mode
            settingsButton.visibility = View.GONE
            if (isTextExpanded) {
                toggleTextExpansion()
            }
            expandButton.text = "▼"
        } else {
            // Show all elements in normal mode
            settingsButton.visibility = View.VISIBLE
            expandButton.text = if (isTextExpanded) "▲ Minimize" else "▼ Show Text"
        }
    }

    private fun toggleTextExpansion() {
        isTextExpanded = !isTextExpanded

        if (isTextExpanded) {
            textContainer.visibility = View.VISIBLE
            expandButton.text = if (isInPipMode) "▲" else "▲ Minimize"
        } else {
            textContainer.visibility = View.GONE
            expandButton.text = if (isInPipMode) "▼" else "▼ Show Text"
        }
    }


    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Automatically enter PiP mode when user leaves the app
        enterPipMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}