package com.mosstts.edge.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class AudioRecord(val id: String, val text: String, val voice: String, val voiceLabel: String,
                       val locale: String, val rate: Int, val pitch: Int, val createdAt: Long,
                       val durationMs: Long, val sizeBytes: Long, val hasSubtitles: Boolean) {
    val title: String get() = text.replace('\n', ' ').replace('\r', ' ').trim().take(48)
    fun exportName(extension: String): String {
        val date = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(java.util.Date(createdAt))
        return "EdgeTTS_${voice.removeSuffix("Neural")}_$date.$extension"
    }
}

/** Only generated history files are owned here; exported copies belong to the user. */
class AudioHistoryStore(private val directory: File, private val maxRecords: Int = 20,
                        private val maxBytes: Long = 100L * 1024 * 1024) {
    private val index = File(directory, "history.json")
    private var entries: List<AudioRecord>
    init {
        require(maxRecords > 0 && maxBytes > 0)
        directory.mkdirs()
        entries = runCatching { readIndex() }.getOrDefault(emptyList())
        // An interrupted generation is never presented as a complete recording.
        directory.listFiles()?.filter { it.name.startsWith(".pending-") }?.forEach { it.delete() }
    }
    @Synchronized fun all(): List<AudioRecord> = entries.filter { file(it, "mp3").isFile }
    fun createPending(): File = File.createTempFile(".pending-", ".mp3", directory)
    fun file(record: AudioRecord, extension: String): File {
        require(record.id.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")))
        require(extension in listOf("mp3", "srt", "vtt"))
        return File(directory, "${record.id}.$extension")
    }
    @Synchronized fun publish(pending: File, text: String, voice: String, label: String, locale: String,
                              rate: Int, pitch: Int, srt: String, vtt: String): AudioRecord {
        require(pending.canonicalFile.parentFile == directory.canonicalFile && pending.name.startsWith(".pending-"))
        require(pending.isFile && pending.length() > 0)
        if (pending.length() > maxBytes) throw IOException("Audio exceeds history storage limit")
        val record = AudioRecord(UUID.randomUUID().toString(), text, voice, label, locale, rate, pitch,
            System.currentTimeMillis(), pending.length() * 1000 / 6000, pending.length(), srt.isNotBlank())
        Files.move(pending.toPath(), file(record, "mp3").toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            if (record.hasSubtitles) {
                file(record, "srt").writeText(srt)
                file(record, "vtt").writeText(vtt)
            }
            val retained = (listOf(record) + all()).distinctBy { it.id }.toMutableList()
            val removed = mutableListOf<AudioRecord>()
            while (retained.size > maxRecords || retained.sumOf { it.sizeBytes } > maxBytes) removed.add(retained.removeAt(retained.lastIndex))
            saveIndex(retained)
            entries = retained
            removed.forEach(::deleteFiles)
            return record
        } catch (error: Exception) { deleteFiles(record); throw error }
    }
    @Synchronized fun delete(id: String) {
        val removed = entries.filter { it.id == id }
        val retained = entries.filter { it.id != id }
        saveIndex(retained)
        entries = retained
        removed.forEach(::deleteFiles)
    }
    @Synchronized fun clear() {
        val removed = entries
        saveIndex(emptyList())
        entries = emptyList()
        removed.forEach(::deleteFiles)
    }
    private fun deleteFiles(record: AudioRecord) { listOf("mp3", "srt", "vtt").forEach { file(record, it).delete() } }
    private fun readIndex(): List<AudioRecord> {
        if (!index.isFile) return emptyList()
        val array = JSONArray(index.readText())
        return (0 until array.length()).mapNotNull { i -> runCatching {
            val value = array.getJSONObject(i)
            AudioRecord(value.getString("id"), value.getString("text"), value.getString("voice"),
                value.getString("voiceLabel"), value.getString("locale"), value.getInt("rate"), value.getInt("pitch"),
                value.getLong("createdAt"), value.getLong("durationMs"), value.getLong("sizeBytes"), value.optBoolean("hasSubtitles"))
                .takeIf { file(it, "mp3").isFile }
        }.getOrNull() }
    }
    private fun saveIndex(records: List<AudioRecord>) {
        val array = JSONArray()
        records.forEach { record -> array.put(JSONObject().apply {
            put("id", record.id); put("text", record.text); put("voice", record.voice); put("voiceLabel", record.voiceLabel)
            put("locale", record.locale); put("rate", record.rate); put("pitch", record.pitch); put("createdAt", record.createdAt)
            put("durationMs", record.durationMs); put("sizeBytes", record.sizeBytes); put("hasSubtitles", record.hasSubtitles)
        }) }
        val pending = Files.createTempFile(directory.toPath(), ".index-", ".json")
        try {
            Files.write(pending, array.toString().toByteArray(Charsets.UTF_8))
            Files.move(pending, index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(pending) }
    }
}
