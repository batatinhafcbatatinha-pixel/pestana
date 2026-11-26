package io.github.batatinha.pestana.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.batatinha.pestana.AlbumRef
import io.github.batatinha.pestana.AppState
import io.github.batatinha.pestana.ArtistRef
import io.github.batatinha.pestana.Screen
import io.github.batatinha.pestana.SearchResults
import io.github.batatinha.pestana.SongRef
import io.github.batatinha.pestana.Source
import kotlinx.coroutines.delay

/** The app mark: six strings and a barre. */
@Composable
fun Logo(size: androidx.compose.ui.unit.Dp = 28.dp) {
    val t = LocalReading.current
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        for (i in 0 until 6) {
            val y = h * (0.2f + i * 0.12f)
            drawLine(t.text.copy(alpha = 0.85f), Offset(w * 0.06f, y), Offset(w * 0.94f, y), strokeWidth = w * (0.025f + i * 0.006f))
        }
        drawRoundRect(t.chord, Offset(w * 0.38f, h * 0.1f), Size(w * 0.2f, h * 0.84f), CornerRadius(w * 0.1f))
    }
}

@Composable
fun HomeScreen(state: AppState) {
    val t = LocalReading.current
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchResults()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val recents = remember(state.dataVersion) { state.db.recents() }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = SearchResults(); error = null; loading = false; return@LaunchedEffect
        }
        delay(280)
        loading = true
        error = null
        results = runCatching { Source.search(ctx, query) }.getOrElse { error = "Sem conexão com a busca."; SearchResults() }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(26.dp)
            Spacer(Modifier.width(10.dp))
            Text("Pestana", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = t.text, modifier = Modifier.weight(1f))
            IconButton(onClick = { state.go(Screen.Import()) }) { Icon(Ic.Import, "Importar cifra", tint = t.muted) }
            IconButton(onClick = { state.go(Screen.Settings) }) { Icon(Ic.Settings, "Ajustes", tint = t.muted) }
        }
        // Search field
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(t.surface)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Search, null, Modifier.size(20.dp), tint = t.muted)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Música, artista ou álbum", color = t.muted, fontSize = 16.sp)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = t.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(t.chord),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(Ic.Close, "Limpar", Modifier.size(20.dp).clickable { query = "" }, tint = t.muted)
            }
        }
        Box(Modifier.height(3.dp).fillMaxWidth().padding(horizontal = 32.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.chord, trackColor = t.bg)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            if (query.isBlank()) {
                if (recents.isEmpty()) {
                    item {
                        EmptyState(
                            "Comece buscando uma música",
                            "As cifras que você abrir ficam salvas no aparelho e funcionam sem internet.",
                        )
                    }
                } else {
                    item { SectionTitle("Abertas recentemente") }
                    items(recents, key = { "r" + it.id }) { SongRow(state, it) }
                }
            } else {
                error?.let { item { EmptyState("Ops", it) } }
                if (results.artists.isNotEmpty()) {
                    item { SectionTitle("Artistas") }
                    items(results.artists, key = { "a" + it.slug }) { a ->
                        ListRow(a.name, null, leading = { Monogram(a.name, url = a.img.ifEmpty { null } ?: io.github.batatinha.pestana.Images.artist(a.slug)) }) { state.go(Screen.Artist(a)) }
                    }
                }
                if (results.songs.isNotEmpty()) {
                    item { SectionTitle("Músicas") }
                    items(results.songs, key = { "s" + it.id }) { SongRow(state, it) }
                }
                if (results.albums.isNotEmpty()) {
                    item { SectionTitle("Álbuns") }
                    items(results.albums, key = { "l" + it.artistSlug + it.slug }) { a ->
                        ListRow(a.name, listOf(a.artist, a.year).filter { it.isNotBlank() }.joinToString(" · "), leading = { Monogram(a.name, Ic.Album, round = false, url = a.img.ifEmpty { null } ?: io.github.batatinha.pestana.Images.album(a.key)) }) {
                            state.go(Screen.Album(a))
                        }
                    }
                }
                if (!loading && error == null && results.isEmpty) {
                    item { EmptyState("Nada encontrado", "Tente outro nome, ou importe a cifra colando o texto.") }
                }
            }
        }
    }
}

