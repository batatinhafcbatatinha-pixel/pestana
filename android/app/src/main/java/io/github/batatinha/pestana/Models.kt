package io.github.batatinha.pestana

import org.json.JSONArray
import org.json.JSONObject

/** A song as the search knows it. `id` is "artist-slug/song-slug" (or "local/<n>" for imports). */
data class SongRef(
    val id: String,
    val title: String,
    val artist: String,
    val artistSlug: String = artistOf(id),
    val album: String = "",
    val albumSlug: String = "",
) {
    val isLocal get() = id.startsWith("local/")
    val url
        get() = when {
            isLocal -> "local:$id"
            id.startsWith("web/") -> "https://" + id.removePrefix("web/")
            else -> "${Source.SITE}/$id/"
        }

    companion object {
        /** The artist's address out of a song id; a version kept by its own link ("web/host/artist/song/x.html") has it too. */
        fun artistOf(id: String): String {
            if (!id.startsWith("web/")) return id.substringBefore('/')
            val parts = id.split('/')
            return if (parts.size == 5 && parts[1].endsWith("cifraclub.com.br") && parts[4].endsWith(".html")) parts[2] else ""
        }

        /** A song imported from any chord page link. */
        fun fromLink(link: String, title: String, artist: String): SongRef {
            val u = link.substringAfter("://").substringBefore('#').substringBefore('?')
            val host = u.substringBefore('/')
            val path = u.substringAfter('/', "").trim('/')
            return if (host.endsWith("cifraclub.com.br") && path.count { it == '/' } == 1) {
                SongRef(path, title, artist)
            } else {
                // One version of a song ("/artist/song/sjgsmzp.html") is kept as a song of its own, under its artist.
                val ofArtist = host.endsWith("cifraclub.com.br") && path.count { it == '/' } == 2 && path.endsWith(".html")
                SongRef("web/$u", title, artist, artistSlug = if (ofArtist) path.substringBefore('/') else "")
            }
        }
    }
}

data class ArtistRef(val slug: String, val name: String, val img: String = "")

data class AlbumRef(
    val slug: String,
    val name: String,
    val artist: String,
    val artistSlug: String,
    val year: String,
    val img: String = "",
    /** "album" or "single" when the discography says which; "" otherwise. */
    val kind: String = "",
) {
    val key get() = "$artistSlug/$slug"
}

data class SearchResults(
    val artists: List<ArtistRef> = emptyList(),
    val songs: List<SongRef> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
) {
    val isEmpty get() = artists.isEmpty() && songs.isEmpty() && albums.isEmpty()
}

data class Version(val label: String, val url: String)

/** A downloaded (or imported) chord sheet. */
data class Sheet(
    val url: String,
    val songId: String,
    val title: String,
    val artist: String,
    val key: String,
    val capo: Int,
    val tuning: String,
    val text: String,
    val versions: List<Version>,
) {
    companion object {
        fun fromExtract(json: String, url: String, songId: String, fallbackTitle: String, fallbackArtist: String): Sheet {
            val o = JSONObject(json)
            val vs = o.optJSONArray("versions") ?: JSONArray()
            // A version imported by its own link says which one it is: "I Miss You (Versão 7)".
            val version = o.optString("version").takeIf { songId.startsWith("web/") && it.isNotBlank() }
            val title = o.optString("title").ifBlank { fallbackTitle }
            return Sheet(
                url = url,
                songId = songId,
                title = if (version != null && title.isNotBlank() && !title.endsWith("($version)")) "$title ($version)" else title,
                artist = o.optString("artist").ifBlank { fallbackArtist },
                key = o.optString("key"),
                capo = o.optInt("capo"),
                tuning = o.optString("tuning"),
                text = o.optString("text"),
                versions = (0 until vs.length()).map { vs.getJSONObject(it).let { v -> Version(v.getString("label"), v.getString("url")) } },
            )
        }
    }

    fun versionsJson(): String = JSONArray().apply {
        versions.forEach { put(JSONObject().put("label", it.label).put("url", it.url)) }
    }.toString()
}

/** Styles and kinds mirror core/src/sheet.rs. */
object Style {
    const val LYRIC = 0
    const val CHORD = 1
    const val HEADER = 2
    const val TAB = 3
    const val NOTE = 4
    const val TAB_NOTE = 5
    const val TAB_TECH = 6
}

object Kind {
    const val TEXT = 0
    const val CHORDS = 1
    const val PAIR_CHORDS = 2
    const val PAIR_LYRIC = 3
    const val HEADER = 4
    const val TAB = 5
    const val BLANK = 6
    const val NOTE = 7
}

class Span(val style: Int, val text: String)
class Line(val kind: Int, val spans: List<Span>)

class Rendered(
    val key: String,
    val origKey: String,
    val hasTabs: Boolean,
    val hasSolos: Boolean,
    val hasRiffs: Boolean,
    val hasChords: Boolean,
    val chords: List<String>,
    val lines: List<Line>,
) {
    companion object {
        fun parse(json: String): Rendered {
            val o = JSONObject(json)
            val ls = o.getJSONArray("lines")
            val lines = ArrayList<Line>(ls.length())
            for (i in 0 until ls.length()) {
                val a = ls.getJSONArray(i)
                val spans = ArrayList<Span>((a.length() - 1) / 2)
                var k = 1
                while (k + 1 < a.length()) {
                    spans += Span(a.getInt(k), a.getString(k + 1))
                    k += 2
                }
                lines += Line(a.getInt(0), spans)
            }
            val cs = o.getJSONArray("chords")
            return Rendered(
                key = o.optString("key"),
                origKey = o.optString("origKey"),
                hasTabs = o.optBoolean("hasTabs"),
                hasSolos = o.optBoolean("hasSolos"),
                hasRiffs = o.optBoolean("hasRiffs"),
                hasChords = o.optBoolean("hasChords"),
                chords = (0 until cs.length()).map { cs.getString(it) },
                lines = lines,
            )
        }
    }
}

/** Frets per string (low string first); `root` marks the bass box's root string, -1 otherwise. */
class ChordShape(val frets: IntArray, val barre: Int, val barreFrom: Int, val root: Int = -1) {
    companion object {
        private val cache = HashMap<String, ChordShape?>()

        fun of(name: String, bass: Boolean = false): ChordShape? = synchronized(cache) {
            cache.getOrPut((if (bass) "b:" else "g:") + name) {
                val o = JSONObject(if (bass) Native.bassShape(name) else Native.chordShape(name))
                val f = o.optJSONArray("frets") ?: return@getOrPut null
                ChordShape(IntArray(f.length()) { f.getInt(it) }, o.optInt("barre"), o.optInt("barreFrom"), o.optInt("root", -1))
            }
        }
    }
}

/** Instruments the app adapts to (diagrams, preferred version, tuner). */
object Instruments {
    val all = listOf("guitar" to "Violão / Guitarra", "bass" to "Baixo")
}

/** Per-song reader settings, remembered locally. */
data class SongPrefs(
    val transpose: Int = 0,
    val speed: Int = 8,
    val simplify: Boolean = false,
    val capo: Int = -1,
)

data class Playlist(val id: Long, val name: String, val count: Int)
