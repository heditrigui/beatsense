package com.hedi.beatsense

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hedi.beatsense.audio.MicListener
import com.hedi.beatsense.audio.Metronome
import com.hedi.beatsense.audio.SAMPLE_RATE
import com.hedi.beatsense.audio.TapTempo
import com.hedi.beatsense.dsp.TempoDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class TempoRange(val label: String, val low: Double, val high: Double) {
    SLOW("Slow 60–120", 60.0, 120.0),
    NORMAL("Normal 80–160", 80.0, 160.0),
    FAST("Fast 110–220", 110.0, 220.0),
}

data class Measurement(val id: Long, val bpm: Double, val name: String, val time: Long)

data class UiState(
    val listening: Boolean = false,
    val elapsedSec: Double = 0.0,
    val level: Float = 0f,
    /** Smoothed tempo from the detector, before the ½×/2× correction. */
    val detectedBpm: Double? = null,
    val locked: Boolean = false,
    val confidence: Float = 0f,
    val beatAnchorNanos: Long = 0L,
    val beatPeriodSec: Double = 0.0,
    val envelope: FloatArray = FloatArray(0),
    val range: TempoRange = TempoRange.NORMAL,
    val multiplier: Double = 1.0,
    val tapBpm: Double? = null,
    val tapCount: Int = 0,
    val metronomeOn: Boolean = false,
    val metronomeBpm: Double = 120.0,
    /** 0..1 — how far the estimate is from locking in (drives the ring around the dial). */
    val settle: Float = 0f,
    val history: List<Measurement> = emptyList(),
    val message: String? = null,
) {
    /** The tempo shown to the user: detector result corrected by ½× / 2×. */
    val bpm: Double? get() = detectedBpm?.let { it * multiplier }
}

