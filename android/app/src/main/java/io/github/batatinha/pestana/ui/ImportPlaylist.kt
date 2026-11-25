package io.github.batatinha.pestana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.batatinha.pestana.AppState
import io.github.batatinha.pestana.Screen
import io.github.batatinha.pestana.SongRef
import io.github.batatinha.pestana.Source
import kotlinx.coroutines.launch

/**
 * Imports Cifra Club playlists into local playlists: paste the link of one
 * playlist, or the link of a profile to pick among its public playlists.
 */
@Composable
fun ImportPlaylistScreen(state: AppState, initialLink: String) {
    val t = LocalReading.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf(initialLink) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<Source.PlaylistPage?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var offline by rememberSaveable { mutableStateOf(true) }
    val picked = remember { mutableStateListOf<String>() }

    fun read() {
        val l = link.trim()
        if (!l.startsWith("http")) { status = "Cole o link da playlist ou do perfil"; return }
        busy = true; status = "Lendo a página…"; page = null
        scope.launch {
            runCatching { Source.readPlaylistPage(ctx, l) }
                .onSuccess { p ->
                    page = p
                    name = p.title.ifBlank { "Playlist importada" }
                    picked.clear(); picked.addAll(p.playlists.map { it.url })
                    status = null
                }
                .onFailure { status = it.message ?: "Não consegui ler esse link." }
            busy = false
        }
    }

    fun save(listName: String, songs: List<SongRef>): Long {
        val id = state.db.createPlaylist(listName)
        songs.forEach { state.db.addToPlaylist(id, it) }
        return id
    }

    fun importAll() {
        val p = page ?: return
        busy = true
        scope.launch {
            val all = ArrayList<SongRef>()
            if (p.songs.isNotEmpty()) {
                val id = save(name.trim().ifBlank { "Playlist importada" }, p.songs)
                all += p.songs
                state.changed()
                if (offline) state.downloadForOffline(all)
                state.replace(Screen.PlaylistDetail(id, name.trim()))
            } else {
                val chosen = p.playlists.filter { it.url in picked }
                var done = 0
                for (pl in chosen) {
                    status = "Importando ${pl.label} (${done + 1}/${chosen.size})…"
                    runCatching { Source.readPlaylistPage(ctx, pl.url) }.onSuccess { sub ->
                        if (sub.songs.isNotEmpty()) {
                            save(pl.label, sub.songs)
                            all += sub.songs
                        }
                    }
                    done++
                }
                state.changed()
                state.toast = "${chosen.size} playlists importadas"
                if (offline && all.isNotEmpty()) state.downloadForOffline(all.distinctBy { it.id })
                state.tab(Screen.Library)
            }
            busy = false
        }
    }

    LaunchedEffect(initialLink) { if (initialLink.isNotBlank()) read() }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Importar playlist", "Cifra Club → seu aparelho", onBack = { state.back() })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.chord, trackColor = t.bg)
        LazyColumn(Modifier.weight(1f)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "Cole o link de uma playlist pública, ou do seu perfil para escolher várias. Não precisa entrar na conta.",
                        color = t.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(link, { link = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("https://www.cifraclub.com.br/…") })
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = ::read, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = t.chord, contentColor = t.bg)) { Text("Ler") }
                    }
                    status?.let { Text(it, color = t.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            val p = page
            if (p != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Baixar cifras para usar offline", color = t.text, fontSize = 15.sp)
                            Text("Ficam salvas no aparelho", color = t.muted, fontSize = 12.sp)
                        }
                        Switch(offline, { offline = it }, colors = SwitchDefaults.colors(checkedTrackColor = t.chord, checkedThumbColor = t.bg))
                    }
                }
                if (p.songs.isNotEmpty()) {
                    item {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, label = { Text("Nome da playlist") })
                        SectionTitle("${p.songs.size} músicas")
                    }
                    items(p.songs, key = { it.id }) { s -> ListRow(s.title, s.artist, leading = { SongThumb(s) }) {} }
                } else {
                    item { SectionTitle("${p.playlists.size} playlists neste perfil") }
                    items(p.playlists, key = { it.url }) { pl ->
                        ListRow(pl.label, null, leading = { Monogram(pl.label, Ic.Playlist, round = false) }, trailing = {
                            Checkbox(pl.url in picked, { on -> if (on) picked.add(pl.url) else picked.remove(pl.url) },
                                colors = CheckboxDefaults.colors(checkedColor = t.chord, checkmarkColor = t.bg))
                        }) { if (pl.url in picked) picked.remove(pl.url) else picked.add(pl.url) }
                    }
                }
            }
        }
        val p = page
        if (p != null) {
            Column(Modifier.fillMaxWidth().background(t.bg).navigationBarsPadding().padding(16.dp)) {
                val n = if (p.songs.isNotEmpty()) p.songs.size else picked.size
                Button(
                    onClick = ::importAll,
                    enabled = !busy && n > 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = t.chord, contentColor = t.bg),
                ) { Text(if (p.songs.isNotEmpty()) "Importar $n músicas" else "Importar $n playlists") }
            }
        }
    }
}
