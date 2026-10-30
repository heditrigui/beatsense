package com.beatsense.app.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceService
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

private const val CLOCK = 0xF8.toByte()
private const val START = 0xFA.toByte()
private const val STOP = 0xFC.toByte()
const val PPQN = 24

/**
 * Decides when each MIDI clock is due (the web app's midi.js is a line-by-line port).
 *
 * MIDI clock is 24 "timing clock" messages (0xF8) per quarter note, framed by Start (0xFA) and
 * Stop (0xFC). Other apps, drum machines and synths follow the tempo by measuring the time
 * between clocks.
 */
class ClockTimer {
    private var next: Double? = null

    val running: Boolean get() = next != null

    /** Starts the clock with the first tick at [at] (ms). */
    fun start(at: Double) { next = at }

    fun stop() { next = null }

    /** Clock times (ms) due before [until] at [bpm]. A tempo change applies from the next tick, so the clock bends smoothly. */
    fun due(until: Double, bpm: Double?): List<Double> {
        var t = next ?: return emptyList()
        if (bpm == null || bpm <= 0) return emptyList()
        val step = 60000.0 / bpm / PPQN
        val out = mutableListOf<Double>()
        while (t < until) {
            out += t
            t += step
        }
        next = t
        return out
    }
}

/**
 * Sends MIDI clock at the current tempo to every connected receiver: other apps on the phone
 * (through [ClockService], which shows up as the "BeatSense Clock" MIDI device) and a USB or
 * Bluetooth MIDI device picked in the app.
 */
object MidiClock {
    private val receivers = CopyOnWriteArraySet<MidiReceiver>()
    private val timer = ClockTimer()
    private var worker: Thread? = null
    @Volatile private var running = false

    /** Tempo to send; null holds the clock until there is one. */
    @Volatile var bpm: Double? = null

    fun addReceiver(r: MidiReceiver) { receivers += r }
    fun removeReceiver(r: MidiReceiver) { receivers -= r }

    /** Starts sending, with the first clock (a downbeat for the receiver) at [firstBeatNanos]. */
    fun start(firstBeatNanos: Long = System.nanoTime()) {
        if (running) return
        running = true
        worker = thread(name = "midi-clock", priority = Thread.MAX_PRIORITY) {
            var started = false
            synchronized(timer) { timer.start(firstBeatNanos / 1e6) }
            while (running) {
                val now = System.nanoTime() / 1e6
                val ticks = synchronized(timer) { timer.due(now + 0.5, bpm) }
                repeat(ticks.size) {
                    if (!started) { send(START); started = true }
                    send(CLOCK)
                }
                // Sleep about a millisecond: clocks are 10-20 ms apart, and receivers smooth out jitter.
                LockSupport.parkNanos(1_000_000)
            }
            if (started) send(STOP)
        }
    }

    fun stop() {
        running = false
        worker?.join(200)
        worker = null
        synchronized(timer) { timer.stop() }
    }

    private val buf = ByteArray(1)
    private fun send(b: Byte) {
        buf[0] = b
        val now = System.nanoTime()
        for (r in receivers) runCatching { r.send(buf, 0, 1, now) }
    }
}

/** Shows BeatSense to other MIDI apps on the phone as a device with one clock output. */
class ClockService : MidiDeviceService() {
    private var out: MidiReceiver? = null

    override fun onCreate() {
        super.onCreate()
        out = outputPortReceivers.firstOrNull()?.also { MidiClock.addReceiver(it) }
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = emptyArray()

    override fun onDestroy() {
        out?.let { MidiClock.removeReceiver(it) }
        super.onDestroy()
    }
}

/** USB and Bluetooth MIDI devices (and other apps' virtual devices) that can receive the clock. */
class MidiDevices(context: Context) {
    data class Target(val id: Int, val name: String)

    private val manager = context.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null

    val available: Boolean get() = manager != null

    @Suppress("DEPRECATION") // getDevicesForTransport needs API 33; this covers 26+.
    fun targets(): List<Target> = manager?.devices.orEmpty()
        .filter { it.inputPortCount > 0 && it.name() != SELF }
        .map { Target(it.id, it.name()) }

    /** Opens [target]'s first input port and sends the clock there; [done] gets the success. */
    @Suppress("DEPRECATION")
    fun connect(target: Target, done: (Boolean) -> Unit) {
        disconnect()
        val m = manager ?: return done(false)
        val info = m.devices.firstOrNull { it.id == target.id } ?: return done(false)
        m.openDevice(info, { dev ->
            val p = dev?.openInputPort(0)
            if (p == null) { dev?.close(); done(false); return@openDevice }
            device = dev
            port = p
            MidiClock.addReceiver(p)
            done(true)
        }, main)
    }

    fun disconnect() {
        port?.let { MidiClock.removeReceiver(it); runCatching { it.close() } }
        runCatching { device?.close() }
        port = null
        device = null
    }

    private fun MidiDeviceInfo.name(): String {
        val p = properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: listOfNotNull(p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)).joinToString(" ").ifBlank { "MIDI device $id" }
    }

    companion object {
        const val SELF = "BeatSense Clock"
    }
}
