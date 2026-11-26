import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

private struct SheetSection: Hashable {
    var label: String
    var url: String?
    var view: Int
    var simplified = false
    /// When its page does not exist, show the main sheet instead.
    var fallback = false
}

private enum Panel { case none, key, text, more }

private let sharpKeys = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
private let flatKeys = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]

/// Transpose a key name like "F#m" for display.
private func shiftKey(_ key: String, _ semis: Int) -> String {
    guard !key.isEmpty else { return key }
    let chars = Array(key)
    let two = chars.count > 1 && (chars[1] == "#" || chars[1] == "b")
    let root = String(chars.prefix(two ? 2 : 1))
    guard let idx = sharpKeys.firstIndex(of: root) ?? flatKeys.firstIndex(of: root) else { return key }
    let minor = key.hasSuffix("m")
    let n = ((idx + semis) % 12 + 12) % 12
    let majorPc = minor ? (n + 3) % 12 : n
    let flats = [5, 10, 3, 8, 1].contains(majorPc)
    return (flats ? flatKeys : sharpKeys)[n] + (minor ? "m" : "")
}

private func monoCharWidth(_ size: CGFloat) -> CGFloat {
    #if canImport(UIKit)
    let f = UIFont.monospacedSystemFont(ofSize: size, weight: .regular)
    #else
    let f = NSFont.monospacedSystemFont(ofSize: size, weight: .regular)
    #endif
    return ("0000000000" as NSString).size(withAttributes: [.font: f]).width / 10
}

struct SongView: View {
    let song: SongRef
    let queue: [SongRef]

    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: Settings
    @Environment(\.reading) private var t

    @State private var main: Sheet?
    @State private var error: String?
    @State private var prefs = SongPrefs()
    @State private var fav = false
    @State private var flags: Rendered?
    @State private var selected = 0
    @State private var extra: [String: Sheet] = [:]
    @State private var failed = Set<String>()
    @State private var rendered = Rendered()
    @State private var cols = 40
    @State private var auto = false
    @State private var panel: Panel = .none
    @State private var chordPopup: String?
    @State private var addToList = false
    @State private var visible = Set<Int>()
    @State private var loaded = false
    @State private var current: SongRef?
    @State private var bassSheet: Sheet?
    @State private var bassMissing = false

    private var shown: SongRef { current ?? song }

    // MARK: derived

    private var bass: Bool { settings.instrument == "bass" }
    private var usingBass: Bool { bass && bassSheet != nil }

    /// The sheet every tab derives from: Cifra Club's bass version
    /// (<song>/tabs-baixo/) in bass mode, the main page otherwise.
    private var base: Sheet? {
        if !bass { return main }
        if let b = bassSheet { return b }
        return bassMissing ? main : nil
    }

    private static func isBassVersion(_ v: Version) -> Bool {
        v.label.lowercased().contains("baixo") || v.url.contains("baixo") || v.url.contains("bass")
    }

    private var sections: [SheetSection] {
        guard let m = base else { return [] }
        var out = [SheetSection(label: "Principal", url: m.url, view: 0)]
        let simp = m.versions.first { $0.label.lowercased().contains("simplif") || $0.url.contains("simplificada") }
        out.append(SheetSection(label: "Simplificada", url: simp?.url ?? (m.url.hasPrefix(siteURL) ? Source.simplifiedURL(m.url) : nil), view: 0, simplified: true, fallback: true))
        if let f = flags {
            if f.hasTabs && f.hasChords { out.append(SheetSection(label: "Acordes", url: m.url, view: 1)) }
            if f.hasSolos { out.append(SheetSection(label: "Solos", url: m.url, view: 2)) }
            if f.hasRiffs { out.append(SheetSection(label: "Riffs", url: m.url, view: 3)) }
        }
        for v in m.versions where !v.label.lowercased().contains("simplif") && !v.url.contains("simplificada") && !v.label.lowercased().contains("principal") && !SongView.isBassVersion(v) {
            out.append(SheetSection(label: v.label.prefix(1).uppercased() + v.label.dropFirst(), url: v.url, view: 0))
        }
        return out
    }

    private var section: SheetSection? { sections.indices.contains(selected) ? sections[selected] : sections.first }

