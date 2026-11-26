package io.github.batatinha.pestana.ui

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent as AKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.batatinha.pestana.AppState
import io.github.batatinha.pestana.Metronome
import io.github.batatinha.pestana.Native
import io.github.batatinha.pestana.Rendered
import io.github.batatinha.pestana.Screen
import io.github.batatinha.pestana.Sheet
import io.github.batatinha.pestana.SongPrefs
import io.github.batatinha.pestana.SongRef
import io.github.batatinha.pestana.Source
import io.github.batatinha.pestana.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A tab of the reader. `fallback`: when its page does not exist, show the main
 * sheet instead (simplified ones then simplify the chords themselves).
 */
private class Section(
    val label: String,
    val url: String?,
    val view: Int,
    val simplified: Boolean = false,
    val fallback: Boolean = simplified,
)

private enum class Panel { NONE, KEY, TEXT, MORE }

private val SHARP_KEYS = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLAT_KEYS = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

/** Transpose a key name like "F#m" for display. */
private fun shiftKey(key: String, semis: Int): String {
    if (key.isEmpty()) return key
    val two = key.length > 1 && (key[1] == '#' || key[1] == 'b')
    val root = key.substring(0, if (two) 2 else 1)
    val idx = SHARP_KEYS.indexOf(root).takeIf { it >= 0 } ?: FLAT_KEYS.indexOf(root)
    if (idx < 0) return key
    val minor = key.endsWith("m")
    val n = Math.floorMod(idx + semis, 12)
    val majorPc = if (minor) (n + 3) % 12 else n
    val flats = majorPc in setOf(5, 10, 3, 8, 1)
    return (if (flats) FLAT_KEYS else SHARP_KEYS)[n] + if (minor) "m" else ""
}

