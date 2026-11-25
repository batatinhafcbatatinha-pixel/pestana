package io.github.batatinha.pestana.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.batatinha.pestana.AppState
import io.github.batatinha.pestana.Screen
import io.github.batatinha.pestana.SongRef
import kotlinx.coroutines.delay

@Composable
fun AppRoot(state: AppState) {
    val t = LocalReading.current
    BackHandler(enabled = state.stack.size > 1 || state.current != Screen.Home) { state.back() }
    val screen = state.current
    val showNav = screen !is Screen.Song && screen !is Screen.Import && screen !is Screen.ImportPlaylist
    Box(Modifier.fillMaxSize().background(t.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { fadeIn(androidx.compose.animation.core.tween(120)) togetherWith fadeOut(androidx.compose.animation.core.tween(90)) },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(state)
                        Screen.Library -> LibraryScreen(state)
                        Screen.Tuner -> TunerScreen(state)
                        Screen.Metronome -> MetronomeScreen(state)
                        Screen.Settings -> SettingsScreen(state)
                        is Screen.Artist -> ArtistScreen(state, s.artist)
                        is Screen.Album -> AlbumScreen(state, s.album)
                        is Screen.PlaylistDetail -> PlaylistScreen(state, s.id, s.name)
                        is Screen.Song -> SongScreen(state, s.song, s.queue)
                        is Screen.Import -> ImportScreen(state, s.text, s.link)
                        is Screen.ImportPlaylist -> ImportPlaylistScreen(state, s.link)
                    }
                }
            }
            if (showNav) BottomNav(state)
        }
        Toast(state)
    }
}

@Composable
private fun BottomNav(state: AppState) {
    val t = LocalReading.current
    Column(Modifier.background(t.bg).navigationBarsPadding()) {
        HorizontalDivider(color = t.line, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            NavItem(state, Screen.Home, Ic.Search, "Buscar")
            NavItem(state, Screen.Library, Ic.Library, "Biblioteca")
            NavItem(state, Screen.Tuner, Ic.Tuner, "Afinador")
            NavItem(state, Screen.Metronome, Ic.Metronome, "Metrônomo")
        }
    }
}

@Composable
private fun RowScope.NavItem(state: AppState, root: Screen, icon: ImageVector, label: String) {
    val t = LocalReading.current
    val selected = state.rootTab == root
    Column(
        Modifier.weight(1f).height(60.dp).clickable { if (selected) state.tab(root) else state.tab(root) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(if (selected) t.surface2 else t.bg).padding(horizontal = 18.dp, vertical = 4.dp),
        ) {
            Icon(icon, label, Modifier.size(22.dp), tint = if (selected) t.chord else t.muted)
        }
        Text(label, fontSize = 11.sp, color = if (selected) t.text else t.muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun Toast(state: AppState) {
    val msg = state.toast ?: return
    val t = LocalReading.current
    LaunchedEffect(msg) {
        delay(2200)
        state.toast = null
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 84.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(12.dp), color = t.text, shadowElevation = 4.dp) {
            Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = t.bg, fontSize = 14.sp)
        }
    }
}

// ---------- shared pieces ----------

@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val t = LocalReading.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Ic.Back, "Voltar", tint = t.text) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = t.text)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 13.sp, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        style = SectionLabel, color = LocalReading.current.muted,
    )
}

/** Round avatar with the first letter, instead of downloading pictures. */
@Composable
fun Monogram(text: String, icon: ImageVector? = null, round: Boolean = true) {
    val t = LocalReading.current
    Box(
        Modifier.size(42.dp).clip(if (round) CircleShape else RoundedCornerShape(10.dp)).background(t.surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = t.muted)
        } else {
            Text(text.trim().take(1).uppercase(), color = t.chord, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val t = LocalReading.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = t.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = t.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun SongRow(state: AppState, s: SongRef, queue: List<SongRef> = emptyList(), trailing: @Composable (() -> Unit)? = null) {
    ListRow(
        s.title, s.artist,
        leading = { Monogram(s.title, Ic.Note, round = false) },
        trailing = trailing,
    ) { state.go(Screen.Song(s, queue)) }
}

@Composable
fun FavButton(on: Boolean, onToggle: () -> Unit) {
    val t = LocalReading.current
    IconButton(onClick = onToggle) {
        Icon(if (on) Ic.HeartFilled else Ic.Heart, if (on) "Remover dos favoritos" else "Favoritar", tint = if (on) t.chord else t.muted)
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    val t = LocalReading.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = t.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = t.muted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalReading.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) t.text else t.surface).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (selected) t.bg else t.text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Choose (or create) a playlist and add the song to it. */
@Composable
fun AddToPlaylistDialog(state: AppState, song: SongRef, onDismiss: () -> Unit) {
    val t = LocalReading.current
    val lists = remember(state.dataVersion) { state.db.playlists() }
    var creating by rememberSaveable { mutableStateOf(lists.isEmpty()) }
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.surface,
        title = { Text(if (creating) "Nova playlist" else "Adicionar à playlist", color = t.text) },
        text = {
            if (creating) {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Nome (ex.: Ensaio sábado)") })
            } else {
                LazyColumn {
                    items(lists, key = { it.id }) { p ->
                        ListRow(p.name, "${p.count} músicas", leading = { Monogram(p.name, Ic.Playlist, round = false) }) {
                            state.db.addToPlaylist(p.id, song)
                            state.changed()
                            state.toast = "Adicionada a ${p.name}"
                            onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val id = state.db.createPlaylist(name.trim())
                    state.db.addToPlaylist(id, song)
                    state.changed()
                    state.toast = "Adicionada a ${name.trim()}"
                    onDismiss()
                }) { Text("Criar e adicionar") }
            } else {
                TextButton(onClick = { creating = true }) { Text("Nova playlist") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

val ListPadding = PaddingValues(bottom = 24.dp)
