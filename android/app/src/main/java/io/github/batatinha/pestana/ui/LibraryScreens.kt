package io.github.batatinha.pestana.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.batatinha.pestana.AlbumRef
import io.github.batatinha.pestana.AppState
import io.github.batatinha.pestana.ArtistRef
import io.github.batatinha.pestana.Screen
import io.github.batatinha.pestana.Sheet
import io.github.batatinha.pestana.SongRef
import io.github.batatinha.pestana.Source
import kotlinx.coroutines.launch

private val LIB_TABS = listOf("Músicas", "Artistas", "Álbuns", "Playlists", "Minhas cifras")

@Composable
fun LibraryScreen(state: AppState) {
    val t = LocalReading.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var newList by remember { mutableStateOf(false) }
    // Playlists picked by holding one: the top bar then offers to delete them.
    var picked by remember { mutableStateOf(setOf<Long>()) }
    var deletingPicked by remember { mutableStateOf(false) }
    BackHandler(enabled = picked.isNotEmpty()) { picked = emptySet() }
    val v = state.dataVersion
    val favSongs = remember(v, tab) { if (tab == 0) state.db.favs("song").map { SongRef(it.id, it.title, it.sub) } else emptyList() }
    val favOrder = rememberSongOrder(state, "favs", favSongs, "Ordem padrão")
    Column(Modifier.fillMaxSize()) {
        if (picked.isNotEmpty()) {
            TopBar(if (picked.size == 1) "1 playlist selecionada" else "${picked.size} playlists selecionadas", null, onBack = { picked = emptySet() }) {
                IconButton(onClick = { deletingPicked = true }) { Icon(Ic.Trash, "Apagar selecionadas", tint = t.text) }
            }
        } else {
            TopBar("Biblioteca", "Salva só neste aparelho") {
                if (tab == 3) IconButton(onClick = { newList = true }) { Icon(Ic.Plus, "Nova playlist", tint = t.text) }
                if (tab == 4) IconButton(onClick = { state.go(Screen.Import()) }) { Icon(Ic.Plus, "Importar cifra", tint = t.text) }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LIB_TABS.forEachIndexed { i, s -> Pill(s, tab == i) { tab = i; picked = emptySet() } }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            when (tab) {
                0 -> {
                    val songs = favOrder.shown
                    if (favSongs.isEmpty()) item { EmptyState("Nenhuma música favorita", "Toque no coração ao abrir uma cifra.") }
                    if (favSongs.isNotEmpty()) item { favOrder.bar() }
                    if (favSongs.isNotEmpty() && songs.isEmpty()) item { EmptyState("Nenhuma música com esses filtros", "Os filtros olham as cifras salvas no aparelho.") }
                    items(songs, key = { it.id }) { s ->
                        ListRow(s.title, favOrder.subtitle(s), leading = { SongThumb(s) }) { state.go(Screen.Song(s, songs)) }
                    }
                }
                1 -> {
                    val artists = state.db.favs("artist")
                    if (artists.isEmpty()) item { EmptyState("Nenhum artista favorito", "Favorite artistas pela busca.") }
                    items(artists, key = { it.id }) { a ->
                        ListRow(a.title, null, leading = { Monogram(a.title, url = io.github.batatinha.pestana.Images.artist(a.id)) }) { state.go(Screen.Artist(ArtistRef(a.id, a.title))) }
                    }
                }
                2 -> {
                    val albums = state.db.favs("album")
                    if (albums.isEmpty()) item { EmptyState("Nenhum álbum favorito", "Favorite álbuns pela busca ou na página do artista.") }
                    items(albums, key = { it.id }) { a ->
                        val (artistSlug, slug) = a.id.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                        ListRow(a.title, listOf(a.sub, a.extra).filter { it.isNotBlank() }.joinToString(" · "), leading = { Monogram(a.title, Ic.Album, round = false, url = io.github.batatinha.pestana.Images.album(a.id)) }) {
                            state.go(Screen.Album(AlbumRef(slug, a.title, a.sub, artistSlug, a.extra)))
                        }
                    }
                }
                3 -> {
                    val lists = state.db.playlists()
                    if (lists.isEmpty()) item { EmptyState("Nenhuma playlist", "Crie listas para ensaios, shows ou estudo. Toque em + acima.") }
                    items(lists, key = { it.id }) { p ->
                        ListRow(
                            p.name, if (p.count == 1) "1 música" else "${p.count} músicas",
                            leading = { Monogram(p.name, if (p.id in picked) Ic.Check else Ic.Playlist, round = false) },
                            selected = p.id in picked,
                            onLongClick = { picked = if (p.id in picked) picked - p.id else picked + p.id },
                        ) {
                            // While picking, a tap picks too.
                            if (picked.isEmpty()) state.go(Screen.PlaylistDetail(p.id, p.name))
                            else picked = if (p.id in picked) picked - p.id else picked + p.id
                        }
                    }
                }
                4 -> {
                    val local = state.db.localSongs()
                    if (local.isEmpty()) item { EmptyState("Nenhuma cifra própria", "Importe colando o texto de uma cifra ou um link.") }
                    items(local, key = { it.id }) { SongRow(state, it, local) }
                }
            }
            item { Spacer(Modifier.height(v.coerceAtMost(0).dp)) }
        }
    }
    if (deletingPicked) {
        val n = picked.size
        AlertDialog(
            onDismissRequest = { deletingPicked = false }, containerColor = t.surface,
            title = { Text(if (n == 1) "Apagar a playlist selecionada?" else "Apagar as $n playlists selecionadas?") },
            text = { Text("As cifras continuam salvas; só as listas são apagadas.") },
            confirmButton = {
                TextButton(onClick = {
                    picked.forEach { state.db.deletePlaylist(it) }
                    picked = emptySet(); deletingPicked = false; state.changed()
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { deletingPicked = false }) { Text("Cancelar") } },
        )
    }
    if (newList) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newList = false },
            containerColor = t.surface,
            title = { Text("Nova playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Nome") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    state.db.createPlaylist(name.trim()); state.changed(); newList = false
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { newList = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun PlaylistScreen(state: AppState, id: Long, initialName: String) {
    val t = LocalReading.current
    var name by remember { mutableStateOf(initialName) }
    var songs by remember(state.dataVersion) { mutableStateOf(state.db.playlistSongs(id)) }
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // Moving songs up and down is done on the list as it was put together.
    val order = rememberSongOrder(state, "playlist$id", songs, "Ordem da lista", active = !editing)
    val shown = order.shown
    val filtering = order.filtering

    Column(Modifier.fillMaxSize()) {
        val count = if (songs.size == 1) "1 música" else "${songs.size} músicas"
        TopBar(name, if (filtering && !editing) "${shown.size} de $count" else count, onBack = { state.back() }) {
            IconButton(onClick = { editing = !editing }) { Icon(if (editing) Ic.Check else Ic.Edit, "Editar", tint = if (editing) t.chord else t.text) }
        }
        if (editing) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Renomear", false) { renaming = true }
                Pill("Apagar playlist", false) { deleting = true }
            }
        } else if (songs.isNotEmpty()) {
            order.bar()
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            if (songs.isEmpty()) item { EmptyState("Playlist vazia", "Abra uma cifra e use ⋯ › Adicionar à playlist.") }
            if (songs.isNotEmpty() && shown.isEmpty()) item { EmptyState("Nenhuma música com esses filtros", "Os filtros olham as cifras salvas no aparelho.") }
            itemsIndexed(shown, key = { _, s -> s.id }) { i, s ->
                if (editing) {
                    ListRow(s.title, s.artist, leading = { SongThumb(s) }, trailing = {
                        Row {
                            IconButton(enabled = i > 0, onClick = {
                                songs = songs.toMutableList().apply { add(i - 1, removeAt(i)) }
                                state.db.reorderPlaylist(id, songs)
                            }) { Icon(Ic.Up, "Subir", tint = t.muted) }
                            IconButton(enabled = i < songs.lastIndex, onClick = {
                                songs = songs.toMutableList().apply { add(i + 1, removeAt(i)) }
                                state.db.reorderPlaylist(id, songs)
                            }) { Icon(Ic.Down, "Descer", tint = t.muted) }
                            IconButton(onClick = { state.db.removeFromPlaylist(id, s.id); state.changed() }) { Icon(Ic.Trash, "Remover", tint = t.muted) }
                        }
                    }) {}
                } else {
                    ListRow(s.title, order.subtitle(s), leading = { SongThumb(s) }) { state.go(Screen.Song(s, shown)) }
                }
            }
        }
    }
    if (renaming) {
        var n by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false }, containerColor = t.surface,
            title = { Text("Renomear") },
            text = { OutlinedTextField(n, { n = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = n.isNotBlank(), onClick = {
                    state.db.renamePlaylist(id, n.trim()); name = n.trim(); state.changed(); renaming = false
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancelar") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false }, containerColor = t.surface,
            title = { Text("Apagar \"$name\"?") },
            text = { Text("As cifras continuam salvas; só a lista é apagada.") },
            confirmButton = { TextButton(onClick = { state.db.deletePlaylist(id); state.changed(); deleting = false; state.back() }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
        )
    }
}

/** Paste a chord sheet, or a link to one, and keep it locally. */
@Composable
fun ImportScreen(state: AppState, initialText: String, initialLink: String) {
    val t = LocalReading.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var artist by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf(initialText) }
    var link by rememberSaveable { mutableStateOf(initialLink) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun fetchLink() {
        val l = link.trim()
        if (!l.startsWith("http")) { error = "Cole um link começando com http"; return }
        if (Source.isPlaylistLink(l)) { state.replace(Screen.ImportPlaylist(l)); return }
        busy = true; error = null
        scope.launch {
            runCatching {
                val ref = SongRef.fromLink(l, "", "")
                val s = Source.fetchSheet(ctx, l, ref.id, "", "")
                state.db.saveSheet(s)
                state.replace(Screen.Song(SongRef.fromLink(l, s.title.ifBlank { "Sem título" }, s.artist)))
            }.onFailure { error = it.message ?: "Não consegui ler esse link." }
            busy = false
        }
    }

    LaunchedEffect(initialLink) { if (initialLink.isNotBlank()) fetchLink() }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Importar cifra", "Fica salva só no aparelho", onBack = { state.back() })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.chord, trackColor = t.bg)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                SectionTitle("De um link (Cifra Club, Ultimate Guitar…)", Modifier.padding(start = 0.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(link, { link = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("https://…") })
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = ::fetchLink, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = t.chord, contentColor = t.bg)) { Text("Abrir") }
                }
                error?.let { Text(it, color = t.chord, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(10.dp))
                Pill("Importar playlist da Cifra Club", false) { state.replace(Screen.ImportPlaylist()) }
                SectionTitle("Ou cole o texto", Modifier.padding(start = 0.dp))
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Título") })
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(artist, { artist = it }, Modifier.weight(1f), singleLine = true, label = { Text("Artista") })
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(key, { key = it }, Modifier.size(width = 96.dp, height = 64.dp), singleLine = true, label = { Text("Tom") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth().height(360.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = t.text),
                    placeholder = { Text("[Intro] C G Am F\n\nC          G\n  Letra com os acordes em cima…", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                )
                Spacer(Modifier.height(80.dp))
            }
        }
        Box(Modifier.fillMaxWidth().background(t.bg).navigationBarsPadding().padding(16.dp)) {
            Button(
                onClick = {
                    val id = state.db.nextLocalId()
                    val s = Sheet("local:$id", id, title.trim().ifBlank { "Sem título" }, artist.trim(), key.trim(), 0, "", text, emptyList())
                    state.db.saveSheet(s)
                    state.changed()
                    state.replace(Screen.Song(SongRef(id, s.title, s.artist)))
                },
                enabled = text.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = t.chord, contentColor = t.bg),
            ) { Text("Salvar e abrir") }
        }
    }
}
