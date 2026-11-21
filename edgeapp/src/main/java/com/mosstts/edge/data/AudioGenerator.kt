package com.mosstts.edge.data

import com.mosstts.edge.protocol.*
import java.io.IOException

class AudioGenerator(private val store: AudioHistoryStore, private val client: EdgeClient = EdgeClient()) {
    fun generate(text: String, voice: EdgeVoice, rate: Int, pitch: Int, session: EdgeSession,
                 progress: (Int, Int) -> Unit): AudioRecord {
        require(text.isNotBlank() && text.length <= 20000)
        val parts = EdgeProtocol.splitText(text, 4000)
        val pending = store.createPending()
        val captions = mutableListOf<EdgeCaption>()
        var bytes = 0L
        try {
            pending.outputStream().buffered().use { output ->
                parts.forEachIndexed { index, part ->
                    session.checkActive()
                    val result = client.synthesizeDetailed(part, voice, rate, pitch, session, sentenceBoundaries = true)
                    session.checkActive()
                    val offsetMs = bytes * 1000 / 6000 // The selected Edge MP3 format is 48 kbit/s CBR.
                    captions.addAll(result.captions.map { it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs) })
                    bytes += result.audio.size
                    if (bytes > 64L * 1024 * 1024) throw IOException("Generated audio exceeds 64 MB")
                    output.write(result.audio)
                    progress(index + 1, parts.size)
                }
            }
            session.checkActive()
            return store.publish(pending, text, voice.name, voice.displayName, voice.locale.toLanguageTag(), rate, pitch,
                SubtitleWriter.render(captions, false), SubtitleWriter.render(captions, true))
        } finally { pending.delete() }
    }
}
