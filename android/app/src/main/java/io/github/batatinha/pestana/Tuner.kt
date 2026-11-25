package io.github.batatinha.pestana

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

private val SHARPS = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLATS = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
private val SOLFEGE = arrayOf("Dó", "Dó#", "Ré", "Ré#", "Mi", "Fá", "Fá#", "Sol", "Sol#", "Lá", "Lá#", "Si")

object Notes {
    fun name(midi: Int, flats: Boolean = false) = (if (flats) FLATS else SHARPS)[Math.floorMod(midi, 12)]
    fun octave(midi: Int) = Math.floorDiv(midi, 12) - 1
    fun solfege(midi: Int) = SOLFEGE[Math.floorMod(midi, 12)]
    fun freq(midi: Int, a4: Float) = a4 * 2f.pow((midi - 69) / 12f)
    fun midiFloat(freq: Float, a4: Float) = 69f + 12f * log2(freq / a4)

    /** "E2" -> 40, "Eb2"/"D#2" -> 39 */
    fun parse(s: String): Int {
        val oct = s.takeLastWhile { it.isDigit() || it == '-' }
        val name = s.dropLast(oct.length)
        val pc = SHARPS.indexOf(name).takeIf { it >= 0 } ?: FLATS.indexOf(name)
        require(pc >= 0) { s }
        return (oct.toInt() + 1) * 12 + pc
    }
}

class Tuning(val id: String, val group: String, val name: String, notes: String) {
    /** MIDI notes, lowest string first. */
    val strings: List<Int> = notes.split(' ').map(Notes::parse)
    val label: String = notes.split(' ').joinToString(" ") { it.trimEnd { c -> c.isDigit() } }
    val minHz get() = (Notes.freq(strings.min(), 440f) * 0.7f).coerceAtLeast(25f)
}

