//! Chord names: parsing, transposition, simplification and spelling.
//!
//! Handles the notations common in Brazilian and international chord sheets:
//! `C`, `Am7`, `G/B`, `C7M`, `Cmaj7`, `A7(9)`, `Bm7(b5)`, `C7/9` (slash
//! followed by a number is an extension, not a bass note), `E7(4)`, `F#º`,
//! `Dsus4`, `Gadd9`, `C#m7(11)` and so on.

const SHARP_NAMES: [&str; 12] = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
const FLAT_NAMES: [&str; 12] = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"];

/// Pieces a chord suffix may be built from. Order matters: longer first.
const SUFFIX_VOCAB: [&str; 23] = [
    "maj", "min", "dim", "aug", "sus", "add", "no", "M", "m", "°", "º", "ø", "+", "-", "(", ")", "#",
    "b", "/", ",", "Δ", "alt", ".",
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Chord {
    /// Pitch class of the root, 0 = C.
    pub root: u8,
    /// Whether the root was written with a flat (used to keep the author's spelling).
    pub root_flat: bool,
    pub suffix: String,
    pub bass: Option<u8>,
    pub bass_flat: bool,
}

fn note_at(s: &str) -> Option<(u8, bool, usize)> {
    let b = s.as_bytes();
    let base = match b.first()? {
        b'C' => 0,
        b'D' => 2,
        b'E' => 4,
        b'F' => 5,
        b'G' => 7,
        b'A' => 9,
        b'B' => 11,
        _ => return None,
    };
    match b.get(1) {
        Some(b'#') => Some(((base + 1) % 12, false, 2)),
        Some(b'b') => Some(((base + 11) % 12, true, 2)),
        _ => Some((base, false, 1)),
    }
}

fn suffix_ok(s: &str) -> bool {
    let mut rest = s;
    'outer: while !rest.is_empty() {
        let c = rest.chars().next().unwrap();
        if c.is_ascii_digit() {
            rest = &rest[1..];
            continue;
        }
        for v in SUFFIX_VOCAB {
            if let Some(r) = rest.strip_prefix(v) {
                rest = r;
                continue 'outer;
            }
        }
        return false;
    }
    true
}

impl Chord {
    pub fn parse(token: &str) -> Option<Chord> {
        let t = token.trim();
        if t.is_empty() || t.len() > 24 {
            return None;
        }
        let (root, root_flat, n) = note_at(t)?;
        let rest = &t[n..];
        // A bass is "/" followed by a note letter; "/9" etc. stays in the suffix.
        let mut suffix = rest;
        let mut bass = None;
        let mut bass_flat = false;
        if let Some(pos) = rest.rfind('/') {
            if let Some((b, bf, bn)) = note_at(&rest[pos + 1..]) {
                if pos + 1 + bn == rest.len() {
                    suffix = &rest[..pos];
                    bass = Some(b);
                    bass_flat = bf;
                }
            }
        }
        if !suffix_ok(suffix) {
            return None;
        }
        // Parentheses must balance.
        let open = suffix.matches('(').count();
        if open != suffix.matches(')').count() {
            return None;
        }
        Some(Chord { root, root_flat, suffix: suffix.to_string(), bass, bass_flat })
    }

    pub fn is_minor(&self) -> bool {
        let s = self.suffix.as_str();
        (s.starts_with('m') && !s.starts_with("maj")) || s.starts_with("min") || s.starts_with('-')
    }

    pub fn transpose(&self, semis: i32) -> Chord {
        let sh = |p: u8| ((p as i32 + semis).rem_euclid(12)) as u8;
        Chord { root: sh(self.root), bass: self.bass.map(sh), ..self.clone() }
    }

    /// Reduce to a plain triad: `Am7(9)` -> `Am`, `G/B` -> `G`, `C7M` -> `C`.
    pub fn simplify(&self) -> Chord {
        let s = self.suffix.as_str();
        let suffix = if s.contains("dim") || s.contains('°') || s.contains('º') || s == "o" || s.starts_with("o7") {
            "dim"
        } else if s.contains('ø') || s.contains("m7(b5)") || s.contains("m7b5") {
            "m7(b5)"
        } else if self.is_minor() {
            "m"
        } else {
            ""
        };
        Chord { suffix: suffix.to_string(), bass: None, ..self.clone() }
    }

    pub fn name(&self, flats: bool) -> String {
        let names = if flats { &FLAT_NAMES } else { &SHARP_NAMES };
        let mut out = String::from(names[self.root as usize]);
        out.push_str(&self.suffix);
        if let Some(b) = self.bass {
            out.push('/');
            out.push_str(names[b as usize]);
        }
        out
    }

