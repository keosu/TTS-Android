package com.mosstts.edge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputFilter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.mosstts.edge.protocol.EdgeClient
import com.mosstts.edge.protocol.EdgeVoice
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: EdgeSettings
    private lateinit var status: TextView
    private lateinit var languagePicker: Spinner
    private lateinit var voicePicker: Spinner
    private lateinit var preview: Button
    private lateinit var refresh: Button
    private lateinit var text: EditText
    private var tts: TextToSpeech? = null
    private var ready = false
    private var updating = false
    private var languages = emptyList<String>()
    private var visibleVoices = emptyList<EdgeVoice>()
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = EdgeSettings(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size
            setPadding(0, 18, 0, 12)
            content.addView(this)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value
            content.addView(this)
            setOnClickListener { action() }
        }
        label("Edge TTS 在线引擎", 26f)
        label("可与 MOSS TTS 同时安装，在系统设置中切换引擎。无需下载模型。\n\n朗读需要联网，文本会发送至 Microsoft 的语音服务。")
        button("打开系统文字转语音设置") {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        label("语言")
        languagePicker = Spinner(this).also { content.addView(it) }
        label("默认音色")
        voicePicker = Spinner(this).also { content.addView(it) }
        refresh = button("刷新在线音色列表") { refreshVoices() }
        languagePicker.onItemSelectedListener = selected { position ->
            if (!updating && position in languages.indices) updateVoicePicker(languages[position])
        }
        voicePicker.onItemSelectedListener = selected { position ->
            if (!updating && position in visibleVoices.indices) settings.voice = visibleVoices[position].name
        }
        updateLanguages()
        fun slider(name: String, initial: Int, min: Int, max: Int, save: (Int) -> Unit) {
            val title = label("$name：$initial%")
            content.addView(SeekBar(this).apply {
                this.max = max - min
                progress = initial - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        title.text = "$name：${progress + min}%"
                        if (fromUser) save(progress + min)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
        }
        slider("默认语速", settings.rate, 50, 300) { settings.rate = it }
        slider("默认音调", settings.pitch, 50, 150) { settings.pitch = it }
        label("阅读器和系统设置传入的语速、音调会与这里的默认值相乘。音量由系统控制。", 14f)
        text = EditText(this).apply {
            hint = "输入试听文本"
            setText(getString(R.string.sample_text))
            minLines = 3
            filters = arrayOf(InputFilter.LengthFilter(TextToSpeech.getMaxSpeechInputLength()))
            content.addView(this)
        }
        preview = button("试听") { speak() }.apply { isEnabled = false }
        button("停止朗读") { tts?.stop(); status.text = "已停止" }
        status = label("正在连接系统 TTS 服务…", 14f)
        button("开源许可与源代码") { showLicenses() }
        tts = TextToSpeech(this, { result ->
            handler.post {
                if (!destroyed) {
                    ready = result == TextToSpeech.SUCCESS
                    preview.isEnabled = ready
                    status.text = if (ready) "引擎已就绪，联网后即可试听" else "系统 TTS 服务连接失败，请重新打开应用"
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = showStatus("正在朗读…")
                        override fun onDone(utteranceId: String?) = showStatus("朗读完成")
                        @Deprecated("Android callback")
                        override fun onError(utteranceId: String?) = showStatus("合成失败，请检查网络后重试")
                        override fun onError(utteranceId: String?, errorCode: Int) = showStatus(when (errorCode) {
                            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "连接超时，请检查网络后重试"
                            TextToSpeech.ERROR_NETWORK -> "在线服务暂不可用，请检查网络后重试"
                            TextToSpeech.ERROR_INVALID_REQUEST -> "音色或文本无效，请刷新音色列表"
                            else -> "合成失败（$errorCode），请重试"
                        })
                    })
                }
            }
        }, packageName)
        refreshVoices()
    }

    private fun selected(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun updateLanguages() {
        updating = true
        val voices = settings.catalog.voices()
        languages = voices.map { it.locale.toLanguageTag() }.distinct().sorted()
        val currentLocale = settings.catalog.find(settings.voice)?.locale?.toLanguageTag() ?: "zh-CN"
        languagePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            languages.map { "${java.util.Locale.forLanguageTag(it).getDisplayName(java.util.Locale.SIMPLIFIED_CHINESE)} · $it" })
        languagePicker.setSelection(languages.indexOf(currentLocale).coerceAtLeast(0))
        updating = false
        updateVoicePicker(languages[languagePicker.selectedItemPosition.coerceAtLeast(0)])
    }

    private fun updateVoicePicker(language: String) {
        updating = true
        visibleVoices = settings.catalog.voices().filter { it.locale.toLanguageTag() == language }
        voicePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, visibleVoices)
        val position = visibleVoices.indexOfFirst { it.name == settings.voice }.coerceAtLeast(0)
        voicePicker.setSelection(position)
        visibleVoices.getOrNull(position)?.let { settings.voice = it.name }
        updating = false
    }

    private fun refreshVoices() {
        refresh.isEnabled = false
        worker.execute {
            val result = runCatching { settings.catalog.refresh(EdgeClient()) }
            handler.post {
                if (!destroyed) {
                    refresh.isEnabled = true
                    result.onSuccess { voices -> updateLanguages(); status.text = "已更新 ${voices.size} 个在线音色" }
                        .onFailure { status.text = "音色列表更新失败，已保留现有音色；请检查网络后重试" }
                }
            }
        }
    }

    private fun speak() {
        if (!ready) return
        val engine = tts ?: return
        val voice = engine.voices?.firstOrNull { it.name == settings.voice }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            status.text = "系统尚未加载该音色，请重新打开应用后试听"
            return
        }
        // The service applies its own stored multipliers.
        engine.setSpeechRate(1f)
        engine.setPitch(1f)
        status.text = "正在请求在线语音…"
        val result = engine.speak(text.text.toString().ifBlank { getString(R.string.sample_text) },
            TextToSpeech.QUEUE_FLUSH, Bundle(), "edge-preview")
        if (result != TextToSpeech.SUCCESS) status.text = "系统拒绝朗读请求，请重试"
    }

    private fun showStatus(value: String) { handler.post { if (!destroyed) status.text = value } }
    private fun showLicenses() {
        val body = TextView(this).apply {
            text = assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() } +
                "\n\n" + assets.open("licenses/Apache-2.0.txt").bufferedReader().use { it.readText() } +
                "\n\n" + assets.open("licenses/LGPL-3.0.txt").bufferedReader().use { it.readText() } +
                "\n\n" + assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(this).setTitle("开源许可与源代码")
            .setView(ScrollView(this).apply { addView(body) }).setPositiveButton("关闭", null).show()
    }

    override fun onDestroy() {
        destroyed = true
        tts?.stop()
        tts?.shutdown()
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
