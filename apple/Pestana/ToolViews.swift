import SwiftUI

// MARK: - Tuner

struct TunerView: View {
    @EnvironmentObject var settings: Settings
    @Environment(\.reading) private var t
    @StateObject private var engine = TunerEngine()
    @State private var granted: Bool?
    @State private var byString = false
    @State private var chosen = -1
    @State private var picker = false
    @State private var done = Set<Int>()

    private var tuning: Tuning { Tunings.byId(settings.tuning) }

    var body: some View {
        Group {
            if granted == false {
                VStack(spacing: 10) {
                    Text("O afinador precisa ouvir o instrumento").font(.headline).foregroundColor(t.text)
                    Text("Permita o microfone nos Ajustes do sistema. O som é analisado só no aparelho, nada é gravado ou enviado.")
                        .font(.subheadline).foregroundColor(t.muted).multilineTextAlignment(.center)
                }
                .padding(32)
            } else {
                content
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(t.bg)
        .navigationTitle("Afinador")
        .toolbar {
            ToolbarItem(placement: .primaryAction) { Button("Trocar afinação") { picker = true } }
        }
        .sheet(isPresented: $picker) { tuningPicker }
        .task {
            let ok = await TunerEngine.requestAccess()
            granted = ok
            if ok { restart() }
        }
        .onChange(of: settings.tuning) { _ in chosen = -1; done = []; restart() }
        .onDisappear { engine.stop() }
    }

    private func restart() {
        engine.stop()
        engine.minHz = tuning.minHz
        engine.start()
    }

    private var reading: (note: Int, cents: Double, string: Int) {
        let f = Double(engine.freq)
        guard f > 0 else { return (byString && chosen >= 0 ? tuning.strings[chosen] : -1, 0, -1) }
        if byString {
            let idx = chosen >= 0 ? chosen : tuning.strings.indices.min { a, b in
                abs(centsFrom(f, Notes.freq(tuning.strings[a], a4: settings.a4), fold: true)) <
                    abs(centsFrom(f, Notes.freq(tuning.strings[b], a4: settings.a4), fold: true))
            } ?? 0
            let m = tuning.strings[idx]
            return (m, centsFrom(f, Notes.freq(m, a4: settings.a4), fold: true), idx)
        }
        let mf = Notes.midiFloat(f, a4: settings.a4)
        let m = Int(mf.rounded())
        return (m, (mf - Double(m)) * 100, tuning.strings.firstIndex(of: m) ?? -1)
    }

    private var content: some View {
        let r = reading
        let has = engine.freq > 0
        let inTune = has && engine.active && abs(r.cents) < 2
        let tone: Color = !has || !engine.active ? t.muted : (inTune ? t.good : (abs(r.cents) < 10 ? t.chord : t.text))
        let hint: String = {
            if !has { return "Toque uma corda" }
            if !engine.active { return "…" }
            if inTune { return "Afinado ✓" }
            if byString { return r.cents < 0 ? "Aperte a corda (está baixa)" : "Afrouxe a corda (está alta)" }
            return r.cents < 0 ? "Baixo" : "Alto"
        }()
        return ScrollView {
            VStack(spacing: 14) {
                Text("\(tuning.group) · \(tuning.name)").font(.subheadline).foregroundColor(t.muted)
                HStack(spacing: 8) {
                    Pill(text: "Automático", selected: !byString) { byString = false }
                    Pill(text: "Por corda", selected: byString) { byString = true }
                }
                ZStack(alignment: .bottom) {
                    Gauge(cents: has ? max(-50, min(50, r.cents)) : 0, tone: tone)
                        .animation(.interpolatingSpring(stiffness: 120, damping: 18), value: r.cents)
                    VStack(spacing: 0) {
                        HStack(alignment: .lastTextBaseline, spacing: 2) {
                            Text(r.note >= 0 ? Notes.name(r.note, flats: tuning.flats) : " ").font(.system(size: 76, weight: .bold)).foregroundColor(tone)
                            if r.note >= 0 { Text("\(Notes.octave(r.note))").font(.title3).foregroundColor(t.muted) }
                        }
                        Text(r.note >= 0 ? Notes.solfege[Notes.pc(r.note)] : " ").foregroundColor(t.muted)
                    }
                }
                .frame(maxWidth: 420)
                .aspectRatio(1.6, contentMode: .fit)
                .padding(.horizontal, 24)
                Text(hint).font(.title3.weight(.semibold)).foregroundColor(tone)
                Text(has ? String(format: "%+.1f cents   ·   %.2f Hz", r.cents, engine.freq) : "A4 = \(Int(settings.a4)) Hz")
                    .font(.subheadline).foregroundColor(t.muted)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        if byString { chip("Auto", selected: chosen < 0, done: false, hearing: false) { chosen = -1 } }
                        // Left-handed players hold the high string on the left.
                        let order = settings.leftHanded ? Array(tuning.strings.indices.reversed()) : Array(tuning.strings.indices)
                        ForEach(order, id: \.self) { i in
                            let hearing = i == r.string && has
                            chip(Notes.name(tuning.strings[i], flats: tuning.flats), selected: byString ? chosen == i : hearing, done: done.contains(i), hearing: hearing) {
                                byString = true
                                chosen = i
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
                Text(byString ? "Toque numa corda para fixar · \"Auto\" detecta sozinho" : (settings.leftHanded ? "Agudo → grave" : "Grave → agudo"))
                    .font(.caption).foregroundColor(t.muted)
                HStack {
                    Text("Referência A4").foregroundColor(t.text)
                    Spacer()
                    Button { settings.a4 = max(415, settings.a4 - 1) } label: { Image(systemName: "minus") }.buttonStyle(.plain)
                    Text("\(Int(settings.a4)) Hz").font(.headline).foregroundColor(t.chord).frame(width: 80)
                    Button { settings.a4 = min(466, settings.a4 + 1) } label: { Image(systemName: "plus") }.buttonStyle(.plain)
                }
                .frame(maxWidth: 420)
                .padding(.horizontal, 24)
            }
            .padding(.vertical, 12)
        }
        .onChange(of: inTune) { on in
            guard on, r.string >= 0 else { return }
            let s = r.string
            Task {
                try? await Task.sleep(nanoseconds: 450_000_000)
                if engine.active && abs(reading.cents) < 3 && reading.string == s { done.insert(s) }
            }
        }
    }

    private func chip(_ label: String, selected: Bool, done: Bool, hearing: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: label.count > 2 ? 13 : 17, weight: .bold))
                .foregroundColor(done ? t.good : t.text)
                .frame(width: 50, height: 50)
                .background(Circle().fill(selected ? t.surface2 : t.surface))
                .overlay(Circle().stroke(hearing ? t.chord : (done ? t.good : (selected ? t.text : t.line)), lineWidth: 2))
        }
        .buttonStyle(.plain)
    }

    private var tuningPicker: some View {
        NavigationStack {
            List {
                // The current instrument's tunings come first.
                let groups = Dictionary(grouping: Tunings.all, by: \.group)
                let mine = settings.instrument == "bass" ? "Baixo" : "Violão / Guitarra"
                let order = [mine] + ["Violão / Guitarra", "Baixo", "Ukulele", "Cavaquinho", "Viola caipira"].filter { $0 != mine }
                ForEach(order, id: \.self) { g in
                    Section(g) {
                        ForEach(groups[g] ?? []) { tu in
                            Button {
                                settings.tuning = tu.id
                                picker = false
                            } label: {
                                HStack {
                                    Text(tu.name).foregroundColor(t.text)
                                    Spacer()
                                    Text(tu.label).font(.footnote.weight(.semibold)).foregroundColor(t.chord)
                                    if tu.id == settings.tuning { Image(systemName: "checkmark").foregroundColor(t.chord) }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Afinação")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Fechar") { picker = false } } }
        }
        .frame(minWidth: 360, minHeight: 460)
    }
}

/// Needle gauge, ±50 cents over ±75°. The needle is a short segment near the
/// scale so the note in the middle stays readable.
private struct Gauge: View {
    var cents: Double
    var tone: Color
    @Environment(\.reading) private var t

    var body: some View {
        Canvas { ctx, size in
            let cx = size.width / 2, cy = size.height * 0.95
            let r = min(size.width / 2, size.height) * 0.92
            func at(_ c: Double, _ rad: CGFloat) -> CGPoint {
                let a = (-90 + c * 1.5) * .pi / 180
                return CGPoint(x: cx + rad * CGFloat(cos(a)), y: cy + rad * CGFloat(sin(a)))
            }
            var zone = Path()
            zone.addArc(center: CGPoint(x: cx, y: cy), radius: r, startAngle: .degrees(-93), endAngle: .degrees(-87), clockwise: false)
            ctx.stroke(zone, with: .color(t.good.opacity(0.25)), lineWidth: r * 0.09)
            for c in stride(from: -50, through: 50, by: 2) {
                let major = c % 10 == 0
                let len = major ? r * 0.1 : (c % 5 == 0 ? r * 0.06 : r * 0.035)
                var p = Path()
                p.move(to: at(Double(c), r)); p.addLine(to: at(Double(c), r - len))
                ctx.stroke(p, with: .color(c == 0 ? t.good : t.muted.opacity(major ? 0.9 : 0.5)), lineWidth: major ? 3 : 1.5)
            }
            var needle = Path()
            needle.move(to: at(cents, r * 0.66)); needle.addLine(to: at(cents, r * 1.02))
            ctx.stroke(needle, with: .color(tone), style: StrokeStyle(lineWidth: 7, lineCap: .round))
        }
    }
}

// MARK: - Metronome

struct MetronomeView: View {
    @EnvironmentObject var settings: Settings
    @ObservedObject private var metronome = Metronome.shared
    @Environment(\.reading) private var t
    @State private var taps: [Date] = []

    private let signatures = [("2/4", 2), ("3/4", 3), ("4/4", 4), ("5/4", 5), ("6/8", 6), ("7/8", 7), ("12/8", 12)]
    private let subdivisions = [("♩", 1), ("♪♪", 2), ("3", 3), ("4", 4)]

    private func tempoName(_ b: Int) -> String {
        switch b {
        case ..<40: return "Grave"
        case ..<60: return "Largo"
        case ..<66: return "Larghetto"
        case ..<76: return "Adagio"
        case ..<108: return "Andante"
        case ..<120: return "Moderato"
        case ..<156: return "Allegro"
        case ..<176: return "Vivace"
        case ..<200: return "Presto"
        default: return "Prestissimo"
        }
    }

    private func sync() {
        metronome.bpm = settings.bpm
        metronome.beats = settings.beats
        metronome.subdivision = settings.subdivision
        metronome.accent = settings.accent
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text(tempoName(settings.bpm)).foregroundColor(t.muted)
                TimelineView(.animation(paused: !metronome.playing)) { _ in
                    let beat = metronome.playing ? metronome.currentBeat() : -1
                    HStack(spacing: 10) {
                        ForEach(0..<settings.beats, id: \.self) { i in
                            let first = i == 0 && settings.accent
                            Circle()
                                .fill(i == beat ? (first ? t.chord : t.text) : t.surface2)
                                .frame(width: first ? 22 : 16, height: first ? 22 : 16)
                        }
                    }
                }
                .frame(height: 24)
                HStack(spacing: 14) {
                    roundButton("−5") { settings.bpm = max(20, settings.bpm - 5); sync() }
                    Button { settings.bpm = max(20, settings.bpm - 1); sync() } label: { Image(systemName: "minus") }.buttonStyle(.plain)
                    VStack(spacing: 0) {
                        Text("\(settings.bpm)").font(.system(size: 72, weight: .bold)).foregroundColor(t.text)
                        Text("BPM").font(.footnote).tracking(2).foregroundColor(t.muted)
                    }
                    .frame(minWidth: 140)
                    Button { settings.bpm = min(300, settings.bpm + 1); sync() } label: { Image(systemName: "plus") }.buttonStyle(.plain)
                    roundButton("+5") { settings.bpm = min(300, settings.bpm + 5); sync() }
                }
                Slider(value: Binding(get: { Double(settings.bpm) }, set: { settings.bpm = Int($0); sync() }), in: 20...300)
                    .frame(maxWidth: 480)
                HStack(spacing: 24) {
                    Button {
                        let now = Date()
                        if let last = taps.last, now.timeIntervalSince(last) > 2 { taps.removeAll() }
                        taps.append(now)
                        if taps.count > 6 { taps.removeFirst() }
                        if taps.count >= 2, let f = taps.first, let l = taps.last {
                            let avg = l.timeIntervalSince(f) / Double(taps.count - 1)
                            settings.bpm = max(20, min(300, Int(60 / avg)))
                            sync()
                        }
                    } label: {
                        Text("TAP").font(.headline).foregroundColor(t.text).frame(width: 64, height: 64).background(Circle().fill(t.surface))
                    }.buttonStyle(.plain)
                    Button { sync(); metronome.toggle() } label: {
                        Image(systemName: metronome.playing ? "pause.fill" : "play.fill").font(.system(size: 32))
                            .foregroundColor(t.bg).frame(width: 84, height: 84).background(Circle().fill(t.chord))
                    }.buttonStyle(.plain)
                    Button { settings.accent.toggle(); sync() } label: {
                        Text(">").font(.title2.bold()).foregroundColor(settings.accent ? t.chord : t.muted)
                            .frame(width: 64, height: 64).background(Circle().fill(settings.accent ? t.surface2 : t.surface))
                    }.buttonStyle(.plain)
                }
                Text("toque no ritmo  ·  tocar  ·  acento").font(.caption).foregroundColor(t.muted)
                VStack(alignment: .leading, spacing: 8) {
                    Text("COMPASSO").font(.caption.weight(.semibold)).foregroundColor(t.muted)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(signatures, id: \.0) { s in Pill(text: s.0, selected: settings.beats == s.1) { settings.beats = s.1; sync() } }
                        }
                    }
                    Text("SUBDIVISÃO").font(.caption.weight(.semibold)).foregroundColor(t.muted).padding(.top, 8)
                    HStack(spacing: 8) {
                        ForEach(subdivisions, id: \.1) { s in Pill(text: s.0, selected: settings.subdivision == s.1) { settings.subdivision = s.1; sync() } }
                    }
                }
                .frame(maxWidth: 480, alignment: .leading)
            }
            .padding(24)
        }
        .background(t.bg)
        .navigationTitle("Metrônomo")
        .onAppear { sync() }
    }