class BpmViewModel(app: Application) : AndroidViewModel(app) {
    private val detector = TempoDetector(SAMPLE_RATE)
    private val lock = Any()
    private val mic = MicListener(app) { buf, n -> synchronized(lock) { detector.feed(buf, n) } }
    private val metronome = Metronome()
    private val tapTempo = TapTempo()
    private val prefs = app.getSharedPreferences("beatsense", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState(history = loadHistory()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loop: Job? = null
    private val recent = ArrayDeque<Double>()
    private var startedAt = 0L
    private var quietSince = 0L

    init {
        metronome.onTick = { _ ->
            _state.update { it.copy(beatAnchorNanos = System.nanoTime()) }
        }
    }

    fun start() {
        if (_state.value.listening) return
        if (_state.value.metronomeOn) stopMetronome()
        synchronized(lock) { detector.reset() }
        recent.clear()
        if (!mic.start()) {
            _state.update { it.copy(message = "Couldn't open the microphone. Is another app using it?") }
            return
        }
        startedAt = System.currentTimeMillis()
        quietSince = 0L
        _state.update {
            it.copy(listening = true, detectedBpm = null, locked = false, settle = 0f, confidence = 0f, multiplier = 1.0,
                elapsedSec = 0.0, envelope = FloatArray(0), message = "Listening… play some music")
        }
        loop = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(250)
                tick()
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        mic.stop()
        _state.update {
            it.copy(
                listening = false,
                level = 0f,
                message = when {
                    it.detectedBpm == null -> "No steady beat found. Try again closer to the speaker."
                    it.locked -> null
                    else -> "Stopped before the tempo settled — the result may be rough."
                },
            )
        }
    }

    fun toggleListening() = if (_state.value.listening) stop() else start()

    private fun tick() {
        val range = _state.value.range
        val est: TempoDetector.Estimate?
        val env: DoubleArray
        val level: Double
        synchronized(lock) {
            est = detector.estimate(range.low, range.high)
            env = detector.envelopeSnapshot()
            level = detector.level
        }
        val now = System.currentTimeMillis()
        val elapsed = (now - startedAt) / 1000.0

        if (level < 0.015) { if (quietSince == 0L) quietSince = now } else quietSince = 0L
        val tooQuiet = quietSince != 0L && now - quietSince > 2500

        if (est != null && est.confidence >= 0.15) {
            recent.addLast(est.bpm)
            while (recent.size > 12) recent.removeFirst()
        }
        val smoothed = if (recent.isEmpty()) null else recent.sorted()[recent.size / 2]
        val last8 = recent.takeLast(8)
        val locked = elapsed >= 6 && last8.size == 8 && (last8.max() - last8.min()) <= 1.5
        // Fill the ring as consistent estimates accumulate, not just as time passes.
        val agreeing = if (smoothed == null) 0 else last8.count { kotlin.math.abs(it - smoothed) <= 1.5 }
        val settle = if (locked) 1f else (agreeing / 8f).coerceAtMost(0.95f)

        _state.update { s ->
            s.copy(
                elapsedSec = elapsed,
                level = level.toFloat(),
                detectedBpm = smoothed ?: s.detectedBpm,
                locked = locked,
                settle = settle,
                confidence = (est?.confidence ?: 0.0).toFloat(),
                beatAnchorNanos = if (est != null && smoothed != null) System.nanoTime() - (est.lastBeatAgoSec * 1e9).toLong() else s.beatAnchorNanos,
                beatPeriodSec = if (smoothed != null) 60.0 / smoothed else 0.0,
                envelope = downsample(env),
                message = when {
                    tooQuiet -> "Too quiet — hold the phone closer to the speaker"
                    est == null -> "Listening… (needs about 4 seconds)"
                    est.confidence < 0.15 -> "No clear beat yet — music with drums works best"
                    locked -> null
                    else -> "Locking on…"
                },
            )
        }

        // Plenty of time to settle; stop on our own to save battery.
        if (elapsed > 45) viewModelScope.launch { stop() }
    }

    /** The last ~4 seconds of onset strength, squeezed into bars for the visualiser. */
    private fun downsample(env: DoubleArray, bars: Int = 90): FloatArray {
        val take = minOf(env.size, (detector.framesPerSecond * 4).toInt())
        if (take < bars) return FloatArray(0)
        val start = env.size - take
        val out = FloatArray(bars)
        var peak = 1e-9
        for (b in 0 until bars) {
            val from = start + b * take / bars
            val to = start + (b + 1) * take / bars
            var m = 0.0
            for (i in from until to) m = maxOf(m, env[i])
            out[b] = m.toFloat()
            peak = maxOf(peak, m)
        }
        for (b in 0 until bars) out[b] = (out[b] / peak).toFloat()
        return out
    }

    fun setRange(r: TempoRange) {
        recent.clear()
        _state.update { it.copy(range = r, multiplier = 1.0, detectedBpm = it.detectedBpm?.let { b -> TempoDetector.fold(b, r.low, r.high) }) }
        syncMetronome()
    }

    fun halve() = setMultiplier(_state.value.multiplier / 2)
    fun double() = setMultiplier(_state.value.multiplier * 2)

    private fun setMultiplier(m: Double) {
        _state.update { it.copy(multiplier = m.coerceIn(0.25, 4.0)) }
        syncMetronome()
    }

    fun tap() {
        val bpm = tapTempo.tap(System.currentTimeMillis())
        _state.update { it.copy(tapBpm = bpm ?: it.tapBpm.takeIf { tapTempo.count > 1 }, tapCount = tapTempo.count) }
    }

    fun resetTap() {
        tapTempo.reset()
        _state.update { it.copy(tapBpm = null, tapCount = 0) }
    }

    /** Tempo the metronome should play: the detected one, else the tapped one, else 120. */
    private fun metronomeBpm(): Double = _state.value.bpm ?: _state.value.tapBpm ?: 120.0

    fun toggleMetronome() {
        if (_state.value.metronomeOn) { stopMetronome(); return }
        if (_state.value.listening) stop()
        val bpm = metronomeBpm()
        metronome.start(bpm)
        _state.update { it.copy(metronomeOn = true, metronomeBpm = bpm, beatPeriodSec = 60.0 / bpm, message = null) }
    }

    fun playAt(bpm: Double) {
        if (_state.value.listening) stop()
        metronome.start(bpm)
        _state.update { it.copy(metronomeOn = true, metronomeBpm = bpm, beatPeriodSec = 60.0 / bpm, message = null) }
    }

    private fun stopMetronome() {
        metronome.stop()
        _state.update { it.copy(metronomeOn = false) }
    }

    private fun syncMetronome() {
        if (_state.value.metronomeOn) {
            val bpm = metronomeBpm()
            metronome.setBpm(bpm)
            _state.update { it.copy(metronomeBpm = bpm, beatPeriodSec = 60.0 / bpm) }
        }
    }

    fun save(name: String) {
        val bpm = _state.value.bpm ?: _state.value.tapBpm ?: return
        val m = Measurement(System.currentTimeMillis(), (bpm * 10).roundToInt() / 10.0, name.trim(), System.currentTimeMillis())
        _state.update { it.copy(history = listOf(m) + it.history) }
        persist()
    }

    fun delete(id: Long) {
        _state.update { s -> s.copy(history = s.history.filterNot { it.id == id }) }
        persist()
    }

    fun permissionDenied() {
        _state.update { it.copy(message = "BeatSense needs the microphone to hear the music. You can allow it in Settings.") }
    }

    private fun loadHistory(): List<Measurement> = runCatching {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Measurement(o.getLong("id"), o.getDouble("bpm"), o.optString("name"), o.getLong("time"))
        }
    }.getOrDefault(emptyList())

    private fun persist() {
        val arr = JSONArray()
        _state.value.history.forEach {
            arr.put(JSONObject().put("id", it.id).put("bpm", it.bpm).put("name", it.name).put("time", it.time))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    override fun onCleared() {
        mic.stop()
        metronome.stop()
    }
}

fun tempoName(bpm: Double): String = when {
    bpm < 60 -> "Largo"
    bpm < 66 -> "Larghetto"
    bpm < 76 -> "Adagio"
    bpm < 108 -> "Andante"
    bpm < 120 -> "Moderato"
    bpm < 156 -> "Allegro"
    bpm < 176 -> "Vivace"
    bpm < 200 -> "Presto"
    else -> "Prestissimo"
}

fun genreHint(bpm: Double): String = when {
    bpm < 70 -> "Ballads, ambient, slow jams"
    bpm < 90 -> "Hip-hop, R&B, chill"
    bpm < 100 -> "Reggaeton, hip-hop, dancehall"
    bpm < 115 -> "Pop, funk, disco"
    bpm < 125 -> "Pop, house, dance"
    bpm < 135 -> "House, techno, EDM"
    bpm < 150 -> "Trance, dubstep, techno"
    bpm < 165 -> "Hardstyle, punk, footwork"
    bpm < 185 -> "Drum & bass, jungle"
    else -> "Hardcore, speed metal"
}
