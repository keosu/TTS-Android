package com.mosstts.edge.protocol

data class EdgeCaption(val startMs: Long, val endMs: Long, val text: String)
data class EdgeAudio(val audio: ByteArray, val captions: List<EdgeCaption>)

object SubtitleWriter {
    private fun timestamp(ms: Long, separator: Char): String {
        val value = ms.coerceAtLeast(0)
        return java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d:%02d%c%03d",
            value / 3600000, value / 60000 % 60, value / 1000 % 60, separator, value % 1000)
    }
    fun render(captions: List<EdgeCaption>, webVtt: Boolean): String = buildString {
        if (webVtt) append("WEBVTT\n\n")
        captions.forEachIndexed { index, caption ->
            val separator = if (webVtt) '.' else ','
            append("${index + 1}\n${timestamp(caption.startMs, separator)} --> ${timestamp(caption.endMs, separator)}\n")
            // VTT markup must not turn ordinary synthesis text into tags.
            val text = caption.text.replace("\r", " ").replace("\n", " ")
            append(if (webVtt) text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") else text)
            append("\n\n")
        }
    }
}
