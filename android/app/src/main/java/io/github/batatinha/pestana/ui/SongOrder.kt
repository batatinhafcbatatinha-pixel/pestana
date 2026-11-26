package io.github.batatinha.pestana.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.batatinha.pestana.AppState
import io.github.batatinha.pestana.Db
import io.github.batatinha.pestana.Native
import io.github.batatinha.pestana.SongRef
import io.github.batatinha.pestana.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** How a list of songs is ordered. Tapping the chosen order again turns it around. */
enum class Sort(val label: String) {
    /** The order the list came in; each screen names it. */
    MANUAL(""),
    PLAYED("Tocadas por último"),
    RELEASE("Lançamento"),
    TITLE("A–Z"),
    ARTIST("Artista"),
    ALBUM("Álbum"),
}

/** What a downloaded sheet tells about its song, for the filters. */
private class SheetFacts(val solo: Boolean, val riff: Boolean, val capo: Boolean, val tuning: String)

private val factsCache = HashMap<String, SheetFacts>()

/** Null while the song's sheet is not on the phone. */
private fun sheetFacts(db: Db, s: SongRef): SheetFacts? {
    val sheet = db.sheet(s.url) ?: return null
    val cacheKey = s.url + "|" + sheet.text.length
    synchronized(factsCache) { factsCache[cacheKey] }?.let { return it }
    // The solos view is the shortest thing to render; the flags cover the whole sheet.
    val r = JSONObject(Native.render(sheet.text, sheet.key, 0, false, 2, true, 0, 1000))
    val facts = SheetFacts(r.optBoolean("hasSolos"), r.optBoolean("hasRiffs"), sheet.capo > 0, sheet.tuning.trim())
    synchronized(factsCache) { factsCache[cacheKey] = facts }
    return facts
}

/** A list of songs as ordered and filtered, with the pills that choose how. */
class SongOrder(
    /** The songs to show, in order. */
    val shown: List<SongRef>,
    /** Whether a filter is hiding some of the songs. */
    val filtering: Boolean,
    private val sort: Sort,
    private val info: Map<String, Db.SongInfo>,
    /** The rows of pills: orders, then filters. */
    val bar: @Composable () -> Unit,
) {
    /** Ordered by album or release, each row says which album and year put it there. */
    fun subtitle(s: SongRef): String {
        val album = info[s.id]?.takeIf { sort == Sort.RELEASE || sort == Sort.ALBUM }
        return listOf(s.artist, album?.album ?: "", album?.year?.takeIf { it > 0 }?.toString() ?: "").filter { it.isNotBlank() }.joinToString(" · ")
    }
}

/**
 * Order and filters for `songs`. The order is remembered under `key`; with
 * `active` false the list is left exactly as it came (while it is being edited).
 */
