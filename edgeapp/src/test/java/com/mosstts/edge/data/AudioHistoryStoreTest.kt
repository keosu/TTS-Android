package com.mosstts.edge.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun add(store: AudioHistoryStore, text: String, bytes: Int = 6000): AudioRecord {
        val pending = store.createPending().apply { writeBytes(ByteArray(bytes) { 42 }) }
        return store.publish(pending, text, "zh-CN-XiaoxiaoNeural", "晓晓", "zh-CN", 125, 100,
            "1\n00:00:00,000 --> 00:00:01,000\n$text\n", "WEBVTT\n")
    }
    @Test fun recordingsAndCaptionsSurviveRestartAndDeleteTogether() {
        val root = temporary.newFolder()
        val store = AudioHistoryStore(root)
        val record = add(store, "带有中文和换行\n的完整文本")
        val restored = AudioHistoryStore(root)
        assertEquals(record, restored.all().single())
        assertEquals(125, restored.all().single().rate)
        assertEquals(1000, record.durationMs)
        assertTrue(restored.file(record, "srt").readText().contains("中文"))
        restored.delete(record.id)
        assertTrue(restored.all().isEmpty())
        assertFalse(restored.file(record, "mp3").exists())
        assertFalse(restored.file(record, "srt").exists())
        assertTrue(AudioHistoryStore(root).all().isEmpty())
    }
    @Test fun retentionRemovesOldAudioButNeverExportedCopies() {
        val store = AudioHistoryStore(temporary.newFolder(), maxRecords = 2)
        val first = add(store, "第一条")
        val exported = temporary.newFile("exported.mp3")
        store.file(first, "mp3").copyTo(exported, overwrite = true)
        val second = add(store, "第二条")
        val third = add(store, "第三条")
        assertEquals(listOf(third.id, second.id), store.all().map { it.id })
        assertFalse(store.file(first, "mp3").exists())
        assertTrue(exported.length() > 0)
        store.clear()
        assertFalse(store.file(third, "vtt").exists())
        assertTrue(exported.length() > 0)
    }
    @Test fun storageBudgetRetainsNewestFilesAndInterruptedAudioIsNotHistory() {
        val root = temporary.newFolder()
        val store = AudioHistoryStore(root, maxBytes = 10000)
        val first = add(store, "第一条")
        val second = add(store, "第二条")
        assertEquals(second.id, store.all().single().id)
        assertFalse(store.file(first, "mp3").exists())
        val unfinished = store.createPending().apply { writeBytes(ByteArray(200)) }
        assertEquals(second.id, AudioHistoryStore(root).all().single().id)
        assertFalse(unfinished.exists())
    }
    @Test fun invalidPathsCannotPublishOrReadOutsideHistoryDirectory() {
        val store = AudioHistoryStore(temporary.newFolder())
        val outside = temporary.newFile("outside.mp3").apply { writeBytes(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            store.publish(outside, "文本", "voice", "name", "zh", 100, 100, "", "")
        }
        assertTrue(outside.exists())
        val record = add(store, "正常记录")
        assertThrows(IllegalArgumentException::class.java) { store.file(record.copy(id = "../outside"), "mp3") }
    }
}
