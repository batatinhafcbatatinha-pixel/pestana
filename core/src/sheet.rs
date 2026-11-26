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

#[derive(Debug, Clone)]
enum Item {
    Blank,
    Text(String),
    Header(String),
    /// Chord line, optionally with the lyric line under it.
    Chords(Vec<Seg>, Option<String>),
    Tab(Vec<String>, TabKind),
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
                    '\u{a0}' | '\u{2000}'..='\u{200a}' | '\u{202f}' | '\u{205f}' | '\u{3000}' => out.push(' '),
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
                    | '\u{180e}'
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
            let mut block = Vec::new();
            while i < lines.len() && is_tab_line(&lines[i]) {
                block.push(lines[i].clone());
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
            if !(only_ambiguous && prev_text) {
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

/// "[Tab - Intro]" -> "[Intro]" once the tablature is hidden.
fn untab_label(label: &str) -> String {
    let inner = label.trim().trim_start_matches('[').trim_end_matches(']');
    let low = inner.to_lowercase();
    for p in ["tab - ", "tab -", "tab: ", "tab:", "tab "] {
        if low.starts_with(p) && inner.len() > p.len() {
            return format!("[{}]", inner[p.len()..].trim());
        }
    }
    label.to_string()
}

/// Keep only what the view asks for.
fn filter(items: Vec<Item>, view: View, show_tabs: bool) -> Vec<Item> {
    let keep_tabs = show_tabs && view != View::Chords;
    match view {
        View::Full | View::Chords => {
            let mut out: Vec<Item> = Vec::with_capacity(items.len());
            // Drop headers whose whole section was tablature, when tabs are hidden.
            let mut idx = 0;
            while idx < items.len() {
                let it = &items[idx];
                match it {
                    Item::Tab(..) | Item::TabNote(_) if !keep_tabs => {
                        // The gap after a hidden tab block goes with it, so the
                        // chord lines that framed the tab sit together.
                        let mut j = idx + 1;
                        while j < items.len() && matches!(items[j], Item::Blank) {
                            j += 1;
                        }
                        let next_is_header = match items.get(j) {
                            Some(Item::Header(_)) => true,
                            Some(Item::Chords(segs, _)) => segs.first().is_some_and(|s| s.style == ST_HEADER),
                            _ => false,
                        };
                        // Keep one blank before the next section; drop it inside a section.
                        if !next_is_header {
                            idx = j - 1;
                        }
                    }
                    Item::Chords(segs, lyric) if !keep_tabs && segs.first().is_some_and(|s| s.style == ST_HEADER) => {
                        let mut segs = segs.clone();
                        segs[0].text = untab_label(&segs[0].text);
                        out.push(Item::Chords(segs, lyric.clone()));
                    }
                    Item::Header(_) if !keep_tabs => {
                        // A header whose first content is tablature ("[Solo]" + tab)
                        // labels that tab: it goes away with it.
                        let mut j = idx + 1;
                        while j < items.len() && matches!(items[j], Item::Blank) {
                            j += 1;
                        }
                        let labels_tab = matches!(items.get(j), Some(Item::Tab(..)) | Some(Item::TabNote(_)));
                        if labels_tab {
                            // Skip the blanks between the header and its tab too.
                            idx = j - 1;
                        } else {
                            if let Item::Header(h) = it {
                                out.push(Item::Header(untab_label(h)));
                            }
                        }
                    }
                    _ => out.push(it.clone()),
                }
                idx += 1;
            }
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

/// Splits a tab line so fret numbers stand out from the string lines:
/// "e|--3h5--|" -> [("e|--", TAB), ("3h5", TAB_NOTE), ("--|", TAB)].
fn tab_spans(line: &str) -> Vec<(u8, String)> {
    let cs: Vec<char> = line.chars().collect();
    // The string name ("e|", "D#|", "A ") is part of the drawing, not a note.
    let lead = cs.iter().take_while(|c| **c == ' ').count();
    let prefix = match cs.iter().skip(lead).position(|c| *c == '|' || *c == ':') {
        Some(p) if p <= 3 => lead + p + 1,
        _ => lead,
    };
    let mut out: Vec<(u8, String)> = Vec::new();
    let mut push = |style: u8, c: char, out: &mut Vec<(u8, String)>| match out.last_mut() {
        Some((st, text)) if *st == style => text.push(c),
        _ => out.push((style, c.to_string())),
    };
    for (i, c) in cs.iter().enumerate() {
        let style = if i < prefix || matches!(c, '-' | '|' | ':' | ' ' | '=') {
            ST_TAB
        } else if c.is_ascii_digit() {
            ST_TAB_NOTE
        } else {
            // h p b r / \ ~ x ( ) ^ v t s . * : hammer-on, pull-off, bend, slide...
            ST_TAB_TECH
        };
        push(style, *c, &mut out);
    }
    out
}

fn wrap_tab(block: &[String], cols: usize, out: &mut Out) {
    let rows: Vec<Vec<char>> = block.iter().map(|l| chars(l)).collect();
    let width = rows.iter().map(|r| r.len()).max().unwrap_or(0);
    if width <= cols {
        for r in &rows {
            out.push(K_TAB, tab_spans(&r.iter().collect::<String>()));
        }
        return;
    }
    // Prefix = string name up to and including the first bar, e.g. "e|".
    let prefix: Vec<Vec<char>> = rows
        .iter()
        .map(|r| {
            let lead: usize = r.iter().take_while(|c| **c == ' ').count();
            match r.iter().skip(lead).position(|c| *c == '|') {
                Some(p) if p <= 3 => r[..lead + p + 1].to_vec(),
                _ => Vec::new(),
            }
        })
        .collect();
    let plen = prefix.iter().map(|p| p.len()).max().unwrap_or(0);
    let body_w = cols.saturating_sub(plen).max(8);
    let safe = |c: usize| {
        rows.iter().zip(prefix.iter()).all(|(r, p)| {
            let i = p.len().max(plen.min(r.len())) + c;
            i >= r.len() || matches!(r[i], '-' | '|' | ' ')
        })
    };
    let body_len = width - plen;
    let mut start = 0;
    while start < body_len {
        let mut end = (start + body_w).min(body_len);
        if end < body_len {
            let mut e = end;
            while e > start + body_w / 2 && !safe(e) {
                e -= 1;
            }
            if safe(e) {
                end = e;
            }
        }
        for (r, p) in rows.iter().zip(prefix.iter()) {
            let off = if p.is_empty() { 0 } else { plen };
            let s: String = p.iter().collect::<String>()
                + &" ".repeat(plen - p.len().min(plen))
                + &r.iter().skip(off + start).take(end - start).collect::<String>();
            let s = if p.is_empty() { r.iter().skip(start).take(end - start).collect::<String>() } else { s };
            out.push(K_TAB, tab_spans(&s));
        }
        start = end;
        if start < body_len {
            out.push(K_BLANK, vec![]);
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
        if let Item::Chords(segs, _) = it {
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
            Item::Tab(block, _) => wrap_tab(block, cols, &mut out),
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
        assert!(items.iter().any(|i| matches!(i, Item::Tab(b, TabKind::Solo) if b.len() == 6)));
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
        let block: Vec<String> = ["e|--1--2--3--4--5--6--7--8--9--10--11--12--|", "B|--1--2--3--4--5--6--7--8--9--10--11--12--|"]
            .iter()
            .map(|s| s.to_string())
            .collect();
        let mut out = Out { lines: Vec::new() };
        wrap_tab(&block, 20, &mut out);
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

    #[test]
    fn hidden_tabs_leave_no_gaps() {
        let text = "[Tab - Intro]

   Am        F
E|--5--3--|
B|--5--3--|

   C    G
E|--8--7--|
B|--8--7--|

[Primeira Parte]
";
        let r = render(text, "", Options { show_tabs: false, ..Default::default() });
        assert!(r.json.contains("[Intro]") && !r.json.contains("Tab - "), "{}", r.json);
        // Intro header, blank, Am F, C G (no blank between them), blank, Primeira Parte.
        let lines = &r.json[r.json.find("\"lines\"").unwrap()..];
        let kinds: Vec<&str> = lines.split("],[").collect();
        let am = kinds.iter().position(|k| k.contains("\"Am\"")).unwrap();
        assert!(kinds[am + 1].contains("\"C\""), "{}", r.json);
        assert!(kinds[am + 2].starts_with('6'), "blank before next section: {}", r.json);

        // A solo in the middle: its header and tab go away without leaving a hole.
        let mid = "C   G
Letra um

[Solo]

E|--5--3--|
B|--5--3--|

Am  F
Letra dois
";
        let r = render(mid, "", Options { show_tabs: false, ..Default::default() });
        assert!(!r.json.contains("[Solo]"), "{}", r.json);
        assert!(!r.json.contains("[6],[6]"), "{}", r.json);
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