    private var derived: Bool {
        guard let s = section, s.fallback else { return false }
        return s.url == nil || failed.contains(s.url ?? "")
    }

    private var source: Sheet? {
        guard let m = base, let s = section else { return nil }
        if derived || s.url == m.url { return m }
        return s.url.flatMap { extra[$0] }
    }

    private var capo: Int { max(0, prefs.capo) }

    /// A bass version is mostly tablature: keep it visible there.
    private var showTabs: Bool { settings.showTabs || (usingBass && (section?.view ?? 0) == 0) }

    private var renderKey: String {
        "\(source?.url ?? "")|\(source?.text.count ?? 0)|\(prefs.transpose - capo)|\(prefs.simplify || (derived && section?.simplified == true))|\(section?.view ?? 0)|\(derived)|\(showTabs)|\(settings.accidentals)|\(cols)"
    }

    // MARK: body

    var body: some View {
        VStack(spacing: 0) {
            if sections.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(sections.indices, id: \.self) { i in Pill(text: sections[i].label, selected: i == selected) { selected = i } }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 6)
                }
                Divider().overlay(t.line)
            }
            GeometryReader { geo in
                content(width: geo.size.width)
                    .onAppear { updateCols(geo.size.width) }
                    .onChange(of: geo.size.width) { updateCols($0) }
                    .onChange(of: settings.fontSize) { _ in updateCols(geo.size.width) }
            }
            controls
        }
        .background(t.bg.ignoresSafeArea())
        .navigationTitle(main?.title ?? shown.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                FavButton(on: fav) {
                    fav = model.toggleFav(song: SongRef(id: shown.id, title: main?.title ?? shown.title, artist: main?.artist ?? shown.artist))
                }
            }
        }
        .task(id: shown.id) { await load(force: false) }
        .task(id: "\(main?.url ?? "")|\(bass)") { await loadBass() }
        .onChange(of: bass) { _ in selected = 0 }
        .task(id: base?.url) {
            guard let m = base else { return }
            let r = await Task.detached { Rendered.parse(Core.render(m.text, key: m.key, transpose: 0, simplify: false, view: 0, showTabs: true, accidentals: 0, cols: 1000)) }.value
            flags = r
        }
        .task(id: section?.url) { await loadSection() }
        .task(id: renderKey) { await render() }
        .onChange(of: prefs) { model.store.savePrefs(shown.id, $0) }
        .onAppear { keepAwake(settings.keepScreenOn) }
        .onDisappear { keepAwake(false); auto = false }
        .sheet(isPresented: $addToList) {
            AddToPlaylistSheet(song: SongRef(id: shown.id, title: main?.title ?? shown.title, artist: main?.artist ?? shown.artist))
        }
        .sheet(item: Binding(get: { chordPopup.map { ChordName(name: $0) } }, set: { chordPopup = $0?.name })) { c in
            VStack(spacing: 12) {
                ChordDiagramView(name: c.name, width: 170, leftHanded: settings.leftHanded, bass: settings.instrument == "bass")
                Button("Fechar") { chordPopup = nil }
            }
            .padding(28)
            .background(t.surface)
            .environment(\.reading, t)
        }
    }

    @ViewBuilder
    private func content(width: CGFloat) -> some View {
        if main == nil {
            if let error {
                VStack(spacing: 12) {
                    EmptyStateView(title: "Não deu para abrir", message: error)
                    Button("Tentar de novo") { Task { await load(force: true) } }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        infoLine.id(-3)
                        if bass && bassMissing {
                            Text("Esta cifra não tem versão para baixo no site. Mostrando a cifra com diagramas de baixo.")
                                .font(.caption).foregroundColor(t.muted).padding(.top, 4)
                        }
                        if settings.showDiagrams && !rendered.chords.isEmpty && (section?.view ?? 0) < 2 {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 14) {
                                    ForEach(rendered.chords, id: \.self) { c in
                                        ChordDiagramView(name: c, width: 54, leftHanded: settings.leftHanded, bass: settings.instrument == "bass")
                                            .onTapGesture { chordPopup = c }
                                    }
                                }
                                .padding(.vertical, 10)
                            }
                            .id(-2)
                        }
                        if source == nil { ProgressView().padding() }
                        if source != nil && rendered.lines.isEmpty {
                            EmptyStateView(title: "Nada aqui", message: "Esta parte não existe nesta versão da cifra.")
                        }
                        ForEach(rendered.lines.indices, id: \.self) { i in
                            lineView(rendered.lines[i])
                                .id(i)
                                .onAppear { visible.insert(i) }
                                .onDisappear { visible.remove(i) }
                        }
                        footer.id(-1)
                        Spacer().frame(height: 120)
                    }
                    .padding(.horizontal, 16)
                }
                .environment(\.openURL, OpenURLAction { url in
                    if url.scheme == "pestana-chord" {
                        chordPopup = url.host?.removingPercentEncoding ?? url.lastPathComponent
                        return .handled
                    }
                    return .systemAction
                })
                .task(id: auto) { await autoscroll(proxy) }
                .modifier(PageKeys(onPage: { dir in page(proxy, dir) }, onToggle: { auto.toggle() }))
            }
        }
    }

    private func lineView(_ line: Line) -> some View {
        let fs = CGFloat(settings.fontSize)
        var a = AttributedString()
        for sp in line.spans {
            var p = AttributedString(sp.text)
            p.font = .system(size: fs, design: .monospaced)
            switch sp.style {
            case .chord:
                p.foregroundColor = t.chord
                p.font = .system(size: fs, weight: .heavy, design: .monospaced)
                let enc = sp.text.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
                p.link = URL(string: "pestana-chord://\(enc)")
            case .header:
                p.foregroundColor = t.header
                p.font = .system(size: fs, weight: .semibold, design: .monospaced)
            case .tab: p.foregroundColor = t.tab.opacity(0.75)
            case .tabNote:
                p.foregroundColor = t.chord
                p.font = .system(size: fs, weight: .bold, design: .monospaced)
            case .tabTech: p.foregroundColor = t.chord
            case .note:
                p.foregroundColor = t.muted
                p.font = .system(size: fs, design: .monospaced).italic()
            case .lyric: p.foregroundColor = t.text
            }
            a.append(p)
        }
        if line.spans.isEmpty { a = AttributedString(" ") ; a.font = .system(size: fs, design: .monospaced) }
        let extra = max(0, (settings.lineSpacing - 1) * fs)
        return Text(a)
            .tint(t.chord)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .padding(.vertical, extra / 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation { panel = .none } }
    }

    private var infoLine: some View {
        var parts: [String] = []
        if !rendered.key.isEmpty {
            let sounding = shiftKey(rendered.origKey, prefs.transpose)
            if capo > 0 { parts.append("Formas em \(rendered.key) · capo \(capo)ª casa (soa \(sounding))") }
            else { parts.append("Tom: \(rendered.key)" + (prefs.transpose != 0 ? " (original \(rendered.origKey))" : "")) }
        }
        if let m = main {
            if m.capo > 0 && capo == 0 { parts.append("Capotraste na \(m.capo)ª casa") }
            if !m.tuning.isEmpty { parts.append("Afinação: \(m.tuning)") }
        }
        return Text(parts.joined(separator: "   ·   "))
            .font(.footnote).foregroundColor(t.muted)
            .padding(.top, 10)
            .onTapGesture { panel = .key }
    }

    @ViewBuilder
    private var footer: some View {
        if let i = queue.firstIndex(where: { $0.id == shown.id }), queue.count > 1 {
            Divider().overlay(t.line).padding(.top, 28)
            HStack {
                if i > 0 {
                    Button { switchTo(queue[i - 1]) } label: {
                        VStack(alignment: .leading) {
                            Text("‹ Anterior").font(.caption).foregroundColor(t.muted)
                            Text(queue[i - 1].title).foregroundColor(t.text).lineLimit(1)
                        }
                    }.buttonStyle(.plain)
                }
                Spacer()
                Text("\(i + 1)/\(queue.count)").font(.caption).foregroundColor(t.muted)
                Spacer()
                if i < queue.count - 1 {
                    Button { switchTo(queue[i + 1]) } label: {
                        VStack(alignment: .trailing) {
                            Text("Próxima ›").font(.caption.weight(.semibold)).foregroundColor(t.chord)
                            Text(queue[i + 1].title).foregroundColor(t.text).lineLimit(1)
                        }
                    }.buttonStyle(.plain)
                }
            }
            .padding(.vertical, 12)
        }
    }

    // MARK: controls

    private var controls: some View {
        VStack(spacing: 0) {
            switch panel {
            case .key: keyPanel
            case .text: textPanel
            case .more: morePanel
            case .none: EmptyView()
            }
            if panel != .none { Divider().overlay(t.line) }
            HStack(spacing: 10) {
                Button { auto.toggle(); if auto { panel = .none } } label: {
                    Image(systemName: auto ? "pause.fill" : "play.fill")
                        .foregroundColor(t.bg)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(t.chord))
                }
                .buttonStyle(.plain)
                .help("Rolagem automática")
                Slider(value: Binding(get: { Double(prefs.speed) }, set: { prefs.speed = Int($0) }), in: 1...40)
                Text("\(prefs.speed)").font(.subheadline.bold()).foregroundColor(t.text).frame(minWidth: 24)
                Button { panel = panel == .key ? .none : .key } label: {
                    Text(rendered.key.isEmpty ? "Tom" : rendered.key).font(.headline).foregroundColor(t.chord)
                        .padding(.horizontal, 12).frame(height: 36)
                        .background(Capsule().fill(panel == .key ? t.surface2 : t.surface))
                }.buttonStyle(.plain)
                Button { panel = panel == .text ? .none : .text } label: {
                    Image(systemName: "textformat.size").foregroundColor(panel == .text ? t.chord : t.text)
                }.buttonStyle(.plain)
                Button { panel = panel == .more ? .none : .more } label: {
                    Image(systemName: "ellipsis").foregroundColor(panel == .more ? t.chord : t.text).frame(width: 28, height: 36)
                }.buttonStyle(.plain)
            }
            .padding(.horizontal, 12).frame(height: 60)
        }
        .background(t.surface.ignoresSafeArea(edges: .bottom))
    }

    private func stepper(_ label: String, _ value: String, minus: @escaping () -> Void, plus: @escaping () -> Void) -> some View {
        HStack {
            Text(label).foregroundColor(t.text)
            Spacer()
            Button(action: minus) { Image(systemName: "minus").frame(width: 36, height: 36) }.buttonStyle(.plain).foregroundColor(t.text)
            Text(value).font(.headline).foregroundColor(t.chord).frame(width: 60)
            Button(action: plus) { Image(systemName: "plus").frame(width: 36, height: 36) }.buttonStyle(.plain).foregroundColor(t.text)
        }
    }

    private var keyPanel: some View {
        VStack(spacing: 6) {
            stepper("Tom", shiftKey(rendered.origKey, prefs.transpose),
                    minus: { prefs.transpose = ((prefs.transpose + 11 + 6) % 12) - 6 },
                    plus: { prefs.transpose = ((prefs.transpose + 1 + 6) % 12 + 12) % 12 - 6 })
            stepper("Capotraste", prefs.capo <= 0 ? "sem" : "\(prefs.capo)ª",
                    minus: { prefs.capo = max(0, prefs.capo - 1) },
                    plus: { prefs.capo = min(11, max(0, prefs.capo) + 1) })
            Toggle("Simplificar acordes", isOn: $prefs.simplify).foregroundColor(t.text)
            HStack(spacing: 8) {
                Text("Acidentes").foregroundColor(t.text)
                Spacer()
                Pill(text: "Auto", selected: settings.accidentals == 0) { settings.accidentals = 0 }
                Pill(text: "♯", selected: settings.accidentals == 1) { settings.accidentals = 1 }
                Pill(text: "♭", selected: settings.accidentals == 2) { settings.accidentals = 2 }
            }
            if prefs.transpose != 0 || prefs.capo > 0 {
                HStack { Pill(text: "Voltar ao original", selected: false) { prefs.transpose = 0; prefs.capo = 0 }; Spacer() }
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
    }

    private var textPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            stepper("Tamanho", "\(Int(settings.fontSize))",
                    minus: { settings.fontSize = max(10, settings.fontSize - 1) },
                    plus: { settings.fontSize = min(32, settings.fontSize + 1) })
            stepper("Espaçamento", String(format: "%.2f", settings.lineSpacing),
                    minus: { settings.lineSpacing = max(1, settings.lineSpacing - 0.05) },
                    plus: { settings.lineSpacing = min(2, settings.lineSpacing + 0.05) })
            colorRow("Acordes", Themes.chordColors.map(\.0), selected: settings.chordColor) { settings.chordColor = $0 }
            colorRow("Letra", Themes.lyricColors.map(\.0), selected: settings.lyricColor) { settings.lyricColor = $0 }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Pill(text: "Sistema", selected: settings.theme == "auto") { settings.theme = "auto" }
                    ForEach(Themes.all, id: \.id) { th in Pill(text: th.name, selected: settings.theme == th.id) { settings.theme = th.id } }
                }
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
    }

    private func colorRow(_ label: String, _ names: [String], selected: Int, set: @escaping (Int) -> Void) -> some View {
        HStack(spacing: 8) {
            Text(label).font(.footnote).foregroundColor(t.muted).frame(width: 64, alignment: .leading)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(names.indices, id: \.self) { i in Pill(text: names[i], selected: selected == i) { set(i) } }
                }
            }
        }
        .padding(.vertical, 6)
    }

    private var morePanel: some View {
        VStack(alignment: .leading, spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Text("Instrumento").font(.footnote).foregroundColor(t.muted)
                    ForEach(instruments, id: \.0) { i in Pill(text: i.1, selected: settings.instrument == i.0) { settings.instrument = i.0 } }
                }
            }
            Toggle("Canhoto", isOn: $settings.leftHanded).foregroundColor(t.text)
            Toggle("Tablaturas", isOn: $settings.showTabs).foregroundColor(t.text)
            Toggle("Diagramas dos acordes", isOn: $settings.showDiagrams).foregroundColor(t.text)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Pill(text: "Adicionar à playlist", selected: false) { addToList = true }
                    Pill(text: Metronome.shared.playing ? "Parar metrônomo" : "Metrônomo (\(settings.bpm))", selected: false) {
                        let m = Metronome.shared
                        m.bpm = settings.bpm; m.beats = settings.beats; m.subdivision = settings.subdivision; m.accent = settings.accent
                        m.toggle()
                    }
                    if !shown.isLocal {
                        Pill(text: "Baixar de novo", selected: false) { Task { await load(force: true) } }
                        if let u = URL(string: section?.url ?? shown.url) { Link(destination: u) { Text("Abrir no site").font(.subheadline).padding(.horizontal, 14).padding(.vertical, 7).background(Capsule().fill(t.surface2)) } }
                    } else {
                        Pill(text: "Apagar cifra", selected: false) { model.store.deleteSong(shown.id); model.changed() }
                    }
                }
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 10)
    }

    // MARK: actions

    private func updateCols(_ width: CGFloat) {
        let w = monoCharWidth(CGFloat(settings.fontSize))
        let c = max(12, Int((width - 32) / max(1, w)))
        if c != cols { cols = c }
    }

    private func load(force: Bool) async {
        error = nil
        if !loaded {
            prefs = model.store.prefs(shown.id)
            fav = model.store.isFav("song", shown.id)
            loaded = true
        }
        let url = shown.url
        var sheet = force ? nil : model.store.sheet(url)
        if sheet == nil {
            if shown.isLocal { error = "Cifra não encontrada."; return }
            do {
                let s = try await Source.fetchSheet(url, songId: shown.id, title: shown.title, artist: shown.artist)
                model.store.saveSheet(s)
                sheet = s
            } catch {
                self.error = error.localizedDescription
                return
            }
        }
        guard let s = sheet else { return }
        if force { extra = [:]; failed = []; bassSheet = nil; bassMissing = false }
        main = s
        let ref = SongRef(id: shown.id, title: s.title.isEmpty ? shown.title : s.title, artist: s.artist.isEmpty ? shown.artist : s.artist)
        model.store.touchRecent(ref)
        model.store.updateSongMeta(shown.id, title: ref.title, artist: ref.artist)
    }

    private func loadBass() async {
        guard bass, let m = main, bassSheet == nil, !bassMissing else { return }
        let url = m.versions.first(where: SongView.isBassVersion)?.url ?? (m.url.hasPrefix(siteURL) ? Source.bassURL(m.url) : nil)
        guard let url else { bassMissing = true; return }
        if let s = model.store.sheet(url) { bassSheet = s; return }
        if let s = try? await Source.fetchSheet(url, songId: shown.id, title: m.title, artist: m.artist) {
            model.store.saveSheet(s)
            bassSheet = s
        } else {
            bassMissing = true
        }
    }

    private func loadSection() async {
        guard let m = base, let url = section?.url, url != m.url, extra[url] == nil, !failed.contains(url) else { return }
        if let s = model.store.sheet(url) { extra[url] = s; return }
        if let s = try? await Source.fetchSheet(url, songId: shown.id, title: m.title, artist: m.artist) {
            model.store.saveSheet(s)
            extra[url] = s
        } else {
            failed.insert(url)
        }
    }

    private func render() async {
        guard let s = source else { return }
        let (text, key) = (s.text, s.key)
        let (tr, simp, view, tabs, acc, c) = (prefs.transpose - capo, prefs.simplify || (derived && section?.simplified == true), section?.view ?? 0, showTabs, settings.accidentals, cols)
        let r = await Task.detached {
            Rendered.parse(Core.render(text, key: key, transpose: tr, simplify: simp, view: view, showTabs: tabs, accidentals: acc, cols: c))
        }.value
        rendered = r
    }

    private func switchTo(_ s: SongRef) {
        auto = false
        main = nil
        flags = nil
        selected = 0
        extra = [:]
        failed = []
        rendered = Rendered()
        loaded = false
        bassSheet = nil
        bassMissing = false
        current = s
    }

    /// Continuous linear scroll, one line per step, at `speed` (same scale as Android).
    private func autoscroll(_ proxy: ScrollViewProxy) async {
        guard auto, !rendered.lines.isEmpty else { return }
        var line = visible.filter { $0 >= 0 }.min() ?? 0
        while auto && !Task.isCancelled {
            let perLine = 1.0 / (Double(max(1, prefs.speed)) * 0.045)
            line += 1
            if line >= rendered.lines.count { auto = false; break }
            withAnimation(.linear(duration: perLine)) { proxy.scrollTo(line, anchor: .top) }
            try? await Task.sleep(nanoseconds: UInt64(perLine * 1_000_000_000))
        }
    }

    private func page(_ proxy: ScrollViewProxy, _ dir: Int) {
        let vis = visible.filter { $0 >= 0 }
        guard let lo = vis.min(), let hi = vis.max() else { return }
        let step = max(1, Int(Double(hi - lo) * 0.8))
        let target = max(0, min(rendered.lines.count - 1, (dir > 0 ? lo : lo) + dir * step))
        withAnimation(.easeInOut(duration: 0.25)) { proxy.scrollTo(target, anchor: .top) }
    }

    private func keepAwake(_ on: Bool) {
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = on
        #else
        if on {
            SongView.activity = ProcessInfo.processInfo.beginActivity(options: [.idleDisplaySleepDisabled], reason: "Lendo uma cifra")
        } else if let a = SongView.activity {
            ProcessInfo.processInfo.endActivity(a)
            SongView.activity = nil
        }
        #endif
    }

    #if os(macOS)
    private static var activity: NSObjectProtocol?
    #endif
}

