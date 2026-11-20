package com.mosstts.edge.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Decode to PCM for SynthesisCallback; Android's TTS API cannot accept MP3 bytes. */
class Mp3Decoder {
    fun decode(file: File, isStopped: () -> Boolean,
               onFormat: (Int, Int) -> Unit, onPcm: (ByteArray) -> Unit): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("MP3 contains no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            codecStarted = true
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var formatDelivered = false
            var bytesDelivered = 0L
            var lastProgress = System.nanoTime()
            fun deliverFormat() {
                val output = decoder.outputFormat
                if (output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                    throw IOException("Decoder did not produce PCM16")
                }
                onFormat(output.getInteger(MediaFormat.KEY_SAMPLE_RATE), output.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                formatDelivered = true
            }
            while (true) {
                if (isStopped()) throw CancellationException("Speech stopped")
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: throw IOException("Missing decoder input")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) deliverFormat()
                else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (!formatDelivered) deliverFormat()
                            val output = decoder.getOutputBuffer(index) ?: throw IOException("Missing decoder output")
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            output.get(bytes)
                            onPcm(bytes)
                            bytesDelivered += bytes.size
                        }
                    } finally { decoder.releaseOutputBuffer(index, false) }
                    lastProgress = System.nanoTime()
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastProgress) > 15) {
                    throw IOException("MP3 decoder timed out")
                }
            }
            if (bytesDelivered == 0L) throw IOException("MP3 decoder produced no PCM")
            return bytesDelivered
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
