package com.mosstts.edge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

data class Playback(val id: String? = null, val preparing: Boolean = false, val playing: Boolean = false,
                    val positionMs: Long = 0, val durationMs: Long = 0)

/** Main-thread player shared by generated recordings, history, and voice previews. */
class StudioPlayer(context: Context, private val changed: (Playback) -> Unit, private val failed: () -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var state = Playback()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
        }, handler).build()
    private val ticker = object : Runnable {
        override fun run() {
            runCatching { player?.let { emit(state.copy(positionMs = it.currentPosition.toLong())) } }
            if (state.playing) handler.postDelayed(this, 200)
        }
    }
    private fun emit(value: Playback) { state = value; changed(value) }
    fun play(id: String, file: File) {
        if (state.id == id && player != null && !state.preparing) { toggle(); return }
        stop()
        emit(Playback(id, preparing = true))
        try {
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(attributes)
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                emit(state.copy(preparing = false, durationMs = it.duration.toLong()))
                resume()
            }
            media.setOnCompletionListener {
                if (player !== it) return@setOnCompletionListener
                handler.removeCallbacks(ticker)
                emit(state.copy(playing = false, positionMs = state.durationMs))
                audioManager.abandonAudioFocusRequest(focus)
            }
            media.setOnErrorListener { source, _, _ -> if (player === source) { stop(); failed() }; true }
            media.prepareAsync()
        } catch (_: Exception) { stop(); failed() }
    }
    fun toggle() { if (state.playing) pause() else resume() }
    private fun resume() {
        val media = player ?: return
        if (state.preparing) return
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { failed(); return }
        try {
            if (state.positionMs >= state.durationMs) media.seekTo(0)
            media.start()
            emit(state.copy(playing = true))
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } catch (_: Exception) { stop(); failed() }
    }
    fun pause() {
        if (!state.playing) return
        runCatching { player?.pause() }
        handler.removeCallbacks(ticker)
        emit(state.copy(playing = false))
        audioManager.abandonAudioFocusRequest(focus)
    }
    fun seek(position: Long) {
        if (state.preparing) return
        val value = position.coerceIn(0, state.durationMs)
        runCatching { player?.seekTo(value.toInt()); emit(state.copy(positionMs = value)) }
    }
    fun stop() {
        handler.removeCallbacks(ticker)
        runCatching { player?.release() }
        player = null
        audioManager.abandonAudioFocusRequest(focus)
        emit(Playback())
    }
}
