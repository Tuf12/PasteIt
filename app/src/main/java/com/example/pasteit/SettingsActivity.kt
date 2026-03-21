package com.example.pasteit

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var speechRateSeekBar: SeekBar
    private lateinit var noiseScaleSeekBar: SeekBar
    private lateinit var noiseScaleWSeekBar: SeekBar
    private lateinit var lengthScaleSeekBar: SeekBar
    private lateinit var silenceScaleSeekBar: SeekBar
    private lateinit var chunkMaxSeekBar: SeekBar

    private lateinit var noiseScaleValue: TextView
    private lateinit var noiseScaleWValue: TextView
    private lateinit var lengthScaleValue: TextView
    private lateinit var silenceScaleValue: TextView
    private lateinit var chunkMaxValue: TextView

    private lateinit var testButton: Button
    private lateinit var resetVoiceDefaultsButton: Button
    private lateinit var backButton: Button
    private lateinit var voiceSpinner: Spinner
    private lateinit var speechFormatSpinner: Spinner
    private lateinit var voiceInfoText: TextView

    /** Avoid firing voice reload when [loadSettings] sets the spinner index. */
    private var voiceSpinnerProgrammatic = false
    private var speechFormatSpinnerProgrammatic = false

    private lateinit var preferences: SharedPreferences
    private var testTTS: SherpaOnnxTts? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        preferences = getSharedPreferences("PasteItSettings", MODE_PRIVATE)

        initializeViews()
        loadSettings()
        setupListeners()
        initializeTestTTS()
    }

    private fun initializeViews() {
        speechRateSeekBar = findViewById(R.id.speechRateSeekBar)
        noiseScaleSeekBar = findViewById(R.id.noiseScaleSeekBar)
        noiseScaleWSeekBar = findViewById(R.id.noiseScaleWSeekBar)
        lengthScaleSeekBar = findViewById(R.id.lengthScaleSeekBar)
        silenceScaleSeekBar = findViewById(R.id.silenceScaleSeekBar)
        chunkMaxSeekBar = findViewById(R.id.chunkMaxSeekBar)

        noiseScaleValue = findViewById(R.id.noiseScaleValue)
        noiseScaleWValue = findViewById(R.id.noiseScaleWValue)
        lengthScaleValue = findViewById(R.id.lengthScaleValue)
        silenceScaleValue = findViewById(R.id.silenceScaleValue)
        chunkMaxValue = findViewById(R.id.chunkMaxValue)

        testButton = findViewById(R.id.testButton)
        resetVoiceDefaultsButton = findViewById(R.id.resetVoiceDefaultsButton)
        backButton = findViewById(R.id.backButton)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        speechFormatSpinner = findViewById(R.id.speechFormatSpinner)
        voiceInfoText = findViewById(R.id.voiceInfoText)

        voiceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TtsVoiceProfile.entries.map { getString(it.labelResId) },
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

        // noise 0.10–1.00 → x1000 [100,1000], progress offset 100
        noiseScaleSeekBar.max = 900
        // noiseW 0.10–1.20 → [100,1200]
        noiseScaleWSeekBar.max = 1100
        // length 0.50–1.50 → [500,1500]
        lengthScaleSeekBar.max = 1000
        // silence 0.05–0.60 → x1000 [50,600]
        silenceScaleSeekBar.max = 550
        // chunk chars [120,520]
        chunkMaxSeekBar.max = 400
    }

    private fun loadSettings() {
        val savedRate = preferences.getInt(
            SherpaTtsEngineParams.PREF_SPEECH_RATE,
            SherpaTtsEngineParams.DEFAULT_SPEECH_RATE_PERCENT,
        )
        speechRateSeekBar.progress = savedRate
        findViewById<TextView>(R.id.rateValue).text = getString(R.string.percent_int, savedRate)

        val n = preferences.getInt(
            SherpaTtsEngineParams.PREF_NOISE_SCALE_X1000,
            SherpaTtsEngineParams.DEFAULT_NOISE_SCALE_X1000,
        ).coerceIn(100, 1000)
        noiseScaleSeekBar.progress = n - 100
        updateNoiseLabel()

        val nw = preferences.getInt(
            SherpaTtsEngineParams.PREF_NOISE_SCALE_W_X1000,
            SherpaTtsEngineParams.DEFAULT_NOISE_SCALE_W_X1000,
        ).coerceIn(100, 1200)
        noiseScaleWSeekBar.progress = nw - 100
        updateNoiseWLabel()

        val len = preferences.getInt(
            SherpaTtsEngineParams.PREF_LENGTH_SCALE_X1000,
            SherpaTtsEngineParams.DEFAULT_LENGTH_SCALE_X1000,
        ).coerceIn(500, 1500)
        lengthScaleSeekBar.progress = len - 500
        updateLengthLabel()

        val sil = preferences.getInt(
            SherpaTtsEngineParams.PREF_SILENCE_SCALE_X1000,
            SherpaTtsEngineParams.DEFAULT_SILENCE_SCALE_X1000,
        ).coerceIn(50, 600)
        silenceScaleSeekBar.progress = sil - 50
        updateSilenceLabel()

        val chunk = preferences.getInt(
            SherpaTtsEngineParams.PREF_CHUNK_MAX_CHARS,
            SherpaTtsEngineParams.DEFAULT_CHUNK_MAX_CHARS,
        ).coerceIn(120, 520)
        chunkMaxSeekBar.progress = chunk - 120
        updateChunkLabel()

        val voice = TtsVoiceProfile.fromPreferences(preferences)
        voiceSpinnerProgrammatic = true
        voiceSpinner.setSelection(TtsVoiceProfile.entries.indexOf(voice).coerceAtLeast(0))
        voiceSpinnerProgrammatic = false
        updateVoiceHint(voice)

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

    private fun updateVoiceHint(profile: TtsVoiceProfile) {
        voiceInfoText.text = getString(profile.hintResId)
    }

    private fun noiseStored(): Int = noiseScaleSeekBar.progress + 100
    private fun noiseWStored(): Int = noiseScaleWSeekBar.progress + 100
    private fun lengthStored(): Int = lengthScaleSeekBar.progress + 500
    private fun silenceStored(): Int = silenceScaleSeekBar.progress + 50
    private fun chunkStored(): Int = chunkMaxSeekBar.progress + 120

    private fun updateNoiseLabel() {
        noiseScaleValue.text = getString(R.string.float_2, noiseStored() / 1000f)
    }

    private fun updateNoiseWLabel() {
        noiseScaleWValue.text = getString(R.string.float_2, noiseWStored() / 1000f)
    }

    private fun updateLengthLabel() {
        lengthScaleValue.text = getString(R.string.float_2, lengthStored() / 1000f)
    }

    private fun updateSilenceLabel() {
        silenceScaleValue.text = getString(R.string.float_2, silenceStored() / 1000f)
    }

    private fun updateChunkLabel() {
        chunkMaxValue.text = getString(R.string.int_value, chunkStored())
    }

    private fun setupListeners() {
        speechRateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.rateValue).text = getString(R.string.percent_int, progress)
                if (fromUser) saveSettings()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val vitsListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                when (seekBar?.id) {
                    R.id.noiseScaleSeekBar -> updateNoiseLabel()
                    R.id.noiseScaleWSeekBar -> updateNoiseWLabel()
                    R.id.lengthScaleSeekBar -> updateLengthLabel()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveSettings()
                applyVitsToTestTts()
            }
        }
        noiseScaleSeekBar.setOnSeekBarChangeListener(vitsListener)
        noiseScaleWSeekBar.setOnSeekBarChangeListener(vitsListener)
        lengthScaleSeekBar.setOnSeekBarChangeListener(vitsListener)

        silenceScaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSilenceLabel()
                if (fromUser) {
                    saveSettings()
                    applyGenerationPrefsToTestTts()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        chunkMaxSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateChunkLabel()
                if (fromUser) saveSettings()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        testButton.setOnClickListener {
            testVoiceSettings()
        }

        resetVoiceDefaultsButton.setOnClickListener {
            resetVoiceSettingsToDefaults()
        }

        backButton.setOnClickListener {
            finish()
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

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun resetVoiceSettingsToDefaults() {
        preferences.edit().apply {
            SherpaTtsEngineParams.writeFactoryVoiceDefaults(this)
            apply()
        }
        loadSettings()
        Toast.makeText(this, getString(R.string.defaults_restored), Toast.LENGTH_SHORT).show()
        if (ttsReady) {
            applyVitsToTestTts()
        }
    }

    private fun saveSettings() {
        preferences.edit().apply {
            putInt(SherpaTtsEngineParams.PREF_SPEECH_RATE, speechRateSeekBar.progress)
            putInt(SherpaTtsEngineParams.PREF_NOISE_SCALE_X1000, noiseStored())
            putInt(SherpaTtsEngineParams.PREF_NOISE_SCALE_W_X1000, noiseWStored())
            putInt(SherpaTtsEngineParams.PREF_LENGTH_SCALE_X1000, lengthStored())
            putInt(SherpaTtsEngineParams.PREF_SILENCE_SCALE_X1000, silenceStored())
            putInt(SherpaTtsEngineParams.PREF_CHUNK_MAX_CHARS, chunkStored())
            apply()
        }
    }

    private fun applyVitsToTestTts() {
        if (!ttsReady) return
        testButton.isEnabled = false
        testButton.text = getString(R.string.reloading_voice)
        testTTS?.applyPreferences(
            preferences,
            speechRateFromUi = speechRateSeekBar.progress / 100f,
        ) { ok ->
            testButton.text = getString(R.string.test_voice_settings)
            testButton.isEnabled = ok && ttsReady
            if (!ok) {
                Toast.makeText(this, getString(R.string.tts_init_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyGenerationPrefsToTestTts() {
        if (!ttsReady) return
        testTTS?.applyPreferences(
            preferences,
            speechRateFromUi = speechRateSeekBar.progress / 100f,
            onComplete = null,
        )
    }

    private fun initializeTestTTS() {
        testButton.isEnabled = false
        testButton.text = getString(R.string.loading_tts)

        testTTS = SherpaOnnxTts(this)
        testTTS?.setCallback(object : SherpaOnnxTts.TtsCallback {
            override fun onInitialized(success: Boolean) {
                runOnUiThread {
                    ttsReady = success
                    testButton.isEnabled = success
                    testButton.text = getString(R.string.test_voice_settings)
                    if (!success) {
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.tts_init_failed),
                            Toast.LENGTH_LONG,
                        ).show()
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
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.tts_error, error),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        })

        testTTS?.initialize(preferences) {
            // Detailed UI handled in callback
        }
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
        testTTS?.speak(
            getString(R.string.test_voice_phrase_stocks),
            "test",
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        testTTS?.release()
    }
}
