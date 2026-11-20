package com.mosstts.edge.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.mosstts.edge.EdgeSettings
import com.mosstts.edge.MainActivity
import com.mosstts.edge.R

class SystemTtsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = EdgeSettings(this)
        when (intent.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val names = settings.catalog.voices().map {
                    "${it.locale.isO3Language}-${it.locale.isO3Country}".trimEnd('-')
                }.distinct()
                setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, Intent()
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(names))
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf()))
            }
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> {
                val lang = intent.getStringExtra("language") ?: "zh"
                val supported = settings.catalog.defaultVoice(lang, intent.getStringExtra("country"), settings.voice) != null
                setResult(if (supported) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED,
                    Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                        if (!supported) "" else if (lang in listOf("zh", "zho", "chi")) getString(R.string.sample_text)
                        else "Hello, this is the Edge online text to speech engine."))
            }
            else -> startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
