// SettingsActivity.kt
package com.example.pasteit

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {
    private lateinit var speechRateSeekBar: SeekBar
    private lateinit var speechPitchSeekBar: SeekBar
    private lateinit var voiceSpinner: Spinner
    private lateinit var testButton: Button
    private lateinit var backButton: Button
    private var availableVoices = mutableListOf<android.speech.tts.Voice>()
    private lateinit var voiceAdapter: ArrayAdapter<String>

    private lateinit var preferences: SharedPreferences
    private var testTTS: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        preferences = getSharedPreferences("PasteItSettings", Context.MODE_PRIVATE)

        initializeViews()
        loadSettings()
        setupListeners()
        initializeTestTTS()
    }

    private fun initializeViews() {
        speechRateSeekBar = findViewById(R.id.speechRateSeekBar)
        speechPitchSeekBar = findViewById(R.id.speechPitchSeekBar)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        testButton = findViewById(R.id.testButton)
        backButton = findViewById(R.id.backButton)

        // Setup seekbars
        speechRateSeekBar.max = 200
        speechPitchSeekBar.max = 200
    }

    private fun loadSettings() {
        val savedRate = preferences.getInt("speech_rate", 100)
        val savedPitch = preferences.getInt("speech_pitch", 100)

        speechRateSeekBar.progress = savedRate
        speechPitchSeekBar.progress = savedPitch

        val rateTv = findViewById<TextView>(R.id.rateValue)
        val pitchTv = findViewById<TextView>(R.id.pitchValue)

        rateTv.text = getString(R.string.percent_int, savedRate)
        pitchTv.text = getString(R.string.percent_int, savedPitch)

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

        speechPitchSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                findViewById<TextView>(R.id.pitchValue).text = getString(R.string.percent_int, progress)
                if (fromUser) saveSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        testButton.setOnClickListener {
            testVoiceSettings()
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun saveSettings() {
        preferences.edit().apply {
            putInt("speech_rate", speechRateSeekBar.progress)
            putInt("speech_pitch", speechPitchSeekBar.progress)
            apply()
        }
    }

    private fun initializeTestTTS() {
        testTTS = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                testButton.isEnabled = true
                setupVoiceSpinner()
            }
        }
    }

    private fun testVoiceSettings() {
        testTTS?.let { tts ->
            val rate = speechRateSeekBar.progress / 100f
            val pitch = speechPitchSeekBar.progress / 100f

            tts.setSpeechRate(rate)
            tts.setPitch(pitch)

            tts.speak(
                "This is a test of your PasteIt voice settings. How does this sound?",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "test"
            )
        }
    }

    private fun setupVoiceSpinner() {
        testTTS?.let { tts ->
            availableVoices.clear()
            val voices = tts.voices
            if (voices != null) {
                availableVoices.addAll(voices.filter { it.locale.language == java.util.Locale.getDefault().language })
            }

            val voiceNames = availableVoices.map { voice ->
                "${voice.name} (${voice.locale.displayCountry})"
            }.toMutableList()

            if (voiceNames.isEmpty()) {
                voiceNames.add("Default Voice")
            }

            voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
            voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            voiceSpinner.adapter = voiceAdapter

            // Load saved voice preference
            val savedVoiceName = preferences.getString("selected_voice", "")
            val savedIndex = availableVoices.indexOfFirst { it.name == savedVoiceName }
            if (savedIndex >= 0) {
                voiceSpinner.setSelection(savedIndex)
            }

            voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    if (position < availableVoices.size) {
                        val selectedVoice = availableVoices[position]
                        preferences.edit {
                            putString("selected_voice", selectedVoice.name)
                        }
                        tts.voice = selectedVoice
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testTTS?.shutdown()
    }
}