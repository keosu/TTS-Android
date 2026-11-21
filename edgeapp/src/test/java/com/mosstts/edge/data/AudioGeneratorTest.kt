package com.mosstts.edge.data

import com.mosstts.edge.protocol.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

class AudioGeneratorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun response() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (EdgeProtocol.headers(text)["path"] != "ssml") return
            val header = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
            webSocket.send(ByteBuffer.allocate(2 + header.size + 6000).putShort(header.size.toShort())
                .put(header).put(ByteArray(6000) { 42 }).array().toByteString())
            webSocket.send("Path:audio.metadata\r\n\r\n" +
                """{"Metadata":[{"Type":"SentenceBoundary","Data":{"Offset":0,"Duration":10000000,"text":{"Text":"示例字幕"}}}]}""")
            webSocket.send("Path:turn.end\r\n\r\n")
            webSocket.close(1000, "Completed")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    })
    @Test fun longTextProducesOneRecordingWithContinuousSubtitleOffsets() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(response()) }
            val store = AudioHistoryStore(temporary.newFolder())
            val text = "字".repeat(1500)
            val progress = mutableListOf<Int>()
            val record = AudioGenerator(store, EdgeClient(server.url("/readaloud").toString()))
                .generate(text, EdgeVoiceCatalog.FALLBACK.first(), 100, 100, EdgeSession()) { done, total ->
                    assertEquals(2, total); progress.add(done)
                }
            assertEquals(listOf(1, 2), progress)
            assertEquals(12000, store.file(record, "mp3").length())
            assertEquals(text, store.all().single().text)
            assertTrue(store.file(record, "srt").readText().contains("00:00:01,000 --> 00:00:02,000"))
            assertTrue(store.file(record, "vtt").readText().startsWith("WEBVTT"))
        }
    }
    @Test fun cancellationBetweenSegmentsNeverPublishesPartialAudio() {
        MockWebServer().use { server ->
            server.enqueue(response())
            val root = temporary.newFolder()
            val store = AudioHistoryStore(root)
            val session = EdgeSession()
            assertThrows(CancellationException::class.java) {
                AudioGenerator(store, EdgeClient(server.url("/readaloud").toString()))
                    .generate("字".repeat(1500), EdgeVoiceCatalog.FALLBACK.first(), 100, 100, session) { _, _ -> session.stop() }
            }
            assertTrue(store.all().isEmpty())
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".mp3") })
        }
    }
    @Test fun subtitleExportPreservesTimingsAndEscapesVttMarkup() {
        val caption = EdgeCaption(3723004, 3725006, "<文字>&\n第二行")
        val srt = SubtitleWriter.render(listOf(caption), false)
        assertTrue(srt.contains("01:02:03,004 --> 01:02:05,006"))
        assertTrue(srt.contains("<文字>& 第二行"))
        assertTrue(SubtitleWriter.render(listOf(caption), true).contains("&lt;文字&gt;&amp; 第二行"))
    }
}
