package com.beatsense.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

const val SAMPLE_RATE = 44100

/** Reads the microphone on a background thread and hands raw PCM chunks to [onChunk]. */
class MicListener(private val context: Context, private val onChunk: (ShortArray, Int) -> Unit) {
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Caller must hold RECORD_AUDIO. Returns false if the microphone could not be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        // Voice processing (noise suppression, auto gain) flattens drums; prefer the raw mic when the phone offers it.
        val audio = context.getSystemService(AudioManager::class.java)
        val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        running = true
        rec.startRecording()
        worker = thread(name = "mic", priority = Thread.MAX_PRIORITY) {
            val buf = ShortArray(2048)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) onChunk(buf, n) else if (n < 0) break
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
        return true
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }
}

/** A sample-accurate click track, used to check a detected tempo against the song by ear. */
class Metronome {
    @Volatile private var running = false
    @Volatile private var bpm = 120.0
    private var worker: Thread? = null

    /** Called on every click (from the audio thread) so the UI can flash in time. */
    @Volatile var onTick: ((accent: Boolean) -> Unit)? = null

    private val click = makeClick(1800.0)
    private val accentClick = makeClick(2600.0)

    private fun makeClick(freq: Double) = ShortArray((0.03 * SAMPLE_RATE).toInt()) {
        val t = it.toDouble() / SAMPLE_RATE
        (sin(2 * PI * freq * t) * exp(-t * 160) * 22000).toInt().toShort()
    }

    fun setBpm(value: Double) {
        bpm = value.coerceIn(30.0, 300.0)
    }

    fun start(value: Double) {
        setBpm(value)
        if (running) return
        running = true
        worker = thread(name = "metronome", priority = Thread.MAX_PRIORITY) {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val silence = ShortArray(1024)
            var beat = 0
            var carry = 0.0
            try {
                while (running) {
                    val accent = beat % 4 == 0
                    onTick?.invoke(accent)
                    val c = if (accent) accentClick else click
                    track.write(c, 0, c.size)
                    // Keep fractional samples so the tempo doesn't drift over a long song.
                    val exact = 60.0 / bpm * SAMPLE_RATE - c.size + carry
                    var gap = exact.toInt()
                    carry = exact - gap
                    while (gap > 0 && running) {
                        val n = minOf(gap, silence.size)
                        track.write(silence, 0, n)
                        gap -= n
                    }
                    beat++
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(300)
        worker = null
    }
}

/** Tap along with the song; the average of the recent intervals gives the tempo. */
class TapTempo {
    private val taps = ArrayDeque<Long>()

    /** Registers a tap at [nowMs]; returns the tempo once there are at least 3 taps. */
    fun tap(nowMs: Long): Double? {
        if (taps.isNotEmpty() && nowMs - taps.last() > 2500) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > 9) taps.removeFirst()
        if (taps.size < 3) return null
        val intervals = taps.zipWithNext { a, b -> (b - a).toDouble() }
        return 60000.0 / intervals.average()
    }

    val count: Int get() = taps.size

    fun reset() = taps.clear()
}
