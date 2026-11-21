package com.mosstts.edge.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class EdgeHttpException(val status: Int, val serverDate: String?) : IOException("Edge HTTP $status")

/** One utterance owns its sockets; stopping it cannot cancel another caller's request. */
class EdgeSession {
    private val stopped = AtomicBoolean(false)
    private val socket = AtomicReference<WebSocket?>()
    val isStopped: Boolean get() = stopped.get()
    fun checkActive() { if (stopped.get()) throw CancellationException("Speech stopped") }
    fun attach(value: WebSocket) {
        socket.set(value)
        if (stopped.get()) value.cancel()
    }
    fun detach(value: WebSocket) { socket.compareAndSet(value, null) }
    fun stop() { stopped.set(true); socket.getAndSet(null)?.cancel() }
}

/** Blocking API intended for Android's synthesis thread, never the UI thread. */
class EdgeClient(
    private val baseUrl: String = EdgeProtocol.BASE_URL,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build(),
    private val idleTimeoutMillis: Long = 60000,
    private val totalTimeoutMillis: Long = 180000,
) {
    @Volatile private var clockOffsetMillis = 0L

    private fun authenticatedUrl(path: String, websocket: Boolean = false): String {
        val token = EdgeGec.generate((System.currentTimeMillis() + clockOffsetMillis) / 1000)
        val base = if (websocket) baseUrl.replaceFirst("https:", "wss:").replaceFirst("http:", "ws:") else baseUrl
        val tokenParameter = if (websocket) "TrustedClientToken" else "trustedclienttoken"
        return "$base/$path?$tokenParameter=${EdgeProtocol.TOKEN}&Sec-MS-GEC=$token" +
            "&Sec-MS-GEC-Version=${EdgeProtocol.VERSION}" + if (websocket) "&ConnectionId=${EdgeProtocol.id()}" else ""
    }

    private fun request(url: String, websocket: Boolean): Request {
        val builder = Request.Builder().url(url)
            .header("User-Agent", EdgeProtocol.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${EdgeProtocol.id().uppercase()};")
        if (websocket) builder.header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
        else builder.header("Accept", "*/*").header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty")
            .header("Sec-CH-UA", "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"143\", \"Chromium\";v=\"143\"")
            .header("Sec-CH-UA-Mobile", "?0")
        return builder.build()
    }

    private fun <T> withClockRetry(block: () -> T): T {
        try { return block() } catch (error: EdgeHttpException) {
            if (error.status != 403 || error.serverDate == null) throw error
            val time = runCatching {
                ZonedDateTime.parse(error.serverDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull() ?: throw error
            clockOffsetMillis = time - System.currentTimeMillis()
            return block()
        }
    }

    fun listVoices(): String = withClockRetry {
        http.newCall(request(authenticatedUrl("voices/list"), false)).execute().use { response ->
            if (!response.isSuccessful) throw EdgeHttpException(response.code, response.header("Date"))
            response.body?.string() ?: throw IOException("Empty voice list")
        }
    }

    fun synthesize(text: String, voice: EdgeVoice, rate: Int, pitch: Int, session: EdgeSession): ByteArray =
        synthesizeDetailed(text, voice, rate, pitch, session).audio

    fun synthesizeDetailed(text: String, voice: EdgeVoice, rate: Int, pitch: Int, session: EdgeSession,
                           sentenceBoundaries: Boolean = false): EdgeAudio =
        withClockRetry { download(text, voice, rate, pitch, session, sentenceBoundaries) }

    private fun download(text: String, voice: EdgeVoice, rate: Int, pitch: Int, session: EdgeSession,
                         sentenceBoundaries: Boolean): EdgeAudio {
        session.checkActive()
        val result = CompletableFuture<EdgeAudio>()
        val audio = ByteArrayOutputStream()
        val captions = mutableListOf<EdgeCaption>()
        val lastActivity = AtomicLong(System.nanoTime())
        val started = System.nanoTime()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(EdgeProtocol.config(sentenceBoundaries)) ||
                    !webSocket.send(EdgeProtocol.ssml(text, voice.name, voice.locale.toLanguageTag(), rate, pitch))) {
                    result.completeExceptionally(IOException("Unable to send synthesis request"))
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastActivity.set(System.nanoTime())
                try {
                    val payload = EdgeProtocol.audioPayload(bytes.toByteArray())
                    if (audio.size() + payload.size > 4 * 1024 * 1024) throw IOException("Audio response exceeds limit")
                    audio.write(payload)
                } catch (error: Exception) { result.completeExceptionally(error); webSocket.cancel() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                lastActivity.set(System.nanoTime())
                when (EdgeProtocol.headers(text)["path"]) {
                    "turn.end" -> {
                        if (audio.size() == 0) result.completeExceptionally(IOException("Edge returned no audio"))
                        else result.complete(EdgeAudio(audio.toByteArray(), captions.toList()))
                        webSocket.close(1000, "Completed")
                    }
                    "audio.metadata" -> if (sentenceBoundaries) {
                        runCatching {
                            val metadata = JSONObject(text.substringAfter("\r\n\r\n")).getJSONArray("Metadata")
                            for (i in 0 until metadata.length()) {
                                val item = metadata.getJSONObject(i)
                                if (item.optString("Type") == "SentenceBoundary") {
                                    val data = item.getJSONObject("Data")
                                    captions.add(EdgeCaption(data.getLong("Offset") / 10000,
                                        (data.getLong("Offset") + data.getLong("Duration")) / 10000,
                                        data.getJSONObject("text").getString("Text")))
                                }
                            }
                        }
                    }
                    "response", "turn.start" -> Unit
                    else -> { result.completeExceptionally(IOException("Unexpected Edge response")); webSocket.cancel() }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.completeExceptionally(if (response != null && response.code != 101)
                    EdgeHttpException(response.code, response.header("Date")) else IOException("Edge connection failed", t))
                response?.close()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isDone) result.completeExceptionally(IOException("Edge closed before turn.end"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (!result.isDone) result.completeExceptionally(IOException("Edge closed before turn.end"))
            }
        }
        val socket = http.newWebSocket(request(authenticatedUrl("edge/v1", true), true), listener)
        session.attach(socket)
        try {
            while (true) {
                session.checkActive()
                try { return result.get(100, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) {
                    val now = System.nanoTime()
                    if (TimeUnit.NANOSECONDS.toMillis(now - lastActivity.get()) > idleTimeoutMillis ||
                        TimeUnit.NANOSECONDS.toMillis(now - started) > totalTimeoutMillis) {
                        throw java.net.SocketTimeoutException("Edge synthesis timed out")
                    }
                } catch (error: ExecutionException) {
                    session.checkActive()
                    throw error.cause ?: error
                }
            }
        } finally {
            session.detach(socket)
            socket.cancel()
        }
    }
}
