import SwiftUI

/// How a list of songs is ordered. Picking the chosen order again turns it around.
enum SongSort: Int, CaseIterable {
    /// The order the list came in; each screen names it.
    case manual = 0, played, release, title, artist, album

    var label: String {
        switch self {
        case .manual: return ""
        case .played: return "Tocadas por último"
        case .release: return "Lançamento"
        case .title: return "A–Z"
        case .artist: return "Artista"
        case .album: return "Álbum"
        }
    }
}

/// What a downloaded sheet tells about its song, for the filters.
struct SheetFacts {
    var solo: Bool
    var riff: Bool
    var capo: Bool
    var tuning: String
}

/// Order and filters of one list of songs (favorites, a playlist, an album).
/// The order is remembered under `key`.
final class SongOrder: ObservableObject {
    let key: String
    let firstLabel: String
    let sorts: [SongSort]

    @Published var sort: SongSort
    @Published var reversed: Bool
    @Published var onlySolo = false
    @Published var onlyRiff = false
    @Published var onlyCapo = false
    @Published var tuning: String?
    @Published var facts: [String: SheetFacts] = [:]
    @Published var info: [String: Store.SongInfo] = [:]
    @Published var played: [String: Int] = [:]
    @Published var lookingUp = false

    init(key: String, firstLabel: String, sorts: [SongSort] = SongSort.allCases) {
        self.key = key
        self.firstLabel = firstLabel
        self.sorts = sorts
        let d = UserDefaults.standard
        let saved = SongSort(rawValue: d.integer(forKey: "sort.\(key)")) ?? .manual
        sort = sorts.contains(saved) ? saved : .manual
        reversed = d.bool(forKey: "reversed.\(key)")
    }

    func name(_ s: SongSort) -> String { s == .manual ? firstLabel : s.label }

    func choose(_ s: SongSort) {
        if s == sort {
            reversed.toggle()
        } else {
            sort = s
            reversed = false
        }
        let d = UserDefaults.standard
        d.set(sort.rawValue, forKey: "sort.\(key)")
        d.set(reversed, forKey: "reversed.\(key)")
    }

    /// Tunings other than the standard one that the list actually has.
    func tunings(_ songs: [SongRef]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for s in songs {
            if let tn = facts[s.id]?.tuning, !tn.isEmpty, !seen.contains(tn) {
                seen.insert(tn)
                out.append(tn)
            }
        }
        return out.sorted()
    }

    /// The tuning being filtered by; one that left the list stops filtering.
    func wanted(_ songs: [SongRef]) -> String? {
        guard let tn = tuning else { return nil }
        return tn.isEmpty || tunings(songs).contains(tn) ? tn : nil
    }

    func filters(_ songs: [SongRef]) -> Int {
        var n = 0
        if onlySolo { n += 1 }
        if onlyRiff { n += 1 }
        if onlyCapo { n += 1 }
        if wanted(songs) != nil { n += 1 }
        return n
    }

    func clearFilters() {
        onlySolo = false
        onlyRiff = false
        onlyCapo = false
        tuning = nil
    }

    /// Whether the list shows exactly as it was put together (so it can be rearranged by hand).
    func untouched(_ songs: [SongRef]) -> Bool {
        sort == .manual && !reversed && filters(songs) == 0
    }

    func shown(_ songs: [SongRef]) -> [SongRef] {
        let tn = wanted(songs)
        let kept = songs.filter { s in
            let f = facts[s.id]
            if onlySolo && f?.solo != true { return false }
            if onlyRiff && f?.riff != true { return false }
            if onlyCapo && f?.capo != true { return false }
            if let tn, f?.tuning != tn { return false }
            return true
        }
        func before(_ a: String, _ b: String) -> Bool { a.localizedCaseInsensitiveCompare(b) == .orderedAscending }
        func year(_ s: SongRef) -> Int { info[s.id]?.year ?? 0 }
        func albumName(_ s: SongRef) -> String { info[s.id]?.album ?? "" }
        // Songs the order knows nothing about (no album found, never played) stay at the end either way.
        var known = kept
        var unknown: [SongRef] = []
        switch sort {
        case .manual:
            break
        case .played:
            known = kept.filter { played[$0.id] != nil }.sorted { (played[$0.id] ?? 0) > (played[$1.id] ?? 0) }
            unknown = kept.filter { played[$0.id] == nil }
        case .release:
            known = kept.filter { year($0) > 0 }.sorted { year($0) > year($1) }
            unknown = kept.filter { year($0) <= 0 }
        case .title:
            known = kept.sorted { before($0.title, $1.title) }
        case .artist:
            known = kept.sorted { a, b in
                a.artist.localizedCaseInsensitiveCompare(b.artist) == .orderedSame ? before(a.title, b.title) : before(a.artist, b.artist)
            }
        case .album:
            known = kept.filter { !albumName($0).isEmpty }.sorted { before(albumName($0), albumName($1)) }
            unknown = kept.filter { albumName($0).isEmpty }
        }
        if reversed { known = Array(known.reversed()) }
        return known + unknown
    }

