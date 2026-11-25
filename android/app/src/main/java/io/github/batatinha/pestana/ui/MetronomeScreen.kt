package io.github.batatinha.pestana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.batatinha.pestana.AppState
import io.github.batatinha.pestana.Metronome
import kotlinx.coroutines.isActive

private fun tempoName(bpm: Int) = when {
    bpm < 40 -> "Grave"
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

private val SIGNATURES = listOf("2/4" to 2, "3/4" to 3, "4/4" to 4, "5/4" to 5, "6/8" to 6, "7/8" to 7, "12/8" to 12)
private val SUBDIVISIONS = listOf("♩" to 1, "♪♪" to 2, "3" to 3, "4" to 4)

@Composable
fun MetronomeScreen(state: AppState) {
    val t = LocalReading.current
    val s = state.settings
    fun sync() {
        Metronome.bpm = s.bpm; Metronome.beats = s.beats; Metronome.subdivision = s.subdivision; Metronome.accent = s.accent
    }
    LaunchedEffect(Unit) { sync() }

    var beat by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Metronome.playing) {
        if (!Metronome.playing) { beat = -1; return@LaunchedEffect }
        while (isActive) withFrameNanos { beat = Metronome.currentBeat() }
    }
    val taps = remember { mutableStateListOf<Long>() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Metrônomo", tempoName(s.bpm))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            // Beats
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until s.beats) {
                    val on = i == beat
                    val first = i == 0 && s.accent
                    Box(
                        Modifier.size(if (first) 22.dp else 16.dp).clip(CircleShape)
                            .background(if (on) (if (first) t.chord else t.text) else t.surface2),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton("−5") { s.putBpm(s.bpm - 5); sync() }
                IconButton(onClick = { s.putBpm(s.bpm - 1); sync() }) { Icon(Ic.Minus, "Menos", tint = t.text) }
                Column(Modifier.widthIn(min = 130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.bpm}", color = t.text, fontSize = 72.sp, fontWeight = FontWeight.Bold)
                    Text("BPM", color = t.muted, fontSize = 13.sp, letterSpacing = 2.sp)
                }
                IconButton(onClick = { s.putBpm(s.bpm + 1); sync() }) { Icon(Ic.Plus, "Mais", tint = t.text) }
                RoundButton("+5") { s.putBpm(s.bpm + 5); sync() }
            }
            Slider(
                s.bpm.toFloat(), { s.putBpm(it.toInt()); sync() }, valueRange = 20f..300f,
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                colors = SliderDefaults.colors(thumbColor = t.chord, activeTrackColor = t.chord, inactiveTrackColor = t.surface2),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                // Tap tempo
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(t.surface).clickable {
                        val now = System.nanoTime()
                        if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
                        taps.add(now)
                        while (taps.size > 6) taps.removeAt(0)
                        if (taps.size >= 2) {
                            val avg = (taps.last() - taps.first()) / (taps.size - 1).toDouble()
                            s.putBpm((60e9 / avg).toInt()); sync()
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("TAP", color = t.text, fontWeight = FontWeight.Bold) }
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(t.chord).clickable { sync(); Metronome.toggle() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (Metronome.playing) Ic.Pause else Ic.Play, if (Metronome.playing) "Parar" else "Tocar", Modifier.size(36.dp), tint = t.bg) }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(if (s.accent) t.surface2 else t.surface).clickable { s.putAccent(!s.accent); sync() },
                    contentAlignment = Alignment.Center,
                ) { Text(">", color = if (s.accent) t.chord else t.muted, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
            }
            Text("toque no ritmo  ·  tocar  ·  acento", color = t.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            SectionTitle("Compasso", Modifier.fillMaxWidth().padding(start = 0.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SIGNATURES.forEach { (label, n) -> Pill(label, s.beats == n) { s.putBeats(n); sync() } }
            }
            SectionTitle("Subdivisão", Modifier.fillMaxWidth().padding(start = 0.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SUBDIVISIONS.forEach { (label, n) -> Pill(label, s.subdivision == n) { s.putSubdivision(n); sync() } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit) {
    val t = LocalReading.current
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(t.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}
