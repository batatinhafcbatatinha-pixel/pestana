//! Prints what the app reads from a saved playlist or profile page.
//! Usage: cargo run --example links -- <file.html> <url> [artist]
//! With "artist", reads it as an artist, discography or album page.
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let html = std::fs::read_to_string(&a[1]).expect("read");
    let json = if a.get(3).is_some() { pestana::html::artist_page(&html, &a[2]) } else { pestana::html::playlist_links(&html, &a[2]) };
    println!("{}", json.replace("},{", "},\n{"));
}
