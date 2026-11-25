package io.github.batatinha.pestana

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sample-accurate metronome: clicks are mixed into the audio stream at exact
 * sample positions, so the tempo never drifts no matter what the UI is doing.
 * The beat shown on screen is derived from the audio clock (playback head).
 */
object Metronome {
    var playing by mutableStateOf(false)
        private set

    @Volatile var bpm = 90
    @Volatile var beats = 4
    /** 1 = quarter, 2 = eighths, 3 = triplets, 4 = sixteenths */
    @Volatile var subdivision = 1
    @Volatile var accent = true
    @Volatile var volume = 0.9f

    private const val SR = 48000
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var track: AudioTrack? = null

    // Ring of recent ticks: frame position and beat index (-1 for subdivisions).
    private val tickFrames = LongArray(64)
    private val tickBeats = IntArray(64)
    @Volatile private var tickCount = 0L

    private fun click(freq: Double, ms: Double, gain: Double): FloatArray {
        val n = (SR * ms / 1000).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / SR
            val env = (1 - exp(-t * 4000)) * exp(-t * 1000 / (ms / 4))
            (sin(2 * PI * freq * t) * env * gain).toFloat()
        }
    }

    private val accentClick = click(1760.0, 40.0, 1.0)
    private val beatClick = click(1175.0, 35.0, 0.8)
    private val subClick = click(880.0, 25.0, 0.4)

    fun toggle() = if (playing) stop() else start()

    fun start() {
        if (running) return
        running = true
        playing = true
        thread = Thread({ loop() }, "metronome").apply { start() }
    }

    fun stop() {
        running = false
        playing = false
        thread?.join(300)
        thread = null
    }

    /** Index of the beat currently heard (0-based), or -1. */
    fun currentBeat(): Int {
        val t = track ?: return -1
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(0L)
        val count = tickCount
        var best = -1
        var bestFrame = -1L
        val from = maxOf(0L, count - 64)
        for (k in from until count) {
            val i = (k % 64).toInt()
            val f = tickFrames[i]
            if (f <= head && f > bestFrame && tickBeats[i] >= 0) {
                bestFrame = f; best = tickBeats[i]
            }
        }
        return best
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(min, 2048 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        tickCount = 0
        val buf = FloatArray(256)
        var frame = 0L
        var nextTick = 0.0
        var tickIndex = 0L
        var active: FloatArray? = null
        var activePos = 0
        try {
            t.play()
            while (running) {
                for (i in buf.indices) {
                    if (frame + i >= nextTick.toLong()) {
                        val sub = subdivision.coerceIn(1, 4)
                        val perBeat = sub
                        val inBar = beats.coerceAtLeast(1) * perBeat
                        val pos = (tickIndex % inBar).toInt()
                        val beat = if (pos % perBeat == 0) pos / perBeat else -1
                        active = when {
                            beat == 0 && accent -> accentClick
                            beat >= 0 -> beatClick
                            else -> subClick
                        }
                        activePos = 0
                        val slot = (tickCount % 64).toInt()
                        tickFrames[slot] = frame + i
                        tickBeats[slot] = beat
                        tickCount++
                        tickIndex++
                        nextTick += SR * 60.0 / bpm.coerceIn(20, 300) / sub
                    }
                    val a = active
                    buf[i] = if (a != null && activePos < a.size) a[activePos++] * volume else 0f
                }
                frame += buf.size
                t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            runCatching { t.pause(); t.flush(); t.stop() }
            t.release()
            track = null
        }
    }
}