    /// Ordered by album or release, each row says which album and year put it there.
    func subtitle(_ s: SongRef) -> String {
        guard sort == .release || sort == .album, let i = info[s.id] else { return s.artist }
        return [s.artist, i.album, i.year > 0 ? String(i.year) : ""].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// Solos, riffs, capo and tuning come from the sheets saved on the device.
    @MainActor
    func load(_ songs: [SongRef], store: Store) async {
        played = store.lastPlayed()
        if info.isEmpty { info = store.songInfo() }
        var sheets: [(String, String, String, Int, String)] = []
        for s in songs {
            if let sh = store.sheet(s.url) { sheets.append((s.id, sh.text, sh.key, sh.capo, sh.tuning)) }
        }
        let list = sheets
        let found = await Task.detached { () -> [String: SheetFacts] in
            var out: [String: SheetFacts] = [:]
            for (id, text, key, capo, tuning) in list {
                // The solos view is the shortest thing to render; the flags cover the whole sheet.
                let json = Core.render(text, key: key, transpose: 0, simplify: false, view: 2, showTabs: true, accidentals: 0, cols: 1000)
                let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
                out[id] = SheetFacts(
                    solo: o["hasSolos"] as? Bool ?? false, riff: o["hasRiffs"] as? Bool ?? false,
                    capo: capo > 0, tuning: tuning.trimmingCharacters(in: .whitespaces)
                )
            }
            return out
        }.value
        facts = found
    }

    /// Albums and years are asked from the search, once per song, when an order needs them.
    @MainActor
    func lookUp(_ songs: [SongRef], store: Store) async {
        guard sort == .release || sort == .album else { return }
        let todo = songs.filter { !$0.isLocal && !$0.id.hasPrefix("web/") && info[$0.id] == nil }
        if todo.isEmpty { return }
        lookingUp = true
        for s in todo {
            if Task.isCancelled { break }
            guard let found = await Source.songAlbum(s) else { continue }
            store.saveSongInfo(s.id, found)
            info[s.id] = found
        }
        lookingUp = false
    }
}

/// One line above a list of songs: the order (a menu), an arrow that turns it
/// around, and the filters (another menu).
struct SongOrderBar: View {
    @ObservedObject var order: SongOrder
    let songs: [SongRef]
    @EnvironmentObject var model: AppModel
    @Environment(\.reading) private var t

    private func chip(_ text: String, selected: Bool = false) -> some View {
        Text(text)
            .font(.subheadline.weight(selected ? .semibold : .regular))
            .foregroundColor(selected ? t.bg : t.text)
            .padding(.horizontal, 14).padding(.vertical, 7)
            .background(Capsule().fill(selected ? t.text : t.surface))
    }

    private func tuningToggle(_ label: String, _ value: String, _ current: String?) -> some View {
        Toggle(label, isOn: Binding(
            get: { current == value },
            set: { on in order.tuning = on ? value : nil }
        ))
    }

    var body: some View {
        let tunings = order.tunings(songs)
        let wanted = order.wanted(songs)
        let count = order.filters(songs)
        let ids = songs.map(\.id).joined(separator: "|")
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Menu {
                    ForEach(order.sorts, id: \.self) { s in
                        // Picking the order already chosen turns it around.
                        Button { order.choose(s) } label: {
                            if s == order.sort {
                                Label(order.name(s), systemImage: "checkmark")
                            } else {
                                Text(order.name(s))
                            }
                        }
                    }
                } label: { chip(order.name(order.sort)) }
                    .buttonStyle(.plain)
                    #if os(macOS)
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                    #endif
                // The arrow shows which way the order runs; tapping it turns the order around.
                Button { order.choose(order.sort) } label: {
                    Image(systemName: order.reversed ? "arrow.up" : "arrow.down")
                        .font(.subheadline)
                        .foregroundColor(t.text)
                        .frame(width: 32, height: 32)
                        .background(Circle().fill(t.surface))
                }
                .buttonStyle(.plain)
                .help("Inverter a ordem")
                Spacer()
                if !order.facts.isEmpty {
                    Menu {
                        Toggle("Com solo", isOn: $order.onlySolo)
                        Toggle("Com riff", isOn: $order.onlyRiff)
                        Toggle("Com capotraste", isOn: $order.onlyCapo)
                        if !tunings.isEmpty {
                            Divider()
                            tuningToggle("Afinação padrão", "", wanted)
                            ForEach(tunings, id: \.self) { tn in tuningToggle(tn, tn, wanted) }
                        }
                        if count > 0 {
                            Divider()
                            Button("Limpar filtros") { order.clearFilters() }
                        }
                    } label: { chip(count == 0 ? "Filtros" : "Filtros · \(count)", selected: count > 0) }
                        .buttonStyle(.plain)
                        #if os(macOS)
                        .menuStyle(.borderlessButton)
                        .fixedSize()
                        #endif
                }
            }
            if order.lookingUp { ProgressView().frame(maxWidth: .infinity) }
        }
        .task(id: "\(ids)#\(model.version)") { await order.load(songs, store: model.store) }
        .task(id: "\(ids)#\(order.sort.rawValue)") { await order.lookUp(songs, store: model.store) }
    }
}
