package com.example.pasteit

import android.content.Intent
import android.content.SharedPreferences
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.app.AlertDialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged

class SettingsActivity : BaseSwipeActivity() {

    override val swipeIndex = 2
    override fun isSwipeEnabled(): Boolean = false
    private lateinit var speechRateSeekBar: SeekBar
    private lateinit var speechPitchSeekBar: SeekBar
    private lateinit var cloudChunkMaxSeekBar: SeekBar
    private lateinit var androidChunkMaxSeekBar: SeekBar

    private lateinit var rateValue: TextView
    private lateinit var pitchValue: TextView
    private lateinit var cloudChunkMaxValue: TextView
    private lateinit var androidChunkMaxValue: TextView

    private lateinit var testButton: Button
    private lateinit var providerSpinner: Spinner
    private lateinit var xaiVoiceSpinner: Spinner
    private lateinit var speechFormatSpinner: Spinner
    private lateinit var providerInfoText: TextView
    private lateinit var xaiVoiceInfoText: TextView
    private lateinit var xaiApiKeyEditText: EditText
    private lateinit var xaiApiKeyLinkText: TextView

    private var providerSpinnerProgrammatic = false
    private var xaiVoiceSpinnerProgrammatic = false
    private var speechFormatSpinnerProgrammatic = false
    private lateinit var skipCodeBlocksSwitch: Switch

    private lateinit var preferences: SharedPreferences
    private var testTTS: PasteItTts? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        preferences = getSharedPreferences("PasteItSettings", MODE_PRIVATE)

