import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// One small SQLite file with everything the app keeps (same schema as Android):
/// downloaded sheets, favorites, playlists, per-song settings, history and
/// picture URLs. No account, no server.
final class Store {
    private var db: OpaquePointer?

    init() {
        let fm = FileManager.default
        let dir = (try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))?
            .appendingPathComponent("Pestana", isDirectory: true) ?? fm.temporaryDirectory
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("pestana.db").path
        sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        exec("PRAGMA journal_mode=WAL")
        exec("""
        CREATE TABLE IF NOT EXISTS sheets(url TEXT PRIMARY KEY, song_id TEXT NOT NULL, title TEXT, artist TEXT,
          key TEXT, capo INTEGER, tuning TEXT, text TEXT, versions TEXT, fetched INTEGER)
        """)
        exec("CREATE INDEX IF NOT EXISTS sheets_song ON sheets(song_id)")
        exec("CREATE TABLE IF NOT EXISTS favs(kind TEXT NOT NULL, id TEXT NOT NULL, title TEXT, sub TEXT, extra TEXT, ts INTEGER, PRIMARY KEY(kind, id))")
        exec("CREATE TABLE IF NOT EXISTS playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER)")
        exec("CREATE TABLE IF NOT EXISTS playlist_items(playlist INTEGER NOT NULL, pos INTEGER, song_id TEXT NOT NULL, title TEXT, artist TEXT, PRIMARY KEY(playlist, song_id))")
        exec("CREATE TABLE IF NOT EXISTS prefs(song_id TEXT PRIMARY KEY, transpose INTEGER, speed INTEGER, simplify INTEGER, capo INTEGER)")
        // The version (Principal, Simplificada…) last opened of each song; fails harmlessly once it exists.
        exec("ALTER TABLE prefs ADD COLUMN section TEXT")
        exec("CREATE TABLE IF NOT EXISTS recents(song_id TEXT PRIMARY KEY, title TEXT, artist TEXT, ts INTEGER)")
        exec("CREATE TABLE IF NOT EXISTS images(key TEXT PRIMARY KEY, url TEXT NOT NULL)")
        // The album each song came out on, as the site's search tells it (list sorting).
        exec("CREATE TABLE IF NOT EXISTS song_info(song_id TEXT PRIMARY KEY, album TEXT, album_slug TEXT, year INTEGER)")
    }

    deinit { sqlite3_close(db) }

    // MARK: - plumbing

    struct Row {
        let stmt: OpaquePointer?
        func str(_ i: Int32) -> String {
            guard let c = sqlite3_column_text(stmt, i) else { return "" }
            return String(cString: c)
        }
        func int(_ i: Int32) -> Int { Int(sqlite3_column_int64(stmt, i)) }
        func int64(_ i: Int32) -> Int64 { sqlite3_column_int64(stmt, i) }
    }

    private func prepare(_ sql: String, _ args: [Any?]) -> OpaquePointer? {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        for (i, a) in args.enumerated() {
            let idx = Int32(i + 1)
            switch a {
            case let s as String: sqlite3_bind_text(stmt, idx, s, -1, SQLITE_TRANSIENT)
            case let n as Int: sqlite3_bind_int64(stmt, idx, Int64(n))
            case let n as Int64: sqlite3_bind_int64(stmt, idx, n)
            case let b as Bool: sqlite3_bind_int64(stmt, idx, b ? 1 : 0)
            default: sqlite3_bind_null(stmt, idx)
            }
        }
        return stmt
    }

    @discardableResult
    func exec(_ sql: String, _ args: [Any?] = []) -> Bool {
        let stmt = prepare(sql, args)
        defer { sqlite3_finalize(stmt) }
        let rc = sqlite3_step(stmt)
        return rc == SQLITE_DONE || rc == SQLITE_ROW
    }

    func query<T>(_ sql: String, _ args: [Any?] = [], _ map: (Row) -> T) -> [T] {
        let stmt = prepare(sql, args)
        defer { sqlite3_finalize(stmt) }
        var out: [T] = []
        while sqlite3_step(stmt) == SQLITE_ROW { out.append(map(Row(stmt: stmt))) }
        return out
    }

    private var now: Int { Int(Date().timeIntervalSince1970 * 1000) }

    // MARK: - sheets

    func sheet(_ url: String) -> Sheet? {
        query("SELECT url, song_id, title, artist, key, capo, tuning, text, versions FROM sheets WHERE url = ?", [url]) { r in
            Sheet(url: r.str(0), songId: r.str(1), title: r.str(2), artist: r.str(3), key: r.str(4), capo: r.int(5),
                  tuning: r.str(6), text: r.str(7), versions: Sheet.versions(from: r.str(8)))
        }.first
    }

    func hasSheet(_ url: String) -> Bool {
        !query("SELECT 1 FROM sheets WHERE url = ?", [url]) { _ in 1 }.isEmpty
    }

    func saveSheet(_ s: Sheet) {
        exec("INSERT OR REPLACE INTO sheets VALUES(?,?,?,?,?,?,?,?,?,?)",
             [s.url, s.songId, s.title, s.artist, s.key, s.capo, s.tuning, s.text, s.versionsJSON, now])
    }

    func nextLocalId() -> String {
        let n = query("SELECT COALESCE(MAX(CAST(SUBSTR(song_id, 7) AS INTEGER)), 0) FROM sheets WHERE song_id LIKE 'local/%'") { $0.int(0) }.first ?? 0
        return "local/\(n + 1)"
    }

    func localSongs() -> [SongRef] {
        query("SELECT song_id, title, artist FROM sheets WHERE url LIKE 'local:%' ORDER BY title COLLATE NOCASE") {
            SongRef(id: $0.str(0), title: $0.str(1), artist: $0.str(2))
        }
    }

    func deleteSong(_ id: String) {
        exec("DELETE FROM sheets WHERE song_id = ?", [id])
        exec("DELETE FROM favs WHERE kind = 'song' AND id = ?", [id])
        exec("DELETE FROM playlist_items WHERE song_id = ?", [id])
        exec("DELETE FROM recents WHERE song_id = ?", [id])
        exec("DELETE FROM prefs WHERE song_id = ?", [id])
    }

    func cachedCount() -> Int {
        query("SELECT COUNT(*) FROM sheets WHERE url NOT LIKE 'local:%'") { $0.int(0) }.first ?? 0
    }

    func clearCache() { exec("DELETE FROM sheets WHERE url NOT LIKE 'local:%'") }

    func updateSongMeta(_ id: String, title: String, artist: String) {
        guard !title.isEmpty else { return }
        exec("UPDATE playlist_items SET title = ?, artist = ? WHERE song_id = ?", [title, artist, id])
        exec("UPDATE recents SET title = ?, artist = ? WHERE song_id = ?", [title, artist, id])
        exec("UPDATE favs SET title = ?, sub = ? WHERE kind = 'song' AND id = ?", [title, artist, id])
    }

    // MARK: - favorites

    struct Fav: Hashable { var id, title, sub, extra: String }

    func isFav(_ kind: String, _ id: String) -> Bool {
        !query("SELECT 1 FROM favs WHERE kind = ? AND id = ?", [kind, id]) { _ in 1 }.isEmpty
    }

    func setFav(_ kind: String, _ id: String, title: String, sub: String = "", extra: String = "", on: Bool) {
        if on {
            exec("INSERT OR REPLACE INTO favs VALUES(?,?,?,?,?,?)", [kind, id, title, sub, extra, now])
        } else {
            exec("DELETE FROM favs WHERE kind = ? AND id = ?", [kind, id])
        }
    }

    func favs(_ kind: String) -> [Fav] {
        query("SELECT id, title, sub, extra FROM favs WHERE kind = ? ORDER BY title COLLATE NOCASE", [kind]) {
            Fav(id: $0.str(0), title: $0.str(1), sub: $0.str(2), extra: $0.str(3))
        }
    }

    // MARK: - playlists

    func playlists() -> [Playlist] {
        query("SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist = p.id) FROM playlists p ORDER BY p.name COLLATE NOCASE") {
            Playlist(id: $0.int64(0), name: $0.str(1), count: $0.int(2))
        }
    }

    @discardableResult
    func createPlaylist(_ name: String) -> Int64 {
        exec("INSERT INTO playlists(name, created) VALUES(?, ?)", [name, now])
        return sqlite3_last_insert_rowid(db)
    }

    func renamePlaylist(_ id: Int64, _ name: String) { exec("UPDATE playlists SET name = ? WHERE id = ?", [name, id]) }

    func deletePlaylist(_ id: Int64) {
        exec("DELETE FROM playlist_items WHERE playlist = ?", [id])
        exec("DELETE FROM playlists WHERE id = ?", [id])
    }

    func playlistSongs(_ id: Int64) -> [SongRef] {
        query("SELECT song_id, title, artist FROM playlist_items WHERE playlist = ? ORDER BY pos", [id]) {
            SongRef(id: $0.str(0), title: $0.str(1), artist: $0.str(2))
        }
    }

    func addToPlaylist(_ id: Int64, _ s: SongRef) {
        let pos = query("SELECT COALESCE(MAX(pos), -1) + 1 FROM playlist_items WHERE playlist = ?", [id]) { $0.int(0) }.first ?? 0
        exec("INSERT OR IGNORE INTO playlist_items VALUES(?,?,?,?,?)", [id, pos, s.id, s.title, s.artist])
    }

    /// Which of `songIds` each playlist already has (playlists with none are left out).
    func playlistsHaving(_ songIds: [String]) -> [Int64: Set<String>] {
        guard !songIds.isEmpty else { return [:] }
        let marks = songIds.map { _ in "?" }.joined(separator: ",")
        var out: [Int64: Set<String>] = [:]
        let rows = query("SELECT playlist, song_id FROM playlist_items WHERE song_id IN (\(marks))", songIds.map { $0 as Any? }) {
            ($0.int64(0), $0.str(1))
        }
        for (p, s) in rows { out[p, default: []].insert(s) }
        return out
    }

    /// Whether the song is in the library: a favorite or in a playlist.
    func inLibrary(_ songId: String) -> Bool {
        isFav("song", songId) || !query("SELECT 1 FROM playlist_items WHERE song_id = ? LIMIT 1", [songId]) { _ in 1 }.isEmpty
    }

    func removeFromPlaylist(_ id: Int64, _ songId: String) {
        exec("DELETE FROM playlist_items WHERE playlist = ? AND song_id = ?", [id, songId])
    }

    func reorderPlaylist(_ id: Int64, _ songs: [SongRef]) {
        exec("BEGIN")
        for (i, s) in songs.enumerated() {
            exec("UPDATE playlist_items SET pos = ? WHERE playlist = ? AND song_id = ?", [i, id, s.id])
        }
        exec("COMMIT")
    }

    // MARK: - per-song settings

    func prefs(_ id: String) -> SongPrefs {
        query("SELECT transpose, speed, simplify, capo, section FROM prefs WHERE song_id = ?", [id]) {
            SongPrefs(transpose: $0.int(0), speed: max(1, $0.int(1)), simplify: $0.int(2) != 0, capo: $0.int(3), section: $0.str(4))
        }.first ?? SongPrefs()
    }

    func savePrefs(_ id: String, _ p: SongPrefs) {
        exec("INSERT OR REPLACE INTO prefs(song_id, transpose, speed, simplify, capo, section) VALUES(?,?,?,?,?,?)",
             [id, p.transpose, p.speed, p.simplify, p.capo, p.section])
    }

    // MARK: - history

    func touchRecent(_ s: SongRef) {
        exec("INSERT OR REPLACE INTO recents VALUES(?,?,?,?)", [s.id, s.title, s.artist, now])
        // Long enough a memory to sort a big playlist by when each song was last played.
        exec("DELETE FROM recents WHERE song_id NOT IN (SELECT song_id FROM recents ORDER BY ts DESC LIMIT 1000)")
    }

    func recents(limit: Int = 20) -> [SongRef] {
        query("SELECT song_id, title, artist FROM recents ORDER BY ts DESC LIMIT ?", [limit]) {
            SongRef(id: $0.str(0), title: $0.str(1), artist: $0.str(2))
        }
    }

    func clearRecents() { exec("DELETE FROM recents") }

    /// When each song was last opened (milliseconds), for the ones still remembered.
    func lastPlayed() -> [String: Int] {
        var m: [String: Int] = [:]
        for (k, v) in query("SELECT song_id, ts FROM recents", [], { ($0.str(0), $0.int(1)) }) { m[k] = v }
        return m
    }

    /// A song's album; all blank when the search was asked and knew none.
    struct SongInfo {
        var album: String
        var albumSlug: String
        var year: Int
    }

    func songInfo() -> [String: SongInfo] {
        var m: [String: SongInfo] = [:]
        let rows = query("SELECT song_id, album, album_slug, year FROM song_info", [], {
            ($0.str(0), SongInfo(album: $0.str(1), albumSlug: $0.str(2), year: $0.int(3)))
        })
        for (k, v) in rows { m[k] = v }
        return m
    }

    func saveSongInfo(_ songId: String, _ i: SongInfo) {
        exec("INSERT OR REPLACE INTO song_info VALUES(?,?,?,?)", [songId, i.album, i.albumSlug, i.year])
    }

    // MARK: - picture URLs

    func allImages() -> [String: String] {
        var m: [String: String] = [:]
        for (k, v) in query("SELECT key, url FROM images", [], { ($0.str(0), $0.str(1)) }) { m[k] = v }
        return m
    }

    func saveImages(_ map: [String: String]) {
        exec("BEGIN")
        for (k, v) in map { exec("INSERT OR REPLACE INTO images VALUES(?, ?)", [k, v]) }
        exec("COMMIT")
    }
}