@Composable
fun SongScreen(state: AppState, song: SongRef, queue: List<SongRef>) {
    val t = LocalReading.current
    val ctx = LocalContext.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var main by remember(song.id) { mutableStateOf<Sheet?>(null) }
    var error by remember(song.id) { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var prefs by remember(song.id) { mutableStateOf(state.db.prefs(song.id)) }
    var fav by remember(song.id, state.dataVersion) { mutableStateOf(state.db.isFav("song", song.id)) }
    var flags by remember(song.id) { mutableStateOf<Rendered?>(null) }
    var selected by rememberSaveable(song.id) { mutableStateOf(0) }
    val extra = remember(song.id) { mutableStateMapOf<String, Sheet>() }
    val failed = remember(song.id) { mutableStateMapOf<String, Boolean>() }
    var bassSheet by remember(song.id) { mutableStateOf<Sheet?>(null) }
    var bassMissing by remember(song.id) { mutableStateOf(false) }
    var rendered by remember(song.id) { mutableStateOf<Rendered?>(null) }
    var chrome by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var auto by remember { mutableStateOf(false) }
    var chordPopup by remember { mutableStateOf<String?>(null) }
    var addToList by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    @Suppress("DEPRECATION") val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    suspend fun load(force: Boolean) {
        error = null
        val url = song.url
        val cached = if (force) null else state.db.sheet(url)
        val sheet = cached ?: run {
            if (song.isLocal) { error = "Cifra não encontrada."; return }
            runCatching { Source.fetchSheet(ctx, url, song.id, song.title, song.artist) }
                .onSuccess { state.db.saveSheet(it) }
                .getOrElse { error = it.message ?: "Erro ao abrir a cifra."; return }
        }
        main = sheet
        if (force) { extra.clear(); failed.clear(); bassSheet = null; bassMissing = false }
        val ref = SongRef(song.id, sheet.title.ifBlank { song.title }, sheet.artist.ifBlank { song.artist })
        state.db.touchRecent(ref)
        state.db.updateSongMeta(song.id, ref.title, ref.artist)
    }

    LaunchedEffect(song.id) { load(false) }

    // Bass mode reads Cifra Club's bass version (<song>/tabs-baixo/) as the base
    // sheet: Principal, Simplificada, Acordes, Solos and Riffs all come from it.
    val bass = settings.instrument == "bass"
    LaunchedEffect(main, bass) {
        val m = main ?: return@LaunchedEffect
        if (!bass || bassSheet != null || bassMissing) return@LaunchedEffect
        val url = m.versions.firstOrNull(::isBassVersion)?.url
            ?: if (m.url.startsWith(Source.SITE)) Source.bassUrl(m.url) else null
        if (url == null) {
            bassMissing = true
            return@LaunchedEffect
        }
        val s = state.db.sheet(url) ?: runCatching { Source.fetchSheet(ctx, url, song.id, m.title, m.artist) }
            .onSuccess { state.db.saveSheet(it) }.getOrNull()
        if (s != null) bassSheet = s else bassMissing = true
    }
    LaunchedEffect(bass) { selected = 0 }
    val usingBass = bass && bassSheet != null
    val base: Sheet? = when {
        !bass -> main
        bassSheet != null -> bassSheet
        bassMissing -> main
        else -> null // still loading the bass version
    }

    // Which derived sections exist (tabs, solos, riffs) is known from the base text.
    LaunchedEffect(base) {
        val b = base ?: return@LaunchedEffect
        flags = withContext(Dispatchers.Default) { Rendered.parse(Native.render(b.text, b.key, 0, false, 0, true, 0, 1000)) }
    }

    val sections = remember(base, flags) {
        val m = base ?: return@remember emptyList()
        val f = flags
        buildList {
            add(Section("Principal", m.url, 0))
            val simp = m.versions.firstOrNull { it.label.contains("simplif", true) || it.url.contains("simplificada") }
            val simpUrl = simp?.url ?: if (m.url.startsWith(Source.SITE)) Source.simplifiedUrl(m.url) else null
            add(Section("Simplificada", simpUrl, 0, simplified = true))
            if (f != null && f.hasTabs && f.hasChords) add(Section("Acordes", m.url, 1))
            if (f != null && f.hasSolos) add(Section("Solos", m.url, 2))
            if (f != null && f.hasRiffs) add(Section("Riffs", m.url, 3))
            m.versions.filter { v ->
                !v.label.contains("simplif", true) && !v.url.contains("simplificada") && !v.label.contains("principal", true) &&
                    !isBassVersion(v)
            }.forEach { add(Section(it.label.replaceFirstChar { c -> c.uppercase() }, it.url, 0)) }
        }
    }
    val sec = sections.getOrNull(selected) ?: sections.firstOrNull()

    // Load another version's page when its tab is chosen.
    LaunchedEffect(sec?.url, base) {
        val m = base ?: return@LaunchedEffect
        val url = sec?.url ?: return@LaunchedEffect
        if (url == m.url || extra.containsKey(url) || failed.containsKey(url)) return@LaunchedEffect
        val s = state.db.sheet(url) ?: runCatching { Source.fetchSheet(ctx, url, song.id, m.title, m.artist) }
            .onSuccess { state.db.saveSheet(it) }.getOrNull()
        if (s != null) extra[url] = s else failed[url] = true
    }

    val m = main
    val derived = sec != null && sec.fallback && (sec.url == null || failed.containsKey(sec.url))
    val source: Sheet? = when {
        base == null || sec == null -> null
        derived || sec.url == base.url -> base
        else -> extra[sec.url]
    }
    val simplify = prefs.simplify || (derived && sec?.simplified == true)
    val capo = prefs.capo.coerceAtLeast(0)
    val shapeShift = prefs.transpose - capo
    // A bass version is mostly tablature: keep it visible there.
    val showTabs = settings.showTabs || (usingBass && sec?.view == 0)

    // Monospace column count for the current width and font size.
    val measurer = rememberTextMeasurer()
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = settings.fontSize.sp)
    val charW = remember(settings.fontSize) { measurer.measure("0".repeat(40), mono).size.width / 40f }
    var cols by remember { mutableStateOf(0) }

    LaunchedEffect(source, shapeShift, simplify, sec?.view, showTabs, settings.accidentals, cols) {
        val s = source ?: return@LaunchedEffect
        if (cols <= 0) return@LaunchedEffect
        rendered = withContext(Dispatchers.Default) {
            Rendered.parse(Native.render(s.text, s.key, shapeShift, simplify, sec?.view ?: 0, showTabs, settings.accidentals, cols))
        }
    }

    LaunchedEffect(prefs) { state.db.savePrefs(song.id, prefs) }

    // Keep the screen awake while reading.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val lineHeightPx = with(density) { (settings.fontSize * settings.lineSpacing).sp.toPx() }
    LaunchedEffect(auto, prefs.speed, lineHeightPx) {
        if (!auto) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9f
                    scroll.dispatchRawDelta(lineHeightPx * linesPerSecond(prefs.speed) * dt)
                }
                last = now
            }
            if (scroll.value >= scroll.maxValue && scroll.maxValue > 0) auto = false
        }
    }

    fun page(dir: Int) {
        scope.launch { scroll.animateScrollBy(dir * scroll.viewportSize * 0.8f) }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val annotated = remember(rendered, t, settings.fontSize) {
        val r = rendered ?: return@remember AnnotatedString("")
        buildAnnotatedString {
            r.lines.forEachIndexed { i, line ->
                for (sp in line.spans) {
                    when (sp.style) {
                        Style.CHORD -> withLink(
                            LinkAnnotation.Clickable(
                                sp.text,
                                TextLinkStyles(
                                    SpanStyle(color = t.chord, fontWeight = FontWeight.ExtraBold),
                                ),
                            ) { chordPopup = sp.text },
                        ) { append(sp.text) }
                        Style.HEADER -> withStyle(SpanStyle(color = t.header, fontWeight = FontWeight.SemiBold)) { append(sp.text) }
                        Style.TAB -> withStyle(SpanStyle(color = t.tab.copy(alpha = 0.75f))) { append(sp.text) }
                        Style.TAB_NOTE -> withStyle(SpanStyle(color = t.chord, fontWeight = FontWeight.Bold)) { append(sp.text) }
                        Style.TAB_TECH -> withStyle(SpanStyle(color = t.chord)) { append(sp.text) }
                        Style.NOTE -> withStyle(SpanStyle(color = t.muted, fontStyle = FontStyle.Italic)) { append(sp.text) }
                        else -> append(sp.text)
                    }
                }
                if (i < r.lines.lastIndex) append('\n')
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(t.bg).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.nativeKeyEvent.keyCode) {
                AKeyEvent.KEYCODE_PAGE_DOWN, AKeyEvent.KEYCODE_DPAD_DOWN, AKeyEvent.KEYCODE_DPAD_RIGHT, AKeyEvent.KEYCODE_SPACE -> { page(1); true }
                AKeyEvent.KEYCODE_PAGE_UP, AKeyEvent.KEYCODE_DPAD_UP, AKeyEvent.KEYCODE_DPAD_LEFT -> { page(-1); true }
                AKeyEvent.KEYCODE_VOLUME_DOWN -> if (settings.volumeKeys) { page(1); true } else false
                AKeyEvent.KEYCODE_VOLUME_UP -> if (settings.volumeKeys) { page(-1); true } else false
                AKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AKeyEvent.KEYCODE_ENTER -> { auto = !auto; true }
                else -> false
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(chrome, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.background(t.bg)) {
                    TopBar(
                        m?.title?.ifBlank { null } ?: song.title,
                        m?.artist?.ifBlank { null } ?: song.artist,
                        onBack = { state.back() },
                    ) {
                        FavButton(fav) {
                            fav = state.toggleFavSong(SongRef(song.id, m?.title ?: song.title, m?.artist ?: song.artist))
                        }
                    }
                    if (sections.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            sections.forEachIndexed { i, s -> Pill(s.label, i == selected) { selected = i } }
                        }
                    }
                    HorizontalDivider(color = t.line, thickness = 0.5.dp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (!chrome) Spacer(Modifier.statusBarsPadding())
            if (refreshing || (m != null && source == null)) LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.chord, trackColor = t.bg)

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val avail = with(density) { (maxWidth - 32.dp).toPx() }
                LaunchedEffect(avail, charW) { cols = (avail / charW).toInt().coerceAtLeast(12) }
                when {
                    error != null && m == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState("Não deu para abrir", error ?: "")
                        Pill("Tentar de novo", false) { scope.launch { load(true) } }
                    }
                    m == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp), color = t.chord, trackColor = t.bg)
                    else -> Column(
                        Modifier.fillMaxSize().verticalScroll(scroll)
                            .pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome; panel = Panel.NONE }) }
                            .padding(horizontal = 16.dp),
                    ) {
                        Spacer(Modifier.height(10.dp))
                        InfoLine(base ?: m, rendered, prefs, capo) { chrome = true; panel = Panel.KEY }
                        if (bass && bassMissing) {
                            Text(
                                "Esta cifra não tem versão para baixo no site. Mostrando a cifra com diagramas de baixo.",
                                color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        val r = rendered
                        if (settings.showDiagrams && r != null && r.chords.isNotEmpty() && sec?.view != 2 && sec?.view != 3) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                r.chords.forEach { c ->
                                    Box(Modifier.clickable { chordPopup = c }) { ChordDiagram(c, 54.dp, settings.leftHanded, bass = bass) }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(10.dp))
                        }
                        if (r != null && r.lines.isEmpty()) {
                            EmptyState("Nada aqui", "Esta parte não existe nesta versão da cifra.")
                        }
                        Text(
                            annotated,
                            style = mono.copy(color = t.text, lineHeight = (settings.fontSize * settings.lineSpacing).sp),
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                        Footer(state, song, queue)
                        Spacer(Modifier.height(if (chrome) 160.dp else 80.dp))
                    }
                }
            }
        }

        // Bottom controls
        AnimatedVisibility(
            chrome, Modifier.align(Alignment.BottomCenter),
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        ) {
            Controls(
                state, prefs, rendered, auto, panel,
                onPrefs = { prefs = it },
                onAuto = { auto = !auto; if (auto) panel = Panel.NONE },
                onPanel = { panel = if (panel == it) Panel.NONE else it },
                onAddToList = { addToList = true },
                onRefresh = {
                    refreshing = true
                    scope.launch { load(true); refreshing = false }
                },
                onOpenWeb = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sec?.url ?: song.url))) } },
                onDelete = {
                    state.db.deleteSong(song.id); state.changed(); state.back()
                },
                onDiagnostic = {
                    val s = source
                    if (s != null) {
                        clipboard.setText(AnnotatedString(diagnostic(s)))
                        state.toast = "Texto copiado. Cole na conversa para análise."
                    }
                },
                isLocal = song.isLocal,
            )
        }
        // Minimal floating pause button while reading full screen.
        if (!chrome && auto) {
            Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(t.surface2).clickable { auto = false },
                    contentAlignment = Alignment.Center,
                ) { Icon(Ic.Pause, "Pausar", tint = t.chord) }
            }
        }
    }

    chordPopup?.let { name ->
        Dialog(onDismissRequest = { chordPopup = null }) {
            Surface(shape = RoundedCornerShape(20.dp), color = t.surface) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ChordDiagram(name, 170.dp, settings.leftHanded, bass = settings.instrument == "bass")
                    Spacer(Modifier.height(8.dp))
                    Text("Toque fora para fechar", color = t.muted, fontSize = 12.sp)
                }
            }
        }
    }
    if (addToList) {
        AddToPlaylistDialog(state, SongRef(song.id, m?.title ?: song.title, m?.artist ?: song.artist)) { addToList = false }
    }
}

