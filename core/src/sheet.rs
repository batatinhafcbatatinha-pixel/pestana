//! Chord sheet model: classify plain-text lines, pair chords with lyrics,
//! split views (full / chords only / solos / riffs), transpose, and wrap to a
//! screen width without ever moving a chord off the syllable it belongs to.

use crate::chord::{key_prefers_flats, Chord};
use crate::json::Json;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum View {
    Full = 0,
    Chords = 1,
    Solos = 2,
    Riffs = 3,
}

impl View {
    pub fn from(i: i32) -> View {
        match i {
            1 => View::Chords,
            2 => View::Solos,
            3 => View::Riffs,
            _ => View::Full,
        }
    }
}

/// Span styles sent to the UI.
pub const ST_LYRIC: u8 = 0;
pub const ST_CHORD: u8 = 1;
pub const ST_HEADER: u8 = 2;
pub const ST_TAB: u8 = 3;
pub const ST_NOTE: u8 = 4;
/// Fret numbers and techniques inside tablature (h, p, b, /, ~ ...).
pub const ST_TAB_NOTE: u8 = 5;
/// Technique marks inside tablature: h, p, b, r, /, \, ~, x, ( ).
pub const ST_TAB_TECH: u8 = 6;

/// Line kinds sent to the UI (used for spacing).
pub const K_TEXT: u8 = 0;
pub const K_CHORDS: u8 = 1;
pub const K_PAIR_CHORDS: u8 = 2;
pub const K_PAIR_LYRIC: u8 = 3;
pub const K_HEADER: u8 = 4;
pub const K_TAB: u8 = 5;
pub const K_BLANK: u8 = 6;
pub const K_NOTE: u8 = 7;

