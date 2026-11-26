import SwiftUI

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/// Reading themes (same palettes as Android), tuned for long reading.
struct ReadingTheme: Equatable {
    var id: String
    var name: String
    var dark: Bool
    var bg: Color
    var surface: Color
    var surface2: Color
    var text: Color
    var muted: Color
    var chord: Color
    var header: Color
    var tab: Color
    var line: Color
    var good: Color
}

enum Themes {
    static let dark = ReadingTheme(id: "dark", name: "Escuro", dark: true, bg: Color(hex: 0x121316), surface: Color(hex: 0x1B1D21), surface2: Color(hex: 0x25282D), text: Color(hex: 0xE6E3DC), muted: Color(hex: 0x8D9098), chord: Color(hex: 0xF5B556), header: Color(hex: 0x9DB8E8), tab: Color(hex: 0xA9C9A4), line: Color(hex: 0x2E3137), good: Color(hex: 0x6FD08C))
    static let light = ReadingTheme(id: "light", name: "Claro", dark: false, bg: Color(hex: 0xFBFAF6), surface: Color(hex: 0xF1EFE9), surface2: Color(hex: 0xE6E3DB), text: Color(hex: 0x1E1F22), muted: Color(hex: 0x6B6E75), chord: Color(hex: 0xC2410C), header: Color(hex: 0x2F5DA8), tab: Color(hex: 0x3B6B47), line: Color(hex: 0xDDD9CF), good: Color(hex: 0x2E8B57))
    static let sepia = ReadingTheme(id: "sepia", name: "Sépia (Kindle)", dark: false, bg: Color(hex: 0xF4ECD8), surface: Color(hex: 0xEADFC6), surface2: Color(hex: 0xDDD0B3), text: Color(hex: 0x3B2F24), muted: Color(hex: 0x7D6B57), chord: Color(hex: 0xB4320C), header: Color(hex: 0x6B4E2E), tab: Color(hex: 0x55603A), line: Color(hex: 0xD6C8A8), good: Color(hex: 0x4E7D3A))
    static let paper = ReadingTheme(id: "paper", name: "Papel (E-ink)", dark: false, bg: Color(hex: 0xEDEDEA), surface: Color(hex: 0xE2E2DE), surface2: Color(hex: 0xD5D5D0), text: Color(hex: 0x111111), muted: Color(hex: 0x555555), chord: Color(hex: 0xB3261E), header: Color(hex: 0x222222), tab: Color(hex: 0x333333), line: Color(hex: 0xC8C8C2), good: Color(hex: 0x1F6F3F))
    static let night = ReadingTheme(id: "night", name: "Noturno (luz quente)", dark: true, bg: Color(hex: 0x17130F), surface: Color(hex: 0x211B15), surface2: Color(hex: 0x2C241C), text: Color(hex: 0xD9C9AE), muted: Color(hex: 0x8E7F69), chord: Color(hex: 0xE8964A), header: Color(hex: 0xC9A36B), tab: Color(hex: 0xB0A57E), line: Color(hex: 0x352C22), good: Color(hex: 0xA7C46A))
    static let amoled = ReadingTheme(id: "amoled", name: "Preto (AMOLED)", dark: true, bg: Color(hex: 0x000000), surface: Color(hex: 0x0E0E0F), surface2: Color(hex: 0x1A1A1C), text: Color(hex: 0xDCDCDC), muted: Color(hex: 0x85878C), chord: Color(hex: 0xFFB547), header: Color(hex: 0x8FB3F0), tab: Color(hex: 0x9CCB95), line: Color(hex: 0x222225), good: Color(hex: 0x63D383))
    static let contrast = ReadingTheme(id: "contrast", name: "Alto contraste", dark: true, bg: Color(hex: 0x000000), surface: Color(hex: 0x111111), surface2: Color(hex: 0x222222), text: Color(hex: 0xFFFFFF), muted: Color(hex: 0xBDBDBD), chord: Color(hex: 0xFFE600), header: Color(hex: 0x4DE8FF), tab: Color(hex: 0x9CFF9C), line: Color(hex: 0x444444), good: Color(hex: 0x00FF88))

    static let all = [dark, light, sepia, paper, night, amoled, contrast]

    static func resolve(_ id: String, systemDark: Bool, chordColor: Int, lyricColor: Int = 0) -> ReadingTheme {
        var t = id == "auto" ? (systemDark ? dark : light) : (all.first { $0.id == id } ?? dark)
        if let pair = chordColors[safe: chordColor]?.1 {
            t.chord = t.dark ? pair.1 : pair.0
        }
        if let pair = lyricColors[safe: lyricColor]?.1 {
            t.text = t.dark ? pair.1 : pair.0
        }
        return t
    }

    /// User-chosen lyric colours: (light, dark).
    static let lyricColors: [(String, (Color, Color)?)] = [
        ("Do tema", nil),
        ("Contraste máximo", (Color(hex: 0x000000), Color(hex: 0xFFFFFF))),
        ("Cinza suave", (Color(hex: 0x44474D), Color(hex: 0xB9BCC2))),
        ("Azul-noite", (Color(hex: 0x1E2A44), Color(hex: 0xC9D6F0))),
        ("Sépia", (Color(hex: 0x4A3A2A), Color(hex: 0xE3D2B4))),
        ("Verde-musgo", (Color(hex: 0x2F3E2B), Color(hex: 0xCFE0C3))),
    ]

    /// User-chosen chord colours: (light, dark).
    static let chordColors: [(String, (Color, Color)?)] = [
        ("Do tema", nil),
        ("Laranja", (Color(hex: 0xC2410C), Color(hex: 0xFFA94D))),
        ("Azul", (Color(hex: 0x1D4ED8), Color(hex: 0x7AB0FF))),
        ("Vermelho", (Color(hex: 0xB91C1C), Color(hex: 0xFF7A7A))),
        ("Verde", (Color(hex: 0x15803D), Color(hex: 0x6EE7A0))),
        ("Roxo", (Color(hex: 0x7E22CE), Color(hex: 0xC9A6FF))),
    ]
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

private struct ThemeKey: EnvironmentKey {
    static let defaultValue = Themes.dark
}

extension EnvironmentValues {
    var reading: ReadingTheme {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}
