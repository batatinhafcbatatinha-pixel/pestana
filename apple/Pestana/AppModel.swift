import Foundation
import SwiftUI

/// Picture URLs by artist slug or "artist/album". Only URLs are stored; the
/// pictures stay in memory (URLCache with no disk), as in Spotifast.
@MainActor
final class Images: ObservableObject {
    static let shared = Images()
    @Published private(set) var urls: [String: String] = [:]
    var store: Store?
    private var tried = Set<String>()

    func load(_ store: Store) {
        self.store = store
        urls = store.allImages()
    }

    func artist(_ slug: String) -> String? { urls[slug] }
    func album(_ key: String) -> String? { urls[key] }

    func remember(_ map: [String: String]) {
        let fresh = map.filter { !$0.value.isEmpty && urls[$0.key] != $0.value }
        guard !fresh.isEmpty else { return }
        urls.merge(fresh) { _, new in new }
        store?.saveImages(fresh)
    }

    /// Looks up an artist's photo once (songs imported from a playlist, for example).
    func ensureArtist(_ slug: String) {
        guard !slug.isEmpty, urls[slug] == nil, !tried.contains(slug) else { return }
        tried.insert(slug)
        Task { _ = try? await Source.search(Source.prettySlug(slug)) }
    }
}

/// App-wide settings stored in UserDefaults.
@MainActor
final class Settings: ObservableObject {
    private let d = UserDefaults.standard

    @Published var theme: String { didSet { d.set(theme, forKey: "theme") } }
    @Published var fontSize: Double { didSet { d.set(fontSize, forKey: "fontSize") } }
    @Published var lineSpacing: Double { didSet { d.set(lineSpacing, forKey: "lineSpacing") } }
    @Published var showTabs: Bool { didSet { d.set(showTabs, forKey: "showTabs") } }
    @Published var showDiagrams: Bool { didSet { d.set(showDiagrams, forKey: "showDiagrams") } }
    @Published var accidentals: Int { didSet { d.set(accidentals, forKey: "accidentals") } }
    @Published var a4: Double { didSet { d.set(a4, forKey: "a4") } }
    @Published var keepScreenOn: Bool { didSet { d.set(keepScreenOn, forKey: "keepScreenOn") } }
    @Published var leftHanded: Bool { didSet { d.set(leftHanded, forKey: "leftHanded") } }
    @Published var tuning: String { didSet { d.set(tuning, forKey: "tuning") } }
    @Published var bpm: Int { didSet { d.set(bpm, forKey: "bpm") } }
    @Published var beats: Int { didSet { d.set(beats, forKey: "beats") } }
    @Published var subdivision: Int { didSet { d.set(subdivision, forKey: "subdivision") } }
    @Published var accent: Bool { didSet { d.set(accent, forKey: "accent") } }
    @Published var chordColor: Int { didSet { d.set(chordColor, forKey: "chordColor") } }
    @Published var lyricColor: Int { didSet { d.set(lyricColor, forKey: "lyricColor") } }
    /// "guitar" or "bass": diagrams, preferred version and tuner follow it.
    @Published var instrument: String {
        didSet {
            d.set(instrument, forKey: "instrument")
            if oldValue != instrument { tuning = instrument == "bass" ? "bass_4" : "guitar_std" }
        }
    }

    init() {
        d.register(defaults: [
            "theme": "auto", "fontSize": 15.0, "lineSpacing": 1.25, "showTabs": true, "showDiagrams": true,
            "accidentals": 0, "a4": 440.0, "keepScreenOn": true, "leftHanded": false, "tuning": "guitar_std",
            "bpm": 90, "beats": 4, "subdivision": 1, "accent": true, "chordColor": 0, "lyricColor": 0, "instrument": "guitar",
        ])
        theme = d.string(forKey: "theme") ?? "auto"
        fontSize = d.double(forKey: "fontSize")
        lineSpacing = d.double(forKey: "lineSpacing")
        showTabs = d.bool(forKey: "showTabs")
        showDiagrams = d.bool(forKey: "showDiagrams")
        accidentals = d.integer(forKey: "accidentals")
        a4 = d.double(forKey: "a4")
        keepScreenOn = d.bool(forKey: "keepScreenOn")
        leftHanded = d.bool(forKey: "leftHanded")
        tuning = d.string(forKey: "tuning") ?? "guitar_std"
        bpm = d.integer(forKey: "bpm")
        beats = d.integer(forKey: "beats")
        subdivision = d.integer(forKey: "subdivision")
        accent = d.bool(forKey: "accent")
        chordColor = d.integer(forKey: "chordColor")
        lyricColor = d.integer(forKey: "lyricColor")
        instrument = d.string(forKey: "instrument") ?? "guitar"
    }
}

let instruments = [("guitar", "Violão / Guitarra"), ("bass", "Baixo")]

enum Route: Hashable {
    case artist(ArtistRef)
    case album(AlbumRef)
    case song(SongRef, [SongRef])
    case playlist(Int64, String)
    case importSheet(String)
    case importPlaylist(String)
    case settings
}

@MainActor
final class AppModel: ObservableObject {
    let store = Store()
    let settings = Settings()

    /// Bumped after any change to favorites/playlists so lists reload.
    @Published var version = 0
    @Published var toast: String?
    /// Offline download progress (done, total).
    @Published var download: (Int, Int)?

    init() {
        // Pictures in memory only.
        URLCache.shared = URLCache(memoryCapacity: 48 * 1024 * 1024, diskCapacity: 0)
        Images.shared.load(store)
        seedIfEmpty()
    }

    func changed() { version += 1 }

    func say(_ msg: String) {
        toast = msg
        Task {
            try? await Task.sleep(nanoseconds: 2_200_000_000)
            if toast == msg { toast = nil }
        }
    }

    func toggleFav(song s: SongRef) -> Bool {
        let on = !store.isFav("song", s.id)
        store.setFav("song", s.id, title: s.title, sub: s.artist, on: on)
        changed()
        return on
    }

    func toggleFav(artist a: ArtistRef) -> Bool {
        let on = !store.isFav("artist", a.slug)
        store.setFav("artist", a.slug, title: a.name, on: on)
        changed()
        return on
    }

    func toggleFav(album a: AlbumRef) -> Bool {
        let on = !store.isFav("album", a.key)
        store.setFav("album", a.key, title: a.name, sub: a.artist, extra: a.year, on: on)
        changed()
        return on
    }

    /// Saves every sheet of `songs` that is not on the device yet.
    func downloadForOffline(_ songs: [SongRef]) {
        guard download == nil else { say("Já estou baixando outras cifras"); return }
        let todo = songs.filter { !$0.isLocal && !store.hasSheet($0.url) }
        guard !todo.isEmpty else { say("Tudo já está salvo para usar offline"); return }
        download = (0, todo.count)
        Task {
            var ok = 0
            for (i, s) in todo.enumerated() {
                if let sheet = try? await Source.fetchSheet(s.url, songId: s.id, title: s.title, artist: s.artist) {
                    store.saveSheet(sheet)
                    store.updateSongMeta(s.id, title: sheet.title, artist: sheet.artist)
                    ok += 1
                }
                download = (i + 1, todo.count)
            }
            download = nil
            changed()
            say(ok == todo.count ? "\(ok) cifras salvas para offline" : "\(ok) de \(todo.count) cifras salvas")
        }
    }

    private func seedIfEmpty() {
        guard !UserDefaults.standard.bool(forKey: "seeded") else { return }
        Samples.all.forEach(store.saveSheet)
        UserDefaults.standard.set(true, forKey: "seeded")
    }
}
