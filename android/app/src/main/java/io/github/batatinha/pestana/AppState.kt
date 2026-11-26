package io.github.batatinha.pestana

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Tuner : Screen
    data object Metronome : Screen
    data object Settings : Screen
    data class Artist(val artist: ArtistRef) : Screen
    data class Album(val album: AlbumRef) : Screen
    data class PlaylistDetail(val id: Long, val name: String) : Screen
    data class Song(val song: SongRef, val queue: List<SongRef> = emptyList()) : Screen
    data class Import(val text: String = "", val link: String = "") : Screen
    data class ImportPlaylist(val link: String = "") : Screen
}

/** App-wide state: database, settings and a simple back stack per tab. */
class AppState(context: Context) {
    val app: Context = context.applicationContext
    val db = Db(app)
    val settings = Settings(app)

    init {
        Images.init(db)
    }

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    /** Bumped after any change to favorites/playlists so lists reload. */
    var dataVersion by mutableIntStateOf(0)
        private set

    var toast by mutableStateOf<String?>(null)

    /** App-wide scope for work that outlives a screen (offline downloads). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Offline download progress: done / total, or null when idle. */
    var download by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    private var downloadJob: Job? = null

    /** Saves every sheet of `songs` that is not on the phone yet. */
    fun downloadForOffline(songs: List<SongRef>) {
        if (downloadJob?.isActive == true) {
            toast = "Já estou baixando outras cifras"
            return
        }
        // The visible download takes over from the quiet one.
        saveJob?.cancel()
        saving.clear()
        val todo = songs.filter { !it.isLocal && !db.hasSheet(it.url) }
        if (todo.isEmpty()) {
            toast = "Tudo já está salvo para usar offline"
            return
        }
        downloadJob = scope.launch {
            var ok = 0
            download = 0 to todo.size
            todo.forEachIndexed { i, s ->
                runCatching { Source.fetchSheet(app, s.url, s.id, s.title, s.artist) }.onSuccess { sheet ->
                    db.saveSheet(sheet)
                    db.updateSongMeta(s.id, sheet.title, sheet.artist)
                    ok++
                }
                download = (i + 1) to todo.size
            }
            download = null
            changed()
            toast = if (ok == todo.size) "$ok cifras salvas para offline" else "$ok de ${todo.size} cifras salvas (algumas falharam)"
        }
    }

    fun changed() {
        dataVersion++
        syncSaved()
    }

    private val saving = ArrayDeque<SongRef>()
    private var saveJob: Job? = null
    private val saveFailed = HashSet<String>()

    /**
     * Keeps the phone's copy of the sheets in line with the library: drops the
     * ones nothing refers to any more and quietly downloads the ones missing
     * for favorites, playlists and favorite albums.
     */
    fun syncSaved() {
        db.pruneCache()
        db.keptSongs()
            .filter { s -> !s.isLocal && s.id !in saveFailed && saving.none { it.id == s.id } && !db.hasSheet(s.url) }
            .forEach { saving.addLast(it) }
        if (saving.isEmpty() || saveJob?.isActive == true || downloadJob?.isActive == true) return
        saveJob = scope.launch {
            while (true) {
                val s = saving.removeFirstOrNull() ?: break
                if (db.hasSheet(s.url)) continue
                runCatching { Source.fetchSheet(app, s.url, s.id, s.title, s.artist) }
                    .onSuccess { sheet ->
                        db.saveSheet(sheet)
                        db.updateSongMeta(s.id, sheet.title, sheet.artist)
                    }
                    // Not retried until the app starts again (page gone, no connection).
                    .onFailure { saveFailed += s.id }
            }
            // Whatever stopped being wanted while this ran goes away.
            db.pruneCache()
            dataVersion++
        }
    }

    fun go(s: Screen) {
        if (s == current) return
        stack.add(s)
    }

    fun replace(s: Screen) {
        stack[stack.lastIndex] = s
    }

    fun tab(root: Screen) {
        stack.clear()
        stack.add(root)
    }

    fun back(): Boolean {
        if (stack.size <= 1) {
            if (current != Screen.Home) {
                tab(Screen.Home)
                return true
            }
            return false
        }
        stack.removeAt(stack.lastIndex)
        return true
    }

    val rootTab: Screen get() = stack.first()

    fun toggleFavSong(s: SongRef): Boolean {
        val on = !db.isFav("song", s.id)
        db.setFav("song", s.id, s.title, s.artist, "", on)
        changed()
        return on
    }

    fun toggleFavArtist(a: ArtistRef): Boolean {
        val on = !db.isFav("artist", a.slug)
        db.setFav("artist", a.slug, a.name, "", "", on)
        changed()
        return on
    }

    fun toggleFavAlbum(a: AlbumRef): Boolean {
        val id = "${a.artistSlug}/${a.slug}"
        val on = !db.isFav("album", id)
        db.setFav("album", id, a.name, a.artist, a.year, on)
        changed()
        // A favorite album keeps all its tracks on the phone.
        if (on) scope.launch {
            var songs = Source.cachedAlbum(a.key).orEmpty()
            if (songs.isEmpty()) Source.albumSongs(app, a) { songs = it }
            if (songs.isNotEmpty() && db.isFav("album", id)) {
                db.setAlbumSongs(id, songs)
                changed()
            }
        }
        return on
    }

    /** First launch: a couple of public-domain songs to try the reader offline. */
    fun seedIfEmpty() {
        val p = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (p.getBoolean("seeded", false)) return
        Samples.all.forEach { db.saveSheet(it) }
        p.edit().putBoolean("seeded", true).apply()
    }

    init {
        syncSaved()
    }
}
