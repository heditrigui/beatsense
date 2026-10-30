package com.beatsense.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beatsense.app.audio.MicListener
import com.beatsense.app.audio.Metronome
import com.beatsense.app.audio.SAMPLE_RATE
import com.beatsense.app.audio.TapTempo
import com.beatsense.app.audio.decodeAudio
import com.beatsense.app.dsp.KeyDetector
import com.beatsense.app.dsp.TempoDetector
import com.beatsense.app.dsp.TrackAnalyzer
import com.beatsense.app.midi.MidiClock
import com.beatsense.app.midi.MidiDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

data class Measurement(val id: Long, val bpm: Double, val name: String, val time: Long, val key: String = "")

/** One point of the tempo-over-time graph: seconds since listening started, and the BPM shown then. */
data class TempoPoint(val timeSec: Float, val bpm: Float)

data class FileResult(val name: String, val result: TrackAnalyzer.Result?)

/** Analysis of picked audio files: progress while it runs, then one result per file. */
data class FileJob(
    val running: Boolean = false,
    val current: String? = null,
    /** 0..1 through the current file. */
    val progress: Float = 0f,
    val total: Int = 0,
    val results: List<FileResult> = emptyList(),
)

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
    /** Musical key heard so far, once it is clear enough to show. */
    val key: KeyDetector.Estimate? = null,
    /** Tempo over time for the current (or last) listening session. */
    val tempoCurve: List<TempoPoint> = emptyList(),
    /** Keep listening past the usual 45 seconds, for live sets and long mixes. */
    val keepListening: Boolean = false,
    val files: FileJob = FileJob(),
    val midiOn: Boolean = false,
    val midiTargets: List<MidiDevices.Target> = emptyList(),
    /** USB/Bluetooth device the clock also goes to; other apps can always connect to "BeatSense Clock". */
    val midiTarget: MidiDevices.Target? = null,
) {
    /** The tempo shown to the user: detector result corrected by ½× / 2×. */
    val bpm: Double? get() = detectedBpm?.let { it * multiplier }
}

