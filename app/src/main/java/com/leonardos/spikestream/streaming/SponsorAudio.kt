package com.leonardos.spikestream.streaming

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.pedro.encoder.input.audio.CustomAudioEffect
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/** Replace microphone PCM before the existing 48 kHz stereo AAC encoder. */
internal class SponsorAudio(private val samples: ByteArray) : CustomAudioEffect() {
    private var offset = 0
    @Volatile private var clockOffset = 0
    @Volatile private var clockTime = System.nanoTime()
    fun sync(positionMs: Int) {
        clockOffset = positionMs.coerceAtLeast(0) * 192
        clockTime = System.nanoTime()
    }
    override fun process(pcmBuffer: ByteArray): ByteArray {
        val elapsedMs = (System.nanoTime() - clockTime) / 1_000_000
        val expected = (clockOffset + elapsedMs * 192).coerceIn(0, samples.size.toLong()).toInt()
        if (kotlin.math.abs(expected - offset) > 192 * 60) offset = expected - expected % 4
        val count = minOf(samples.size - offset, pcmBuffer.size)
        samples.copyInto(pcmBuffer, 0, offset, offset + count)
        pcmBuffer.fill(0, count)
        offset += count
        return pcmBuffer
    }

    companion object {
        fun decode(extractor: MediaExtractor, track: Int, check: () -> Unit): ByteArray {
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) in 1..2 &&
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) in 8000..96000)
            val decoder = MediaCodec.createDecoderByType("audio/mp4a-latm")
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                val out = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                val batch = ByteArray(8192)
                var used = 0
                var inputEnded = false
                var phase = 0
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val deadline = android.os.SystemClock.elapsedRealtime() + 60000
                while (true) {
                    check()
                    require(android.os.SystemClock.elapsedRealtime() < deadline)
                    if (!inputEnded) {
                        val idx = decoder.dequeueInputBuffer(5000)
                        if (idx >= 0) {
                            val size = extractor.readSampleData(requireNotNull(decoder.getInputBuffer(idx)), 0)
                            inputEnded = size < 0
                            decoder.queueInputBuffer(idx, 0, maxOf(0,size), if (inputEnded) 0 else extractor.sampleTime,
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (!inputEnded) extractor.advance()
                        }
                    }
                    val idx = decoder.dequeueOutputBuffer(info, 5000)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(!f.containsKey("pcm-encoding") || f.getInteger("pcm-encoding") == 2)
                    }
                    if (idx >= 0) {
                        require(rate in 8000..96000 && channels in 1..2)
                        val buf = requireNotNull(decoder.getOutputBuffer(idx)).order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        while (buf.remaining() >= channels * 2) {
                            val left = buf.short.toInt()
                            val right = if (channels == 2) buf.short.toInt() else left
                            phase += 48000
                            while (phase >= rate) {
                                batch[used++] = left.toByte()
                                batch[used++] = (left shr 8).toByte()
                                batch[used++] = right.toByte()
                                batch[used++] = (right shr 8).toByte()
                                if (used == batch.size) {
                                    out.write(batch)
                                    used = 0
                                }
                                phase -= rate
                            }
                        }
                        decoder.releaseOutputBuffer(idx, false)
                        require(out.size() <= 48000 * 4 * 61)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                out.write(batch, 0, used)
                return out.toByteArray().also { require(it.isNotEmpty()) }
            } finally { decoder.release() }
        }
    }
}