/** Big photo + name header for artist and album pages. */
@Composable
private fun Header(title: String, subtitle: String, url: String?, round: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
    val t = LocalReading.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Monogram(title, icon, round = round, url = url, size = 96.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            if (subtitle.isNotBlank()) Text(subtitle, color = t.muted, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
fun ArtistScreen(state: AppState, artist: ArtistRef) {
    val ctx = LocalContext.current
    val cached = remember(artist.slug) { Source.cachedArtist(artist.slug) }
    var songs by remember { mutableStateOf(cached?.first) }
    var albums by remember { mutableStateOf(cached?.second ?: emptyList()) }
    var loading by remember { mutableStateOf(cached == null) }
    var fav by remember(state.dataVersion) { mutableStateOf(state.db.isFav("artist", artist.slug)) }
    val img = artist.img.ifEmpty { null } ?: io.github.batatinha.pestana.Images.artist(artist.slug)
    // The artist's page on the site: the most played songs and the whole discography.
    var page by remember(artist.slug) { mutableStateOf(Source.cachedArtistPage(artist.slug)) }
    LaunchedEffect(artist) {
        if (page == null) page = Source.artistPage(ctx, artist)
    }
    LaunchedEffect(artist) {
        // Results appear as each query answers (and instantly when cached).
        Source.artistSongs(ctx, artist) { s, a -> if (s.isNotEmpty() || songs == null) songs = s; albums = a }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("", null, onBack = { state.back() }) {
            FavButton(fav) { fav = state.toggleFavArtist(artist.copy(img = img ?: "")) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            item { Header(artist.name, "Artista", img, round = true, icon = null) }
            val top = page?.top.orEmpty()
            // Every album the site lists; what the search found until that arrives.
            val discs = page?.albums?.takeIf { it.isNotEmpty() } ?: albums
            // The search's songs that are not among the most played already.
            val others = songs.orEmpty().filter { s -> top.none { it.id == s.id } }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), color = LocalReading.current.chord, trackColor = LocalReading.current.bg) }
            if (top.isEmpty() && others.isEmpty() && songs != null && !loading) {
                item { EmptyState("Sem músicas", "Não encontrei cifras desse artista agora.") }
            }
            if (top.isNotEmpty()) {
                item { SectionTitle("Mais tocadas") }
                items(top, key = { "t" + it.id }) { SongRow(state, it, top) }
            }
            if (discs.isNotEmpty()) {
                item { SectionTitle(if (discs.size == 1) "1 álbum" else "${discs.size} álbuns") }
                items(discs, key = { "l" + it.slug }) { a ->
                    val sub = listOf(a.year, if (a.kind == "single") "Single/EP" else "").filter { it.isNotBlank() }.joinToString(" · ")
                    ListRow(a.name, sub, leading = { Monogram(a.name, Ic.Album, round = false, url = a.img.ifEmpty { null } ?: io.github.batatinha.pestana.Images.album(a.key)) }) { state.go(Screen.Album(a)) }
                }
            }
            if (others.isNotEmpty()) {
                item { SectionTitle(if (top.isEmpty()) "Músicas" else "Outras músicas") }
                items(others, key = { it.id }) { SongRow(state, it, others) }
            }
        }
    }
}

@Composable
fun AlbumScreen(state: AppState, album: AlbumRef) {
    val ctx = LocalContext.current
    var songs by remember { mutableStateOf(Source.cachedAlbum(album.key)) }
    var loading by remember { mutableStateOf(songs == null) }
    val favId = album.key
    var fav by remember(state.dataVersion) { mutableStateOf(state.db.isFav("album", favId)) }
    val img = album.img.ifEmpty { null } ?: io.github.batatinha.pestana.Images.album(album.key)
    LaunchedEffect(album) {
        Source.albumSongs(ctx, album) { songs = it }
        loading = false
    }
    // Inside one album, ordering by artist, album or release says nothing.
    val order = rememberSongOrder(state, "album", songs.orEmpty(), "Faixas", sorts = listOf(Sort.MANUAL, Sort.PLAYED, Sort.TITLE))
    Column(Modifier.fillMaxSize()) {
        TopBar("", null, onBack = { state.back() }) {
            FavButton(fav) { fav = state.toggleFavAlbum(album) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            item { Header(album.name, listOf(album.artist, album.year).filter { it.isNotBlank() }.joinToString(" · "), img, round = false, icon = Ic.Album) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), color = LocalReading.current.chord, trackColor = LocalReading.current.bg) }
            val list = songs.orEmpty()
            if (list.isEmpty() && !loading) item { EmptyState("Sem músicas", "A busca não trouxe as faixas deste álbum.") }
            if (list.isNotEmpty()) {
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Tocar em sequência", false) { order.shown.firstOrNull()?.let { state.go(Screen.Song(it, order.shown)) } }
                        Pill("Artista", false) { state.go(Screen.Artist(ArtistRef(album.artistSlug, album.artist))) }
                    }
                }
                item { order.bar() }
                if (order.shown.isEmpty()) item { EmptyState("Nenhuma música com esses filtros", "Os filtros olham as cifras salvas no aparelho.") }
                items(order.shown, key = { it.id }) { SongRow(state, it, order.shown) }
            }
        }
    }
}
