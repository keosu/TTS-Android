package com.mosstts.edge.protocol

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Wire format documented by the LGPLv3 edge-tts project; no Python runtime is required. */
object EdgeProtocol {
    const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val VERSION = "2-143.0.3650.75"
    const val BASE_URL = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    private val timestampFormat = DateTimeFormatter.ofPattern(
        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US,
    ).withZone(ZoneOffset.UTC)

    fun id(): String = UUID.randomUUID().toString().replace("-", "")
    fun timestamp(): String = timestampFormat.format(Instant.now())

    fun config(): String = "X-Timestamp:${timestamp()}\r\n" +
        "Content-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
        "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
        "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}\r\n"

    fun ssml(text: String, voice: String, language: String, rate: Int, pitch: Int): String {
        val rateValue = (rate.coerceIn(50, 300) - 100).let { if (it >= 0) "+$it%" else "$it%" }
        // Android supplies pitch as a percentage; Edge interprets it as relative pitch.
        val pitchValue = (pitch.coerceIn(50, 150) - 100).let { if (it >= 0) "+$it%" else "$it%" }
        return "X-RequestId:${id()}\r\nContent-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp()}Z\r\nPath:ssml\r\n\r\n" +
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${escape(language)}'>" +
            "<voice name='${escape(voice)}'><prosody rate='$rateValue' pitch='$pitchValue' volume='+0%'>" +
            "${escape(text)}</prosody></voice></speak>"
    }

    fun escape(text: String): String = buildString {
        for (char in text) append(when (char) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '\'' -> "&apos;"
            '"' -> "&quot;"
            else -> if (char.code in 0..8 || char.code in 11..12 || char.code in 14..31 ||
                char == '\uFFFE' || char == '\uFFFF') " " else char.toString()
        })
    }

    /** Split before XML escaping, counting escaped UTF-8 bytes and preserving surrogate pairs. */
    fun splitText(text: String, maxBytes: Int = 1500): List<String> {
        require(maxBytes >= 10)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            var bytes = 0
            var boundary = start
            while (end < text.length) {
                val next = end + Character.charCount(text.codePointAt(end))
                val cost = escape(text.substring(end, next)).toByteArray(Charsets.UTF_8).size
                if (bytes + cost > maxBytes) break
                bytes += cost
                end = next
                if (text[end - 1] in "。！？.!?;；\n ") boundary = end
            }
            if (end < text.length && boundary > start) end = boundary
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }

    fun headers(text: String): Map<String, String> = text.substringBefore("\r\n\r\n")
        .split("\r\n").mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon < 0) null else line.substring(0, colon).lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }.toMap()

    fun audioPayload(frame: ByteArray): ByteArray {
        require(frame.size >= 2) { "Missing binary frame header" }
        val headerLength = ((frame[0].toInt() and 255) shl 8) or (frame[1].toInt() and 255)
        require(headerLength <= frame.size - 2) { "Invalid binary frame header length" }
        val fields = headers(String(frame, 2, headerLength, Charsets.UTF_8))
        require(fields["path"] == "audio") { "Unexpected binary frame path" }
        val payload = frame.copyOfRange(2 + headerLength, frame.size)
        require(fields["content-type"] == "audio/mpeg" ||
            (fields["content-type"] == null && payload.isEmpty())) { "Unexpected audio content type" }
        return payload
    }
}