    private func roundButton(_ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(.subheadline.weight(.semibold)).foregroundColor(t.text)
                .frame(width: 44, height: 44).background(RoundedRectangle(cornerRadius: 14).fill(t.surface))
        }.buttonStyle(.plain)
    }
}

// MARK: - Settings

struct SettingsView: View {
    @EnvironmentObject var settings: Settings
    @EnvironmentObject var model: AppModel
    @Environment(\.reading) private var t
    @Environment(\.colorScheme) private var scheme
    @State private var cached = 0

    var body: some View {
        Form {
            Section {
                Picker("Instrumento", selection: $settings.instrument) {
                    ForEach(instruments, id: \.0) { Text($0.1).tag($0.0) }
                }
                Toggle("Canhoto", isOn: $settings.leftHanded)
            } header: { Text("Instrumento") } footer: {
                Text("Muda os diagramas de acorde, abre a versão para baixo quando existir e ajusta o afinador. Canhoto espelha os diagramas e inverte as cordas do afinador.")
            }
            Section("Tema de leitura") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        themeCard(nil)
                        ForEach(Themes.all, id: \.id) { themeCard($0) }
                    }
                    .padding(.vertical, 4)
                }
                Stepper("Tamanho da letra: \(Int(settings.fontSize))", value: $settings.fontSize, in: 10...32, step: 1)
                Stepper(String(format: "Espaçamento: %.2f", settings.lineSpacing), value: $settings.lineSpacing, in: 1...2, step: 0.05)
            }
            Section("Cores") {
                Picker("Acordes", selection: $settings.chordColor) {
                    ForEach(Themes.chordColors.indices, id: \.self) { Text(Themes.chordColors[$0].0).tag($0) }
                }
                Picker("Letra", selection: $settings.lyricColor) {
                    ForEach(Themes.lyricColors.indices, id: \.self) { Text(Themes.lyricColors[$0].0).tag($0) }
                }
            }
            Section("Cifras") {
                Toggle("Mostrar tablaturas", isOn: $settings.showTabs)
                Toggle("Mostrar diagramas dos acordes", isOn: $settings.showDiagrams)
                Toggle("Manter a tela ligada", isOn: $settings.keepScreenOn)
                Picker("Acidentes", selection: $settings.accidentals) {
                    Text("Auto").tag(0); Text("♯").tag(1); Text("♭").tag(2)
                }
            }
            Section("Afinador") {
                Stepper("Referência A4: \(Int(settings.a4)) Hz", value: $settings.a4, in: 415...466, step: 1)
            }
            Section("Seus dados (só neste aparelho)") {
                Button("Apagar cifras salvas para offline (\(cached))") { model.store.clearCache(); cached = 0; model.say("Cache apagado") }
                Button("Limpar histórico") { model.store.clearRecents(); model.changed(); model.say("Histórico limpo") }
            }
            Section("Sobre") {
                Text("Pestana · código aberto (MIT). As cifras pertencem aos seus autores e sites de origem; o app só as exibe para uso pessoal.")
                    .font(.footnote).foregroundColor(t.muted)
                Link("Código-fonte e verificação de segurança", destination: URL(string: "https://github.com/batatinhafcbatatinha-pixel/pestana")!)
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(t.bg)
        .navigationTitle("Ajustes")
        .onAppear { cached = model.store.cachedCount() }
    }

    private func themeCard(_ th: ReadingTheme?) -> some View {
        let id = th?.id ?? "auto"
        let p = Themes.resolve(id, systemDark: scheme == .dark, chordColor: settings.chordColor, lyricColor: settings.lyricColor)
        let selected = settings.theme == id
        return Button { settings.theme = id } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text("G      D/F#").font(.system(size: 12, weight: .heavy, design: .monospaced)).foregroundColor(p.chord)
                Text("Quando eu ").font(.system(size: 12, design: .monospaced)).foregroundColor(p.text)
                HStack(spacing: 0) {
                    Text("e|--").foregroundColor(p.tab.opacity(0.75))
                    Text("3").bold().foregroundColor(p.chord)
                    Text("--").foregroundColor(p.tab.opacity(0.75))
                    Text("2").bold().foregroundColor(p.chord)
                }
                .font(.system(size: 12, design: .monospaced))
                Text(th?.name ?? "Do sistema").font(.caption2).foregroundColor(p.muted).padding(.top, 4)
            }
            .padding(12)
            .frame(width: 128, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(p.bg))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? t.chord : t.line, lineWidth: selected ? 2 : 1))
        }
        .buttonStyle(.plain)
    }
}