private struct ChordName: Identifiable {
    var name: String
    var id: String { name }
}

/// Page turner keys (Bluetooth pedals send these): Page Down/Up, arrows, space.
private struct PageKeys: ViewModifier {
    var onPage: (Int) -> Void
    var onToggle: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 17.0, macOS 14.0, *) {
            content
                .focusable()
                .focusEffectDisabled()
                .onKeyPress(keys: [.pageDown, .downArrow, .rightArrow, .space]) { _ in onPage(1); return .handled }
                .onKeyPress(keys: [.pageUp, .upArrow, .leftArrow]) { _ in onPage(-1); return .handled }
                .onKeyPress(keys: [.return]) { _ in onToggle(); return .handled }
        } else {
            content
        }
    }
}

// MARK: - Chord diagram

/// Guitar chord box: strings vertical (low E on the left), 5 frets.
struct ChordDiagramView: View {
    var name: String
    var width: CGFloat = 64
    var leftHanded = false
    var bass = false
    @Environment(\.reading) private var t

    var body: some View {
        let shape = ChordShape.of(name, bass: bass)
        VStack(spacing: 2) {
            Text(name).font(.system(size: width / 4.6, weight: .bold)).foregroundColor(t.chord).lineLimit(1)
            Canvas { ctx, size in
                let w = size.width, h = size.height
                let left = w * 0.16, right = w * 0.9, top = h * 0.16, bottom = h * 0.97
                let n = max(2, shape?.frets.count ?? 6)
                let sx = (right - left) / CGFloat(n - 1), fy = (bottom - top) / 5
                let lineColor = t.text.opacity(0.55)
                guard let shape else {
                    ctx.draw(Text("?").foregroundColor(t.muted), at: CGPoint(x: w / 2, y: h / 2))
                    return
                }
                let fretted = shape.frets.filter { $0 > 0 }
                let maxF = fretted.max() ?? 0
                let base = maxF <= 5 ? 1 : (fretted.min() ?? 1)
                func x(_ s: Int) -> CGFloat { leftHanded ? right - CGFloat(s) * sx : left + CGFloat(s) * sx }
                for f in 0...5 {
                    let y = top + CGFloat(f) * fy
                    var p = Path(); p.move(to: CGPoint(x: left, y: y)); p.addLine(to: CGPoint(x: right, y: y))
                    ctx.stroke(p, with: .color(lineColor), lineWidth: f == 0 && base == 1 ? w * 0.045 : w * 0.012)
                }
                for s in 0..<n {
                    var p = Path(); p.move(to: CGPoint(x: x(s), y: top)); p.addLine(to: CGPoint(x: x(s), y: bottom))
                    ctx.stroke(p, with: .color(lineColor), lineWidth: w * (0.01 + CGFloat(n - 1 - s) * 0.003))
                }
                if base > 1 {
                    ctx.draw(Text("\(base)ª").font(.system(size: w / 7.5)).foregroundColor(t.muted), at: CGPoint(x: w * 0.06, y: top + fy * 0.5))
                }
                let r = sx * 0.36
                if shape.barre > 0 {
                    let y = top + (CGFloat(shape.barre - base) + 0.5) * fy
                    let x1 = x(shape.barreFrom), x2 = x(n - 1)
                    let rect = CGRect(x: min(x1, x2) - r, y: y - r, width: abs(x2 - x1) + 2 * r, height: 2 * r)
                    ctx.fill(Path(roundedRect: rect, cornerRadius: r), with: .color(t.chord))
                }
                for (s, f) in shape.frets.enumerated() {
                    let cx = x(s)
                    if f < 0 {
                        let m = r * 0.7, y = top - fy * 0.45
                        var p = Path()
                        p.move(to: CGPoint(x: cx - m, y: y - m)); p.addLine(to: CGPoint(x: cx + m, y: y + m))
                        p.move(to: CGPoint(x: cx - m, y: y + m)); p.addLine(to: CGPoint(x: cx + m, y: y - m))
                        ctx.stroke(p, with: .color(t.muted), lineWidth: w * 0.02)
                    } else if f == 0 {
                        let c = CGPoint(x: cx, y: top - fy * 0.45)
                        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r * 0.7, y: c.y - r * 0.7, width: r * 1.4, height: r * 1.4)), with: .color(t.muted), lineWidth: w * 0.018)
                    } else if shape.root >= 0 && s != shape.root {
                        let c = CGPoint(x: cx, y: top + (CGFloat(f - base) + 0.5) * fy)
                        ctx.stroke(Path(ellipseIn: CGRect(x: c.x - r * 0.85, y: c.y - r * 0.85, width: r * 1.7, height: r * 1.7)), with: .color(t.chord), lineWidth: w * 0.03)
                    } else if f != shape.barre || s < shape.barreFrom {
                        let c = CGPoint(x: cx, y: top + (CGFloat(f - base) + 0.5) * fy)
                        ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(t.chord))
                    }
                }
            }
            .frame(width: width, height: width * 1.15)
        }
    }
}
