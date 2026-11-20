package com.mosstts.edge.protocol

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in: validates the Kotlin implementation against the current Microsoft endpoint. */
class EdgeLiveTest {
    @Test fun listsVoicesAndSynthesizesChineseWithProsody() {
        assumeTrue(System.getenv("EDGE_TTS_LIVE_TEST") == "1")
        val client = EdgeClient()
        val voices = EdgeVoiceCatalog.parse(client.listVoices())
        assertTrue(voices.size > 100)
        val voice = voices.single { it.name == "zh-CN-XiaoxiaoNeural" }
        val audio = client.synthesize("你好，这是安卓语音服务测试。", voice, 125, 110, EdgeSession())
        assertTrue("Empty MP3", audio.size > 1000)
        assertTrue("Missing MP3 sync header", (0 until audio.lastIndex).any {
            audio[it].toInt() and 255 == 255 && audio[it + 1].toInt() and 224 == 224
        })
        println("Microsoft live check: ${voices.size} voices; Chinese MP3 ${audio.size} bytes")
    }
}