@Composable
private fun InfoLine(m: Sheet, r: Rendered?, prefs: SongPrefs, capo: Int, onKey: () -> Unit) {
    val t = LocalReading.current
    val parts = buildList {
        if (r != null && r.key.isNotEmpty()) {
            val sounding = shiftKey(r.origKey, prefs.transpose)
            if (capo > 0) add("Formas em ${r.key} · capo ${capo}ª casa (soa $sounding)")
            else add("Tom: ${r.key}" + if (prefs.transpose != 0) " (original ${r.origKey})" else "")
        }
        if (m.capo > 0 && capo == 0) add("Capotraste na ${m.capo}ª casa")
        if (m.tuning.isNotBlank()) add("Afinação: ${m.tuning}")
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("   ·   "), color = t.muted, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onKey).padding(vertical = 4.dp),
    )
}

@Composable
private fun Footer(state: AppState, song: SongRef, queue: List<SongRef>) {
    val t = LocalReading.current
    val i = queue.indexOfFirst { it.id == song.id }
    if (i < 0 || queue.size < 2) return
    Spacer(Modifier.height(28.dp))
    HorizontalDivider(color = t.line)
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (i > 0) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { state.replace(Screen.Song(queue[i - 1], queue)) }.padding(8.dp)) {
                Text("‹ Anterior", color = t.muted, fontSize = 12.sp)
                Text(queue[i - 1].title, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text("${i + 1}/${queue.size}", color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
        if (i < queue.lastIndex) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { state.replace(Screen.Song(queue[i + 1], queue)) }.padding(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text("Próxima ›", color = t.chord, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(queue[i + 1].title, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Controls(
    state: AppState,
    prefs: SongPrefs,
    r: Rendered?,
    auto: Boolean,
    panel: Panel,
    onPrefs: (SongPrefs) -> Unit,
    onAuto: () -> Unit,
    onPanel: (Panel) -> Unit,
    onAddToList: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWeb: () -> Unit,
    onDelete: () -> Unit,
    onDiagnostic: () -> Unit,
    isLocal: Boolean,
) {
    val t = LocalReading.current
    val s = state.settings
    Surface(color = t.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding()) {
            when (panel) {
                Panel.KEY -> Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Stepper("Tom", if (r == null) "" else shiftKey(r.origKey, prefs.transpose).ifEmpty { "${prefs.transpose}" },
                        { onPrefs(prefs.copy(transpose = Math.floorMod(prefs.transpose + 11 + 6, 12) - 6)) },
                        { onPrefs(prefs.copy(transpose = Math.floorMod(prefs.transpose + 1 + 6, 12) - 6)) })
                    Stepper("Capotraste", if (prefs.capo <= 0) "sem" else "${prefs.capo}ª",
                        { onPrefs(prefs.copy(capo = (prefs.capo - 1).coerceAtLeast(0))) },
                        { onPrefs(prefs.copy(capo = (prefs.capo.coerceAtLeast(0) + 1).coerceAtMost(11))) })
                    ToggleRow("Simplificar acordes", prefs.simplify) { onPrefs(prefs.copy(simplify = it)) }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Acidentes", color = t.text, modifier = Modifier.weight(1f))
                        Pill("Auto", s.accidentals == 0) { s.putAccidentals(0) }
                        Pill("♯", s.accidentals == 1) { s.putAccidentals(1) }
                        Pill("♭", s.accidentals == 2) { s.putAccidentals(2) }
                    }
                    if (prefs.transpose != 0 || prefs.capo > 0) {
                        Spacer(Modifier.height(6.dp))
                        Pill("Voltar ao original", false) { onPrefs(prefs.copy(transpose = 0, capo = 0)) }
                    }
                }
                Panel.TEXT -> Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Stepper("Tamanho", "${s.fontSize.toInt()}", { s.putFontSize(s.fontSize - 1) }, { s.putFontSize(s.fontSize + 1) })
                    Stepper("Espaçamento", "%.2f".format(s.lineSpacing), { s.putLineSpacing(s.lineSpacing - 0.05f) }, { s.putLineSpacing(s.lineSpacing + 0.05f) })
                    Spacer(Modifier.height(6.dp))
                    OptionRow("Acordes") {
                        ChordColors.all.forEachIndexed { i, (name, _) -> Pill(name, s.chordColor == i) { s.putChordColor(i) } }
                    }
                    OptionRow("Letra") {
                        LyricColors.all.forEachIndexed { i, (name, _) -> Pill(name, s.lyricColor == i) { s.putLyricColor(i) } }
                    }
                    OptionRow("Tema") {
                        Pill("Sistema", s.theme == "auto") { s.putTheme("auto") }
                        Themes.all.forEach { th -> Pill(th.name, s.theme == th.id) { s.putTheme(th.id) } }
                    }
                }
                Panel.MORE -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Instrumento", color = t.muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
                        io.github.batatinha.pestana.Instruments.all.forEach { (id, label) -> Pill(label, s.instrument == id) { s.putInstrument(id) } }
                    }
                    ToggleRow("Canhoto", s.leftHanded) { s.putLeftHanded(it) }
                    ToggleRow("Tablaturas", s.showTabs) { s.putShowTabs(it) }
                    ToggleRow("Diagramas dos acordes", s.showDiagrams) { s.putShowDiagrams(it) }
                    ToggleRow("Metrônomo (${state.settings.bpm} BPM)", Metronome.playing) {
                        Metronome.bpm = s.bpm; Metronome.beats = s.beats; Metronome.subdivision = s.subdivision; Metronome.accent = s.accent
                        if (it) Metronome.start() else Metronome.stop()
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Adicionar à playlist", false, onAddToList)
                        if (!isLocal) Pill("Baixar de novo", false, onRefresh)
                        if (!isLocal) Pill("Abrir no site", false, onOpenWeb)
                        if (isLocal) Pill("Apagar cifra", false, onDelete)
                        Pill("Copiar para diagnóstico", false, onDiagnostic)
                    }
                }
                Panel.NONE -> {}
            }
            if (panel != Panel.NONE) HorizontalDivider(color = t.line, thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(t.chord).clickable(onClick = onAuto),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (auto) Ic.Pause else Ic.Play, if (auto) "Pausar rolagem" else "Rolagem automática", tint = t.bg) }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Slider(
                    value = prefs.speed.toFloat(),
                    onValueChange = { onPrefs(prefs.copy(speed = it.toInt().coerceIn(1, 40))) },
                    valueRange = 1f..40f,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = t.chord, activeTrackColor = t.chord, inactiveTrackColor = t.surface2,
                    ),
                )
                Text(
                    "${prefs.speed}", color = t.text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.widthIn(min = 26.dp).padding(start = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                BarButton(r?.key?.ifEmpty { "Tom" } ?: "Tom", panel == Panel.KEY) { onPanel(Panel.KEY) }
                IconButton(onClick = { onPanel(Panel.TEXT) }) { Icon(Ic.Text, "Texto e tema", tint = if (panel == Panel.TEXT) t.chord else t.text) }
                IconButton(onClick = { onPanel(Panel.MORE) }) { Icon(Ic.More, "Mais opções", tint = if (panel == Panel.MORE) t.chord else t.text) }
            }
        }
    }
}

