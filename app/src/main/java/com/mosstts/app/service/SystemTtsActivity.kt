package com.mosstts.app.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.mosstts.app.MainActivity
import com.mosstts.app.MossTTSApp
import com.mosstts.app.R
import com.mosstts.app.Screen

/** 系统 TTS 的无界面协议入口。检查模型时只查看文件，不加载 ONNX。 */
class SystemTtsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> checkVoiceData()
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> getSampleText()
            TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA -> openApp(Screen.Models.route)
            else -> openApp(Screen.Settings.route)
        }
        finish()
    }

    private fun checkVoiceData() {
        val ready = (application as MossTTSApp).modelDownloader.isModelReady()
        val voices = ArrayList(SystemTtsLanguage.dataVoiceNames)
        val result = Intent()
            .putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                if (ready) voices else arrayListOf(),
            )
            .putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                if (ready) arrayListOf() else voices,
            )
        setResult(
            if (ready) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
            else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL,
            result,
        )
    }

    private fun getSampleText() {
        val supported = SystemTtsLanguage.supportsLanguage(
            intent.getStringExtra("language") ?: SystemTtsLanguage.locale.language,
        ) && SystemTtsLanguage.supportsCountry(intent.getStringExtra("country"))
        setResult(
            if (supported) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED,
            Intent().putExtra(
                TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                if (supported) getString(R.string.system_tts_sample_text) else "",
            ),
        )
    }

    private fun openApp(route: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_INITIAL_SCREEN, route),
        )
    }
}
