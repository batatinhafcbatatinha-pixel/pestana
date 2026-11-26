import AVFoundation
import Foundation

// MARK: - Notes and tunings

enum Notes {
    static let sharps = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    static let flats = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]
    static let solfege = ["Dó", "Dó#", "Ré", "Ré#", "Mi", "Fá", "Fá#", "Sol", "Sol#", "Lá", "Lá#", "Si"]

    static func pc(_ midi: Int) -> Int { ((midi % 12) + 12) % 12 }
    static func name(_ midi: Int, flats f: Bool = false) -> String { (f ? flats : sharps)[pc(midi)] }
    static func octave(_ midi: Int) -> Int { Int(floor(Double(midi) / 12)) - 1 }
    static func freq(_ midi: Int, a4: Double) -> Double { a4 * pow(2, Double(midi - 69) / 12) }
    static func midiFloat(_ f: Double, a4: Double) -> Double { 69 + 12 * log2(f / a4) }

    static func parse(_ s: String) -> Int {
        let oct = s.reversed().prefix { $0.isNumber || $0 == "-" }
        let o = Int(String(oct.reversed())) ?? 4
        let n = String(s.dropLast(oct.count))
        let pc = sharps.firstIndex(of: n) ?? flats.firstIndex(of: n) ?? 0
        return (o + 1) * 12 + pc
    }
}

func centsFrom(_ f: Double, _ target: Double, fold: Bool = false) -> Double {
    var c = 1200 * log2(f / target)
    if fold {
        while c > 600 { c -= 1200 }
        while c < -600 { c += 1200 }
    }
    return c
}

struct Tuning: Identifiable, Hashable {
    var id: String
    var group: String
    var name: String
    var strings: [Int]
    var label: String
    var flats: Bool

    init(_ id: String, _ group: String, _ name: String, _ notes: String) {
        self.id = id
        self.group = group
        self.name = name
        let parts = notes.split(separator: " ").map(String.init)
        strings = parts.map(Notes.parse)
        label = parts.map { $0.trimmingCharacters(in: .decimalDigits) }.joined(separator: " ")
        flats = notes.contains("b")
    }

    var minHz: Float { Float(max(25, Notes.freq(strings.min() ?? 40, a4: 440) * 0.7)) }
}

enum Tunings {
    static let all: [Tuning] = [
        Tuning("guitar_std", "Violão / Guitarra", "Padrão", "E2 A2 D3 G3 B3 E4"),
        Tuning("guitar_eb", "Violão / Guitarra", "Meio tom abaixo", "Eb2 Ab2 Db3 Gb3 Bb3 Eb4"),
        Tuning("guitar_d", "Violão / Guitarra", "Um tom abaixo", "D2 G2 C3 F3 A3 D4"),
        Tuning("guitar_dropd", "Violão / Guitarra", "Drop D", "D2 A2 D3 G3 B3 E4"),
        Tuning("guitar_dropcs", "Violão / Guitarra", "Drop C#", "C#2 G#2 C#3 F#3 A#3 D#4"),
        Tuning("guitar_dropc", "Violão / Guitarra", "Drop C", "C2 G2 C3 F3 A3 D4"),
        Tuning("guitar_dropb", "Violão / Guitarra", "Drop B", "B1 F#2 B2 E3 G#3 C#4"),
        Tuning("guitar_dadgad", "Violão / Guitarra", "DADGAD", "D2 A2 D3 G3 A3 D4"),
        Tuning("guitar_openg", "Violão / Guitarra", "Open G", "D2 G2 D3 G3 B3 D4"),
        Tuning("guitar_opend", "Violão / Guitarra", "Open D", "D2 A2 D3 F#3 A3 D4"),
        Tuning("guitar_opene", "Violão / Guitarra", "Open E", "E2 B2 E3 G#3 B3 E4"),
        Tuning("guitar_opena", "Violão / Guitarra", "Open A", "E2 A2 E3 A3 C#4 E4"),
        Tuning("guitar_openc", "Violão / Guitarra", "Open C", "C2 G2 C3 G3 C4 E4"),
        Tuning("guitar_7", "Violão / Guitarra", "7 cordas (Si)", "B1 E2 A2 D3 G3 B3 E4"),
        Tuning("guitar_7c", "Violão / Guitarra", "7 cordas (Dó, choro)", "C2 E2 A2 D3 G3 B3 E4"),
        Tuning("bass_4", "Baixo", "4 cordas", "E1 A1 D2 G2"),
        Tuning("bass_5", "Baixo", "5 cordas", "B0 E1 A1 D2 G2"),
        Tuning("bass_dropd", "Baixo", "Drop D", "D1 A1 D2 G2"),
        Tuning("ukulele", "Ukulele", "Padrão (GCEA)", "G4 C4 E4 A4"),
        Tuning("cavaco", "Cavaquinho", "Natural (DGBD)", "D4 G4 B4 D5"),
        Tuning("viola_cebolao_e", "Viola caipira", "Cebolão em Mi", "B2 E3 G#3 B3 E4"),
        Tuning("viola_cebolao_d", "Viola caipira", "Cebolão em Ré", "A2 D3 F#3 A3 D4"),
        Tuning("viola_rio_abaixo", "Viola caipira", "Rio Abaixo", "G2 D3 G3 B3 D4"),
    ]

