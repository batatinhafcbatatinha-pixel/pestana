package io.github.batatinha.pestana.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import io.github.batatinha.pestana.AppState

@Composable
fun SettingsScreen(state: AppState) {
    val t = LocalReading.current
    val s = state.settings
    val ctx = LocalContext.current
    var cached by remember { mutableIntStateOf(state.db.cachedCount()) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(state.db.exportJson().toByteArray()) }
        }.onSuccess { state.toast = "Backup salvo" }.onFailure { state.toast = "Não consegui salvar o backup" }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val json = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            state.db.importJson(json)
        }.onSuccess { state.changed(); state.toast = "Backup restaurado ($it itens)" }
            .onFailure { state.toast = it.message ?: "Arquivo inválido" }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Ajustes", onBack = { state.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("Tema de leitura")
            androidx.compose.runtime.CompositionLocalProvider(LocalChordColor provides s.chordColor, LocalSettingsBox provides s.chordBox) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeCard(null, s.theme == "auto") { s.putTheme("auto") }
                    Themes.all.forEach { th -> ThemeCard(th, s.theme == th.id) { s.putTheme(th.id) } }
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Stepper("Tamanho da letra", "${s.fontSize.toInt()}", { s.putFontSize(s.fontSize - 1) }, { s.putFontSize(s.fontSize + 1) })
                Stepper("Espaçamento entre linhas", "%.2f".format(s.lineSpacing), { s.putLineSpacing(s.lineSpacing - 0.05f) }, { s.putLineSpacing(s.lineSpacing + 0.05f) })
            }
            SectionTitle("Cor dos acordes")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChordColors.all.forEachIndexed { i, (name, _) -> Pill(name, s.chordColor == i) { s.putChordColor(i) } }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                ToggleRow("Fundo destacado nos acordes", s.chordBox, "Um realce atrás de cada acorde") { s.putChordBox(it) }
            }
            SectionTitle("Cifras")
            Column(Modifier.padding(horizontal = 20.dp)) {
                ToggleRow("Mostrar tablaturas", s.showTabs, "Solos e riffs dentro da cifra") { s.putShowTabs(it) }
                ToggleRow("Mostrar diagramas dos acordes", s.showDiagrams) { s.putShowDiagrams(it) }
                ToggleRow("Diagramas para canhoto", s.leftHanded) { s.putLeftHanded(it) }
                ToggleRow("Manter a tela ligada", s.keepScreenOn, "Enquanto uma cifra estiver aberta") { s.putKeepScreenOn(it) }
                ToggleRow("Rolar com as teclas de volume", s.volumeKeys, "Pedais Bluetooth funcionam sempre (Page Up/Down, setas)") { s.putVolumeKeys(it) }
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Acidentes", color = t.text, modifier = Modifier.weight(1f))
                    Pill("Auto", s.accidentals == 0) { s.putAccidentals(0) }
                    Pill("♯", s.accidentals == 1) { s.putAccidentals(1) }
                    Pill("♭", s.accidentals == 2) { s.putAccidentals(2) }
                }
            }
            SectionTitle("Afinador")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Stepper("Referência A4", "${s.a4.toInt()} Hz", { s.putA4(s.a4 - 1) }, { s.putA4(s.a4 + 1) })
            }
            SectionTitle("Seus dados (só neste aparelho)")
            ListRow("Fazer backup", "Favoritos, playlists, ajustes por música e cifras próprias") { exporter.launch("pestana-backup.json") }
            ListRow("Restaurar backup", "Junta com o que já existe") { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
            ListRow("Cifras salvas para offline", "$cached cifras · toque para apagar o cache (favoritos e playlists ficam)") {
                state.db.clearCache(); cached = 0; state.toast = "Cache apagado"
            }
            ListRow("Limpar histórico", "Lista de abertas recentemente") { state.db.clearRecents(); state.changed(); state.toast = "Histórico limpo" }
            SectionTitle("Sobre")
            Text(
                "Pestana 0.1 · código aberto (MIT).\nAs cifras pertencem aos seus autores e sites de origem; o app só as exibe para uso pessoal.",
                color = t.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ThemeCard(th: ReadingTheme?, selected: Boolean, onClick: () -> Unit) {
    val cur = LocalReading.current
    val p = ChordColors.apply(th ?: Themes.resolve("auto"), LocalChordColor.current)
    Column(
        Modifier.width(118.dp).clip(RoundedCornerShape(14.dp)).background(p.bg)
            .border(if (selected) 2.dp else 1.dp, if (selected) cur.chord else cur.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                val st = androidx.compose.ui.text.SpanStyle(
                    color = p.chord, fontWeight = FontWeight.ExtraBold,
                    background = if (LocalSettingsBox.current) p.chordBg else androidx.compose.ui.graphics.Color.Unspecified,
                )
                withStyle(st) { append("G") }
                append("      ")
                withStyle(st) { append("D/F#") }
            },
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Text("Quando eu ", color = p.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("e|--3--2--", color = p.tab, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(th?.name ?: "Do sistema", color = p.muted, fontSize = 11.sp, maxLines = 1)
    }
}

private val LocalChordColor = androidx.compose.runtime.compositionLocalOf { 0 }
private val LocalSettingsBox = androidx.compose.runtime.compositionLocalOf { true }