    /// Intervals (semitones above the root) implied by the suffix.
    /// Returns (required, optional) pitch-class offsets.
    pub fn intervals(&self) -> (Vec<u8>, Vec<u8>) {
        let s = self.suffix.replace(['(', ')', ','], " ").replace("7M", "maj7").replace('Δ', "maj7");
        let s = s.as_str();
        let mut third: Option<u8> = Some(4);
        let mut fifth: u8 = 7;
        let mut fifth_required = false;
        let mut ext: Vec<u8> = Vec::new();

        let minor = self.is_minor();
        if minor {
            third = Some(3);
        }
        if s.contains("sus2") {
            third = Some(2);
        } else if s.contains("sus") || (s.contains('4') && !s.contains("add4") && !s.contains("b4")) {
            // "E7(4)" and "Asus4" both mean a suspended fourth in Brazilian sheets.
            third = Some(5);
        }
        let dim = s.contains("dim") || s.contains('°') || s.contains('º') || s == "o" || s.starts_with("o7");
        let half_dim = s.contains('ø') || s.contains("b5") || s.contains("-5");
        if dim {
            third = Some(3);
            fifth = 6;
            fifth_required = true;
            if s.contains('7') {
                ext.push(9);
            }
        } else if half_dim {
            third = Some(3);
            fifth = 6;
            fifth_required = true;
            ext.push(10);
        }
        if s.contains("aug") || s.contains('+') || s.contains("#5") {
            fifth = 8;
            fifth_required = true;
        }
        if !dim && !half_dim {
            let maj7 = s.contains("maj") || s.contains('M');
            let has = |d: &str| s.contains(d);
            if maj7 && (has("7") || has("9") || has("11") || has("13")) {
                ext.push(11);
            } else if has("7") || ((has("9") || has("11") || has("13")) && !has("add") && !has("/9") && !has("6")) {
                // C9 implies the seventh; Cadd9, C6/9 and C/9-style "7/9" handled below.
                if !(has("6") && !has("7")) {
                    ext.push(10);
                }
            }
            if has("6") && !has("b6") && !has("13") {
                ext.push(9);
            }
            if has("b6") || has("b13") {
                ext.push(8);
            }
            if has("13") && !has("b13") {
                ext.push(9);
            }
        }
        if s.contains("b9") {
            ext.push(1);
        } else if s.contains("#9") {
            ext.push(3);
        } else if s.contains('9') {
            ext.push(2);
        }
        if s.contains("#11") {
            ext.push(6);
        } else if s.contains("11") {
            ext.push(5);
        }
        let mut req = vec![0u8];
        if let Some(t) = third {
            req.push(t);
        }
        let mut opt = Vec::new();
        if fifth_required {
            req.push(fifth);
        } else {
            opt.push(fifth);
        }
        for e in ext {
            if !req.contains(&e) {
                req.push(e);
            }
        }
        (req, opt)
    }
}

/// Flat keys: F, Bb, Eb, Ab, Db, Gb and their relative minors.
pub fn key_prefers_flats(root: u8, minor: bool) -> bool {
    let major_root = if minor { (root + 3) % 12 } else { root };
    matches!(major_root, 5 | 10 | 3 | 8 | 1)
}

pub fn note_name(pc: u8, flats: bool) -> &'static str {
    if flats {
        FLAT_NAMES[(pc % 12) as usize]
    } else {
        SHARP_NAMES[(pc % 12) as usize]
    }
}

/// Words that are valid chord spellings but almost always lyrics on their own.
pub fn ambiguous_word(t: &str) -> bool {
    matches!(t, "A" | "E" | "Em" | "Do" | "Am" | "Bb" | "Ab")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: &str) -> Chord {
        Chord::parse(s).unwrap_or_else(|| panic!("should parse {s}"))
    }

    #[test]
    fn parses_common_notations() {
        for s in [
            "C", "Am", "G/B", "C7M", "Cmaj7", "A7(9)", "Bm7(b5)", "C7/9", "E7(4)", "F#º", "Dsus4", "Gadd9", "C#m7(11)",
            "Bb", "Ebm", "D/F#", "A4", "E7+", "G6", "Am6", "Cdim", "Caug", "C5", "Bø", "F7M(9)", "Gm7/F",
        ] {
            p(s);
        }
        for s in ["Eu", "Amor", "Do", "Bom", "Ela", "Agora", "Hey", "Ai", "Cada", "Gente", "x2", "|", "Dó", "Bela"] {
            assert!(Chord::parse(s).is_none(), "{s} is not a chord");
        }
    }

    #[test]
    fn slash_number_is_extension() {
        let c = p("C7/9");
        assert_eq!(c.bass, None);
        assert_eq!(c.suffix, "7/9");
        let c = p("D/F#");
        assert_eq!(c.bass, Some(6));
    }

    #[test]
    fn transposes_and_spells() {
        assert_eq!(p("G/B").transpose(2).name(false), "A/C#");
        assert_eq!(p("Am7").transpose(1).name(true), "Bbm7");
        assert_eq!(p("B").transpose(1).name(false), "C");
        assert_eq!(p("C").transpose(-1).name(false), "B");
    }

    #[test]
    fn simplifies() {
        assert_eq!(p("Am7(9)").simplify().name(false), "Am");
        assert_eq!(p("G/B").simplify().name(false), "G");
        assert_eq!(p("C7M").simplify().name(false), "C");
        assert_eq!(p("F#º").simplify().name(false), "F#dim");
    }

    #[test]
    fn intervals() {
        assert_eq!(p("C").intervals().0, vec![0, 4]);
        assert_eq!(p("Am7").intervals().0, vec![0, 3, 10]);
        assert_eq!(p("C7M").intervals().0, vec![0, 4, 11]);
        assert_eq!(p("E7(4)").intervals().0, vec![0, 5, 10]);
        assert_eq!(p("Bm7(b5)").intervals().0, vec![0, 3, 6, 10]);
        assert_eq!(p("Cadd9").intervals().0, vec![0, 4, 2]);
        assert_eq!(p("A7(9)").intervals().0, vec![0, 4, 10, 2]);
    }
}
