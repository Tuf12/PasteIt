package com.example.pasteit

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var speechRateSeekBar: SeekBar
    private lateinit var testButton: Button
    private lateinit var backButton: Button
    private lateinit var voiceInfoText: TextView

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
        testButton = findViewById(R.id.testButton)
        backButton = findViewById(R.id.backButton)
        voiceInfoText = findViewById(R.id.voiceInfoText)

        speechRateSeekBar.max = 200
        
        voiceInfoText.text = getString(R.string.voice_info, "Alba (British English)")
    }

    private fun loadSettings() {
        val savedRate = preferences.getInt("speech_rate", 100)
        speechRateSeekBar.progress = savedRate

        val rateTv = findViewById<TextView>(R.id.rateValue)
        rateTv.text = getString(R.string.percent_int, savedRate)
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
            apply()
        }
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
                        Toast.makeText(this@SettingsActivity, 
                            getString(R.string.tts_init_failed), Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this@SettingsActivity, 
                        getString(R.string.tts_error, error), Toast.LENGTH_SHORT).show()
                }
            }
        })
        
        testTTS?.initialize { success ->
            // Handled in callback
        }
    }

    private fun testVoiceSettings() {
        if (!ttsReady) {
            Toast.makeText(this, getString(R.string.tts_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        
        val rate = speechRateSeekBar.progress / 100f
        testTTS?.setSpeechRate(rate)
        testTTS?.speak(
            "This is a test of your PasteIt voice settings. How does this sound?",
            "test"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        testTTS?.release()
    }
}