    static func byId(_ id: String) -> Tuning { all.first { $0.id == id } ?? all[0] }
}

// MARK: - Tuner

/// Microphone -> pitch: raw input (the .measurement mode turns off the voice
/// processing on iPhone), the Rust MPM detector on overlapping windows, and a
/// median filter that drops octave jumps.
@MainActor
final class TunerEngine: ObservableObject {
    @Published var freq: Float = -1
    @Published var clarity: Float = 0
    @Published var level: Float = 0
    @Published var active = false

    var minHz: Float = 50
    private let engine = AVAudioEngine()
    private let queue = DispatchQueue(label: "tuner", qos: .userInteractive)
    private let state = TunerState()
    private var running = false

    static func requestAccess() async -> Bool {
        await AVCaptureDevice.requestAccess(for: .audio)
    }

    func start() {
        guard !running else { return }
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.record, mode: .measurement)
        try? session.setActive(true)
        #endif
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else { return }
        let sr = Int(format.sampleRate)
        let minHz = self.minHz
        let state = self.state
        let queue = self.queue
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buf, _ in
            guard let ch = buf.floatChannelData?[0] else { return }
            let chunk = Array(UnsafeBufferPointer(start: ch, count: Int(buf.frameLength)))
            queue.async {
                guard let r = state.feed(chunk, sr: sr, minHz: minHz) else { return }
                Task { @MainActor in self?.apply(r) }
            }
        }
        do {
            try engine.start()
            running = true
        } catch {
            input.removeTap(onBus: 0)
        }
    }

    private func apply(_ r: TunerState.Reading) {
        level = r.level
        if r.active {
            freq = r.freq
            clarity = r.clarity
            active = true
        } else {
            active = false
        }
    }

    func stop() {
        guard running else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        running = false
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
}

/// Ring buffer + detection + median filter, used from a single serial queue.
final class TunerState {
    struct Reading { var freq: Float; var clarity: Float; var level: Float; var active: Bool }

    private let detector = PitchDetector()
    private var ring: [Float] = []
    private var sinceLast = 0
    private var history: [Float] = []
    private var lastValid = Date.distantPast

    func feed(_ chunk: [Float], sr: Int, minHz: Float) -> Reading? {
        let window = minHz < 45 ? 8192 : 4096
        ring.append(contentsOf: chunk)
        if ring.count > window { ring.removeFirst(ring.count - window) }
        sinceLast += chunk.count
        guard ring.count == window, sinceLast >= 1024 else { return nil }
        sinceLast = 0
        let (f, c, rms) = detector.detect(ring, sampleRate: sr, minHz: minHz, maxHz: 1600)
        if f > 0 && c >= 0.88 && rms > 0.0015 {
            if history.count >= 3 {
                let med = median(history)
                if abs(1200 * log2(f / med)) > 80 { history.removeAll() }
            }
            history.append(f)
            if history.count > 5 { history.removeFirst() }
            lastValid = Date()
            return Reading(freq: median(history), clarity: c, level: rms, active: true)
        }
        if Date().timeIntervalSince(lastValid) > 0.7 {
            history.removeAll()
            return Reading(freq: -1, clarity: 0, level: rms, active: false)
        }
        return nil
    }

    private func median(_ v: [Float]) -> Float {
        let s = v.sorted()
        return s.count % 2 == 1 ? s[s.count / 2] : (s[s.count / 2 - 1] + s[s.count / 2]) / 2
    }
}

// MARK: - Metronome