@Composable
private fun BarButton(label: String, active: Boolean, onClick: () -> Unit) {
    val t = LocalReading.current
    Box(
        Modifier.height(36.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(18.dp)).background(if (active) t.surface2 else t.surface)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = t.chord, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1) }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val t = LocalReading.current
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.text, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) { Icon(Ic.Minus, "Diminuir", tint = t.text) }
        Text(value, color = t.chord, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onPlus) { Icon(Ic.Plus, "Aumentar", tint = t.text) }
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    val t = LocalReading.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = t.text, fontSize = 15.sp)
            if (sub != null) Text(sub, color = t.muted, fontSize = 12.sp)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = t.chord, checkedThumbColor = t.bg, uncheckedTrackColor = t.surface2))
    }
}

/** The raw sheet with invisible characters spelled out, to debug parsing. */
private fun diagnostic(s: Sheet): String = buildString {
    append("Pestana diagnóstico\n")
    append("url: ").append(s.url).append('\n')
    append("tom: ").append(s.key).append("  capo: ").append(s.capo).append('\n')
    append("versões: ").append(s.versions.joinToString { it.label + " " + it.url }).append("\n---\n")
    for (c in s.text) {
        val type = Character.getType(c).toByte()
        val odd = c != '\n' && (c.code < 32 || c.code in 0x7f..0xa0 || type == Character.FORMAT ||
            type == Character.SPACE_SEPARATOR && c != ' ')
        if (odd) append("<U+").append(c.code.toString(16).uppercase().padStart(4, '0')).append('>') else append(c)
    }
}

private fun isBassVersion(v: io.github.batatinha.pestana.Version) =
    v.label.contains("baixo", true) || v.url.contains("baixo", true) || v.url.contains("bass", true)

/** A labelled, horizontally scrolling row of pills with room to breathe. */
@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    val t = LocalReading.current
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.muted, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/**
 * Auto-scroll rate for the 1..40 slider. Up to 10 it grows evenly (lyrics with
 * chords); above that it grows faster, up to 8 lines a second, because a page
 * of tablature has many more lines for the same stretch of music.
 */
internal fun linesPerSecond(speed: Int): Float {
    val s = speed.coerceIn(1, 40)
    return if (s <= 10) s * 0.045f else 0.45f * Math.pow(8.0 / 0.45, (s - 10) / 30.0).toFloat()
}
