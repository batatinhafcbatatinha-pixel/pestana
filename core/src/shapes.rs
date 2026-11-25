//! Chord diagrams: finds the most playable guitar voicing for any chord by
//! searching every fingering in each 4-fret window and scoring it the way a
//! guitarist would (low position, few muted strings, few fingers, barres,
//! open strings, correct bass note).

use crate::chord::Chord;
use crate::json::Json;

/// Standard tuning, low to high (pitch classes).
pub const STANDARD: [u8; 6] = [4, 9, 2, 7, 11, 4];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Shape {
    /// -1 = muted, 0 = open, n = fret. Low E first.
    pub frets: [i8; 6],
    /// Barre fret (0 = none) and its lowest string index.
    pub barre: i8,
    pub barre_from: usize,
}

/// Canonical open shapes players expect to see.
fn known(name: &str) -> Option<[i8; 6]> {
    let s = match name {
        "C" => "x32010",
        "D" => "xx0232",
        "E" => "022100",
        "G" => "320003",
        "A" => "x02220",
        "Am" => "x02210",
        "Em" => "022000",
        "Dm" => "xx0231",
        "C7" => "x32310",
        "D7" => "xx0212",
        "E7" => "020100",
        "G7" => "320001",
        "A7" => "x02020",
        "B7" => "x21202",
        "Am7" => "x02010",
        "Em7" => "022030",
        "Dm7" => "xx0211",
        "C7M" | "Cmaj7" => "x32000",
        "D7M" | "Dmaj7" => "xx0222",
        "A7M" | "Amaj7" => "x02120",
        "F7M" | "Fmaj7" => "xx3210",
        "E7M" | "Emaj7" => "021100",
        "G7M" | "Gmaj7" => "320002",
        "Asus4" | "A4" => "x02230",
        "Dsus4" | "D4" => "xx0233",
        "Esus4" | "E4" => "022200",
        "Asus2" | "A2" => "x02200",
        "Dsus2" | "D2" => "xx0230",
        "Cadd9" | "C9" | "C(9)" | "Cadd9/E" => "x32033",
        "E7(4)" | "E7sus4" => "020200",
        "A7(4)" | "A7sus4" => "x02030",
        "D7(4)" | "D7sus4" => "xx0213",
        "A6" => "x02222",
        "E5" => "022xxx",
        "A5" => "x022xx",
        "D/F#" => "200232",
        "G/B" => "x20003",
        "C/G" => "332010",
        "C/E" => "032010",
        "Am/G" => "302210",
        "F/C" => "x33211",
        "F" => "133211",
        "Fm" => "133111",
        "Bm" => "x24432",
        "B" => "x24442",
        "Bb" => "x13331",
        "F#m" => "244222",
        "Gm" => "355333",
        "Cm" => "x35543",
        _ => return None,
    };
    let mut f = [0i8; 6];
    for (i, c) in s.chars().enumerate() {
        f[i] = if c == 'x' { -1 } else { c.to_digit(10)? as i8 };
    }
    Some(f)
}

fn fingers_needed(fr: &[i8; 6]) -> (usize, i8, usize) {
    let fretted: Vec<(usize, i8)> = fr.iter().enumerate().filter(|(_, f)| **f > 0).map(|(i, f)| (i, *f)).collect();
    if fretted.len() <= 4 {
        // Still report a barre when the lowest fret covers several strings ending on top.
        return (fretted.len(), 0, 0);
    }
    let min = fretted.iter().map(|x| x.1).min().unwrap_or(0);
    let from = fretted.iter().find(|x| x.1 == min).map(|x| x.0).unwrap_or(0);
    // A barre covers from `from` to the top string; nothing under it may be open or muted.
    if fr[from..].iter().any(|f| *f <= 0) {
        return (99, 0, 0);
    }
    let above = fretted.iter().filter(|x| x.1 > min).count();
    (1 + above, min, from)
}

fn evaluate(fr: &[i8; 6], tuning: &[u8; 6], need: &[u8], allow: &[u8], bass: u8, root: u8) -> Option<i32> {
    let sounding: Vec<(usize, u8)> =
        fr.iter().enumerate().filter(|(_, f)| **f >= 0).map(|(i, f)| (i, (tuning[i] + *f as u8) % 12)).collect();
    if sounding.len() < 3 {
        return None;
    }
    if sounding[0].1 != bass {
        return None;
    }
    if sounding.iter().any(|(_, p)| !allow.contains(p)) {
        return None;
    }
    if need.iter().any(|n| !sounding.iter().any(|(_, p)| p == n)) {
        return None;
    }
    // Muted strings: only below the lowest note, or the top string.
    let first = sounding[0].0;
    let inner_mutes = fr[first..5].iter().filter(|f| **f < 0).count();
    if inner_mutes > 1 {
        return None;
    }
    let (fingers, barre, _) = fingers_needed(fr);
    if fingers > 4 {
        return None;
    }
    let fretted: Vec<i8> = fr.iter().copied().filter(|f| *f > 0).collect();
    let low = fretted.iter().copied().min().unwrap_or(0) as i32;
    let high = fretted.iter().copied().max().unwrap_or(0) as i32;
    if high - low > 3 {
        return None;
    }
    let mutes = fr.iter().filter(|f| **f < 0).count() as i32;
    let opens = fr.iter().filter(|f| **f == 0).count() as i32;
    let mut score = low * 3 + mutes * 2 + inner_mutes as i32 * 6 + fingers as i32 + (high - low);
    if barre > 0 {
        score += 2;
    }
    if fr[5] < 0 {
        score += 3;
    }
    if low <= 4 {
        score -= opens;
    } else if opens > 0 {
        score += 4; // open strings far from the hand sound odd
    }
    score += match sounding.len() {
        3 => 6,
        4 => 2,
        _ => 0,
    };
    if sounding.iter().filter(|(_, p)| *p == root).count() >= 2 {
        score -= 1;
    }
    Some(score)
}