        initializeViews()
        setupNavBar()
        loadSettings()
        setupListeners()
        initializeTestTTS()
    }

    private fun initializeViews() {
        speechRateSeekBar = findViewById(R.id.speechRateSeekBar)
        speechPitchSeekBar = findViewById(R.id.speechPitchSeekBar)
        cloudChunkMaxSeekBar = findViewById(R.id.cloudChunkMaxSeekBar)
        androidChunkMaxSeekBar = findViewById(R.id.androidChunkMaxSeekBar)

        rateValue = findViewById(R.id.rateValue)
        pitchValue = findViewById(R.id.pitchValue)
        cloudChunkMaxValue = findViewById(R.id.cloudChunkMaxValue)
        androidChunkMaxValue = findViewById(R.id.androidChunkMaxValue)

        testButton = findViewById(R.id.testButton)
        providerSpinner = findViewById(R.id.providerSpinner)
        xaiVoiceSpinner = findViewById(R.id.xaiVoiceSpinner)
        speechFormatSpinner = findViewById(R.id.speechFormatSpinner)
        providerInfoText = findViewById(R.id.providerInfoText)
        xaiVoiceInfoText = findViewById(R.id.xaiVoiceInfoText)
        xaiApiKeyEditText = findViewById(R.id.xaiApiKeyEditText)
        xaiApiKeyLinkText = findViewById(R.id.xaiApiKeyLinkText)
        skipCodeBlocksSwitch = findViewById(R.id.skipCodeBlocksSwitch)

        providerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TtsProviderMode.entries.map { getString(it.labelResId) },
        )

        xaiVoiceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            XaiVoiceOption.entries.map { getString(it.labelResId) },
        )

        speechFormatSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.speech_format_auto),
                getString(R.string.speech_format_off),
                getString(R.string.speech_format_stock),
            ),
        )

        speechRateSeekBar.max = 200
        speechPitchSeekBar.max = 150
        cloudChunkMaxSeekBar.max =
            TextChunkingPreferences.MAX_CLOUD_CHUNK_MAX_CHARS - TextChunkingPreferences.MIN_CLOUD_CHUNK_MAX_CHARS
        androidChunkMaxSeekBar.max =
            AndroidTtsEngineParams.MAX_CHUNK_MAX_CHARS - AndroidTtsEngineParams.MIN_CHUNK_MAX_CHARS
    }

    private fun setupNavBar() {
        val accent = ContextCompat.getColor(this, R.color.pasteit_accent)
        val dim = ContextCompat.getColor(this, R.color.pasteit_text_dim)

        val playerTab = findViewById<LinearLayout>(R.id.navPlayerTab)
        val playerIcon = findViewById<ImageView>(R.id.navPlayerIcon)
        val playerLabel = findViewById<TextView>(R.id.navPlayerLabel)
        val libraryTab = findViewById<LinearLayout>(R.id.navLibraryTab)
        val libraryIcon = findViewById<ImageView>(R.id.navLibraryIcon)
        val libraryLabel = findViewById<TextView>(R.id.navLibraryLabel)
        val settingsIcon = findViewById<ImageView>(R.id.navSettingsIcon)
        val settingsLabel = findViewById<TextView>(R.id.navSettingsLabel)

        // Settings tab is active
        settingsIcon.setColorFilter(accent)
        settingsLabel.setTextColor(accent)
        settingsIcon.alpha = 1f
        settingsLabel.alpha = 1f

        // Player tab inactive
        playerIcon.setColorFilter(dim)
        playerLabel.setTextColor(dim)
        playerIcon.alpha = 0.4f
        playerLabel.alpha = 0.4f

        // Library tab inactive
        libraryIcon.setColorFilter(dim)
        libraryLabel.setTextColor(dim)
        libraryIcon.alpha = 0.4f
        libraryLabel.alpha = 0.4f

        playerTab.setOnClickListener { finish() }
        libraryTab.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun loadSettings() {
        val savedRate = preferences.getInt(
            AndroidTtsEngineParams.PREF_SPEECH_RATE,
            AndroidTtsEngineParams.DEFAULT_SPEECH_RATE_PERCENT,
        )
        speechRateSeekBar.progress = savedRate
        rateValue.text = getString(R.string.percent_int, savedRate)

        val savedPitch = preferences.getInt(
            AndroidTtsEngineParams.PREF_SPEECH_PITCH,
            AndroidTtsEngineParams.DEFAULT_SPEECH_PITCH_PERCENT,
        ).coerceIn(50, 200)
        speechPitchSeekBar.progress = savedPitch - 50
        pitchValue.text = getString(R.string.percent_int, savedPitch)

        val cloudChunk = TextChunkingPreferences.cloudChunkMax(preferences)
        cloudChunkMaxSeekBar.progress = cloudChunk - TextChunkingPreferences.MIN_CLOUD_CHUNK_MAX_CHARS
        updateCloudChunkLabel()

        val androidChunk = preferences.getInt(
            AndroidTtsEngineParams.PREF_CHUNK_MAX_CHARS,
            AndroidTtsEngineParams.DEFAULT_CHUNK_MAX_CHARS,
        ).coerceIn(
            AndroidTtsEngineParams.MIN_CHUNK_MAX_CHARS,
            AndroidTtsEngineParams.MAX_CHUNK_MAX_CHARS,
        )
        androidChunkMaxSeekBar.progress = androidChunk - AndroidTtsEngineParams.MIN_CHUNK_MAX_CHARS
        updateAndroidChunkLabel()

        val provider = TtsProviderMode.fromPreferences(preferences)
        providerSpinnerProgrammatic = true
        providerSpinner.setSelection(TtsProviderMode.entries.indexOf(provider).coerceAtLeast(0))
        providerSpinnerProgrammatic = false
        updateProviderHint(provider)
        updateCloudControls(provider)

        val xaiVoice = XaiVoiceOption.fromPreferences(preferences)
        xaiVoiceSpinnerProgrammatic = true
        xaiVoiceSpinner.setSelection(XaiVoiceOption.entries.indexOf(xaiVoice).coerceAtLeast(0))
        xaiVoiceSpinnerProgrammatic = false
        updateXaiVoiceHint(xaiVoice)

        xaiApiKeyEditText.setText(XaiApiKeyStore.get(this))

        skipCodeBlocksSwitch.isChecked =
            preferences.getBoolean(SpeechFormattingPreferences.PREF_SKIP_CODE_BLOCKS, false)

        val mode = SpeechFormatMode.fromPreferences(preferences)
        speechFormatSpinnerProgrammatic = true
        speechFormatSpinner.setSelection(
            when (mode) {
                SpeechFormatMode.AUTO -> 0
                SpeechFormatMode.NONE -> 1
                SpeechFormatMode.STOCK -> 2
            },
        )
        speechFormatSpinnerProgrammatic = false
    }

    private fun updateProviderHint(provider: TtsProviderMode) {
        providerInfoText.text = getString(provider.hintResId)
    }

    private fun updateXaiVoiceHint(voice: XaiVoiceOption) {
        xaiVoiceInfoText.text = buildString {
            append(getString(voice.hintResId))
            append("\n\n")
            append(getString(R.string.xai_voice_cache_behavior_hint))
        }
    }

    private fun updateCloudControls(provider: TtsProviderMode) {
        val xaiEnabled = provider != TtsProviderMode.ANDROID
        xaiApiKeyEditText.isEnabled = xaiEnabled
        xaiApiKeyLinkText.isEnabled = xaiEnabled
        xaiApiKeyLinkText.alpha = if (xaiEnabled) 1f else 0.5f
        xaiVoiceSpinner.isEnabled = xaiEnabled
        cloudChunkMaxSeekBar.isEnabled = xaiEnabled
        xaiVoiceInfoText.alpha = if (xaiEnabled) 1f else 0.5f
        cloudChunkMaxValue.alpha = if (xaiEnabled) 1f else 0.5f
    }

    private fun cloudChunkStored(): Int =
        cloudChunkMaxSeekBar.progress + TextChunkingPreferences.MIN_CLOUD_CHUNK_MAX_CHARS

    private fun androidChunkStored(): Int =
        androidChunkMaxSeekBar.progress + AndroidTtsEngineParams.MIN_CHUNK_MAX_CHARS

    private fun updateCloudChunkLabel() {
        cloudChunkMaxValue.text = getString(R.string.int_value, cloudChunkStored())
    }

    private fun updateAndroidChunkLabel() {
        androidChunkMaxValue.text = getString(R.string.int_value, androidChunkStored())
    }

    private fun setupListeners() {
        speechRateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                rateValue.text = getString(R.string.percent_int, progress)
                if (fromUser) saveSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        speechPitchSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                pitchValue.text = getString(R.string.percent_int, progress + 50)
                if (fromUser) saveSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        cloudChunkMaxSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateCloudChunkLabel()
                if (fromUser) saveSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        androidChunkMaxSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAndroidChunkLabel()
                if (fromUser) saveSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (providerSpinnerProgrammatic) return
                val provider = TtsProviderMode.entries.getOrNull(position) ?: return
                preferences.edit().putString(TtsProviderMode.PREF_TTS_PROVIDER, provider.prefValue).apply()
                updateProviderHint(provider)
                updateCloudControls(provider)
                applySettingsToTestTts()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        xaiVoiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (xaiVoiceSpinnerProgrammatic) return
                val voice = XaiVoiceOption.entries.getOrNull(position) ?: return
                val previousVoice = XaiVoiceOption.fromPreferences(preferences)
                if (previousVoice == voice) {
                    updateXaiVoiceHint(voice)
                    return
                }
                preferences.edit().putString(XaiVoiceOption.PREF_XAI_VOICE, voice.prefValue).apply()
                updateXaiVoiceHint(voice)
                if (shouldShowVoiceChangeDialog(voice)) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.xai_voice_change_dialog_title)
                        .setMessage(R.string.xai_voice_change_warning)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                applySettingsToTestTts()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        speechFormatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (speechFormatSpinnerProgrammatic) return
                val value = when (position) {
                    1 -> SpeechFormatMode.NONE.prefValue
                    2 -> SpeechFormatMode.STOCK.prefValue
                    else -> SpeechFormatMode.AUTO.prefValue
                }
                preferences.edit().putString(SpeechFormattingPreferences.PREF_MODE, value).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        xaiApiKeyEditText.doAfterTextChanged { editable ->
            XaiApiKeyStore.put(this, editable?.toString().orEmpty())
        }

        xaiApiKeyLinkText.setOnClickListener {
            val browseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(XAI_CONSOLE_URL))
            try {
                startActivity(browseIntent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.no_browser_found), Toast.LENGTH_SHORT).show()
            }
        }

        testButton.setOnClickListener { testVoiceSettings() }

        skipCodeBlocksSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit()
                .putBoolean(SpeechFormattingPreferences.PREF_SKIP_CODE_BLOCKS, checked)
                .apply()
        }
    }

    private fun saveSettings() {
        preferences.edit().apply {
            putInt(AndroidTtsEngineParams.PREF_SPEECH_RATE, speechRateSeekBar.progress)
            putInt(AndroidTtsEngineParams.PREF_SPEECH_PITCH, speechPitchSeekBar.progress + 50)
            putInt(AndroidTtsEngineParams.PREF_CHUNK_MAX_CHARS, androidChunkStored())
            putInt(TextChunkingPreferences.PREF_CLOUD_CHUNK_MAX_CHARS, cloudChunkStored())
            apply()
        }
    }

    private fun shouldShowVoiceChangeDialog(selectedVoice: XaiVoiceOption): Boolean {
        if (CurrentTextPersistence.loadContentSource(preferences) != ActiveContentSource.LIBRARY) return true
        val savedItemId = CurrentTextPersistence.loadSavedItemId(preferences) ?: return true
        val document = SavedContentStore(this).load(savedItemId) ?: return true
        val dominantVoice = XaiCacheInspector.dominantVoiceForSavedItem(
            context = this,
            preferences = preferences,
            savedItemId = savedItemId,
            sourceText = document.sourceText,
        ) ?: return true
        return dominantVoice != selectedVoice
    }

    private fun applySettingsToTestTts() {
        if (!ttsReady) return
        saveSettings()
        testTTS?.applyPreferences(
            preferences,
            speechRateFromUi = speechRateSeekBar.progress / 100f,
            onComplete = null,
        )
    }

    private fun initializeTestTTS() {
        testButton.isEnabled = false
        testButton.text = getString(R.string.loading_tts)

        testTTS = PasteItTts(this)
        testTTS?.setCallback(object : AppTtsCallback {
            override fun onInitialized(success: Boolean) {
                runOnUiThread {
                    ttsReady = success
                    testButton.isEnabled = success
                    testButton.text = getString(R.string.test_voice_settings)
                    if (!success) {
                        Toast.makeText(this@SettingsActivity, getString(R.string.tts_init_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onSpeakStart(utteranceId: String) {
                runOnUiThread {
                    testButton.isEnabled = false
                    testButton.text = getString(R.string.playing)
                }
            }

            override fun onSpeakDone(utteranceId: String) {
                runOnUiThread {
                    testButton.isEnabled = true
                    testButton.text = getString(R.string.test_voice_settings)
                }
            }

            override fun onSpeakError(utteranceId: String, error: String) {
                runOnUiThread {
                    testButton.isEnabled = true
                    testButton.text = getString(R.string.test_voice_settings)
                    Toast.makeText(this@SettingsActivity, getString(R.string.tts_error, error), Toast.LENGTH_SHORT).show()
                }
            }
        })

        testTTS?.initialize(preferences) { }
    }

    private fun testVoiceSettings() {
        if (!ttsReady) {
            Toast.makeText(this, getString(R.string.tts_not_ready), Toast.LENGTH_SHORT).show()
            return
        }

        saveSettings()
        val rate = speechRateSeekBar.progress / 100f
        testTTS?.applyPreferences(preferences, speechRateFromUi = rate, onComplete = null)
        testTTS?.setSpeechRate(rate)
        // Always allow live cloud synthesis here so voice checks reflect the selected xAI voice
        // even if the app is currently pointed at a library item with no cached chunk.
        testTTS?.speak(
            text = getString(R.string.test_voice_phrase_stocks),
            utteranceId = "test",
            allowUncachedInLibrary = true,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        testTTS?.release()
    }

    companion object {
        private const val XAI_CONSOLE_URL = "https://console.x.ai/home"
    }
}
