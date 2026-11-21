package com.mosstts.edge.protocol

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EdgeClientTest {
    private val voice = EdgeVoiceCatalog.FALLBACK.first()

    @Test fun downloadsFragmentsAndSendsAuthenticatedHandshakeAndSsml() {
        MockWebServer().use { server ->
            val mp3 = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 1, 2, 3)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (EdgeProtocol.headers(text)["path"] == "ssml") {
                        val headers = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
                        val frame = ByteBuffer.allocate(2 + headers.size + mp3.size)
                            .putShort(headers.size.toShort()).put(headers).put(mp3).array()
                        webSocket.send(frame.toByteString())
                        webSocket.send("Path:turn.end\r\n\r\n")
                    }
                }
            }))
            val audio = EdgeClient(server.url("/readaloud").toString()).synthesize("你好", voice, 100, 100, EdgeSession())
            assertArrayEquals(mp3, audio)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertTrue(request.path!!.startsWith("/readaloud/edge/v1?"))
            assertEquals(EdgeProtocol.TOKEN, request.requestUrl!!.queryParameter("TrustedClientToken"))
            assertTrue(request.requestUrl!!.queryParameter("Sec-MS-GEC")!!.matches(Regex("[A-F0-9]{16}-[A-F0-9]{64}")))
            assertEquals("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold", request.getHeader("Origin"))
        }
    }

    @Test fun retries403OnceWithServerClockAndVoiceListTokenCasing() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setHeader("Date", "Tue, 14 Nov 2023 22:13:20 GMT"))
            server.enqueue(MockResponse().setBody("[]"))
            assertEquals("[]", EdgeClient(server.url("/readaloud").toString()).listVoices())
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals(EdgeProtocol.TOKEN, first.requestUrl!!.queryParameter("trustedclienttoken"))
            assertNotEquals(first.requestUrl!!.queryParameter("Sec-MS-GEC"), second.requestUrl!!.queryParameter("Sec-MS-GEC"))
            assertEquals(EdgeGec.generate(1700000000), second.requestUrl!!.queryParameter("Sec-MS-GEC"))
        }
    }

    @Test fun stopInterruptsSocketWaitPromptly() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val session = EdgeSession()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val task = executor.submit<Boolean> {
                    try {
                        EdgeClient(server.url("/readaloud").toString()).synthesize("等待", voice, 100, 100, session)
                        false
                    } catch (_: CancellationException) { true }
                }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                session.stop()
                assertTrue(task.get(2, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun idleConnectionFailsInsteadOfBlockingSystemQueueForever() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            assertThrows(java.net.SocketTimeoutException::class.java) {
                EdgeClient(server.url("/readaloud").toString(), idleTimeoutMillis = 200)
                    .synthesize("等待", voice, 100, 100, EdgeSession())
            }
        }
    }

    @Test fun turnEndWithoutAudioFails() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (EdgeProtocol.headers(text)["path"] == "ssml") {
                        webSocket.send("Path:turn.end\r\n\r\n")
                        webSocket.close(1000, "No audio")
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }))
            assertThrows(IOException::class.java) {
                EdgeClient(server.url("/readaloud").toString()).synthesize("你好", voice, 100, 100, EdgeSession())
            }
        }
    }

    @Test fun cachedVoicesSurviveRestartAndFailedRefresh() {
        MockWebServer().use { server ->
            val cache = File.createTempFile("edge-voices-test", ".json")
            try {
                server.enqueue(MockResponse().setBody("""[{"ShortName":"fr-FR-DeniseNeural","Locale":"fr-FR","Gender":"Female"}]"""))
                server.enqueue(MockResponse().setBody("[]"))
                val catalog = EdgeVoiceCatalog(cache)
                val client = EdgeClient(server.url("/readaloud").toString())
                catalog.refresh(client)
                assertNotNull(EdgeVoiceCatalog(cache).find("fr-FR-DeniseNeural"))
                assertThrows(IllegalArgumentException::class.java) { catalog.refresh(client) }
                assertNotNull(catalog.find("fr-FR-DeniseNeural"))
                assertNotNull(EdgeVoiceCatalog(cache).find("fr-FR-DeniseNeural"))
            } finally { cache.delete() }
        }
    }
}
