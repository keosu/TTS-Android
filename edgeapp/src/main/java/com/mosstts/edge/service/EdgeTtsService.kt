package com.mosstts.edge.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.mosstts.edge.EdgeSettings
import com.mosstts.edge.audio.Mp3Decoder
import com.mosstts.edge.protocol.EdgeClient
import com.mosstts.edge.protocol.EdgeProtocol
import com.mosstts.edge.protocol.EdgeSession
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

class EdgeTtsService : TextToSpeechService() {
    private val client = EdgeClient()
    private val current = AtomicReference<EdgeSession?>()
    private val settings by lazy { EdgeSettings(this) }

    override fun onGetLanguage(): Array<String> {
        val locale = (settings.catalog.find(settings.voice) ?: settings.catalog.voices().first()).locale
        return arrayOf(locale.isO3Language, locale.isO3Country, locale.variant)
    }
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val voices = settings.catalog.voices().filter { it.supportsLanguage(lang) }
        if (voices.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        if (country.isNullOrEmpty() || voices.none { it.supportsCountry(country) }) return TextToSpeech.LANG_AVAILABLE
        return TextToSpeech.LANG_COUNTRY_AVAILABLE
    }
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?) = onIsLanguageAvailable(lang, country, variant)
    override fun onGetVoices(): List<Voice> = settings.catalog.voices().map {
        Voice(it.name, it.locale, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
    }
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        settings.catalog.defaultVoice(lang, country, settings.voice)?.name
    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (settings.catalog.find(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(voiceName: String?) = onIsValidVoiceName(voiceName)
    @Suppress("DEPRECATION")
    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): Set<String> =
        if (onIsLanguageAvailable(lang, country, variant) >= TextToSpeech.LANG_AVAILABLE)
            setOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) else emptySet()

    /** All framework callbacks stay on the synthesis thread and finish before this method returns. */
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val session = EdgeSession()
        current.set(session)
        try {
            val text = request.charSequenceText?.toString().orEmpty()
            if (text.isBlank()) {
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
            val voice = if (request.voiceName.isNullOrEmpty())
                settings.catalog.defaultVoice(request.language, request.country, settings.voice)
            else settings.catalog.find(request.voiceName)
            if (voice == null) {
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
            // App preferences provide a multiplier; callers retain their standard Android rate/pitch controls.
            val rate = ((request.speechRate.toLong() * settings.rate) / 100).coerceIn(50, 300).toInt()
            val pitch = ((request.pitch.toLong() * settings.pitch) / 100).coerceIn(50, 150).toInt()
            var started = false
            var pcmFrameBytes = 2
            for (part in EdgeProtocol.splitText(text)) {
                session.checkActive()
                if (part.isBlank()) continue
                val audio = client.synthesize(part, voice, rate, pitch, session)
                session.checkActive()
                val file = File.createTempFile("edge-tts-", ".mp3", cacheDir)
                try {
                    file.writeBytes(audio)
                    Mp3Decoder().decode(file, { session.isStopped }, { sampleRate, channels ->
                        if (sampleRate != 24000 || channels != 1) throw IOException("Unexpected Edge audio format")
                        pcmFrameBytes = channels * 2
                        if (!started) {
                            session.checkActive()
                            if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, channels) != TextToSpeech.SUCCESS) {
                                session.stop(); throw CancellationException("Client rejected audio")
                            }
                            started = true
                        }
                    }, { pcm ->
                        val maxBytes = callback.maxBufferSize
                        if (maxBytes < pcmFrameBytes) throw IOException("Invalid TTS buffer size")
                        val chunkBytes = maxBytes - maxBytes % pcmFrameBytes
                        var offset = 0
                        while (offset < pcm.size) {
                            session.checkActive()
                            val count = minOf(chunkBytes, pcm.size - offset)
                            if (callback.audioAvailable(pcm, offset, count) != TextToSpeech.SUCCESS) {
                                session.stop(); throw CancellationException("Client stopped audio")
                            }
                            offset += count
                        }
                    })
                } finally { file.delete() }
            }
        } catch (_: CancellationException) {
            // Android owns the stop notification; never emit late done/error after stop.
        } catch (error: SocketTimeoutException) {
            if (!session.isStopped) callback.error(TextToSpeech.ERROR_NETWORK_TIMEOUT)
            Log.w("EdgeTtsService", "Online synthesis timed out")
        } catch (error: IOException) {
            if (!session.isStopped) callback.error(TextToSpeech.ERROR_NETWORK)
            Log.w("EdgeTtsService", "Online synthesis failed: ${error.javaClass.simpleName}")
        } catch (error: Exception) {
            if (!session.isStopped) callback.error(TextToSpeech.ERROR_SYNTHESIS)
            Log.w("EdgeTtsService", "Audio synthesis failed: ${error.javaClass.simpleName}")
        } finally {
            // error() sets the status; done() delivers the final error/completion to the caller.
            if (!session.isStopped && !callback.hasFinished()) callback.done()
            current.compareAndSet(session, null)
        }
    }

    override fun onStop() { current.get()?.stop() }
    override fun onDestroy() { onStop(); super.onDestroy() }
}
