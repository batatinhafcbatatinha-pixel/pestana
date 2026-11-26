import Foundation

let siteURL = "https://www.cifraclub.com.br"

/// A song. `id` is "artist-slug/song-slug", "local/<n>" for imports, "web/<host/path>" for links.
struct SongRef: Hashable, Identifiable {
    var id: String
    var title: String
    var artist: String
    var album: String = ""
    var albumSlug: String = ""

    var artistSlug: String {
        if isLocal { return "" }
        let parts = id.split(separator: "/").map(String.init)
        if id.hasPrefix("web/") {
            // One version kept by its own link: "web/host/artist/song/x.html".
            return parts.count == 5 && parts[1].hasSuffix("cifraclub.com.br") && parts[4].hasSuffix(".html") ? parts[2] : ""
        }
        return parts.first ?? ""
    }
    var isLocal: Bool { id.hasPrefix("local/") }
    var url: String {
        if isLocal { return "local:\(id)" }
        if id.hasPrefix("web/") { return "https://" + id.dropFirst(4) }
        return "\(siteURL)/\(id)/"
    }

    static func fromLink(_ link: String, title: String, artist: String) -> SongRef {
        var u = link
        if let r = u.range(of: "://") { u = String(u[r.upperBound...]) }
        u = String(u.split(separator: "#").first ?? "")
        u = String(u.split(separator: "?").first ?? "")
        let host = String(u.split(separator: "/").first ?? "")
        let path = u.split(separator: "/").dropFirst().joined(separator: "/")
        if host.hasSuffix("cifraclub.com.br") && path.split(separator: "/").count == 2 {
            return SongRef(id: path, title: title, artist: artist)
        }
        return SongRef(id: "web/\(u)", title: title, artist: artist)
    }
}

struct ArtistRef: Hashable {
    var slug: String
    var name: String
    var img: String = ""
}

struct AlbumRef: Hashable {
    var slug: String
    var name: String
    var artist: String
    var artistSlug: String
    var year: String
    var img: String = ""
    /// "album" or "single" when the discography says which; "" otherwise.
    var kind: String = ""
    var key: String { "\(artistSlug)/\(slug)" }
}

struct SearchResults {
    var artists: [ArtistRef] = []
    var songs: [SongRef] = []
    var albums: [AlbumRef] = []
    var isEmpty: Bool { artists.isEmpty && songs.isEmpty && albums.isEmpty }
}

struct Version: Hashable {
    var label: String
    var url: String
}

struct Sheet {
    var url: String
    var songId: String
    var title: String
    var artist: String
    var key: String
    var capo: Int
    var tuning: String
    var text: String
    var versions: [Version]

    static func fromExtract(_ json: String, url: String, songId: String, title: String, artist: String) -> Sheet {
        let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
        let vs = (o["versions"] as? [[String: Any]] ?? []).compactMap { v -> Version? in
            guard let l = v["label"] as? String, let u = v["url"] as? String else { return nil }
            return Version(label: l, url: u)
        }
        func s(_ k: String) -> String { o[k] as? String ?? "" }
        // A version imported by its own link says which one it is: "I Miss You (Versão 7)".
        var name = s("title").isEmpty ? title : s("title")
        let version = s("version")
        if songId.hasPrefix("web/") && !version.isEmpty && !name.isEmpty && !name.hasSuffix("(\(version))") {
            name += " (\(version))"
        }
        return Sheet(
            url: url, songId: songId,
            title: name,
            artist: s("artist").isEmpty ? artist : s("artist"),
            key: s("key"), capo: o["capo"] as? Int ?? 0, tuning: s("tuning"), text: s("text"), versions: vs
        )
    }

    var versionsJSON: String {
        let arr = versions.map { ["label": $0.label, "url": $0.url] }
        let data = (try? JSONSerialization.data(withJSONObject: arr)) ?? Data("[]".utf8)
        return String(decoding: data, as: UTF8.self)
    }

    static func versions(from json: String) -> [Version] {
        let arr = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [[String: String]] ?? []
        return arr.compactMap { v in v["label"].flatMap { l in v["url"].map { Version(label: l, url: $0) } } }
    }
}

/// Mirrors the styles and kinds of core/src/sheet.rs.
enum SpanStyle: Int {
    case lyric = 0, chord, header, tab, note, tabNote, tabTech
}

struct Span {
    var style: SpanStyle
    var text: String
}

struct Line {
    var kind: Int
    var spans: [Span]
}

struct Rendered {
    var key = ""
    var origKey = ""
    var hasTabs = false
    var hasSolos = false
    var hasRiffs = false
    var hasChords = false
    var chords: [String] = []
    var lines: [Line] = []

    static func parse(_ json: String) -> Rendered {
        guard let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else { return Rendered() }
        var r = Rendered()
        r.key = o["key"] as? String ?? ""
        r.origKey = o["origKey"] as? String ?? ""
        r.hasTabs = o["hasTabs"] as? Bool ?? false
        r.hasSolos = o["hasSolos"] as? Bool ?? false
        r.hasRiffs = o["hasRiffs"] as? Bool ?? false
        r.hasChords = o["hasChords"] as? Bool ?? false
        r.chords = o["chords"] as? [String] ?? []
        for case let a as [Any] in (o["lines"] as? [Any] ?? []) {
            guard let kind = a.first as? Int else { continue }
            var spans: [Span] = []
            var k = 1
            while k + 1 < a.count {
                if let st = a[k] as? Int, let tx = a[k + 1] as? String {
                    spans.append(Span(style: SpanStyle(rawValue: st) ?? .lyric, text: tx))
                }
                k += 2
            }
            r.lines.append(Line(kind: kind, spans: spans))
        }
        return r
    }
}

struct ChordShape {
    var frets: [Int]
    var barre: Int
    var barreFrom: Int
    /// Root string of a bass box, -1 for guitar shapes.
    var root: Int = -1

    private static var cache: [String: ChordShape?] = [:]
    private static let lock = NSLock()

    static func of(_ name: String, bass: Bool = false) -> ChordShape? {
        lock.lock(); defer { lock.unlock() }
        let key = (bass ? "b:" : "g:") + name
        if let c = cache[key] { return c }
        let json = bass ? Core.bassShape(name) : Core.chordShape(name)
        let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any]
        var shape: ChordShape?
        if let f = o?["frets"] as? [Int], f.count >= 4 {
            shape = ChordShape(frets: f, barre: o?["barre"] as? Int ?? 0, barreFrom: o?["barreFrom"] as? Int ?? 0, root: o?["root"] as? Int ?? -1)
        }
        cache[key] = shape
        return shape
    }
}

/// Per-song reader settings, remembered locally.
struct SongPrefs: Equatable {
    var transpose = 0
    var speed = 8
    var simplify = false
    var capo = 0
}

struct Playlist: Hashable, Identifiable {
    var id: Int64
    var name: String
    var count: Int
}