object Tunings {
    val all = listOf(
        Tuning("guitar_std", "Violão / Guitarra", "Padrão", "E2 A2 D3 G3 B3 E4"),
        Tuning("guitar_eb", "Violão / Guitarra", "Meio tom abaixo", "Eb2 Ab2 Db3 Gb3 Bb3 Eb4"),
        Tuning("guitar_d", "Violão / Guitarra", "Um tom abaixo", "D2 G2 C3 F3 A3 D4"),
        Tuning("guitar_dropd", "Violão / Guitarra", "Drop D", "D2 A2 D3 G3 B3 E4"),
        Tuning("guitar_dropcs", "Violão / Guitarra", "Drop C#", "C#2 G#2 C#3 F#3 A#3 D#4"),
        Tuning("guitar_dropc", "Violão / Guitarra", "Drop C", "C2 G2 C3 F3 A3 D4"),
        Tuning("guitar_dropb", "Violão / Guitarra", "Drop B", "B1 F#2 B2 E3 G#3 C#4"),
        Tuning("guitar_dadgad", "Violão / Guitarra", "DADGAD", "D2 A2 D3 G3 A3 D4"),
        Tuning("guitar_openg", "Violão / Guitarra", "Open G", "D2 G2 D3 G3 B3 D4"),
        Tuning("guitar_opend", "Violão / Guitarra", "Open D", "D2 A2 D3 F#3 A3 D4"),
        Tuning("guitar_opene", "Violão / Guitarra", "Open E", "E2 B2 E3 G#3 B3 E4"),
        Tuning("guitar_opena", "Violão / Guitarra", "Open A", "E2 A2 E3 A3 C#4 E4"),
        Tuning("guitar_openc", "Violão / Guitarra", "Open C", "C2 G2 C3 G3 C4 E4"),
        Tuning("guitar_7", "Violão / Guitarra", "7 cordas (Si)", "B1 E2 A2 D3 G3 B3 E4"),
        Tuning("guitar_7c", "Violão / Guitarra", "7 cordas (Dó, choro)", "C2 E2 A2 D3 G3 B3 E4"),
        Tuning("bass_4", "Baixo", "4 cordas", "E1 A1 D2 G2"),
        Tuning("bass_5", "Baixo", "5 cordas", "B0 E1 A1 D2 G2"),
        Tuning("bass_dropd", "Baixo", "Drop D", "D1 A1 D2 G2"),
        Tuning("ukulele", "Ukulele", "Padrão (GCEA)", "G4 C4 E4 A4"),
        Tuning("cavaco", "Cavaquinho", "Natural (DGBD)", "D4 G4 B4 D5"),
        Tuning("viola_cebolao_e", "Viola caipira", "Cebolão em Mi", "B2 E3 G#3 B3 E4"),
        Tuning("viola_cebolao_d", "Viola caipira", "Cebolão em Ré", "A2 D3 F#3 A3 D4"),
        Tuning("viola_rio_abaixo", "Viola caipira", "Rio Abaixo", "G2 D3 G3 B3 D4"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[0]
}

/**
 * Microphone -> pitch. Reads raw audio (UNPROCESSED when the phone supports
 * it, so no noise suppression or gain control bends the signal), runs the
 * Rust MPM detector on overlapping windows and median-filters the result.
 */
class TunerEngine(private val context: Context) {
    class Reading(val freq: Float, val clarity: Float, val level: Float, val active: Boolean)

    private val _state = MutableStateFlow(Reading(-1f, 0f, 0f, false))
    val state: StateFlow<Reading> = _state

    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var minHz = 50f
    @Volatile var maxHz = 1600f

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "tuner").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun source(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rates = intArrayOf(48000, 44100)
        var rec: AudioRecord? = null
        var sr = 48000
        for (r in rates) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (min <= 0) continue
            val a = runCatching {
                AudioRecord(source(), r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, 8192 * 4))
            }.getOrNull()
            if (a != null && a.state == AudioRecord.STATE_INITIALIZED) {
                rec = a; sr = r; break
            }
            a?.release()
        }
        if (rec == null) {
            running = false
            return
        }
        val det = Native.newDetector()
        val hop = 1024
        var window = if (minHz < 45f) 8192 else 4096
        var ring = FloatArray(window)
        val chunk = FloatArray(hop)
        val history = ArrayDeque<Float>()
        var lastValid = 0L
        var filled = 0
        try {
            rec.startRecording()
            while (running) {
                val want = if (minHz < 45f) 8192 else 4096
                if (want != window) {
                    window = want; ring = FloatArray(window); filled = 0; history.clear()
                }
                var got = 0
                while (got < hop && running) {
                    val n = rec.read(chunk, got, hop - got, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    got += n
                }
                if (got < hop) continue
                System.arraycopy(ring, hop, ring, 0, window - hop)
                System.arraycopy(chunk, 0, ring, window - hop, hop)
                filled = minOf(window, filled + hop)
                if (filled < window) continue

                val r = Native.detect(det, ring, window, sr, minHz, maxHz)
                val freq = r[0]; val clarity = r[1]; val rms = r[2]
                val now = System.nanoTime()
                if (freq > 0 && clarity >= 0.88f && rms > 0.0015f) {
                    // Drop octave jumps against the recent median.
                    if (history.size >= 3) {
                        val med = median(history)
                        if (abs(1200f * log2(freq / med)) > 80f) {
                            history.clear()
                        }
                    }
                    history.addLast(freq)
                    while (history.size > 5) history.removeFirst()
                    lastValid = now
                    _state.value = Reading(median(history), clarity, rms, true)
                } else if (now - lastValid > 700_000_000L) {
                    history.clear()
                    val prev = _state.value
                    if (prev.active || prev.level != rms) _state.value = Reading(prev.freq, 0f, rms, false)
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            Native.freeDetector(det)
        }
    }

    private fun median(d: ArrayDeque<Float>): Float {
        val s = d.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }
}

/** Cents of `freq` from `target`, folded into ±600 so octave strings (viola) still read. */
fun centsFrom(freq: Float, target: Float, fold: Boolean = false): Float {
    var c = 1200f * log2(freq / target)
    if (fold) {
        while (c > 600f) c -= 1200f
        while (c < -600f) c += 1200f
    }
    return c
}

fun Float.roundCents() = (this * 10f).roundToInt() / 10f
