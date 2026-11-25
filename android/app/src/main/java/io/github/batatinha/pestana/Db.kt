package io.github.batatinha.pestana

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app keeps lives in one small SQLite file on the phone:
 * downloaded sheets (offline cache), favorites, playlists, per-song settings
 * and history. No account, no server.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "pestana.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sheets(url TEXT PRIMARY KEY, song_id TEXT NOT NULL, title TEXT, artist TEXT,
               key TEXT, capo INTEGER, tuning TEXT, text TEXT, versions TEXT, fetched INTEGER)""",
        )
        db.execSQL("CREATE INDEX sheets_song ON sheets(song_id)")
        db.execSQL(
            """CREATE TABLE favs(kind TEXT NOT NULL, id TEXT NOT NULL, title TEXT, sub TEXT, extra TEXT,
               ts INTEGER, PRIMARY KEY(kind, id))""",
        )
        db.execSQL("CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER)")
        db.execSQL(
            """CREATE TABLE playlist_items(playlist INTEGER NOT NULL, pos INTEGER, song_id TEXT NOT NULL,
               title TEXT, artist TEXT, PRIMARY KEY(playlist, song_id))""",
        )
        db.execSQL("CREATE TABLE prefs(song_id TEXT PRIMARY KEY, transpose INTEGER, speed INTEGER, simplify INTEGER, capo INTEGER)")
        db.execSQL("CREATE TABLE recents(song_id TEXT PRIMARY KEY, title TEXT, artist TEXT, ts INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    // ---- sheets ----

    fun sheet(url: String): Sheet? = readableDatabase.rawQuery(
        "SELECT url, song_id, title, artist, key, capo, tuning, text, versions FROM sheets WHERE url = ?", arrayOf(url),
    ).use { c ->
        if (!c.moveToFirst()) return null
        val vs = JSONArray(c.getString(8) ?: "[]")
        Sheet(
            c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "",
            c.getInt(5), c.getString(6) ?: "", c.getString(7) ?: "",
            (0 until vs.length()).map { vs.getJSONObject(it).let { v -> Version(v.getString("label"), v.getString("url")) } },
        )
    }

    fun saveSheet(s: Sheet) {
        writableDatabase.insertWithOnConflict("sheets", null, ContentValues().apply {
            put("url", s.url); put("song_id", s.songId); put("title", s.title); put("artist", s.artist)
            put("key", s.key); put("capo", s.capo); put("tuning", s.tuning); put("text", s.text)
            put("versions", s.versionsJson()); put("fetched", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun nextLocalId(): String {
        val n = readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(CAST(SUBSTR(song_id, 7) AS INTEGER)), 0) FROM sheets WHERE song_id LIKE 'local/%'", null,
        ).use { if (it.moveToFirst()) it.getLong(0) else 0 }
        return "local/${n + 1}"
    }

    fun localSongs(): List<SongRef> = readableDatabase.rawQuery(
        "SELECT song_id, title, artist FROM sheets WHERE url LIKE 'local:%' ORDER BY title COLLATE NOCASE", null,
    ).use { c -> buildList { while (c.moveToNext()) add(SongRef(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")) } }

    fun deleteSong(id: String) {
        writableDatabase.delete("sheets", "song_id = ?", arrayOf(id))
        writableDatabase.delete("favs", "kind = 'song' AND id = ?", arrayOf(id))
        writableDatabase.delete("playlist_items", "song_id = ?", arrayOf(id))
        writableDatabase.delete("recents", "song_id = ?", arrayOf(id))
        writableDatabase.delete("prefs", "song_id = ?", arrayOf(id))
    }

    fun hasSheet(songUrl: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM sheets WHERE url = ?", arrayOf(songUrl)).use { it.moveToFirst() }

    /** Real title/artist learned from a downloaded page replace the guessed ones. */
    fun updateSongMeta(songId: String, title: String, artist: String) {
        if (title.isBlank()) return
        val v = ContentValues().apply { put("title", title); put("artist", artist) }
        writableDatabase.update("playlist_items", v, "song_id = ?", arrayOf(songId))
        writableDatabase.update("recents", v, "song_id = ?", arrayOf(songId))
        writableDatabase.update("favs", ContentValues().apply { put("title", title); put("sub", artist) }, "kind = 'song' AND id = ?", arrayOf(songId))
    }

    fun cachedCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM sheets WHERE url NOT LIKE 'local:%'", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearCache() {
        writableDatabase.delete("sheets", "url NOT LIKE 'local:%'", null)
    }

    // ---- favorites ----

    fun isFav(kind: String, id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM favs WHERE kind = ? AND id = ?", arrayOf(kind, id)).use { it.moveToFirst() }

    fun setFav(kind: String, id: String, title: String, sub: String, extra: String, on: Boolean) {
        if (on) {
            writableDatabase.insertWithOnConflict("favs", null, ContentValues().apply {
                put("kind", kind); put("id", id); put("title", title); put("sub", sub); put("extra", extra)
                put("ts", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            writableDatabase.delete("favs", "kind = ? AND id = ?", arrayOf(kind, id))
        }
    }

    data class Fav(val kind: String, val id: String, val title: String, val sub: String, val extra: String)

    fun favs(kind: String): List<Fav> = readableDatabase.rawQuery(
        "SELECT kind, id, title, sub, extra FROM favs WHERE kind = ? ORDER BY title COLLATE NOCASE", arrayOf(kind),
    ).use { c -> buildList { while (c.moveToNext()) add(Fav(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "")) } }

    // ---- playlists ----

    fun playlists(): List<Playlist> = readableDatabase.rawQuery(
        """SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist = p.id)
           FROM playlists p ORDER BY p.name COLLATE NOCASE""", null,
    ).use { c -> buildList { while (c.moveToNext()) add(Playlist(c.getLong(0), c.getString(1), c.getInt(2))) } }

    fun createPlaylist(name: String): Long = writableDatabase.insert("playlists", null, ContentValues().apply {
        put("name", name); put("created", System.currentTimeMillis())
    })

    fun renamePlaylist(id: Long, name: String) {
        writableDatabase.update("playlists", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        writableDatabase.delete("playlist_items", "playlist = ?", arrayOf(id.toString()))
        writableDatabase.delete("playlists", "id = ?", arrayOf(id.toString()))
    }

    fun playlistSongs(id: Long): List<SongRef> = readableDatabase.rawQuery(
        "SELECT song_id, title, artist FROM playlist_items WHERE playlist = ? ORDER BY pos", arrayOf(id.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(SongRef(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")) } }

    fun addToPlaylist(id: Long, s: SongRef) {
        val pos = readableDatabase.rawQuery("SELECT COALESCE(MAX(pos), -1) + 1 FROM playlist_items WHERE playlist = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        writableDatabase.insertWithOnConflict("playlist_items", null, ContentValues().apply {
            put("playlist", id); put("pos", pos); put("song_id", s.id); put("title", s.title); put("artist", s.artist)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeFromPlaylist(id: Long, songId: String) {
        writableDatabase.delete("playlist_items", "playlist = ? AND song_id = ?", arrayOf(id.toString(), songId))
    }

    fun reorderPlaylist(id: Long, songs: List<SongRef>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            songs.forEachIndexed { i, s ->
                db.update("playlist_items", ContentValues().apply { put("pos", i) }, "playlist = ? AND song_id = ?", arrayOf(id.toString(), s.id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- per-song settings ----

    fun prefs(songId: String): SongPrefs = readableDatabase.rawQuery(
        "SELECT transpose, speed, simplify, capo FROM prefs WHERE song_id = ?", arrayOf(songId),
    ).use { c -> if (c.moveToFirst()) SongPrefs(c.getInt(0), c.getInt(1), c.getInt(2) != 0, c.getInt(3)) else SongPrefs() }

    fun savePrefs(songId: String, p: SongPrefs) {
        writableDatabase.insertWithOnConflict("prefs", null, ContentValues().apply {
            put("song_id", songId); put("transpose", p.transpose); put("speed", p.speed)
            put("simplify", if (p.simplify) 1 else 0); put("capo", p.capo)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- history ----

    fun touchRecent(s: SongRef) {
        writableDatabase.insertWithOnConflict("recents", null, ContentValues().apply {
            put("song_id", s.id); put("title", s.title); put("artist", s.artist); put("ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("DELETE FROM recents WHERE song_id NOT IN (SELECT song_id FROM recents ORDER BY ts DESC LIMIT 50)")
    }

    fun recents(limit: Int = 20): List<SongRef> = readableDatabase.rawQuery(
        "SELECT song_id, title, artist FROM recents ORDER BY ts DESC LIMIT $limit", null,
    ).use { c -> buildList { while (c.moveToNext()) add(SongRef(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")) } }

    fun clearRecents() {
        writableDatabase.delete("recents", null, null)
    }

    // ---- backup ----

    private fun dump(sql: String): JSONArray = readableDatabase.rawQuery(sql, null).use { c ->
        val arr = JSONArray()
        while (c.moveToNext()) {
            val o = JSONObject()
            for (i in 0 until c.columnCount) {
                when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                    android.database.Cursor.FIELD_TYPE_NULL -> {}
                    else -> o.put(c.getColumnName(i), c.getString(i))
                }
            }
            arr.put(o)
        }
        arr
    }

    fun exportJson(): String = JSONObject().apply {
        put("app", "pestana"); put("version", 1)
        put("favs", dump("SELECT * FROM favs"))
        put("playlists", dump("SELECT * FROM playlists"))
        put("playlist_items", dump("SELECT * FROM playlist_items"))
        put("prefs", dump("SELECT * FROM prefs"))
        put("local", dump("SELECT * FROM sheets WHERE url LIKE 'local:%'"))
    }.toString()

    /** Merges a backup into the current data. Returns how many rows were read. */
    fun importJson(json: String): Int {
        val o = JSONObject(json)
        require(o.optString("app") == "pestana") { "Arquivo não é um backup do Pestana" }
        var n = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            fun load(table: String, key: String, map: (JSONObject) -> ContentValues = { r -> values(r) }) {
                val arr = o.optJSONArray(key) ?: return
                for (i in 0 until arr.length()) {
                    db.insertWithOnConflict(table, null, map(arr.getJSONObject(i)), SQLiteDatabase.CONFLICT_REPLACE)
                    n++
                }
            }
            // Playlist ids may clash with existing ones: remap them.
            val idMap = HashMap<Long, Long>()
            o.optJSONArray("playlists")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val newId = db.insert("playlists", null, ContentValues().apply {
                        put("name", r.getString("name")); put("created", r.optLong("created"))
                    })
                    idMap[r.getLong("id")] = newId
                    n++
                }
            }
            load("favs", "favs")
            load("prefs", "prefs")
            load("sheets", "local")
            load("playlist_items", "playlist_items") { r ->
                values(r).apply { put("playlist", idMap[r.getLong("playlist")] ?: r.getLong("playlist")) }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    private fun values(r: JSONObject) = ContentValues().apply {
        for (k in r.keys()) {
            when (val v = r.get(k)) {
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Number -> put(k, v.toLong())
                else -> put(k, v.toString())
            }
        }
    }
}