#[derive(Debug, Clone)]
struct Seg {
    col: usize,
    text: String,
    style: u8,
    chord: Option<Chord>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TabKind {
    Solo,
    Riff,
}

/// A line drawn over tablature and wrapped with it, column for column.
#[derive(Debug, Clone)]
enum Overlay {
    Text(String),
    Chords(Vec<Seg>),
}

/// Tablature rows plus what is glued to them: chord names and remarks above
/// ("then bend on the 15th fret"), and marks below ("pm . . .", "let ring").
#[derive(Debug, Clone, Default)]
struct TabBlock {
    above: Vec<Overlay>,
    rows: Vec<String>,
    below: Vec<String>,
}

#[derive(Debug, Clone)]
enum Item {
    Blank,
    Text(String),
    Header(String),
    /// Chord line, optionally with the lyric line under it.
    Chords(Vec<Seg>, Option<String>),
    Tab(TabBlock, TabKind),
    /// Text that only makes sense next to tablature ("Parte 1 de 4").
    TabNote(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Options {
    pub transpose: i32,
    pub simplify: bool,
    pub view: View,
    pub show_tabs: bool,
    /// 0 = automatic, 1 = sharps, 2 = flats
    pub accidentals: i32,
    pub cols: usize,
}

impl Default for Options {
    fn default() -> Self {
        Options { transpose: 0, simplify: false, view: View::Full, show_tabs: true, accidentals: 0, cols: 1000 }
    }
}

fn chars(s: &str) -> Vec<char> {
    s.chars().collect()
}

fn normalize(text: &str) -> Vec<String> {
    text.replace("\r\n", "\n")
        .replace('\r', "\n")
        .split('\n')
        .map(|l| {
            let mut out = String::with_capacity(l.len());
            for c in l.chars() {
                match c {
                    '\t' => {
                        let n = 4 - out.chars().count() % 4;
                        out.extend(std::iter::repeat(' ').take(n));
                    }
                    // Odd spaces become plain spaces.
                    '\u{a0}' | '\u{2000}'..='\u{200a}' | '\u{202f}' | '\u{205f}' | '\u{3000}' | '\u{2800}' | '\u{3164}'
                    | '\u{ffa0}' | '\u{115f}' | '\u{1160}' => out.push(' '),
                    // Invisible characters some sites sprinkle in the text (zero-width
                    // spaces, joiners, direction marks, soft hyphens, BOM): drop them,
                    // or "Am" stops looking like a chord.
                    '\u{200b}'..='\u{200f}'
                    | '\u{202a}'..='\u{202e}'
                    | '\u{2060}'..='\u{2064}'
                    | '\u{2066}'..='\u{206f}'
                    | '\u{feff}'
                    | '\u{ad}'
                    | '\u{34f}'
                    | '\u{180b}'..='\u{180e}'
                    | '\u{17b4}'
                    | '\u{17b5}'
                    | '\u{fe00}'..='\u{fe0f}'
                    | '\u{e0000}'..='\u{e007f}'
                    | '\u{61c}' => {}
                    c if c.is_control() => {}
                    c => out.push(c),
                }
            }
            out.trim_end().to_string()
        })
        .collect()
}

const TAB_CHARS: &str = "-0123456789|hpbr/\\~x()<>^.svtPHBRX*= :";

pub fn is_tab_line(line: &str) -> bool {
    let t = line.trim();
    if t.len() < 6 {
        return false;
    }
    let cs = chars(t);
    // "e|--3--" / "E ---" / "D#|--"
    let mut body_start = 0;
    if matches!(cs[0], 'E' | 'A' | 'D' | 'G' | 'B' | 'e' | 'a' | 'd' | 'g' | 'b' | 'C' | 'F' | 'c' | 'f') {
        let mut i = 1;
        if i < cs.len() && (cs[i] == '#' || cs[i] == 'b') && cs.get(i + 1).is_some_and(|c| *c == '|' || *c == ':' || *c == '-') {
            i += 1;
        }
        while i < cs.len() && cs[i] == ' ' {
            i += 1;
        }
        if i < cs.len() && (cs[i] == '|' || cs[i] == ':' || cs[i] == '-') {
            body_start = i;
        }
    }
    let body = &cs[body_start..];
    let dashes = body.iter().filter(|c| **c == '-').count();
    if dashes < 3 {
        return false;
    }
    let tabby = body.iter().filter(|c| TAB_CHARS.contains(**c)).count();
    if body_start > 0 {
        tabby * 10 >= body.len() * 8
    } else {
        dashes >= 6 && tabby * 10 >= body.len() * 9 && dashes * 2 >= body.len()
    }
}

const HEADER_WORDS: [&str; 30] = [
    "intro", "introdução", "introducao", "refrão", "refrao", "refrao", "pré-refrão", "pre-refrão", "pré refrão",
    "pre-refrao", "ponte", "solo", "riff", "final", "verso", "estrofe", "primeira parte", "segunda parte",
    "terceira parte", "parte", "coro", "chorus", "verse", "bridge", "outro", "interlúdio", "interludio", "base",
    "dedilhado", "tab",
];

/// "[Intro]" or "Refrão:" style labels. Returns (label, rest of line).
fn split_header(line: &str) -> Option<(String, String)> {
    let t = line.trim_start();
    let lead = line.len() - t.len();
    if t.starts_with('[') {
        let end = t.find(']')?;
        let label = &t[..=end];
        if label.chars().count() > 42 {
            return None;
        }
        let rest = &line[lead + end + 1..];
        return Some((label.to_string(), rest.to_string()));
    }
    let lower = t.to_lowercase();
    let lower_trim = lower.trim_end_matches(':').trim();
    if lower_trim.chars().count() > 30 {
        return None;
    }
    for w in HEADER_WORDS {
        if let Some(after) = lower_trim.strip_prefix(w) {
            let ok = after.is_empty()
                || (lower.trim_end().ends_with(':') && after.chars().all(|c| !c.is_alphabetic() || c == ' ' || c.is_alphanumeric()))
                || after.trim().chars().all(|c| c.is_ascii_digit() || " ()x-".contains(c));
            if ok && !(w == "parte" && after.contains(" de ")) {
                return Some((t.trim_end().to_string(), String::new()));
            }
        }
    }
    None
}

fn is_ignorable(tok: &str) -> bool {
    let t = tok.trim_matches(|c| c == '(' || c == ')');
    if t.is_empty() {
        return true;
    }
    if matches!(t, "|" | "||" | "/" | "//" | "-" | "–" | "%" | "..." | "*" | ":" | "N.C." | "NC" | "N.C" | "x" | ">" | "<" | "~" | "+")
    {
        return true;
    }
    if t.chars().all(|c| c == '|' || c == ':' || c == '-' || c == '.' || c == '/') {
        return true;
    }
    // x2, 2x, 2X, x3, 4x ...
    let l = t.to_lowercase();
    let digits = l.trim_matches('x');
    (l.starts_with('x') || l.ends_with('x')) && !digits.is_empty() && digits.chars().all(|c| c.is_ascii_digit())
}

/// Tokenize a line as a chord line. Returns None if any token is a word.
fn chord_segments(line: &str, col_offset: usize) -> Option<Vec<Seg>> {
    let cs = chars(line);
    let mut segs = Vec::new();
    let mut i = 0;
    let mut n_chords = 0;
    while i < cs.len() {
        if cs[i] == ' ' {
            i += 1;
            continue;
        }
        let start = i;
        while i < cs.len() && cs[i] != ' ' {
            i += 1;
        }
        let tok: String = cs[start..i].iter().collect();
        let col = start + col_offset;
        if let Some(c) = Chord::parse(&tok) {
            segs.push(Seg { col, text: tok, style: ST_CHORD, chord: Some(c) });
            n_chords += 1;
            continue;
        }
        // "(C" / "G)" / "(Am)" / "C|" around a chord
        let l = tok.trim_start_matches(['(', '|']);
        let r = l.trim_end_matches([')', '|', ',', '*']);
        if !r.is_empty() && r.len() < tok.len() {
            if let Some(c) = Chord::parse(r) {
                let pre = tok.len() - l.len();
                if pre > 0 {
                    segs.push(Seg { col, text: tok[..pre].to_string(), style: ST_NOTE, chord: None });
                }
                segs.push(Seg { col: col + pre, text: r.to_string(), style: ST_CHORD, chord: Some(c) });
                let post = &l[r.len()..];
                if !post.is_empty() {
                    segs.push(Seg { col: col + pre + r.chars().count(), text: post.to_string(), style: ST_NOTE, chord: None });
                }
                n_chords += 1;
                continue;
            }
        }
        // "C-G-Am" runs of chords joined by dashes
        if tok.contains('-') && !tok.starts_with('-') {
            let parts: Vec<&str> = tok.split('-').collect();
            if parts.len() > 1 && parts.iter().all(|p| !p.is_empty() && Chord::parse(p).is_some()) {
                let mut c = col;
                for (k, p) in parts.iter().enumerate() {
                    if k > 0 {
                        segs.push(Seg { col: c, text: "-".into(), style: ST_NOTE, chord: None });
                        c += 1;
                    }
                    segs.push(Seg { col: c, text: p.to_string(), style: ST_CHORD, chord: Chord::parse(p) });
                    c += p.chars().count();
                    n_chords += 1;
                }
                continue;
            }
        }
        if is_ignorable(&tok) {
            segs.push(Seg { col, text: tok, style: ST_NOTE, chord: None });
            continue;
        }
        return None;
    }
    if n_chords == 0 {
        return None;
    }
    Some(segs)
}

fn is_tab_note(line: &str) -> bool {
    let l = line.trim().to_lowercase();
    if let Some(rest) = l.strip_prefix("parte ") {
        let w: Vec<&str> = rest.split_whitespace().collect();
        return w.len() == 3 && w[1] == "de" && w[0].chars().all(|c| c.is_ascii_digit());
    }
    l.starts_with("tab -") || l.starts_with("tablatura")
}

/// A remark line printed under tablature (palm mute dots, "let ring"...).
fn is_tab_mark(line: &str) -> bool {
    let t = line.trim();
    if t.is_empty() || t.chars().count() > 80 || is_tab_line(line) || split_header(line).is_some() {
        return false;
    }
    let low = t.to_lowercase();
    if ["pm", "p.m", "let ring", "l.r", "harm", "vib"].iter().any(|w| low.starts_with(w)) {
        return true;
    }
    // "T      T": notes fretted with the thumb.
    if t.split_whitespace().all(|w| w == "T") {
        return true;
    }
    let visible = t.chars().filter(|c| !c.is_whitespace()).count().max(1);
    let letters = t.chars().filter(|c| c.is_alphabetic()).count();
    letters * 2 <= visible
}

fn classify(lines: &[String]) -> Vec<Item> {
    let mut items = Vec::new();
    let mut section = String::new();
    let mut i = 0;
    while i < lines.len() {
        let line = &lines[i];
        if line.trim().is_empty() {
            items.push(Item::Blank);
            i += 1;
            continue;
        }
        if is_tab_line(line) {
            let kind = if section.contains("solo") { TabKind::Solo } else { TabKind::Riff };
            let mut block = TabBlock::default();
            while i < lines.len() && is_tab_line(&lines[i]) {
                block.rows.push(lines[i].clone());
                i += 1;
            }
            // Lines glued on top (no blank line between) belong to the drawing.
            while block.above.len() < 3 {
                match items.last() {
                    Some(Item::Text(t)) => {
                        block.above.insert(0, Overlay::Text(t.clone()));
                        items.pop();
                    }
                    Some(Item::Chords(segs, None)) if !segs.first().is_some_and(|s| s.style == ST_HEADER) => {
                        block.above.insert(0, Overlay::Chords(segs.clone()));
                        items.pop();
                    }
                    _ => break,
                }
            }
            // Marks glued underneath: "pm . . .", "let ring", "x x", "~~~".
            while i < lines.len() && block.below.len() < 3 && is_tab_mark(&lines[i]) {
                block.below.push(lines[i].clone());
                i += 1;
            }
            items.push(Item::Tab(block, kind));
            continue;
        }
        if is_tab_note(line) {
            if line.to_lowercase().contains("solo") {
                section = "solo".into();
            }
            items.push(Item::TabNote(line.trim().to_string()));
            i += 1;
            continue;
        }
        if let Some((label, rest)) = split_header(line) {
            section = label.to_lowercase();
            if rest.trim().is_empty() {
                items.push(Item::Header(label));
                i += 1;
                continue;
            }
            let lead = line.chars().count() - rest.chars().count();
            if let Some(mut segs) = chord_segments(&rest, lead) {
                let label_col = line.chars().take_while(|c| *c == ' ').count();
                segs.insert(0, Seg { col: label_col, text: label, style: ST_HEADER, chord: None });
                items.push(Item::Chords(segs, None));
                i += 1;
                continue;
            }
        }
        if let Some(segs) = chord_segments(line, 0) {
            // A lone ambiguous word ("E", "A", "Em") between lyric lines is lyric.
            let only_ambiguous = segs.len() == 1 && crate::chord::ambiguous_word(&segs[0].text);
            let prev_text = i > 0 && matches!(items.last(), Some(Item::Text(_)));
            // "A" or "E" alone is a word only inside prose; over a lyric line it is a chord.
            let next_is_lyric = lines.get(i + 1).is_some_and(|n| {
                !n.trim().is_empty() && !is_tab_line(n) && chord_segments(n, 0).is_none() && split_header(n).is_none()
            });
            if !(only_ambiguous && prev_text && !next_is_lyric) {
                let next = lines.get(i + 1);
                let lyric = next.filter(|n| {
                    !n.trim().is_empty() && !is_tab_line(n) && chord_segments(n, 0).is_none() && split_header(n).is_none()
                });
                if let Some(l) = lyric {
                    items.push(Item::Chords(segs, Some(l.clone())));
                    i += 2;
                } else {
                    items.push(Item::Chords(segs, None));
                    i += 1;
                }
                continue;
            }
        }
        items.push(Item::Text(line.clone()));
        i += 1;
    }
    items
}

/// What goes away when tablature is hidden:
/// - whole "[Tab - ...]" sections (Cifra Club repeats the song's parts there);
/// - every tab block, with what is glued to it (no blank line between): the
///   chord line above it and annotations such as "pm . . .", "let ring";
/// - short "(Guitarra 1)" / "(variação)" labels right before a tab;
/// - section headers left with nothing but tablature.
fn tab_material(items: &[Item]) -> Vec<bool> {
    let n = items.len();
    let mut drop = vec![false; n];
    let header_label = |it: &Item| -> Option<String> {
        match it {
            Item::Header(h) => Some(h.clone()),
            Item::Chords(segs, _) if segs.first().is_some_and(|s| s.style == ST_HEADER) => Some(segs[0].text.clone()),
            _ => None,
        }
    };
    let is_tab_label = |l: &str| {
        let l = l.trim().trim_start_matches('[').to_lowercase();
        l.starts_with("tab ") || l.starts_with("tab-") || l.starts_with("tab:") || l == "tab]" || l.starts_with("tablatura")
    };

    // "[Tab - ...]" sections, up to the next header.
    let mut i = 0;
    while i < n {
        if header_label(&items[i]).is_some_and(|l| is_tab_label(&l)) {
            drop[i] = true;
            let mut j = i + 1;
            while j < n && header_label(&items[j]).is_none() {
                drop[j] = true;
                j += 1;
            }
            i = j;
        } else {
            i += 1;
        }
    }

    // Tab blocks and what is glued to them.
    let glued = |it: &Item| matches!(it, Item::Text(_) | Item::TabNote(_) | Item::Chords(_, None) | Item::Tab(..));
    for i in 0..n {
        if !matches!(items[i], Item::Tab(..) | Item::TabNote(_)) {
            continue;
        }
        drop[i] = true;
        let mut j = i;
        while j > 0 && glued(&items[j - 1]) && header_label(&items[j - 1]).is_none() {
            j -= 1;
            drop[j] = true;
        }
        let mut j = i + 1;
        while j < n && glued(&items[j]) && header_label(&items[j]).is_none() {
            drop[j] = true;
            j += 1;
        }
    }

    // Short "(...)" labels whose next content is dropped tablature.
    for i in (0..n).rev() {
        if let Item::Text(t) = &items[i] {
            let t = t.trim();
            if t.starts_with('(') && t.ends_with(')') && t.chars().count() <= 32 {
                let next = (i + 1..n).find(|&k| !matches!(items[k], Item::Blank));
                if next.is_some_and(|k| drop[k]) {
                    drop[i] = true;
                }
            }
        }
    }

    // Headers left with nothing but dropped material.
    for i in 0..n {
        if drop[i] || header_label(&items[i]).is_none() {
            continue;
        }
        let mut j = i + 1;
        let mut any_kept = false;
        let mut any_dropped = false;
        while j < n && header_label(&items[j]).is_none() {
            if drop[j] {
                any_dropped = true;
            } else if !matches!(items[j], Item::Blank) {
                any_kept = true;
                break;
            }
            j += 1;
        }
        // An instrumental label ("[Solo]", "[Riff 2]") whose first content is
        // tablature names that tab, even when lyrics follow without a header.
        let first = (i + 1..n).find(|&k| !matches!(items[k], Item::Blank));
        let instrumental = header_label(&items[i]).is_some_and(|l| {
            let l = l.to_lowercase();
            ["solo", "riff", "dedilhado", "base", "tab", "interlúdio", "interludio"].iter().any(|w| l.contains(w))
        });
        // Header-with-chords lines ("[Intro] Am F") carry content themselves.
        if matches!(items[i], Item::Header(_))
            && ((any_dropped && !any_kept) || (instrumental && first.is_some_and(|k| drop[k])))
        {
            drop[i] = true;
        }
    }
    drop
}

/// Keep only what the view asks for.
fn filter(items: Vec<Item>, view: View, show_tabs: bool) -> Vec<Item> {
    let keep_tabs = show_tabs && view != View::Chords;
    match view {
        View::Full | View::Chords => {
            if keep_tabs {
                return collapse_blanks(items);
            }
            let drop = tab_material(&items);
            let out: Vec<Item> = items.into_iter().zip(drop).filter(|(_, d)| !d).map(|(i, _)| i).collect();
            collapse_blanks(out)
        }
        View::Solos | View::Riffs => {
            let want = if view == View::Solos { TabKind::Solo } else { TabKind::Riff };
            let mut out = Vec::new();
            let mut header: Option<Item> = None;
            let mut pending_notes: Vec<Item> = Vec::new();
            for it in items {
                match &it {
                    Item::Header(_) => {
                        header = Some(it.clone());
                        pending_notes.clear();
                    }
                    Item::Chords(segs, None) if segs.first().is_some_and(|s| s.style == ST_HEADER) => {
                        header = Some(Item::Header(segs[0].text.clone()));
                        pending_notes.clear();
                    }
                    Item::TabNote(_) => pending_notes.push(it.clone()),
                    Item::Tab(_, k) if *k == want => {
                        if let Some(h) = header.take() {
                            if !out.is_empty() {
                                out.push(Item::Blank);
                            }
                            out.push(h);
                        }
                        out.append(&mut pending_notes);
                        out.push(it.clone());
                        out.push(Item::Blank);
                    }
                    _ => {}
                }
            }
            collapse_blanks(out)
        }
    }
}

fn collapse_blanks(items: Vec<Item>) -> Vec<Item> {
    let mut out: Vec<Item> = Vec::with_capacity(items.len());
    for it in items {
        if matches!(it, Item::Blank) && (out.is_empty() || matches!(out.last(), Some(Item::Blank))) {
            continue;
        }
        out.push(it);
    }
    while matches!(out.last(), Some(Item::Blank)) {
        out.pop();
    }
    out
}

/// Rewrite chord names and push later segments right so none overlap.
fn relayout(segs: &[Seg], f: &dyn Fn(&Chord) -> String) -> Vec<Seg> {
    let mut out: Vec<Seg> = Vec::with_capacity(segs.len());
    let mut end = 0usize;
    for (k, s) in segs.iter().enumerate() {
        let text = match &s.chord {
            Some(c) => f(c),
            None => s.text.clone(),
        };
        // Segments glued in the source ("(" + "C") stay glued.
        let glued = k > 0 && segs[k - 1].col + segs[k - 1].text.chars().count() == s.col;
        let min = if k == 0 { 0 } else if glued { end } else { end + 1 };
        let col = s.col.max(min);
        end = col + text.chars().count();
        out.push(Seg { col, text, style: s.style, chord: s.chord.clone() });
    }
    out
}

struct Out {
    lines: Vec<(u8, Vec<(u8, String)>)>,
}

impl Out {
    fn push(&mut self, kind: u8, spans: Vec<(u8, String)>) {
        self.lines.push((kind, spans));
    }
}

fn seg_line(segs: &[Seg], from: usize, to: usize) -> Vec<(u8, String)> {
    let mut spans = Vec::new();
    let mut cur = from;
    for s in segs {
        let len = s.text.chars().count();
        if s.col < from || s.col + len > to {
            continue;
        }
        if s.col > cur {
            spans.push((ST_LYRIC, " ".repeat(s.col - cur)));
        }
        spans.push((s.style, s.text.clone()));
        cur = s.col + len;
    }
    spans
}

fn wrap_words(text: &str, cols: usize) -> Vec<String> {
    let cs = chars(text);
    if cs.len() <= cols {
        return vec![text.to_string()];
    }
    let indent = cs.iter().take_while(|c| **c == ' ').count().min(cols / 3);
    let mut out = Vec::new();
    let mut start = 0;
    let mut first = true;
    while start < cs.len() {
        let width = if first { cols } else { cols.saturating_sub(indent).max(1) };
        if cs.len() - start <= width {
            let s: String = cs[start..].iter().collect();
            out.push(if first { s } else { format!("{}{}", " ".repeat(indent), s) });
            break;
        }
        let mut b = start + width;
        while b > start + width / 3 && cs[b] != ' ' {
            b -= 1;
        }
        if cs[b] != ' ' {
            b = start + width;
        }
        let s: String = cs[start..b].iter().collect::<String>().trim_end().to_string();
        out.push(if first { s } else { format!("{}{}", " ".repeat(indent), s) });
        first = false;
        start = b;
        while start < cs.len() && cs[start] == ' ' {
            start += 1;
        }
    }
    out
}

/// Wrap a chord line together with its lyric so chords stay above the same letters.
fn wrap_pair(segs: &[Seg], lyric: Option<&str>, cols: usize, out: &mut Out) {
    let ly: Vec<char> = lyric.map(chars).unwrap_or_default();
    let seg_end = segs.last().map(|s| s.col + s.text.chars().count()).unwrap_or(0);
    let total = ly.len().max(seg_end);
    let kind_c = if lyric.is_some() { K_PAIR_CHORDS } else { K_CHORDS };
    let mut start = 0usize;
    let cols = cols.max(8);
    loop {
        let remaining = total - start;
        let mut b;
        if remaining <= cols {
            b = total;
        } else {
            let limit = start + cols;
            let cuts_chord = |b: usize| segs.iter().any(|s| s.col < b && b < s.col + s.text.chars().count());
            let at_space = |b: usize| b >= ly.len() || ly[b] == ' ' || (b > 0 && ly[b - 1] == ' ');
            b = 0;
            let mut p = limit;
            while p > start + cols / 3 {
                if at_space(p) && !cuts_chord(p) {
                    b = p;
                    break;
                }
                p -= 1;
            }
            if b == 0 {
                p = limit;
                while p > start + 1 && cuts_chord(p) {
                    p -= 1;
                }
                b = p;
            }
        }
        let chords_line = seg_line(segs, start, b);
        let has_chords = chords_line.iter().any(|(s, _)| *s != ST_LYRIC);
        let lyr: String = if start < ly.len() { ly[start..b.min(ly.len())].iter().collect() } else { String::new() };
        let lyr = lyr.trim_end().to_string();
        if has_chords {
            out.push(if lyric.is_some() && !lyr.is_empty() { kind_c } else { K_CHORDS }, chords_line);
        }
        if lyric.is_some() && !lyr.trim().is_empty() {
            out.push(K_PAIR_LYRIC, vec![(ST_LYRIC, lyr)]);
        }
        if b >= total {
            break;
        }
        // Skip leading spaces on the next part, but never past a chord.
        let mut next = b;
        let first_chord = segs.iter().map(|s| s.col).filter(|c| *c >= b).min().unwrap_or(usize::MAX);
        while next < ly.len() && ly[next] == ' ' && next < first_chord {
            next += 1;
        }
        if next >= ly.len() && first_chord != usize::MAX {
            next = next.min(first_chord);
        }
        start = next;
        if start >= total {
            break;
        }
    }
}

/// Length of a tab line's string name part: "e|", "D#|", "4 ", " |" ...
fn tab_prefix(cs: &[char]) -> usize {
    let lead = cs.iter().take_while(|c| **c == ' ').count();
    let at = |i: usize| cs.get(i).copied().unwrap_or('\0');
    if matches!(at(lead), '|' | ':') {
        return lead + 1;
    }
    let c = at(lead);
    let letter = "EADGBeadgbCFcf".contains(c) && c != '\0';
    let digit = ('1'..='8').contains(&c);
    if !(letter || digit) {
        return lead;
    }
    let mut i = lead + 1;
    if letter && (at(i) == '#' || at(i) == 'b') && matches!(at(i + 1), '|' | ':' | ' ' | '-') {
        i += 1;
    }
    let spaced = at(i) == ' ';
    if spaced {
        i += 1;
    }
    match at(i) {
        '|' | ':' => i + 1,
        // "4 ------" (string number) or "E ------": the name ends before the line.
        '-' if spaced || letter => i,
        _ => lead,
    }
}

/// Splits a tab line so fret numbers stand out from the string lines:
/// "e|--3h5--|" -> [("e|--", TAB), ("3", TAB_NOTE), ("h", TAB_TECH), ...].
#[cfg(test)]
fn tab_spans(line: &str) -> Vec<(u8, String)> {
    let cs: Vec<char> = line.chars().collect();
    // The string name ("e|", "D#|", "4 ") is part of the drawing, not a note.
    let prefix = tab_prefix(&cs);
    body_spans(&cs, prefix)
}

/// A string number written inside a row, where a second staff starts on the
/// same line ("1 |-------  4 |---7--"): a lone digit with a space before it
/// and a bar or a space after it.
fn is_row_label(cs: &[char], i: usize) -> bool {
    ('1'..='8').contains(&cs[i])
        && (i == 0 || cs[i - 1] == ' ')
        && match cs.get(i + 1) {
            Some('|') => true,
            Some(' ') => cs[i + 1..].iter().find(|c| **c != ' ').is_some_and(|c| matches!(c, '|' | '-')),
            _ => false,
        }
}

/// Something to play: a fret number or a muted note, not a string label.
fn is_played(cs: &[char], i: usize) -> bool {
    (cs[i].is_ascii_digit() && !is_row_label(cs, i)) || matches!(cs[i], 'x' | 'X')
}

fn body_spans(cs: &[char], prefix: usize) -> Vec<(u8, String)> {
    let mut out: Vec<(u8, String)> = Vec::new();
    for (i, c) in cs.iter().enumerate() {
        let style = if i < prefix || matches!(c, '-' | '|' | ':' | ' ' | '=') || is_row_label(cs, i) {
            ST_TAB
        } else if c.is_ascii_digit() {
            ST_TAB_NOTE
        } else {
            // h p b r / \ ~ x ( ) ^ v t s . * : hammer-on, pull-off, bend, slide...
            ST_TAB_TECH
        };
        match out.last_mut() {
            Some((st, text)) if *st == style => text.push(*c),
            _ => out.push((style, c.to_string())),
        }
    }
    out
}

/// Wraps a tab block to `cols`. Every row, and the lines drawn over and under
/// it, is cut at the same columns; cuts prefer bar lines, never split a fret
/// number or a chord name, chunks come out about the same size, and chunks
/// with nothing to play are dropped.
fn wrap_tab(block: &TabBlock, cols: usize, fmt: &dyn Fn(&Chord) -> String, out: &mut Out) {
    let rows: Vec<Vec<char>> = block.rows.iter().map(|l| chars(l)).collect();
    let above: Vec<Overlay> = block
        .above
        .iter()
        .map(|o| match o {
            Overlay::Chords(segs) => Overlay::Chords(relayout(segs, fmt)),
            t => t.clone(),
        })
        .collect();
    let below: Vec<Vec<char>> = block.below.iter().map(|l| chars(l)).collect();
    let over_width = |o: &Overlay| match o {
        Overlay::Text(t) => t.chars().count(),
        Overlay::Chords(segs) => segs.last().map(|s| s.col + s.text.chars().count()).unwrap_or(0),
    };
    let width = rows
        .iter()
        .chain(below.iter())
        .map(|r| r.len())
        .chain(above.iter().map(over_width))
        .max()
        .unwrap_or(0);

    let emit_over = |o: &Overlay, from: usize, to: usize, pad: usize, out: &mut Out| match o {
        Overlay::Text(t) => {
            let s: String = t.chars().skip(from).take(to.saturating_sub(from)).collect();
            if !s.trim().is_empty() {
                out.push(K_NOTE, vec![(ST_NOTE, format!("{}{}", " ".repeat(pad), s.trim_end()))]);
            }
        }
        Overlay::Chords(segs) => {
            let mut spans = seg_line(segs, from, to);
            if spans.iter().any(|(st, _)| *st == ST_CHORD) {
                if pad > 0 {
                    spans.insert(0, (ST_LYRIC, " ".repeat(pad)));
                }
                out.push(K_CHORDS, spans);
            }
        }
    };
    let emit_below = |b: &Vec<char>, from: usize, to: usize, pad: usize, out: &mut Out| {
        let s: String = b.iter().skip(from).take(to.saturating_sub(from)).collect();
        if !s.trim().is_empty() {
            out.push(K_NOTE, vec![(ST_NOTE, format!("{}{}", " ".repeat(pad), s.trim_end()))]);
        }
    };

    // A staff with nothing on it and nothing written around it (sites leave a
    // stray empty string line after the last tab) is not worth a line.
    let playable = rows.iter().any(|r| (0..r.len()).any(|i| is_played(r, i)));
    if !playable && above.is_empty() && below.is_empty() {
        return;
    }

    if width <= cols {
        for o in &above {
            emit_over(o, 0, usize::MAX, 0, out);
        }
        for r in &rows {
            out.push(K_TAB, body_spans(r, tab_prefix(r)));
        }
        for b in &below {
            emit_below(b, 0, usize::MAX, 0, out);
        }
        return;
    }

    // Only the empty tail of the staff is too long ("--2---0----------------|"
    // a couple of columns wider than the screen): shorten the tail instead of
    // cutting the staff in two.
    let filler = |c: char| matches!(c, '-' | ' ' | '=' | '|');
    let used = rows
        .iter()
        .map(|r| r.iter().rposition(|c| !filler(*c)).map_or(0, |i| i + 1))
        .chain(below.iter().map(|b| b.iter().rposition(|c| *c != ' ').map_or(0, |i| i + 1)))
        .chain(above.iter().map(over_width))
        .max()
        .unwrap_or(0);
    if used + 2 <= cols {
        for o in &above {
            emit_over(o, 0, usize::MAX, 0, out);
        }
        for r in &rows {
            let mut line: Vec<char> = r.iter().take(cols).copied().collect();
            if r.len() > cols && r.iter().rev().find(|c| **c != ' ') == Some(&'|') {
                line[cols - 1] = '|';
            }
            out.push(K_TAB, body_spans(&line, tab_prefix(r)));
        }
        for b in &below {
            emit_below(b, 0, usize::MAX, 0, out);
        }
        return;
    }

    let prefixes: Vec<usize> = rows.iter().map(|r| tab_prefix(r)).collect();
    let plen = prefixes.iter().copied().max().unwrap_or(0);
    let body_w = cols.saturating_sub(plen).max(8);
    let body_len = width.saturating_sub(plen);
    let col = |r: &Vec<char>, c: usize| r.get(plen + c).copied().unwrap_or(' ');
    // A cut at body column c falls between columns c-1 and c.
    let in_chord = |c: usize| {
        above.iter().any(|o| match o {
            Overlay::Chords(segs) => segs.iter().any(|s| s.col < plen + c && plen + c < s.col + s.text.chars().count()),
            Overlay::Text(_) => false,
        })
    };
    let safe = |c: usize| {
        !in_chord(c)
            && rows.iter().all(|r| {
                let (a, b) = (col(r, c.saturating_sub(1)), col(r, c));
                // Never inside a number like "12" or a run like "5h7".
                !(a.is_ascii_alphanumeric() && b.is_ascii_alphanumeric()) && matches!(b, '-' | '|' | ' ' | '=')
            })
    };
    let bar = |c: usize| c > 0 && rows.iter().all(|r| col(r, c - 1) == '|');
    // Remarks over the tab should break between words when possible.
    let text_rows: Vec<Vec<char>> = above.iter().filter_map(|o| if let Overlay::Text(t) = o { Some(chars(t)) } else { None }).collect();
    let splits_word = |c: usize| {
        text_rows.iter().any(|t| {
            let (a, b) = (t.get(plen + c.saturating_sub(1)).copied().unwrap_or(' '), t.get(plen + c).copied().unwrap_or(' '));
            c > 0 && a != ' ' && b != ' '
        })
    };
    let has_notes = |from: usize, to: usize| {
        rows.iter().any(|r| (plen + from..(plen + to).min(r.len())).any(|i| is_played(r, i)))
    };

    // Balanced cuts: as many chunks as needed, about the same width each.
    let mut cuts: Vec<(usize, usize)> = Vec::new();
    let mut start = 0;
    while start < body_len {
        let remaining = body_len - start;
        if remaining <= body_w {
            cuts.push((start, body_len));
            break;
        }
        let chunks_left = remaining.div_ceil(body_w);
        let target = start + remaining.div_ceil(chunks_left);
        let lo = start + body_w / 3;
        let hi = start + body_w;
        let mut best: Option<(usize, usize)> = None; // (score, column)
        for c in lo..=hi {
            if !safe(c) {
                continue;
            }
            let score = c.abs_diff(target) * 2 + if bar(c) { 0 } else { 6 } + if splits_word(c) { 40 } else { 0 };
            if best.is_none_or(|b| score < b.0) {
                best = Some((score, c));
            }
        }
        let end = best.map(|b| b.1).unwrap_or(hi);
        cuts.push((start, end));
        start = end;
    }

    // Chunks with nothing to play (a leftover "|", a few dashes, a "~" cut off
    // its note) are skipped; an entirely empty staff keeps its first chunk.
    let keep: Vec<(usize, usize)> = if playable {
        cuts.into_iter().filter(|(a, b)| has_notes(*a, *b)).collect()
    } else {
        cuts.into_iter().take(1).collect()
    };
    for (n, (start, end)) in keep.into_iter().enumerate() {
        if n > 0 {
            out.push(K_BLANK, vec![]);
        }
        let (from, pad) = if start == 0 { (0, 0) } else { (plen + start, plen) };
        for o in &above {
            emit_over(o, from, plen + end, pad, out);
        }
        for (r, p) in rows.iter().zip(prefixes.iter()) {
            // Rows of one block may name their string differently ("4 |--" over
            // "- |--"): at the start of the staff every column is drawn as written.
            let head = if start == 0 { plen } else { *p };
            let mut line: Vec<char> = r[..head.min(r.len())].to_vec();
            line.extend(std::iter::repeat(' ').take(plen.saturating_sub(line.len())));
            line.extend(r.iter().skip(plen + start).take(end - start));
            out.push(K_TAB, body_spans(&line, *p));
        }
        for b in &below {
            emit_below(b, from, plen + end, pad, out);
        }
    }
}

pub struct Rendered {
    pub json: String,
}

/// Parse `text` and render it with `opts`. `key_hint` is the key the source
/// declares (e.g. "G"), used to choose sharps or flats and to report the key.
pub fn render(text: &str, key_hint: &str, opts: Options) -> Rendered {
    let lines = normalize(text);
    let items = classify(&lines);

    let has_tabs = items.iter().any(|i| matches!(i, Item::Tab(..)));
    let has_solo = items.iter().any(|i| matches!(i, Item::Tab(_, TabKind::Solo)));
    let has_riff = items.iter().any(|i| matches!(i, Item::Tab(_, TabKind::Riff)));
    let has_chords = items.iter().any(|i| matches!(i, Item::Chords(..)));

    // Original key: the declared one, else the first chord of the sheet.
    let first_chord = items.iter().find_map(|i| match i {
        Item::Chords(s, _) => s.iter().find_map(|s| s.chord.clone()),
        _ => None,
    });
    let orig_key = Chord::parse(key_hint.trim()).or(first_chord);
    let new_key = orig_key.as_ref().map(|k| {
        let t = k.transpose(opts.transpose);
        Chord { suffix: if k.is_minor() { "m".into() } else { String::new() }, bass: None, ..t }
    });
    let flats = match opts.accidentals {
        1 => Some(false),
        2 => Some(true),
        _ if opts.transpose.rem_euclid(12) == 0 => None, // keep author's spelling
        _ => new_key.as_ref().map(|k| key_prefers_flats(k.root, k.is_minor())).or(Some(false)),
    };
    let spell = |t: &Chord| -> String {
        match flats {
            Some(f) => t.name(f),
            None => {
                // Keep the source spelling of each note.
                let mut s = String::from(crate::chord::note_name(t.root, t.root_flat));
                s.push_str(&t.suffix);
                if let Some(b) = t.bass {
                    s.push('/');
                    s.push_str(crate::chord::note_name(b, t.bass_flat));
                }
                s
            }
        }
    };
    let fmt = |c: &Chord| -> String {
        let c = if opts.simplify { c.simplify() } else { c.clone() };
        spell(&c.transpose(opts.transpose))
    };

    // Unique chords across the whole sheet, in order.
    let mut used: Vec<String> = Vec::new();
    for it in &items {
        let segs_list: Vec<&Vec<Seg>> = match it {
            Item::Chords(segs, _) => vec![segs],
            Item::Tab(b, _) => b.above.iter().filter_map(|o| if let Overlay::Chords(s) = o { Some(s) } else { None }).collect(),
            _ => vec![],
        };
        for segs in segs_list {
            for s in segs {
                if let Some(c) = &s.chord {
                    let n = fmt(c);
                    if !used.contains(&n) {
                        used.push(n);
                    }
                }
            }
        }
    }

    let items = filter(items, opts.view, opts.show_tabs);
    let mut out = Out { lines: Vec::new() };
    let cols = opts.cols.max(8);
    for it in &items {
        match it {
            Item::Blank => out.push(K_BLANK, vec![]),
            Item::Text(t) => {
                for l in wrap_words(t, cols) {
                    out.push(K_TEXT, vec![(ST_LYRIC, l)]);
                }
            }
            Item::Header(h) => {
                for l in wrap_words(h, cols) {
                    out.push(K_HEADER, vec![(ST_HEADER, l)]);
                }
            }
            Item::TabNote(t) => out.push(K_NOTE, vec![(ST_NOTE, t.clone())]),
            Item::Chords(segs, lyric) => {
                let segs = relayout(segs, &fmt);
                wrap_pair(&segs, lyric.as_deref(), cols, &mut out);
            }
            Item::Tab(block, _) => wrap_tab(block, cols, &fmt, &mut out),
        }
    }

    let mut j = Json::object();
    j.key("key").string(&new_key.as_ref().map(&spell).unwrap_or_default());
    j.key("origKey").string(&orig_key.as_ref().map(|k| {
        let mut s = String::from(crate::chord::note_name(k.root, k.root_flat));
        if k.is_minor() { s.push('m'); }
        s
    }).unwrap_or_default());
    j.key("hasTabs").bool(has_tabs);
    j.key("hasSolos").bool(has_solo);
    j.key("hasRiffs").bool(has_riff);
    j.key("hasChords").bool(has_chords);
    j.key("chords").begin_array();
    for c in &used {
        j.string(c);
    }
    j.end_array();
    j.key("lines").begin_array();
    for (kind, spans) in &out.lines {
        j.begin_array();
        j.int(*kind as i64);
        for (style, text) in spans {
            j.int(*style as i64);
            j.string(text);
        }
        j.end_array();
    }
    j.end_array();
    Rendered { json: j.finish() }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SONG: &str = "[Intro] G  D/F#  Em  C

[Primeira Parte]

G              D/F#
  Quando eu olho o céu azul
Em                 C
  Lembro de você

[Tab - Solo]

Parte 1 de 2
E|-----------------|
B|---3--5--3-------|
G|-----------4--2--|
D|-----------------|
A|-----------------|
E|-----------------|

[Refrão]

C            G
  E a gente canta
";

    fn lines_of(json: &str) -> String {
        json.to_string()
    }

    #[test]
    fn classifies() {
        let items = classify(&normalize(SONG));
        assert!(matches!(&items[0], Item::Chords(s, None) if s[0].style == ST_HEADER && s.len() == 5));
        assert!(items.iter().any(|i| matches!(i, Item::Tab(b, TabKind::Solo) if b.rows.len() == 6)));
        assert!(items.iter().any(|i| matches!(i, Item::Chords(_, Some(l)) if l.contains("Quando"))));
        assert!(items.iter().any(|i| matches!(i, Item::TabNote(_))));
    }

    #[test]
    fn chords_view_drops_tabs_and_tab_sections() {
        let r = render(SONG, "G", Options { view: View::Chords, ..Default::default() });
        assert!(!r.json.contains("B|---"));
        assert!(!r.json.contains("Tab - Solo"));
        assert!(!r.json.contains("Parte 1"));
        assert!(r.json.contains("Quando"));
        assert!(r.json.contains("\"hasSolos\":true"));
    }

    #[test]
    fn solos_view_only_has_solo() {
        let r = render(SONG, "G", Options { view: View::Solos, ..Default::default() });
        assert!(r.json.contains("B|---") && r.json.contains(",5,\"3\","));
        assert!(r.json.contains("Tab - Solo"));
        assert!(!r.json.contains("Quando"));
    }

    #[test]
    fn transposes_with_layout() {
        let r = render(SONG, "G", Options { transpose: 2, ..Default::default() });
        let j = lines_of(&r.json);
        assert!(j.contains("\"key\":\"A\""), "{j}");
        assert!(j.contains("\"E/G#\""));
        // Lyric untouched
        assert!(j.contains("Quando eu olho o céu azul"));
    }

    #[test]
    fn relayout_keeps_gap() {
        let segs = chord_segments("G D", 0).unwrap();
        let r = relayout(&segs, &|c| c.transpose(1).name(false) + "m7");
        assert_eq!(r[0].col, 0);
        assert_eq!(r[1].col, "G#m7".len() + 1);
    }

    #[test]
    fn wrap_pair_keeps_chord_over_syllable() {
        let segs = chord_segments("C                      G           Am", 0).unwrap();
        let lyric = "Eu quero ver você aqui comigo outra vez sim";
        let mut out = Out { lines: Vec::new() };
        wrap_pair(&segs, Some(lyric), 20, &mut out);
        // Rebuild text lines and check every chord sits above the same letter as in the source.
        let text: Vec<String> = out.lines.iter().map(|(_, s)| s.iter().map(|(_, t)| t.as_str()).collect()).collect();
        assert!(text.iter().all(|l| l.chars().count() <= 20), "{text:?}");
        let src_g = lyric.chars().nth(23).unwrap();
        // find line with G
        let gi = text.iter().position(|l| l.contains('G')).unwrap();
        let col = text[gi].find('G').unwrap();
        let ly = &text[gi + 1];
        assert_eq!(ly.chars().nth(col), Some(src_g), "{text:?}");
    }

    #[test]
    fn wraps_tab_blocks_together() {
        let rows: Vec<String> = ["e|--1--2--3--4--5--6--7--8--9--10--11--12--|", "B|--1--2--3--4--5--6--7--8--9--10--11--12--|"]
            .iter()
            .map(|s| s.to_string())
            .collect();
        let block = TabBlock { rows, ..Default::default() };
        let mut out = Out { lines: Vec::new() };
        wrap_tab(&block, 20, &|c: &Chord| c.name(false), &mut out);
        let text: Vec<String> = out.lines.iter().map(|(_, s)| s.iter().map(|(_, t)| t.as_str()).collect()).collect();
        assert!(text.iter().all(|l| l.chars().count() <= 20), "{text:?}");
        assert!(text[0].starts_with("e|") && text[1].starts_with("B|"));
        assert!(!text.iter().any(|l| l.ends_with('1') && l.contains("--1")), "{text:?}");
    }

    #[test]
    fn lyric_lines_not_chords() {
        assert!(chord_segments("Em cada canto eu vejo", 0).is_none());
        assert!(chord_segments("A noite é linda", 0).is_none());
        assert!(chord_segments("C  G  (2x)", 0).is_some());
        assert!(chord_segments("| C | G | Am F |", 0).is_some());
        assert!(chord_segments("(C  G)", 0).is_some());
    }

    #[test]
    fn lone_a_and_e_over_lyrics_are_chords() {
        // Lyric continued on its own line, then "A" and "E" alone over lyrics.
        let text = "F#7\n    The pink champagne on ice, and she\nsaid\nA\n  We are all just prisoners here\nE\n  Of our own device\nG\n  And in the master's chambers\n";
        let r = render(text, "", Default::default());
        for c in ["A", "E", "G"] {
            assert!(r.json.contains(&format!("[2,1,\"{c}\"]")), "{c} should be a chord: {}", r.json);
        }
        // Prose stays prose: "E" alone between lyric lines with nothing under it.
        let prose = "Eu fui embora\nE\n\nVoltei\n";
        let r = render(prose, "", Default::default());
        assert!(!r.json.contains("[1,1,\"E\"]") && !r.json.contains("[2,1,\"E\"]"), "{}", r.json);
    }

    // Turn It Again (RHCP): a remark glued above a long tab block.
    const TURN_IT_AGAIN: &str = r#"then                                    bend on the 15th fret twice here
e|---------------------------------------------------------------------------|
B|-15-----18--15--15------15--15-----15--15-18-------18------15----|---------|
G|-------------------17b--------17b----------------17b------17------17-------|
D|---------------------------------------------------------------------------|
A|---------------------------------------------------------------------------|
E|---------------------------------------------------------------------------||
"#;

    #[test]
    fn tab_wrap_keeps_remark_aligned_and_no_scraps() {
        let r = render(TURN_IT_AGAIN, "", Options { cols: 40, ..Default::default() });
        let l = plain_lines(&r.json);
        let joined = l.join("\n");
        assert!(l.iter().all(|x| x.chars().count() <= 40), "{joined}");
        // The remark stays whole words, aligned over the tab, not "th" / "e 15th".
        assert!(!l.iter().any(|x| x.trim() == "th"), "{joined}");
        assert!(joined.contains("bend on the 15th"), "{joined}");
        // Every chunk has at least one note: no "e|-" or "E||" scraps.
        for chunk in joined.split("\n\n") {
            assert!(chunk.chars().any(|c| c.is_ascii_digit()), "empty chunk:\n{chunk}\n---\n{joined}");
        }
        // Fret numbers are never split ("1" + "5").
        for line in &l {
            assert!(!line.ends_with("-1") && !line.ends_with("|1"), "{joined}");
        }
    }

    #[test]
    fn tab_string_numbers_are_not_notes() {
        let spans = tab_spans("4 ------|-----------------------------|----");
        assert_eq!(spans[0], (ST_TAB, "4 ------|-----------------------------|----".to_string()));
        let spans = tab_spans("  ----3-|/5----5/8");
        assert!(spans.iter().any(|s| s.0 == ST_TAB_NOTE && s.1 == "3"));
    }

    #[test]
    fn chords_above_tab_wrap_with_it() {
        let text = "      Bm                    F#7\nE|-------------2---------------2-------------------------------------------|\nB|---------3-----3-----------2-------------------------------------------------||\n";
        let r = render(text, "", Options { cols: 40, ..Default::default() });
        let l = plain_lines(&r.json);
        let joined = l.join("\n");
        // Each chord stays over its own notes, in the chunk where they land.
        assert!(l[0].contains("Bm"), "{joined}");
        let f = l.iter().position(|x| x.contains("F#7")).unwrap();
        assert!(l[f + 1].starts_with("E|") && l[f + 1].contains('2'), "{joined}");
        assert!(r.json.contains("[1,\"Bm\"") || r.json.contains(",1,\"Bm\""), "chords keep chord style: {}", r.json);
        assert!(!joined.contains("E||") && !joined.contains("B||"), "{joined}");
    }

    #[test]
    fn invisible_characters_do_not_hide_chords() {
        let text = "\u{feff}[Primeira Parte]\n\n  \u{200b}Am\u{200c}\nPsychic spies\u{200b} from China\n\u{2060}        F\u{a0}  Dm\nIt's Californication\n";
        let items = classify(&normalize(text));
        assert!(matches!(&items[0], Item::Header(h) if h == "[Primeira Parte]"), "{items:?}");
        let pairs = items.iter().filter(|i| matches!(i, Item::Chords(_, Some(_)))).count();
        assert_eq!(pairs, 2, "{items:?}");
    }

    #[test]
    fn tab_numbers_get_their_own_style() {
        let spans = tab_spans("e|--3h5---12b14r12--7/9~--(5)--x-|");
        assert_eq!(spans[0], (ST_TAB, "e|--".to_string()));
        assert_eq!(spans[1], (ST_TAB_NOTE, "3".to_string()));
        assert_eq!(spans[2], (ST_TAB_TECH, "h".to_string()));
        assert_eq!(spans[3], (ST_TAB_NOTE, "5".to_string()));
        let tech: String = spans.iter().filter(|s| s.0 == ST_TAB_TECH).map(|s| s.1.as_str()).collect();
        assert_eq!(tech, "hbr/~()x");
        let notes: Vec<&str> = spans.iter().filter(|s| s.0 == ST_TAB_NOTE).map(|s| s.1.as_str()).collect();
        assert_eq!(notes, ["3", "5", "12", "14", "12", "7", "9", "5"]);
        let spans = tab_spans("Eb|-0-|");
        assert_eq!(spans[0], (ST_TAB, "Eb|-".to_string()));
    }

    /// Visible text of each rendered line ("" for blanks).
    fn plain_lines(json: &str) -> Vec<String> {
        let lines = &json[json.find("\"lines\":[").unwrap() + 9..json.len() - 2];
        lines
            .split("],[")
            .map(|l| {
                l.trim_matches(|c| c == '[' || c == ']')
                    .split(',')
                    .filter(|p| p.starts_with('"'))
                    .map(|p| p.trim_matches('"'))
                    .collect::<Vec<_>>()
                    .join("")
            })
            .collect()
    }

    // Shaped like Cifra Club's Californication (as seen in the app).
    const CALIFORNICATION: &str = r#"[Intro] Am  F  Am  F
        Am  F  Am  F

[Tab - Intro]

   Am           F
E|-----------------|
B|---1-----1-------|
G|-----2-----2-----|

[Primeira Parte]

   Am           F
E|-----------------|
B|---1-----1-------|

   C        G        F        Dm
E|-----------------|
B|---1-----3-------|

   Am           F
E|-----------------|
B|---1-----1-------|

  Am
Psychic spies from China
          F
Try to steal your mind's elation
"#;

    // Shaped like Cifra Club's The Trooper: guitar labels, palm-mute lines,
    // a "[Tab - Primeira Parte]" section before the real one.
    const TROOPER: &str = r#"[Intro] E5  C5  E5

[Tab - Intro]

(Guitarra 1)

E|--------------------------|
A|-7-7-7p5---5-5-5p3---3-3--|
  pm . . .   . . .

(Guitarra 2)

E|--------------------------|
A|-7-7-7p5---5-5-5p3---3-3--|

        E5
E|--------------------------|
A|-7-7-7p5---5-5-5p3---3-3--|
  pm . . .   . . .
(variação)

[Tab - Primeira Parte]

        E5           E5          C5
E|--------------------------|
A|-7-7-7---5-5-5---3-3-3----|

[Primeira Parte]

E5
   You'll take my life but I'll take yours too

[Solo]

(Guitarra 1)

E|--12b14r12--10-12~--|
B|--------------------|

[Refrão]

D5
   Oooh oooh oooh
"#;

    #[test]
    fn hidden_tabs_californication() {
        let r = render(CALIFORNICATION, "", Options { show_tabs: false, cols: 80, ..Default::default() });
        let l = plain_lines(&r.json);
        let joined = l.join("\n");
        assert!(!joined.contains("Tab"), "{joined}");
        assert!(!joined.contains("C        G"), "chord lines above tabs go: {joined}");
        // Exactly: intro line (2 rows), blank, Primeira Parte, blank, the verse.
        assert_eq!(l[0].trim_start(), "[Intro] Am  F  Am  F", "{joined}");
        assert_eq!(l[2], "", "{joined}");
        assert_eq!(l[3], "[Primeira Parte]", "{joined}");
        assert_eq!(l[4], "", "{joined}");
        assert_eq!(l[5].trim(), "Am", "{joined}");
        assert_eq!(l[6], "Psychic spies from China", "{joined}");
        assert!(!joined.contains("\n\n\n"), "no double gaps: {joined}");
    }

    #[test]
    fn hidden_tabs_trooper() {
        let r = render(TROOPER, "", Options { show_tabs: false, cols: 80, ..Default::default() });
        let l = plain_lines(&r.json);
        let joined = l.join("\n");
        for gone in ["Guitarra", "pm", "variação", "Tab", "[Solo]", "7p5"] {
            assert!(!joined.contains(gone), "{gone} should be hidden: {joined}");
        }
        assert_eq!(joined.matches("[Primeira Parte]").count(), 1, "{joined}");
        assert!(joined.contains("You'll take my life") && joined.contains("[Refrão]"), "{joined}");
        assert!(!joined.contains("\n\n\n"), "no double gaps: {joined}");
        // With tabs shown, everything is still there.
        let r = render(TROOPER, "", Options { cols: 80, ..Default::default() });
        assert!(r.json.contains("Guitarra") && r.json.contains("pm") && r.json.contains("[Tab - Intro]"));
    }

    #[test]
    fn hidden_tabs_mid_song_solo() {
        let mid = "C   G\nLetra um\n\n[Solo]\n\nE|--5--3--|\nB|--5--3--|\n\nAm  F\nLetra dois\n";
        let r = render(mid, "", Options { show_tabs: false, ..Default::default() });
        let joined = plain_lines(&r.json).join("\n");
        assert!(!joined.contains("[Solo]"), "{joined}");
        assert_eq!(joined, "C   G\nLetra um\n\nAm  F\nLetra dois", "{joined}");
    }

    #[test]
    fn empty_staff_is_dropped() {
        // A stray empty string line after the last tab (seen on real pages).
        let t = "E|--3--5--|
B|--3--5--|

E|---------------------------------------------------|

fim
";
        for cols in [20, 80] {
            let r = render(t, "", Options { cols, ..Default::default() });
            let l = plain_lines(&r.json);
            assert_eq!(l.iter().filter(|x| x.starts_with("E|")).count(), 1, "cols {cols}: {l:?}");
            assert!(l.iter().any(|x| x == "fim"), "{l:?}");
        }
    }

    #[test]
    fn chunks_without_notes_are_dropped_even_first() {
        // Two staves on one line: the first is empty, and "4" names a string.
        let t = "  |-------    |-----------7h10p7--------------
1 |-------  4 |------------------7------------
";
        let r = render(t, "", Options { cols: 26, ..Default::default() });
        let l = plain_lines(&r.json);
        assert!(l.iter().all(|x| x.is_empty() || x.chars().any(|c| c.is_ascii_digit())), "{l:?}");
        assert!(l.iter().any(|x| x.contains("7h10p7")), "{l:?}");
        // A "~" cut off from its note is not a chunk worth showing.
        let t = "E|--12--14-----12---------------------|
G|---------------14---~---------------|
";
        let r = render(t, "", Options { cols: 30, ..Default::default() });
        let l = plain_lines(&r.json);
        assert_eq!(l.len(), 2, "{l:?}");
    }

    #[test]
    fn long_empty_tail_is_shortened_not_wrapped() {
        // 45 columns on a 43 column screen, notes end at column 28.
        let t = "    Bm  B7(4/9) Em   F#7
E|--2----2------0----------2----------------|
A|--2----2------2------4--------------------|
";
        let r = render(t, "", Options { cols: 43, ..Default::default() });
        let l = plain_lines(&r.json);
        assert_eq!(l.len(), 3, "{l:?}");
        assert_eq!(l[1], "E|--2----2------0----------2--------------|", "{l:?}");
        assert!(l.iter().all(|x| x.chars().count() <= 43), "{l:?}");
        // Notes up to the end: still wrapped, nothing lost.
        let t = "E|--2----2------0----------2-------------3--|
";
        let r = render(t, "", Options { cols: 43, ..Default::default() });
        let l = plain_lines(&r.json);
        assert!(l.len() > 1 && l.concat().contains("3"), "{l:?}");
    }

    #[test]
    fn thumb_marks_stay_with_the_tab() {
        let t = "|----5---(5)-------|
|5/7---------------|
  T          T
";
        let r = render(t, "", Options { show_tabs: false, ..Default::default() });
        assert!(r.json.ends_with("\"lines\":[]}"), "{}", r.json);
    }

    #[test]
    fn string_number_inside_a_row_is_not_a_note() {
        let spans = tab_spans("1 |-------  4 |----7--");
        let notes: Vec<&str> = spans.iter().filter(|(st, _)| *st == ST_TAB_NOTE).map(|(_, t)| t.as_str()).collect();
        assert_eq!(notes, vec!["7"], "{spans:?}");
    }

    #[test]
    fn rows_with_different_string_names_keep_their_start() {
        // "- |" has no name the parser knows; its first columns must not vanish.
        let t = "- |-------7/9--------9---7-----7--
4 |-----5------------------10-----
";
        let r = render(t, "", Options { cols: 22, ..Default::default() });
        let l = plain_lines(&r.json);
        assert!(l[0].starts_with("- |-------7/9"), "{l:?}");
        assert!(l[1].starts_with("4 |-----5"), "{l:?}");
    }

    #[test]
    fn tab_detection() {
        assert!(is_tab_line("e|---0---3---|"));
        assert!(is_tab_line("D#|--5h7--|"));
        assert!(is_tab_line("|----0----2----3-----|"));
        assert!(!is_tab_line("Eu sei - que - você"));
        assert!(!is_tab_line("G   D   Em"));
    }
}
