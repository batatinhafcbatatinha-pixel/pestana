//! Extracts a saved web page and renders it the way the app does, as plain text.
//! Usage: cargo run --example page -- <file.html> <url> [cols] [show_tabs 0/1] [view]
use pestana::sheet::{render, Options, View};

/// Reads the JSON string that starts right after the quote at `s[0]`.
/// Returns the decoded text and the number of bytes consumed.
fn string_at(s: &str) -> (String, usize) {
    let mut out = String::new();
    let mut it = s.char_indices().skip(1);
    while let Some((i, c)) = it.next() {
        match c {
            '"' => return (out, i + 1),
            '\\' => match it.next().map(|x| x.1) {
                Some('n') => out.push('\n'),
                Some('t') => out.push('\t'),
                Some('r') => {}
                Some('u') => {
                    let h: String = it.by_ref().take(4).map(|x| x.1).collect();
                    out.push(u32::from_str_radix(&h, 16).ok().and_then(char::from_u32).unwrap_or(' '));
                }
                Some(o) => out.push(o),
                None => break,
            },
            c => out.push(c),
        }
    }
    (out, s.len())
}

fn field(json: &str, key: &str) -> String {
    let pat = format!("\"{key}\":");
    json.find(&pat).map(|i| string_at(&json[i + pat.len()..]).0).unwrap_or_default()
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let html = std::fs::read_to_string(&a[1]).expect("read");
    let j = pestana::html::extract(&html, &a[2]);
    let cols = a.get(3).and_then(|c| c.parse().ok()).unwrap_or(1000);
    let show_tabs = a.get(4).is_none_or(|s| s != "0");
    let view = View::from(a.get(5).and_then(|c| c.parse().ok()).unwrap_or(0));
    eprintln!("{}", &j[j.find("\"versions\"").unwrap_or(0)..]);
    let r = render(&field(&j, "text"), &field(&j, "key"), Options { cols, show_tabs, view, ..Options::default() });
    let lines = &r.json[r.json.find("\"lines\":[").unwrap() + 9..];
    for l in lines.split("],[") {
        let mut s = String::new();
        let mut rest = l;
        while let Some(i) = rest.find('"') {
            let (t, used) = string_at(&rest[i..]);
            s.push_str(&t);
            rest = &rest[i + used..];
        }
        println!("{s}");
    }
}
