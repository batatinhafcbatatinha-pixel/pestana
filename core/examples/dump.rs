//! Renders a chord sheet text file the way the app does and prints the JSON.
//! Usage: cargo run --example dump -- <file> [cols] [key]
use pestana::sheet::{render, Options};

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let text = std::fs::read_to_string(&a[1]).expect("read");
    let cols = a.get(2).and_then(|c| c.parse().ok()).unwrap_or(1000);
    let key = a.get(3).cloned().unwrap_or_default();
    let r = render(&text, &key, Options { cols, ..Options::default() });
    println!("{}", r.json);
}
