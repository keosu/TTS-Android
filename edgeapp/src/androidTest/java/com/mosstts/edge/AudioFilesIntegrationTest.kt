package com.mosstts.edge

import android.media.MediaPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.mosstts.edge.data.AudioDocuments
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudioFilesIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun sample() = instrumentation.context.assets.open("edge-sample.mp3").use { it.readBytes() }

    @Test fun concatenatedSegmentsArePlayableAsOneCompleteMp3() {
        val file = File.createTempFile("edge-combined-test", ".mp3", context.cacheDir)
        val media = MediaPlayer()
        try {
            val bytes = sample()
            file.writeBytes(bytes + bytes)
            media.setDataSource(file.absolutePath)
            media.prepare()
            assertTrue("Combined MP3 duration only includes the first segment", media.duration > 4500)
        } finally { media.release(); file.delete() }
    }
    @Test fun fileProviderAndDocumentExportPreserveExactMp3Bytes() {
        val parent = File(context.filesDir, "audio_history").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "export-test-").toFile()
        try {
            val bytes = sample()
            val source = File(root, "source.mp3").apply { writeBytes(bytes) }
            val sourceUri = AudioDocuments.shareUri(context, source)
            assertEquals("audio/mpeg", context.contentResolver.getType(sourceUri))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(sourceUri)!!.use { it.readBytes() })
            val target = File(root, "exported.mp3").apply { writeBytes(ByteArray(bytes.size + 100)) }
            AudioDocuments.save(context, source, AudioDocuments.shareUri(context, target))
            assertArrayEquals(bytes, target.readBytes())
        } finally { root.deleteRecursively() }
    }
}
