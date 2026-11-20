package com.mosstts.app.service

import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.mosstts.app.MossTTSApp
import com.mosstts.app.data.ModelManager
import com.mosstts.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 系统 TTS 引擎服务：把本地 MOSS-TTS 模型暴露为 Android 系统文字转语音引擎。
 *
 * 其他应用（阅读器、无障碍服务等）在系统设置里选择「MOSS TTS 本地引擎」后，
 * 即可通过标准 TextToSpeech API 调用本引擎朗读文本。
 *
 * 框架在专用合成线程上串行调用 [onSynthesizeText]。所有 PCM 和完成/错误回调
 * 必须在该方法返回前交付；播放和队列由框架负责。[onStop] 可从另一线程中断推理。
 */
class MossTTSService : TextToSpeechService() {

    companion object {
        private const val TAG = "MossTTSService"

        /** 模型输出固定 48kHz 单声道 */
        private const val SAMPLE_RATE = 48000

        /** 16bit PCM：每个采样点占 2 字节 */
        private const val BYTES_PER_SAMPLE = 2

        /** getMaxBufferSize 返回异常时，单块字节数的兜底值 */
        private const val FALLBACK_CHUNK_BYTES = 16384
    }

    /**
     * 中断代号：[onStop] 时自增。
     * 每次合成开始前记录当前值，执行过程中发现值变化即说明收到停止指令，提前退出。
     */
    private val stopToken = AtomicInteger()

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        AppLogger.info(TAG, "系统 TTS 引擎服务已创建")
    }

    // ==================== 语言与特性 ====================

    /** TextToSpeechService 的语言三元组使用 ISO 三字母码。 */
    override fun onGetLanguage(): Array<String> {
        val locale = SystemTtsLanguage.locale
        return arrayOf(locale.isO3Language, locale.isO3Country, locale.variant)
    }

    /**
     * 是否支持指定语言。
     * 匹配 zh/zho/chi 和 CN/CHN。国家匹配时必须返回 LANG_COUNTRY_AVAILABLE，
     * 否则框架会认为 zh-CN 的 Voice 无效。
     */
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!SystemTtsLanguage.supportsLanguage(lang) || !SystemTtsLanguage.supportsCountry(country)) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        if ((application as? MossTTSApp)?.modelDownloader?.isModelReady() != true) {
            return TextToSpeech.LANG_MISSING_DATA
        }
        return if (country.isNullOrEmpty()) TextToSpeech.LANG_AVAILABLE
        else TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    /**
     * 查询不加载 ONNX；实际合成时再初始化模型。
     */
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetVoices(): List<Voice> = listOf(
        Voice(
            SystemTtsLanguage.VOICE_NAME,
            SystemTtsLanguage.locale,
            Voice.QUALITY_NORMAL,
            Voice.LATENCY_VERY_HIGH,
            false,
            emptySet(),
        ),
    )

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        if (SystemTtsLanguage.supportsLanguage(lang) && SystemTtsLanguage.supportsCountry(country)) {
            SystemTtsLanguage.VOICE_NAME
        } else null

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName == SystemTtsLanguage.VOICE_NAME) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    /**
     * 声明引擎特性集合（API 24+ 的真实方法名，需求文档中的 onGetFeatures() 并不存在）。
     * 本引擎为纯本地推理，不依赖网络、非嵌入式安装，故不声明任何特性，返回空集合。
     */
    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): Set<String> {
        return emptySet()
    }

    // ==================== 合成 ====================

    /**
     * 在框架的合成线程等待 suspend 推理完成，保证返回后不再调用 callback。
     * ModelManager 自行切换推理线程，不阻塞应用的主线程。
     */
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: ""
        val params: Bundle? = request.params
        // utteranceId 仅用于日志关联（框架通过 callback 对象本身路由完成/失败通知）
        val utteranceId = params?.get(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID) as? String
        // 语速为百分比（100 为正常速度）：TextToSpeech.Engine 并无 KEY_PARAM_RATE 常量，
        // 语速由框架经 SynthesisRequest.getSpeechRate() 传入；音量为 0.0~1.0。
        // 此处仅解析记录；音量由框架应用到播放。
        val ratePercent = request.speechRate
        val volume = parseVolume(params)
        AppLogger.info(
            TAG,
            "收到合成请求: utteranceId=$utteranceId, 语速=${ratePercent}%, 音量=$volume, 文本长度=${text.length}"
        )

        val token = stopToken.get()
        try {
            runBlocking {
                synthesizeAndDeliver(text, utteranceId, request, callback, token)
            }
        } catch (e: CancellationException) {
            if (stopToken.get() == token) callback.error(TextToSpeech.ERROR_SYNTHESIS)
        } catch (e: Exception) {
            AppLogger.error(TAG, "合成流程异常: utteranceId=$utteranceId", e)
            runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
        } finally {
            // error() 只标记失败，done() 才向客户端发送结束/失败事件。
            // 收到 stop 时由框架终止回调，其余路径都必须在返回前收尾。
            if (stopToken.get() == token && !callback.hasFinished()) {
                callback.done()
            }
        }
    }

    /**
     * 停止所有合成：中断正在进行的 ONNX 推理，并让音频发送循环退出。
     * 播放由框架负责，框架收到停止后会自行停止播放，引擎无需操作播放器。
     */
    override fun onStop() {
        AppLogger.info(TAG, "onStop: 中断当前合成")
        stopToken.incrementAndGet()
        modelManagerOrNull()?.cancelSynthesis()
    }

    override fun onDestroy() {
        AppLogger.info(TAG, "系统 TTS 引擎服务销毁")
        // 中断可能仍在进行的合成
        stopToken.incrementAndGet()
        modelManagerOrNull()?.cancelSynthesis()
        super.onDestroy()
    }

    // ==================== 内部实现 ====================

    /**
     * 执行合成并把 PCM 分块交给框架播放。
     * 全程 try-catch（由调用方包裹），任何失败都通过 [SynthesisCallback.error]
     * 通知调用方；收到停止指令时安静退出（框架已知晓本次中断，无需再回调）。
     */
    private suspend fun synthesizeAndDeliver(
        text: String,
        utteranceId: String?,
        request: SynthesisRequest,
        callback: SynthesisCallback,
        token: Int,
    ) {
        // 空文本：无内容可合成，按失败通知调用方（等价于旧式 API 的 onError(utteranceId)）
        if (text.isBlank()) {
            AppLogger.warn(TAG, "文本为空，按错误处理: utteranceId=$utteranceId")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }

        // NPE 防护：进程被系统重建等场景下 application 可能不是 MossTTSApp，或单例构造失败
        val manager = modelManagerOrNull()
        if (manager == null) {
            AppLogger.error(TAG, "ModelManager 不可用（应用未正常初始化）: utteranceId=$utteranceId")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }

        // 确保模型已下载并加载（initialize 内部幂等，READY 时直接返回 true）
        if (!manager.isReady() && !manager.initialize()) {
            // 模型未下载或初始化失败：通知调用方，不弹通知、不崩溃
            AppLogger.error(TAG, "模型未下载或初始化失败，无法合成: utteranceId=$utteranceId")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }
        if (stopToken.get() != token) return

        AppLogger.info(
            TAG,
            "开始合成: utteranceId=$utteranceId, voice=${request.voiceName}, 文本=${text.take(30)}"
        )

        // 完整合成（不走流式回调，合成完成后一次性分块交给框架，逻辑简单可靠）
        val result = manager.synthesize(
            text = text,
            // 应用未指定音色时使用默认内置音色；指定了不存在的音色时引擎内部会回退到首个内置音色
            voice = request.voiceName,
            referenceAudioCodes = null, // 系统 TTS 不使用克隆音色
            maxFrames = null, // 不限制帧数，按模型默认配置生成
        )

        // 合成过程中若收到停止指令：ONNX 推理被 cancel 中断后 result 会为 null，
        // 此时框架已知晓本次中断，安静退出即可，不再回调 error/done
        if (stopToken.get() != token) {
            AppLogger.info(TAG, "合成被中断，停止发送音频: utteranceId=$utteranceId")
            return
        }

        if (result == null) {
            AppLogger.error(TAG, "合成失败: utteranceId=$utteranceId")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }

        // 向框架声明音频格式：48kHz / 单声道 / 16bit PCM（MOSS-TTS 模型输出固定格式）
        val sampleRate = if (result.sampleRate > 0) result.sampleRate else SAMPLE_RATE
        val startRet = callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        if (startRet != TextToSpeech.SUCCESS) {
            AppLogger.error(TAG, "callback.start 失败: $startRet, utteranceId=$utteranceId")
            return
        }

        // 分块发送 PCM，块大小受框架 getMaxBufferSize 限制
        val maxBytes = callback.getMaxBufferSize()
        val chunkBytes = if (maxBytes > 0) maxBytes else FALLBACK_CHUNK_BYTES
        val chunkSamples = (chunkBytes / BYTES_PER_SAMPLE).coerceAtLeast(1)

        var offset = 0
        while (offset < result.pcm.size) {
            if (stopToken.get() != token) {
                AppLogger.info(TAG, "发送音频期间收到停止指令: utteranceId=$utteranceId")
                return
            }
            val end = minOf(offset + chunkSamples, result.pcm.size)
            // FloatArray（-1.0 ~ 1.0）→ 16bit 小端 PCM 字节
            val bytes = floatToPcm16Bytes(result.pcm, offset, end)
            val ret = callback.audioAvailable(bytes, 0, bytes.size)
            if (ret != TextToSpeech.SUCCESS) {
                // 客户端已停止或出错，框架不再接收音频，安静退出即可
                AppLogger.warn(TAG, "audioAvailable 返回 $ret，停止发送: utteranceId=$utteranceId")
                return
            }
            offset = end
        }

        if (stopToken.get() != token) {
            AppLogger.info(TAG, "发送音频完成后收到停止指令: utteranceId=$utteranceId")
            return
        }

        val doneRet = callback.done()
        AppLogger.info(
            TAG,
            "合成播报完成: utteranceId=$utteranceId, 采样点=${result.pcm.size}, done=$doneRet"
        )
    }

    /**
     * 获取全局共享的 ModelManager。
     * NPE 防护：进程重建/类加载异常等场景下返回 null，由调用方走错误分支，绝不崩溃。
     */
    private fun modelManagerOrNull(): ModelManager? {
        return try {
            (application as? MossTTSApp)?.modelManager
        } catch (e: Exception) {
            AppLogger.error(TAG, "获取 ModelManager 失败: ${e.message}", e)
            null
        }
    }

    /**
     * 解析 params 中的音量参数（KEY_PARAM_VOLUME，0.0 ~ 1.0）。
     * 音量最终由框架应用到播放，此处解析仅用于日志；解析失败时按 1.0 处理，保证不崩。
     */
    private fun parseVolume(params: Bundle?): Float {
        if (params == null) return 1f
        val raw: Any? = params.get(TextToSpeech.Engine.KEY_PARAM_VOLUME)
        return when (raw) {
            is Float -> raw.coerceIn(0f, 1f)
            is String -> raw.trim().toFloatOrNull()?.coerceIn(0f, 1f)
            else -> null
        } ?: 1f
    }

    /**
     * FloatArray（-1.0 ~ 1.0）转 16bit 小端 PCM 字节数组（仅转换 [from, to) 区间）。
     * 转换公式与应用内 AudioPlayer.saveWav 保持一致：(sample * 32767).toInt().toShort()。
     */
    private fun floatToPcm16Bytes(pcm: FloatArray, from: Int, to: Int): ByteArray {
        val bytes = ByteArray((to - from) * BYTES_PER_SAMPLE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until to) {
            val sample = pcm[i].coerceIn(-1f, 1f)
            buffer.putShort((sample * 32767f).toInt().toShort())
        }
        return bytes
    }
}
