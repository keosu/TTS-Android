package com.mosstts.edge.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class EdgeProtocolTest {
    @Test fun v2TokenMatchesIndependentPythonReferenceVectors() {
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6", EdgeGec.generate(0))
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2", EdgeGec.generate(1700000000))
        assertEquals("99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654", EdgeGec.generate(1800000000))
        assertEquals(EdgeGec.generate(1800000000), EdgeGec.generate(1800000299))
        assertNotEquals(EdgeGec.generate(1800000000), EdgeGec.generate(1800000300))
    }

    @Test fun ssmlEscapesInjectionAndMapsAndroidProsody() {
        val ssml = EdgeProtocol.ssml("<voice>&\u000B'\"", "zh-CN-XiaoxiaoNeural", "zh-CN", 150, 80)
        assertTrue(ssml.contains("&lt;voice&gt;&amp; &apos;&quot;"))
        assertTrue(ssml.contains("rate='+50%' pitch='-20%' volume='+0%'"))
        assertTrue(ssml.contains("X-Timestamp:"))
        assertTrue(ssml.contains("Path:ssml\r\n\r\n"))
    }

    @Test fun splittingPreservesAllTextAndBoundsEscapedUtf8IncludingEmoji() {
        val text = "中文😀&<>\"'。 long words\n".repeat(400)
        val parts = EdgeProtocol.splitText(text, 60)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.size > 1)
        for (part in parts) {
            assertTrue(EdgeProtocol.escape(part).toByteArray(Charsets.UTF_8).size <= 60)
            assertFalse(Character.isLowSurrogate(part.first()))
            assertFalse(Character.isHighSurrogate(part.last()))
        }
    }

    @Test fun binaryFramingKeepsEveryMp3Byte() {
        val header = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
        val payload = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0, 1, 2)
        val frame = ByteBuffer.allocate(2 + header.size + payload.size).putShort(header.size.toShort()).put(header).put(payload).array()
        assertArrayEquals(payload, EdgeProtocol.audioPayload(frame))
        val endHeader = "Path:audio\r\n".toByteArray()
        assertTrue(EdgeProtocol.audioPayload(ByteBuffer.allocate(2 + endHeader.size)
            .putShort(endHeader.size.toShort()).put(endHeader).array()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedBinaryHeaders() {
        EdgeProtocol.audioPayload(byteArrayOf(0, 100, 1))
    }

    @Test fun fallbackChineseVoicesAcceptAndroidIsoCodesAndRegionalDefaults() {
        val catalog = EdgeVoiceCatalog(File("does-not-exist.json"))
        for (lang in listOf("zh", "zho", "chi", "ZHO")) {
            assertEquals("zh-CN-YunxiNeural", catalog.defaultVoice(lang, "CHN", "zh-CN-YunxiNeural")!!.name)
        }
        assertEquals("zh-HK-HiuMaanNeural", catalog.defaultVoice("zho", "HKG", null)!!.name)
        assertNull(catalog.defaultVoice("not-a-language", "", null))
        assertTrue(catalog.find("zh-TW-HsiaoChenNeural")!!.supportsCountry("TWN"))
    }

    @Test fun malformedOrEmptyCatalogDoesNotReplaceUsableVoices() {
        assertThrows(IllegalArgumentException::class.java) { EdgeVoiceCatalog.parse("[]") }
        val voices = EdgeVoiceCatalog.parse("""[{"ShortName":"fr-FR-DeniseNeural","Locale":"fr-FR","Gender":"Female","FriendlyName":"Denise"}]""")
        assertTrue(voices.single().supportsLanguage("fra"))
        assertTrue(voices.single().supportsCountry("FRA"))
    }
}
