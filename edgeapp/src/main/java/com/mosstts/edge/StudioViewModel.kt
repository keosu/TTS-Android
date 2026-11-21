package com.mosstts.edge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mosstts.edge.audio.Playback
import com.mosstts.edge.audio.StudioPlayer
import com.mosstts.edge.data.AudioGenerator
import com.mosstts.edge.data.AudioRecord
import com.mosstts.edge.data.AudioDocuments
import com.mosstts.edge.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CancellationException

data class StudioState(
    val text: String = "", val voices: List<EdgeVoice> = emptyList(), val voice: String = EdgeVoiceCatalog.DEFAULT_VOICE,
    val rate: Int = 100, val pitch: Int = 100, val systemVoice: String = EdgeVoiceCatalog.DEFAULT_VOICE,
    val systemRate: Int = 100, val systemPitch: Int = 100, val theme: Int = 0, val autoPlay: Boolean = false,
    val refreshing: Boolean = false, val generating: Boolean = false, val completedParts: Int = 0, val totalParts: Int = 0,
    val history: List<AudioRecord> = emptyList(), val result: AudioRecord? = null, val playback: Playback = Playback(),
    val previewLoading: String? = null, val exporting: Boolean = false, val message: String? = null,
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = EdgeSettings(application)
    private val store = (application as EdgeApplication).history
    private val client = EdgeClient()
    private val _state = MutableStateFlow(StudioState(settings.draft, settings.catalog.voices(), settings.studioVoice,
        settings.studioRate, settings.studioPitch, settings.voice, settings.rate, settings.pitch, settings.theme,
        settings.autoPlay, history = store.all(), result = store.all().firstOrNull()))
    val state = _state.asStateFlow()
    private var generationSession: EdgeSession? = null
    private var previewSession: EdgeSession? = null
    private var previewFile: File? = null
    private var saveDraftJob: Job? = null
    private val player = StudioPlayer(application, { playback -> _state.update { it.copy(playback = playback) } },
        { message("音频播放失败，请重新生成或选择其他记录") })

    init {
        if (settings.catalog.find(settings.studioVoice) == null) selectVoice(EdgeVoiceCatalog.DEFAULT_VOICE)
        if (settings.catalog.voices().size <= EdgeVoiceCatalog.FALLBACK.size) refreshVoices()
    }
    fun message(value: String) { _state.update { it.copy(message = value) } }
    fun clearMessage(value: String) { _state.update { if (it.message == value) it.copy(message = null) else it } }
    fun text(value: String) {
        val bounded = value.take(20000)
        _state.update { it.copy(text = bounded) }
        saveDraftJob?.cancel()
        saveDraftJob = viewModelScope.launch { delay(400); settings.draft = bounded }
    }
    fun selectVoice(value: String, system: Boolean = false) {
        if (system) { settings.voice = value; _state.update { it.copy(systemVoice = value) } }
        else { settings.studioVoice = value; _state.update { it.copy(voice = value) } }
    }
    fun rate(value: Int, system: Boolean = false) {
        if (system) { settings.rate = value; _state.update { it.copy(systemRate = value) } }
        else { settings.studioRate = value; _state.update { it.copy(rate = value) } }
    }
    fun pitch(value: Int, system: Boolean = false) {
        if (system) { settings.pitch = value; _state.update { it.copy(systemPitch = value) } }
        else { settings.studioPitch = value; _state.update { it.copy(pitch = value) } }
    }
    fun theme(value: Int) { settings.theme = value; _state.update { it.copy(theme = value) } }
    fun autoPlay(value: Boolean) { settings.autoPlay = value; _state.update { it.copy(autoPlay = value) } }
    fun refreshVoices() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val voices = withContext(Dispatchers.IO) { settings.catalog.refresh(client) }
                _state.update { it.copy(voices = voices) }
                if (voices.none { it.name == _state.value.voice }) selectVoice(voices.first().name)
                message("已更新 ${voices.size} 个音色")
            } catch (_: CancellationException) { }
            catch (_: Exception) { message("音色更新失败，现有音色仍可使用") }
            finally { _state.update { it.copy(refreshing = false) } }
        }
    }
    fun generate() {
        val snapshot = _state.value
        if (snapshot.generating || snapshot.text.isBlank()) return
        val voice = snapshot.voices.firstOrNull { it.name == snapshot.voice } ?: return
        stopPreview(); player.stop()
        val session = EdgeSession()
        generationSession = session
        settings.draft = snapshot.text
        _state.update { it.copy(generating = true, completedParts = 0,
            totalParts = EdgeProtocol.splitText(snapshot.text, 4000).size) }
        viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.IO) {
                    AudioGenerator(store, client).generate(snapshot.text, voice, snapshot.rate, snapshot.pitch, session) { done, total ->
                        _state.update { it.copy(completedParts = done, totalParts = total) }
                    }
                }
                _state.update { it.copy(result = record, history = store.all()) }
                message("语音已生成并保存到最近记录")
                if (settings.autoPlay) play(record)
            } catch (_: CancellationException) { message("已取消生成") }
            catch (_: Exception) { message("生成失败，请检查网络后重试。已有记录已保留") }
            finally {
                generationSession = null
                _state.update { it.copy(generating = false, history = store.all()) }
            }
        }
    }
    fun cancelGeneration() { generationSession?.stop() }
    fun play(record: AudioRecord) {
        stopPreview()
        if (!store.file(record, "mp3").isFile) { message("音频已被清理，请重新生成"); return }
        player.play(record.id, store.file(record, "mp3"))
    }
    fun seek(position: Long) = player.seek(position)
    fun stopPlayback() = player.stop()
    fun preview(voice: EdgeVoice) {
        if (_state.value.previewLoading == voice.name) { stopPreview(); return }
        if (_state.value.playback.id == "sample:${voice.name}") { player.toggle(); return }
        stopPreview(); player.stop()
        val session = EdgeSession()
        previewSession = session
        _state.update { it.copy(previewLoading = voice.name) }
        viewModelScope.launch {
            var file: File? = null
            try {
                val temporary = File.createTempFile("edge-preview-", ".mp3", getApplication<Application>().cacheDir)
                file = temporary
                withContext(Dispatchers.IO) {
                    val sample = when (voice.locale.language) {
                        "zh" -> "你好，欢迎使用这款自然流畅的在线语音。"
                        "ja" -> "こんにちは。自然な音声をお楽しみください。"
                        "ko" -> "안녕하세요. 자연스러운 음성을 들어 보세요."
                        "fr" -> "Bonjour, découvrez cette voix naturelle."
                        "de" -> "Hallo, hören Sie diese natürliche Stimme."
                        "es" -> "Hola, escucha esta voz natural."
                        else -> "Hello, listen to this clear and natural voice."
                    }
                    val bytes = client.synthesize(sample, voice, 100, 100, session)
                    session.checkActive()
                    temporary.writeBytes(bytes)
                }
                session.checkActive()
                previewFile = file
                player.play("sample:${voice.name}", temporary)
            } catch (_: CancellationException) { file?.delete() }
            catch (_: Exception) { file?.delete(); message("试听失败，请检查网络后重试") }
            finally {
                if (previewSession === session) {
                    previewSession = null
                    _state.update { it.copy(previewLoading = null) }
                }
            }
        }
    }
    fun stopPreview() {
        previewSession?.stop()
        previewSession = null
        _state.update { it.copy(previewLoading = null) }
        if (_state.value.playback.id?.startsWith("sample:") == true) player.stop()
        previewFile?.delete(); previewFile = null
    }
    fun useRecord(record: AudioRecord) {
        text(record.text); selectVoice(record.voice); rate(record.rate); pitch(record.pitch)
        _state.update { it.copy(result = record) }
    }
    fun refreshHistory() { _state.update { it.copy(history = store.all()) } }
    fun delete(record: AudioRecord) {
        if (_state.value.playback.id == record.id) player.stop()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.delete(record.id) } }
                .onSuccess { _state.update { it.copy(history = store.all(), result = it.result?.takeUnless { item -> item.id == record.id }) } }
                .onFailure { message("删除失败，请重试") }
        }
    }
    fun clearHistory() {
        player.stop()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.clear() } }
                .onSuccess { _state.update { it.copy(history = emptyList(), result = null) }; message("最近记录已清空") }
                .onFailure { message("清空失败，请重试") }
        }
    }
    fun audioFile(record: AudioRecord) = store.file(record, "mp3")
    fun export(id: String, extension: String, uri: Uri) {
        val record = store.all().firstOrNull { it.id == id }
        if (record == null) { message("记录已被清理，请重新生成"); return }
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AudioDocuments.save(getApplication(), store.file(record, extension), uri)
                }
                message("已保存 ${extension.uppercase()} 文件")
            } catch (_: Exception) { message("保存失败，请选择可写入的位置后重试") }
            finally { _state.update { it.copy(exporting = false) } }
        }
    }
    override fun onCleared() {
        generationSession?.stop(); stopPreview(); player.stop()
        settings.draft = _state.value.text
        super.onCleared()
    }
}
