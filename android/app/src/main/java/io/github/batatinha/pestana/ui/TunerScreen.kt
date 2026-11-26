package io.github.batatinha.pestana.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.batatinha.pestana.AppState
import io.github.batatinha.pestana.Notes
import io.github.batatinha.pestana.TunerEngine
import io.github.batatinha.pestana.Tunings
import io.github.batatinha.pestana.centsFrom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun TunerScreen(state: AppState) {
    val t = LocalReading.current
    val ctx = LocalContext.current
    val s = state.settings
    val tuning = Tunings.byId(s.tuning)
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var byString by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable(s.tuning) { mutableStateOf(-1) } // -1 = detect string automatically
    var picker by remember { mutableStateOf(false) }
    val done = remember(s.tuning) { mutableStateMapOf<Int, Boolean>() }

    val engine = remember { TunerEngine(ctx) }
    engine.minHz = tuning.minHz
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(granted, lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> if (granted) engine.start()
                Lifecycle.Event.ON_PAUSE -> engine.stop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) engine.start()
        onDispose {
            lifecycle.removeObserver(obs)
            engine.stop()
        }
    }

    val reading by engine.state.collectAsState()
    val freq = reading.freq
    val has = freq > 0

    // What we are measuring against.
    var noteMidi = -1
    var cents = 0f
    var stringIdx = -1
    if (has) {
        if (byString) {
            stringIdx = if (chosen >= 0) chosen else tuning.strings.indices.minBy { i ->
                abs(centsFrom(freq, Notes.freq(tuning.strings[i], s.a4), fold = true))
            }
            noteMidi = tuning.strings[stringIdx]
            cents = centsFrom(freq, Notes.freq(noteMidi, s.a4), fold = true)
        } else {
            val mf = Notes.midiFloat(freq, s.a4)
            noteMidi = mf.roundToInt()
            cents = (mf - noteMidi) * 100f
            stringIdx = tuning.strings.indexOfFirst { it == noteMidi }
        }
    } else if (byString && chosen >= 0) {
        noteMidi = tuning.strings[chosen]
    }
    val inTune = has && reading.active && abs(cents) < 2f
    val close = has && abs(cents) < 10f

    // A string counts as tuned after staying within ±3 cents for a moment.
    LaunchedEffect(stringIdx, abs(cents) < 3f, reading.active) {
        if (stringIdx >= 0 && has && reading.active && abs(cents) < 3f) {
            kotlinx.coroutines.delay(450)
            done[stringIdx] = true
        }
    }

    val needle by animateFloatAsState(
        if (has) cents.coerceIn(-50f, 50f) else 0f,
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow), label = "needle",
    )
    val tone by animateColorAsState(
        when {
            !has || !reading.active -> t.muted
            inTune -> t.good
            close -> t.chord
            else -> t.text
        }, label = "tone",
    )

    Column(Modifier.fillMaxSize()) {
        TopBar("Afinador", "${tuning.group} · ${tuning.name}") {
            Box(
                Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50)).background(t.surface).clickable { picker = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("Trocar", color = t.chord, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        }
        if (!granted) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("O afinador precisa ouvir o instrumento", color = t.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("O som é analisado só no aparelho, nada é gravado ou enviado.", color = t.muted, fontSize = 14.sp)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }, colors = ButtonDefaults.buttonColors(containerColor = t.chord, contentColor = t.bg)) {
                    Text("Permitir microfone")
                }
            }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Automático", !byString) { byString = false }
                Pill("Por corda", byString) { byString = true }
            }
            // Gauge
            Box(Modifier.fillMaxWidth().widthIn(max = 420.dp).padding(horizontal = 24.dp, vertical = 8.dp).aspectRatio(1.6f), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2
                    val cy = size.height * 0.95f
                    val r = minOf(size.width / 2, size.height) * 0.92f
                    fun at(c: Float, rad: Float): Offset {
                        val a = Math.toRadians((-90 + c * 1.5).toDouble()) // ±50 cents = ±75°
                        return Offset(cx + rad * cos(a).toFloat(), cy + rad * sin(a).toFloat())
                    }
                    // in-tune zone
                    drawArc(t.good.copy(alpha = 0.25f), -90f - 3f, 6f, false, Offset(cx - r, cy - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(r * 0.09f))
                    for (c in -50..50) {
                        val major = c % 10 == 0
                        if (c % 2 != 0 && !major) continue
                        val len = if (major) r * 0.1f else if (c % 5 == 0) r * 0.06f else r * 0.035f
                        drawLine(
                            if (c == 0) t.good else t.muted.copy(alpha = if (major) 0.9f else 0.5f),
                            at(c.toFloat(), r), at(c.toFloat(), r - len), strokeWidth = if (major) 3f else 1.5f,
                        )
                    }
                    // Short needle riding the scale, leaving the centre free for the note.
                    drawLine(tone, at(needle, r * 0.66f), at(needle, r * 1.02f), strokeWidth = 7f, cap = StrokeCap.Round)
                }
                Column(Modifier.padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (noteMidi >= 0) Notes.name(noteMidi) else " ", color = tone, fontSize = 76.sp, fontWeight = FontWeight.Bold)
                        if (noteMidi >= 0) Text("${Notes.octave(noteMidi)}", color = t.muted, fontSize = 22.sp, modifier = Modifier.padding(bottom = 16.dp))
                    }
                    Text(if (noteMidi >= 0) Notes.solfege(noteMidi) else " ", color = t.muted, fontSize = 15.sp)
                }
            }
            // Readout
            val hint = when {
                !has -> "Toque uma corda"
                !reading.active -> "…"
                inTune -> "Afinado ✓"
                byString && cents < 0 -> "Aperte a corda (está baixa)"
                byString -> "Afrouxe a corda (está alta)"
                cents < 0 -> "Baixo"
                else -> "Alto"
            }
            Text(hint, color = tone, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (has) "%+.1f cents   ·   %.2f Hz".format(cents, freq) else "A4 = ${s.a4.roundToInt()} Hz",
                color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
            )
            // Level
            Box(Modifier.padding(top = 10.dp).size(120.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(t.surface2)) {
                val lvl = (reading.level * 12f).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(lvl).height(3.dp).background(if (reading.active) t.good else t.muted))
            }
            Spacer(Modifier.height(22.dp))
            // Strings
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (byString) {
                    StringChip("Auto", chosen < 0, false, false) { chosen = -1 }
                }
                // Left-handed players hold the high string on the left.
                val order = if (s.leftHanded) tuning.strings.indices.reversed() else tuning.strings.indices
                for (i in order) {
                    val m = tuning.strings[i]
                    val active = i == stringIdx && has
                    StringChip(Notes.name(m, flats = tuning.label.contains('b')), if (byString) chosen == i else active, done[i] == true, active) {
                        byString = true; chosen = i
                    }
                }
            }
            Text(
                if (byString) "Toque numa corda para fixar · \"Auto\" detecta sozinho" else if (s.leftHanded) "Agudo → grave" else "Grave → agudo",
                color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(18.dp))
            Box(Modifier.widthIn(max = 420.dp).padding(horizontal = 24.dp)) {
                Stepper("Referência A4", "${s.a4.roundToInt()} Hz", { s.putA4(s.a4 - 1) }, { s.putA4(s.a4 + 1) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picker) {
        AlertDialog(
            onDismissRequest = { picker = false },
            containerColor = t.surface,
            title = { Text("Afinação") },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    var lastGroup = ""
                    Tunings.all.forEach { tu ->
                        if (tu.group != lastGroup) {
                            lastGroup = tu.group
                            item(key = "g" + tu.group) { Text(tu.group.uppercase(), style = SectionLabel, color = t.muted, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                        }
                        item(key = tu.id) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { s.putTuning(tu.id); picker = false }
                                    .background(if (tu.id == s.tuning) t.surface2 else t.surface).padding(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                Text(tu.name, color = t.text, modifier = Modifier.weight(1f))
                                Text(tu.label, color = t.chord, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("Fechar") } },
        )
    }
}

@Composable
private fun StringChip(label: String, selected: Boolean, done: Boolean, hearing: Boolean, onClick: () -> Unit) {
    val t = LocalReading.current
    Box(
        Modifier.size(50.dp).clip(CircleShape)
            .background(if (selected) t.surface2 else t.surface)
            .border(2.dp, when { hearing -> t.chord; done -> t.good; selected -> t.text; else -> t.line }, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (done) t.good else t.text, fontWeight = FontWeight.Bold, fontSize = if (label.length > 2) 13.sp else 17.sp)
    }
}