class BpmViewModel(app: Application) : AndroidViewModel(app) {
    private val detector = TempoDetector(SAMPLE_RATE)
    private val keyDetector = KeyDetector(SAMPLE_RATE)
    private val lock = Any()
    private val mic = MicListener(app) { buf, n -> synchronized(lock) { detector.feed(buf, n); keyDetector.feed(buf, n) } }
    private val midi = MidiDevices(app)
    private val metronome = Metronome()
    private val tapTempo = TapTempo()
    private val prefs = app.getSharedPreferences("beatsense", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState(history = loadHistory()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loop: Job? = null
    private val recent = ArrayDeque<Double>()
    private var startedAt = 0L
    private var quietSince = 0L
    private var fileJob: Job? = null

    init {
        metronome.onTick = { _ ->
            _state.update { it.copy(beatAnchorNanos = System.nanoTime()) }
        }
        // MIDI clock follows whatever tempo the app is showing: the metronome's, the detected one, or the tapped one.
        viewModelScope.launch {
            state.map { s -> if (!s.midiOn) null else if (s.metronomeOn) s.metronomeBpm else s.bpm ?: s.tapBpm }
                .distinctUntilChanged()
                .collect { MidiClock.bpm = it }
        }
    }

    fun start() {
        if (_state.value.listening) return
        if (_state.value.metronomeOn) stopMetronome()
        synchronized(lock) { detector.reset(); keyDetector.reset() }
        recent.clear()
        if (!mic.start()) {
            _state.update { it.copy(message = "Couldn't open the microphone. Is another app using it?") }
            return
        }
        startedAt = System.currentTimeMillis()
        quietSince = 0L
        _state.update {
            it.copy(listening = true, detectedBpm = null, locked = false, settle = 0f, confidence = 0f, multiplier = 1.0,
                elapsedSec = 0.0, envelope = FloatArray(0), key = null, tempoCurve = emptyList(), message = "Listening… play some music")
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
        val key: KeyDetector.Estimate?
        synchronized(lock) {
            est = detector.estimate(range.low, range.high)
            env = detector.envelopeSnapshot()
            level = detector.level
            key = keyDetector.estimate()
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
            // One graph point a second; very long sessions keep every other point so the graph stays light.
            var curve = s.tempoCurve
            if (smoothed != null && (curve.isEmpty() || elapsed - curve.last().timeSec >= 1)) {
                curve = curve + TempoPoint(elapsed.toFloat(), (smoothed * s.multiplier).toFloat())
                if (curve.size > 3600) curve = curve.filterIndexed { i, _ -> i % 2 == 0 }
            }
            s.copy(
                elapsedSec = elapsed,
                key = liveKey(key, s.key),
                tempoCurve = curve,
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

        // Plenty of time to settle; stop on our own to save battery, unless asked to keep going.
        if (elapsed > 45 && !_state.value.keepListening) viewModelScope.launch { stop() }
    }

    /** Shows a key once it's clear, and keeps showing it while the music sits between chords. */
    private fun liveKey(now: KeyDetector.Estimate?, shown: KeyDetector.Estimate?): KeyDetector.Estimate? = when {
        now == null -> null
        now.confidence >= 0.3 -> now
        shown != null && shown.tonic == now.tonic && shown.minor == now.minor -> shown
        else -> null
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
        val key = _state.value.key?.takeIf { _state.value.bpm != null }?.let { "${it.name} · ${it.camelot}" } ?: ""
        val m = Measurement(System.currentTimeMillis(), (bpm * 10).roundToInt() / 10.0, name.trim(), System.currentTimeMillis(), key)
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
            Measurement(o.getLong("id"), o.getDouble("bpm"), o.optString("name"), o.getLong("time"), o.optString("key"))
        }
    }.getOrDefault(emptyList())

    private fun persist() {
        val arr = JSONArray()
        _state.value.history.forEach {
            arr.put(JSONObject().put("id", it.id).put("bpm", it.bpm).put("name", it.name).put("time", it.time).put("key", it.key))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun setKeepListening(on: Boolean) = _state.update { it.copy(keepListening = on) }

    // ---- Audio files ----------------------------------------------------------------------

    /** Analyses each file in turn (decoded in memory, nothing stored), adding a result as each one finishes. */
    fun analyseFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        fileJob?.cancel()
        val app = getApplication<Application>()
        val range = _state.value.range
        _state.update { it.copy(files = FileJob(running = true, total = uris.size)) }
        fileJob = viewModelScope.launch(Dispatchers.Default) {
            for (uri in uris) {
                if (!isActive) break
                val name = displayName(uri)
                _state.update { it.copy(files = it.files.copy(current = name, progress = 0f)) }
                var analyzer: TrackAnalyzer? = null
                var expectedSec = 0.0
                var lastShown = 0L
                val ok = decodeAudio(app, uri, cancelled = { !isActive }, onStart = { rate, duration ->
                    analyzer = TrackAnalyzer(rate, range.low, range.high)
                    expectedSec = duration ?: 0.0
                }) { pcm, n ->
                    val a = analyzer!!
                    a.feed(pcm, n)
                    val now = System.currentTimeMillis()
                    if (expectedSec > 0 && now - lastShown > 200) {
                        lastShown = now
                        val p = (a.durationSec / expectedSec).toFloat().coerceIn(0f, 1f)
                        _state.update { it.copy(files = it.files.copy(progress = p)) }
                    }
                }
                val result = if (ok && isActive) analyzer?.result() else null
                if (!isActive) break
                _state.update { it.copy(files = it.files.copy(results = it.files.results + FileResult(name, result))) }
            }
            _state.update { it.copy(files = it.files.copy(running = false, current = null, progress = 0f)) }
        }
    }

    /** Analyses every audio file in a folder the user picked (and its subfolders). */
    fun analyseFolder(tree: Uri) {
        _state.update { it.copy(files = FileJob(running = true, current = "Looking for audio files…")) }
        viewModelScope.launch(Dispatchers.IO) {
            val found = mutableListOf<Pair<String, Uri>>()
            val resolver = getApplication<Application>().contentResolver
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            fun walk(docId: String, depth: Int) {
                resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val mime = c.getString(2) ?: ""
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (depth < 4) walk(id, depth + 1)
                            mime.startsWith("audio/") -> found += (c.getString(1) ?: id) to DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        }
                    }
                }
            }
            runCatching { walk(DocumentsContract.getTreeDocumentId(tree), 0) }
            if (found.isEmpty()) {
                _state.update { it.copy(files = FileJob(), message = "No audio files in that folder.") }
                return@launch
            }
            analyseFiles(found.sortedBy { it.first.lowercase() }.map { it.second })
        }
    }

    fun cancelAnalysis() {
        fileJob?.cancel()
        _state.update { it.copy(files = it.files.copy(running = false, current = null, progress = 0f)) }
    }

    fun clearFiles() {
        fileJob?.cancel()
        _state.update { it.copy(files = FileJob()) }
    }

    /** Writes the results as CSV to a file the user picked. */
    fun exportCsv(uri: Uri) {
        val csv = TrackAnalyzer.toCsv(_state.value.files.results.map { it.name to it.result })
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray()) }
            }.isSuccess
            _state.update { it.copy(message = if (ok) "Results exported as CSV." else "Couldn't write the CSV file.") }
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Audio file"

    // ---- MIDI clock -----------------------------------------------------------------------

    val midiAvailable: Boolean get() = midi.available

    fun toggleMidi() {
        if (_state.value.midiOn) {
            MidiClock.stop()
            _state.update { it.copy(midiOn = false) }
            return
        }
        // Start on the next beat, so the receiver's bar lines up with what's playing.
        val s = _state.value
        val period = if (s.metronomeOn) 60.0 / s.metronomeBpm else s.bpm?.let { 60.0 / it } ?: 0.0
        val now = System.nanoTime()
        val first = if (period > 0 && s.beatAnchorNanos > 0) {
            val p = (period * 1e9).toLong()
            now + (p - ((now - s.beatAnchorNanos) % p + p) % p)
        } else now
        _state.update { it.copy(midiOn = true, midiTargets = midi.targets()) }
        MidiClock.start(first)
    }

    fun refreshMidiTargets() = _state.update { it.copy(midiTargets = midi.targets()) }

    fun connectMidi(target: MidiDevices.Target?) {
        if (target == null) {
            midi.disconnect()
            _state.update { it.copy(midiTarget = null) }
            return
        }
        midi.connect(target) { ok ->
            _state.update { it.copy(midiTarget = target.takeIf { ok }, message = if (ok) null else "Couldn't open ${target.name}.") }
        }
    }

    override fun onCleared() {
        mic.stop()
        metronome.stop()
        fileJob?.cancel()
        MidiClock.stop()
        midi.disconnect()
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
