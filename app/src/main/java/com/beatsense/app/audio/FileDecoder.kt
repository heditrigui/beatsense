package com.beatsense.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes an audio file chunk by chunk with the phone's own codecs (MP3, AAC, FLAC, Ogg, WAV…)
 * and hands mono 16-bit PCM to [onPcm] as it goes. Nothing is kept: each chunk is analysed and
 * dropped, so even an hour-long mix needs only a little memory.
 *
 * [onStart] gets the sample rate and duration (seconds, or null if unknown) before the first
 * chunk. Returns false if the file has no audio track the phone can decode.
 */
fun decodeAudio(
    context: Context,
    uri: Uri,
    cancelled: () -> Boolean,
    onStart: (rate: Int, durationSec: Double?) -> Unit,
    onPcm: (ShortArray, Int) -> Unit,
): Boolean {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return false
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1e6 else null
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        var started = false
        var mono = ShortArray(0)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (!cancelled()) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (o >= 0 && !started)) {
                val out = codec.outputFormat
                channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                float = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                if (!started) {
                    onStart(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), duration)
                    started = true
                }
            }
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                // Mix the channels down to mono 16-bit, the format the detectors take.
                val frames = info.size / (channels * if (float) 4 else 2)
                if (mono.size < frames) mono = ShortArray(frames)
                if (float) {
                    val f = buf.asFloatBuffer()
                    for (i in 0 until frames) {
                        var s = 0f
                        for (c in 0 until channels) s += f.get()
                        mono[i] = (s / channels * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                    }
                } else {
                    val sh = buf.asShortBuffer()
                    for (i in 0 until frames) {
                        var s = 0
                        for (c in 0 until channels) s += sh.get()
                        mono[i] = (s / channels).toShort()
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if (frames > 0) onPcm(mono, frames)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return started
    } catch (e: Exception) {
        return false
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        extractor.release()
    }
}