pub fn find(chord: &Chord, tuning: &[u8; 6]) -> Option<Shape> {
    let (req, opt) = chord.intervals();
    let root = chord.root;
    let mut need: Vec<u8> = req.iter().map(|i| (root + i) % 12).collect();
    let mut allow: Vec<u8> = need.clone();
    allow.extend(opt.iter().map(|i| (root + i) % 12));
    let bass = chord.bass.unwrap_or(root);
    if !allow.contains(&bass) {
        allow.push(bass);
    }
    // With many tones, the fifth and then the root (above the bass) may be dropped.
    if need.len() > 4 {
        need.retain(|p| *p != (root + 7) % 12);
    }

    let mut best: Option<([i8; 6], i32)> = None;
    for w in 0..=12i8 {
        let mut opts: Vec<i8> = vec![-1, 0];
        let lo = if w == 0 { 1 } else { w };
        for f in lo..lo + 4 {
            opts.push(f);
        }
        let n = opts.len();
        let mut idx = [0usize; 6];
        loop {
            let fr = [opts[idx[0]], opts[idx[1]], opts[idx[2]], opts[idx[3]], opts[idx[4]], opts[idx[5]]];
            if let Some(s) = evaluate(&fr, tuning, &need, &allow, bass, root) {
                if best.as_ref().map_or(true, |b| s < b.1) {
                    best = Some((fr, s));
                }
            }
            let mut k = 0;
            loop {
                idx[k] += 1;
                if idx[k] < n {
                    break;
                }
                idx[k] = 0;
                k += 1;
                if k == 6 {
                    break;
                }
            }
            if k == 6 {
                break;
            }
        }
    }
    best.map(|(fr, _)| with_barre(fr))
}

fn with_barre(fr: [i8; 6]) -> Shape {
    let (_, barre, from) = fingers_needed(&fr);
    if barre > 0 {
        return Shape { frets: fr, barre, barre_from: from };
    }
    // Draw a barre for shapes like F (133211) when the lowest fret repeats.
    let min = fr.iter().copied().filter(|f| *f > 0).min().unwrap_or(0);
    let count = fr.iter().filter(|f| **f == min).count();
    if min > 0 && count >= 3 {
        if let Some(from) = fr.iter().position(|f| *f == min) {
            if fr[from..].iter().all(|f| *f >= min) {
                return Shape { frets: fr, barre: min, barre_from: from };
            }
        }
    }
    Shape { frets: fr, barre: 0, barre_from: 0 }
}

/// JSON for the UI: {"frets":[...],"barre":n,"barreFrom":i} or "{}".
pub fn shape_json(name: &str) -> String {
    let shape = match known(name) {
        Some(f) => Some(with_barre(f)),
        None => Chord::parse(name).and_then(|c| find(&c, &STANDARD)),
    };
    let mut j = Json::object();
    if let Some(s) = shape {
        j.key("frets").begin_array();
        for f in s.frets {
            j.int(f as i64);
        }
        j.end_array();
        j.key("barre").int(s.barre as i64);
        j.key("barreFrom").int(s.barre_from as i64);
    }
    j.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f(name: &str) -> [i8; 6] {
        find(&Chord::parse(name).unwrap(), &STANDARD).unwrap_or_else(|| panic!("{name}")).frets
    }

    fn sounds(fr: [i8; 6]) -> Vec<u8> {
        fr.iter().enumerate().filter(|(_, f)| **f >= 0).map(|(i, f)| (STANDARD[i] + *f as u8) % 12).collect()
    }

    #[test]
    fn finds_playable_shapes() {
        // Search (not the table) must find standard-looking shapes.
        assert_eq!(f("C"), [-1, 3, 2, 0, 1, 0]);
        assert_eq!(f("F"), [1, 3, 3, 2, 1, 1]);
        for name in ["C#m7", "Ab", "Bbm", "F#7", "G#m7(b5)", "Ebmaj7", "Bdim", "A7(9)", "C7/9", "G/B", "D/F#", "E7(4)", "Gm6", "F#º"] {
            let fr = f(name);
            let c = Chord::parse(name).unwrap();
            let pcs = sounds(fr);
            assert_eq!(pcs[0], c.bass.unwrap_or(c.root), "{name} bass {fr:?}");
        }
    }

    #[test]
    fn json_has_frets() {
        assert!(shape_json("G").contains("[3,2,0,0,0,3]"));
        assert!(shape_json("F").contains("\"barre\":1"));
        assert_eq!(shape_json("xyz"), "{}");
    }
}