/// Sample-accurate metronome: clicks are written into the audio stream at exact
/// sample positions by the render callback, so the tempo never drifts.
final class Metronome: ObservableObject {
    static let shared = Metronome()

    @Published private(set) var playing = false

    private let lock = NSLock()
    private var _bpm = 90, _beats = 4, _sub = 1, _accent = true
    var bpm: Int { get { lock.withLock { _bpm } } set { lock.withLock { _bpm = max(20, min(300, newValue)) } } }
    var beats: Int { get { lock.withLock { _beats } } set { lock.withLock { _beats = max(1, newValue) } } }
    var subdivision: Int { get { lock.withLock { _sub } } set { lock.withLock { _sub = max(1, min(4, newValue)) } } }
    var accent: Bool { get { lock.withLock { _accent } } set { lock.withLock { _accent = newValue } } }

    private var engine: AVAudioEngine?
    private var sr: Double = 48000
    // Render state (audio thread).
    private var frame: Int64 = 0
    private var nextTick: Double = 0
    private var tickIndex = 0
    private var clickPos = Int.max
    private var click: [Float] = []
    // Recent ticks for the UI: (frame, beat or -1).
    private var ticks: [(Int64, Int)] = []

    private func makeClick(_ freq: Double, ms: Double, gain: Double) -> [Float] {
        let n = Int(sr * ms / 1000)
        return (0..<n).map { i in
            let t = Double(i) / sr
            let env = (1 - exp(-t * 4000)) * exp(-t * 1000 / (ms / 4))
            return Float(sin(2 * .pi * freq * t) * env * gain)
        }
    }

    private lazy var accentClick = makeClick(1760, ms: 40, gain: 1.0)
    private lazy var beatClick = makeClick(1175, ms: 35, gain: 0.8)
    private lazy var subClick = makeClick(880, ms: 25, gain: 0.4)

    func toggle() { playing ? stop() : start() }

    func start() {
        guard engine == nil else { return }
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
        let e = AVAudioEngine()
        sr = e.outputNode.outputFormat(forBus: 0).sampleRate
        if sr <= 0 { sr = 48000 }
        accentClick = makeClick(1760, ms: 40, gain: 1.0)
        beatClick = makeClick(1175, ms: 35, gain: 0.8)
        subClick = makeClick(880, ms: 25, gain: 0.4)
        frame = 0; nextTick = 0; tickIndex = 0; clickPos = Int.max
        lock.withLock { ticks.removeAll() }
        let format = AVAudioFormat(standardFormatWithSampleRate: sr, channels: 1)!
        let node = AVAudioSourceNode(format: format) { [unowned self] _, _, count, abl -> OSStatus in
            let buffers = UnsafeMutableAudioBufferListPointer(abl)
            guard let out = buffers.first?.mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            let (bpm, beats, sub, accent) = lock.withLock { (_bpm, _beats, _sub, _accent) }
            for i in 0..<Int(count) {
                if Double(frame) >= nextTick {
                    let perBar = beats * sub
                    let pos = tickIndex % perBar
                    let beat = pos % sub == 0 ? pos / sub : -1
                    click = beat == 0 && accent ? accentClick : (beat >= 0 ? beatClick : subClick)
                    clickPos = 0
                    lock.withLock {
                        ticks.append((frame, beat))
                        if ticks.count > 64 { ticks.removeFirst() }
                    }
                    tickIndex += 1
                    nextTick += sr * 60 / Double(bpm) / Double(sub)
                }
                if clickPos < click.count {
                    out[i] = click[clickPos] * 0.9
                    clickPos += 1
                } else {
                    out[i] = 0
                }
                frame += 1
            }
            for b in buffers.dropFirst() {
                if let d = b.mData { memcpy(d, out, Int(b.mDataByteSize)) }
            }
            return noErr
        }
        e.attach(node)
        e.connect(node, to: e.mainMixerNode, format: format)
        do {
            try e.start()
            engine = e
            playing = true
        } catch {
            engine = nil
        }
    }

    func stop() {
        engine?.stop()
        engine = nil
        playing = false
    }

    /// The beat being heard now (0-based), from the audio clock minus output latency.
    func currentBeat() -> Int {
        guard let e = engine else { return -1 }
        let latency = Int64((e.outputNode.presentationLatency) * sr)
        let heard = frame - latency
        return lock.withLock { ticks.last { $0.0 <= heard && $0.1 >= 0 }?.1 ?? -1 }
    }
}