@Composable
fun rememberSongOrder(
    state: AppState,
    key: String,
    songs: List<SongRef>,
    firstLabel: String,
    sorts: List<Sort> = Sort.entries,
    active: Boolean = true,
): SongOrder {
    val t = LocalReading.current
    val ctx = LocalContext.current
    val saved = remember { state.app.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var sort by remember(key) {
        mutableStateOf(Sort.entries.getOrElse(saved.getInt("sort.$key", 0)) { Sort.MANUAL }.takeIf { it in sorts } ?: Sort.MANUAL)
    }
    var reversed by remember(key) { mutableStateOf(saved.getBoolean("reversed.$key", false)) }
    var onlySolo by rememberSaveable(key) { mutableStateOf(false) }
    var onlyRiff by rememberSaveable(key) { mutableStateOf(false) }
    var onlyCapo by rememberSaveable(key) { mutableStateOf(false) }
    var tuning by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var facts by remember { mutableStateOf<Map<String, SheetFacts>>(emptyMap()) }
    var info by remember { mutableStateOf(state.db.songInfo()) }
    var lookingUp by remember { mutableStateOf(false) }
    val played = remember(state.dataVersion) { state.db.lastPlayed() }

    fun choose(s: Sort) {
        if (s == sort) reversed = !reversed else { sort = s; reversed = false }
        saved.edit().putInt("sort.$key", sort.ordinal).putBoolean("reversed.$key", reversed).apply()
    }

    // Solos, riffs, capo and tuning come from the sheets saved on the phone.
    LaunchedEffect(songs, state.dataVersion) {
        facts = withContext(Dispatchers.Default) {
            buildMap { songs.forEach { s -> runCatching { sheetFacts(state.db, s) }.getOrNull()?.let { put(s.id, it) } } }
        }
    }

    // Albums and years are asked from the search, once per song, when an order needs them.
    LaunchedEffect(sort, songs) {
        if (sort != Sort.RELEASE && sort != Sort.ALBUM) return@LaunchedEffect
        val todo = songs.filter { !it.isLocal && !it.id.startsWith("web/") && !info.containsKey(it.id) }
        if (todo.isEmpty()) return@LaunchedEffect
        lookingUp = true
        try {
            for (s in todo) {
                val found = Source.songAlbum(ctx, s) ?: continue
                state.db.saveSongInfo(s.id, found)
                info = info + (s.id to found)
            }
        } finally {
            lookingUp = false
        }
    }

    // Tunings other than the standard one that the list actually has.
    val tunings = remember(facts, songs) { songs.mapNotNull { facts[it.id]?.tuning }.filter { it.isNotBlank() }.distinct().sorted() }
    // A tuning that left the list stops filtering.
    val wanted = tuning?.takeIf { it.isEmpty() || it in tunings }
    val filtering = active && (onlySolo || onlyRiff || onlyCapo || wanted != null)

    val shown = remember(songs, sort, reversed, onlySolo, onlyRiff, onlyCapo, wanted, facts, info, played, active) {
        if (!active) return@remember songs
        val kept = songs.filter { s ->
            val f = facts[s.id]
            (!onlySolo || f?.solo == true) && (!onlyRiff || f?.riff == true) && (!onlyCapo || f?.capo == true) &&
                (wanted == null || (f != null && f.tuning == wanted))
        }
        val byTitle = compareBy<SongRef, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        // Songs the order knows nothing about (no album found, never played) stay at the end either way.
        val (known, unknown) = when (sort) {
            Sort.MANUAL -> kept to emptyList()
            Sort.PLAYED -> kept.partition { played.containsKey(it.id) }.let { (a, b) -> a.sortedByDescending { played[it.id] } to b }
            Sort.RELEASE -> kept.partition { (info[it.id]?.year ?: 0) > 0 }.let { (a, b) -> a.sortedByDescending { info[it.id]?.year } to b }
            Sort.TITLE -> kept.sortedWith(byTitle) to emptyList()
            Sort.ARTIST -> kept.sortedWith(compareBy<SongRef, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.then(byTitle)) to emptyList()
            Sort.ALBUM -> kept.partition { !info[it.id]?.album.isNullOrBlank() }.let { (a, b) ->
                a.sortedWith(compareBy<SongRef, String>(String.CASE_INSENSITIVE_ORDER) { info[it.id]?.album ?: "" }) to b
            }
        }
        (if (reversed) known.reversed() else known) + unknown
    }

    val bar: @Composable () -> Unit = {
        var sortMenu by remember { mutableStateOf(false) }
        var filterMenu by remember { mutableStateOf(false) }
        fun name(s: Sort) = if (s == Sort.MANUAL) firstLabel else s.label
        val filters = listOf(onlySolo, onlyRiff, onlyCapo, wanted != null).count { it }
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Pill(name(sort), false) { sortMenu = true }
                    DropdownMenu(sortMenu, { sortMenu = false }, containerColor = t.surface) {
                        sorts.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(name(s), color = t.text, fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) },
                                trailingIcon = { if (s == sort) Icon(Ic.Check, null, Modifier.size(18.dp), tint = t.chord) },
                                // Picking the order already chosen turns it around.
                                onClick = { choose(s); sortMenu = false },
                            )
                        }
                    }
                }
                // The arrow shows which way the order runs; tapping it turns the order around.
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(t.surface).clickable { choose(sort) },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (reversed) Ic.Up else Ic.Down, if (reversed) "Ordem invertida" else "Inverter a ordem", Modifier.size(18.dp), tint = t.text) }
                Spacer(Modifier.weight(1f))
                if (facts.isNotEmpty()) Box {
                    Pill(if (filters == 0) "Filtros" else "Filtros · $filters", filters > 0) { filterMenu = true }
                    DropdownMenu(filterMenu, { filterMenu = false }, containerColor = t.surface) {
                        @Composable
                        fun option(label: String, on: Boolean, toggle: () -> Unit) = DropdownMenuItem(
                            text = { Text(label, color = t.text, fontWeight = FontWeight.Normal) },
                            trailingIcon = { if (on) Icon(Ic.Check, null, Modifier.size(18.dp), tint = t.chord) },
                            onClick = toggle,
                        )
                        option("Com solo", onlySolo) { onlySolo = !onlySolo }
                        option("Com riff", onlyRiff) { onlyRiff = !onlyRiff }
                        option("Com capotraste", onlyCapo) { onlyCapo = !onlyCapo }
                        if (tunings.isNotEmpty()) {
                            HorizontalDivider(color = t.line, thickness = 0.5.dp)
                            option("Afinação padrão", wanted == "") { tuning = if (wanted == "") null else "" }
                            tunings.forEach { tn -> option(tn, wanted == tn) { tuning = if (wanted == tn) null else tn } }
                        }
                        if (filters > 0) {
                            HorizontalDivider(color = t.line, thickness = 0.5.dp)
                            DropdownMenuItem(
                                text = { Text("Limpar filtros", color = t.chord) },
                                onClick = { onlySolo = false; onlyRiff = false; onlyCapo = false; tuning = null; filterMenu = false },
                            )
                        }
                    }
                }
            }
            if (lookingUp) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), color = t.chord, trackColor = t.bg)
        }
    }
    return SongOrder(shown, filtering, sort, info, bar)
}
