package com.beatsense.app.dsp

import java.util.Locale

/**
 * Whole-track analysis for audio files (the web app's track.js is a line-by-line port).
 *
 * The file is fed through the same [TempoDetector] the live mode uses. Every few seconds the
 * detector gives an estimate over its 12-second window, exactly as it does while listening;
 * the track's tempo is the median of the confident ones, and the whole list is the tempo
 * curve. The [KeyDetector] listens to the whole file with no forgetting.
 */
class TrackAnalyzer(private val rate: Int, private val low: Double = 80.0, private val high: Double = 160.0) {

    data class Point(val timeSec: Double, val bpm: Double, val confidence: Double)

    data class Result(
        val durationSec: Double,
        val bpm: Double?,
        val confidence: Double,
        val key: KeyDetector.Estimate?,
        val curve: List<Point>,
    )

    private val tempo = TempoDetector(rate)
    private val key = KeyDetector(rate, 0.0)
    private var samples = 0L
    private var nextEstimate = (FIRST_ESTIMATE_SEC * rate).toLong()
    private val curve = mutableListOf<Point>()
    private val part = ShortArray(8192)

    /** Feed 16-bit PCM mono samples, in order, as they are decoded. */
    fun feed(pcm: ShortArray, count: Int) {
        var pos = 0
        while (pos < count) {
            // Stop at each estimate point, so estimates land at the same place however the file is chunked.
            val n = minOf(count - pos, (nextEstimate - samples).toInt(), part.size)
            System.arraycopy(pcm, pos, part, 0, n)
            tempo.feed(part, n)
            key.feed(part, n)
            samples += n
            pos += n
            if (samples == nextEstimate) {
                estimate()
                nextEstimate += (ESTIMATE_EVERY_SEC * rate).toLong()
            }
        }
    }

    private fun estimate() {
        tempo.estimate(low, high)?.let { curve += Point(samples.toDouble() / rate, it.bpm, it.confidence) }
    }

    val durationSec: Double get() = samples.toDouble() / rate

    /** BPM (median of the confident estimates), key, and the tempo curve. */
    fun result(): Result {
        // A short file may end before the first estimate point.
        if (curve.isEmpty()) estimate()
        val good = curve.filter { it.confidence >= 0.15 }
        val bpms = good.map { it.bpm }.sorted()
        val k = key.estimate()
        return Result(
            durationSec = durationSec,
            bpm = if (bpms.isEmpty()) null else bpms[bpms.size / 2],
            confidence = if (good.isEmpty()) 0.0 else good.sumOf { it.confidence } / good.size,
            key = k?.takeIf { it.confidence >= 0.2 },
            curve = curve.toList(),
        )
    }

    companion object {
        private const val ESTIMATE_EVERY_SEC = 3
        private const val FIRST_ESTIMATE_SEC = 6

        private val CSV_HEADER = listOf("file", "duration_s", "bpm", "confidence", "key", "camelot", "key_confidence")

        /** One CSV row per analysed file. Same columns and format as the web app's export. */
        fun toCsv(rows: List<Pair<String, Result?>>): String {
            fun cell(v: String) = if (v.any { it == '"' || it == ',' || it == '\n' || it == '\r' }) "\"${v.replace("\"", "\"\"")}\"" else v
            fun f(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)
            val sb = StringBuilder(CSV_HEADER.joinToString(",")).append("\r\n")
            for ((file, r) in rows) {
                sb.append(
                    listOf(
                        file,
                        r?.let { f(it.durationSec, 1) } ?: "",
                        r?.bpm?.let { f(it, 1) } ?: "",
                        r?.takeIf { it.bpm != null }?.let { f(it.confidence, 2) } ?: "",
                        r?.key?.name ?: "",
                        r?.key?.camelot ?: "",
                        r?.key?.let { f(it.confidence, 2) } ?: "",
                    ).joinToString(",") { cell(it) },
                ).append("\r\n")
            }
            return sb.toString()
        }
    }
}
