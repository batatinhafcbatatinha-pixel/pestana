//! Pull a chord sheet out of a web page: the `<pre>` block as plain text, the
//! title, artist, key, capo, tuning and links to other versions of the song.
//! Written to be tolerant of markup changes: it relies on `<pre>`, headings,
//! `<title>` and link text, not on site-specific class names.

use crate::chord::Chord;
use crate::json::Json;

pub fn decode_entities(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        rest = &rest[i..];
        let end = rest[..rest.len().min(12)].find(';');
        let Some(end) = end else {
            out.push('&');
            rest = &rest[1..];
            continue;
        };
        let ent = &rest[1..end];
        let rep: Option<String> = match ent {
            "amp" => Some("&".into()),
            "lt" => Some("<".into()),
            "gt" => Some(">".into()),
            "quot" => Some("\"".into()),
            "apos" => Some("'".into()),
            "nbsp" => Some(" ".into()),
            "ndash" => Some("–".into()),
            "mdash" => Some("—".into()),
            "hellip" => Some("…".into()),
            "ordf" => Some("ª".into()),
            "ordm" => Some("º".into()),
            "deg" => Some("°".into()),
            "sharp" => Some("♯".into()),
            _ if ent.starts_with("#x") || ent.starts_with("#X") => {
                u32::from_str_radix(&ent[2..], 16).ok().and_then(char::from_u32).map(|c| c.to_string())
            }
            _ if ent.starts_with('#') => ent[1..].parse::<u32>().ok().and_then(char::from_u32).map(|c| c.to_string()),
            _ => None,
        };
        match rep {
            Some(r) => {
                out.push_str(&r);
                rest = &rest[end + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

/// Strip tags; `<br>` becomes a newline.
pub fn strip_tags(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('<') {
        out.push_str(&rest[..i]);
        let Some(j) = rest[i..].find('>') else {
            out.push_str(&rest[i..]);
            rest = "";
            break;
        };
        let tag = rest[i + 1..i + j].trim_start_matches('/').to_ascii_lowercase();
        if tag.starts_with("br") {
            out.push('\n');
        }
        rest = &rest[i + j + 1..];
    }
    out.push_str(rest);
    decode_entities(&out)
}

fn lower(s: &str) -> String {
    // ASCII lowercase keeps byte offsets identical to the source.
    s.to_ascii_lowercase()
}

/// Inner HTML of every `<tag ...>...</tag>` (non-nested).
fn elements<'a>(html: &'a str, low: &str, tag: &str) -> Vec<(&'a str, &'a str)> {
    let open = format!("<{tag}");
    let close = format!("</{tag}>");
    let mut out = Vec::new();
    let mut pos = 0;
    while let Some(i) = low[pos..].find(&open) {
        let start = pos + i;
        let after = low.as_bytes().get(start + open.len()).copied().unwrap_or(b'>');
        if !(after == b'>' || after == b' ' || after == b'\n' || after == b'\t') {
            pos = start + open.len();
            continue;
        }
        let Some(gt) = low[start..].find('>') else { break };
        let body_start = start + gt + 1;
        let Some(end) = low[body_start..].find(&close) else { break };
        out.push((&html[start..body_start], &html[body_start..body_start + end]));
        pos = body_start + end + close.len();
    }
    out
}

fn attr<'a>(tag: &'a str, name: &str) -> Option<&'a str> {
    let low = lower(tag);
    let mut pos = 0;
    while let Some(i) = low[pos..].find(name) {
        let at = pos + i;
        let before_ok = at == 0 || matches!(low.as_bytes()[at - 1], b' ' | b'\n' | b'\t');
        let rest = &tag[at + name.len()..];
        let rest_t = rest.trim_start();
        if before_ok && rest_t.starts_with('=') {
            let v = rest_t[1..].trim_start();
            let q = v.chars().next()?;
            if q == '"' || q == '\'' {
                let e = v[1..].find(q)?;
                return Some(&v[1..1 + e]);
            }
            let e = v.find(|c: char| c.is_whitespace() || c == '>').unwrap_or(v.len());
            return Some(&v[..e]);
        }
        pos = at + name.len();
    }
    None
}

fn clean(s: &str) -> String {
    strip_tags(s).split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Path part of a URL: "https://x.com/a/b/?q#f" -> "/a/b/"
fn url_path(u: &str) -> &str {
    let u = u.split(['#', '?']).next().unwrap_or("");
    match u.find("://") {
        Some(i) => match u[i + 3..].find('/') {
            Some(j) => &u[i + 3 + j..],
            None => "/",
        },
        None => u,
    }
}

fn origin(u: &str) -> &str {
    match u.find("://") {
        Some(i) => match u[i + 3..].find('/') {
            Some(j) => &u[..i + 3 + j],
            None => u,
        },
        None => "",
    }
}

const VERSION_WORDS: [&str; 14] = [
    "simplificada", "principal", "versão", "versao", "version", "tab", "violão", "violao", "guitarra", "cavaco",
    "ukulele", "baixo", "teclado", "viola",
];

const VERSION_SKIP: [&str; 8] = ["imprimir", "letra", "video", "vídeo", "aula", "coment", "enviar", "corrigir"];

pub fn extract(html: &str, page_url: &str) -> String {
    let low = lower(html);

    // Sheet: the longest <pre>.
    let pres = elements(html, &low, "pre");
    let sheet = pres.iter().map(|(_, b)| strip_tags(b)).max_by_key(|s| s.len()).unwrap_or_default();

    // Title and artist: <h1>/<h2>, falling back to "<title>Song - Artist - Site</title>".
    let h1 = elements(html, &low, "h1").first().map(|(_, b)| clean(b)).unwrap_or_default();
    let h2 = elements(html, &low, "h2").first().map(|(_, b)| clean(b)).unwrap_or_default();
    let title_tag = elements(html, &low, "title").first().map(|(_, b)| clean(b)).unwrap_or_default();
    let parts: Vec<&str> = title_tag.split(" - ").map(|s| s.trim()).collect();
    let mut title = if !h1.is_empty() { h1 } else { parts.first().map(|s| s.to_string()).unwrap_or_default() };
    let mut artist = if parts.len() >= 2 { parts[1].to_string() } else { h2.clone() };
    if artist.is_empty() {
        artist = h2;
    }
    for suffix in [" (cifra)", " cifra", " - cifra club"] {
        if title.to_lowercase().ends_with(suffix) {
            title.truncate(title.len() - suffix.len());
        }
    }

    // Plain text of the page around the sheet, for "Tom:", "Capotraste", "Afinação".
    let text = strip_tags(&elements(html, &low, "body").first().map(|(_, b)| *b).unwrap_or(html).replace("</", "\n</"));
    let text_low = text.to_lowercase();
    let mut key = String::new();
    if let Some(i) = text_low.find("tom:") {
        let after = &text[i + 4..];
        if let Some(tok) = after.split_whitespace().next() {
            if Chord::parse(tok).is_some() {
                key = tok.to_string();
            }
        }
    }
    let mut capo = 0;
    for marker in ["capotraste na ", "capo na ", "capo "] {
        if let Some(i) = text_low.find(marker) {
            let d: String = text_low[i + marker.len()..].chars().take_while(|c| c.is_ascii_digit()).collect();
            if let Ok(n) = d.parse::<i64>() {
                if (1..=12).contains(&n) {
                    capo = n;
                    break;
                }
            }
        }
    }
    let mut tuning = String::new();
    if let Some(i) = text_low.find("afinação:") {
        let after = &text[i + "afinação:".len()..];
        tuning = after.lines().next().unwrap_or("").trim().chars().take(40).collect();
    }

    // Versions: links under the same song path whose text names a version.
    let base = url_path(page_url).to_string();
    let base = if base.ends_with('/') { base } else { base[..base.rfind('/').map(|i| i + 1).unwrap_or(0)].to_string() };
    let org = origin(page_url);
    let mut versions: Vec<(String, String)> = Vec::new();
    for (tag, body) in elements(html, &low, "a") {
        let Some(href) = attr(tag, "href") else { continue };
        let href = decode_entities(href);
        let label = clean(body);
        let ll = label.to_lowercase();
        let path = url_path(&href).to_string();
        let hl = href.to_lowercase();
        if label.is_empty() || label.chars().count() > 40 || base.len() < 3 || !path.starts_with(&base) || path == base {
            continue;
        }
        if VERSION_SKIP.iter().any(|w| hl.contains(w) || ll.contains(w)) {
            continue;
        }
        if !VERSION_WORDS.iter().any(|w| ll.contains(w) || hl.contains(w)) {
            continue;
        }
        let full = if href.starts_with("http") { href.clone() } else { format!("{org}{path}") };
        if !versions.iter().any(|(_, u)| *u == full) {
            versions.push((label, full));
        }
    }

    let mut j = Json::object();
    j.key("title").string(&title);
    j.key("artist").string(&artist);
    j.key("key").string(&key);
    j.key("capo").int(capo);
    j.key("tuning").string(&tuning);
    j.key("text").string(sheet.trim_matches('\n'));
    j.key("versions").begin_array();
    for (label, url) in &versions {
        j.begin_object();
        j.key("label").string(label);
        j.key("url").string(url);
        j.end_object();
    }
    j.end_array();
    j.finish()
}


/// Paths whose first segment is a site section, not an artist.
const NOT_ARTIST: [&str; 34] = [
    "musico", "musicos", "playlist", "playlists", "estilos", "estilo", "aprenda", "cursos", "curso", "blog", "academy",
    "top", "novidades", "contribuicoes", "contribuicao", "app", "dicionario-de-acordes", "afinador", "metronomo",
    "ajuda", "enviar", "login", "radio", "videoaulas", "videoaula", "busca", "cifras", "artistas", "generos", "palco",
    "ranking", "termos", "privacidade", "assine",
];

/// Links on a playlist or profile page: songs ("/artist/song/") and playlists.
pub fn playlist_links(html: &str, page_url: &str) -> String {
    let low = lower(html);
    let org = origin(page_url).to_string();
    let page_path = url_path(page_url).trim_end_matches('/').to_string();
    let title = elements(html, &low, "h1").first().map(|(_, b)| clean(b))
        .or_else(|| elements(html, &low, "title").first().map(|(_, b)| clean(b).split(" - ").next().unwrap_or("").to_string()))
        .unwrap_or_default();
    let mut songs: Vec<(String, String, String)> = Vec::new(); // (path, label, artist slug)
    let mut lists: Vec<(String, String)> = Vec::new();
    for (tag, body) in elements(html, &low, "a") {
        let Some(href) = attr(tag, "href") else { continue };
        let href = decode_entities(href);
        if href.starts_with("http") && !href.starts_with(&org) {
            continue;
        }
        let path = url_path(&href).to_string();
        let label = clean(body);
        let segs: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
        let pl = path.to_lowercase();
        if pl.contains("playlist") {
            let p = path.trim_end_matches('/').to_string();
            if p != page_path && !label.is_empty() && label.chars().count() < 80 && !lists.iter().any(|(u, _)| *u == p) {
                lists.push((p, label));
            }
            continue;
        }
        if segs.len() != 2 || NOT_ARTIST.contains(&segs[0]) || segs[1].contains('.') || label.is_empty() {
            continue;
        }
        if !segs.iter().all(|s| s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')) {
            continue;
        }
        let id = format!("{}/{}", segs[0], segs[1]);
        if let Some(existing) = songs.iter_mut().find(|(p, _, _)| *p == id) {
            // Keep the longer label (cards often repeat the link: image + title).
            if label.len() > existing.1.len() && label.chars().count() < 120 {
                existing.1 = label;
            }
            continue;
        }
        songs.push((id, label, segs[0].to_string()));
    }
    let mut j = Json::object();
    j.key("title").string(&title);
    j.key("songs").begin_array();
    for (id, label, _) in &songs {
        j.begin_object();
        j.key("id").string(id);
        j.key("label").string(label);
        j.end_object();
    }
    j.end_array();
    j.key("playlists").begin_array();
    for (p, label) in &lists {
        j.begin_object();
        j.key("url").string(&format!("{org}{p}/"));
        j.key("label").string(label);
        j.end_object();
    }
    j.end_array();
    j.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    const PAGE: &str = r#"<html><head><title>Tempo Perdido - Legião Urbana - Cifra Club</title></head>
<body><h1 class="t1">Tempo Perdido</h1><h2 class="t3"><a href="/legiao-urbana/">Legião Urbana</a></h2>
<div>Tom: <a class="js-modal-trigger">C</a></div>
<div>Capotraste na 2ª casa</div>
<a href="/legiao-urbana/tempo-perdido/simplificada.html">Simplificada</a>
<a href="/legiao-urbana/tempo-perdido/imprimir.html">Imprimir</a>
<a href="https://www.site.com/legiao-urbana/tempo-perdido/versao-2.html">Versão 2</a>
<a href="/legiao-urbana/">Legião</a>
<pre>[Intro] <b>C</b>  <b>Dm</b>

<b>C</b>              <b>Dm</b>
  Todos os dias quando acordo
<span class="tablatura"><span class="cnt">E|--3--&amp;--|</span></span>
</pre></body></html>"#;

    #[test]
    fn extracts() {
        let j = extract(PAGE, "https://www.site.com/legiao-urbana/tempo-perdido/");
        assert!(j.contains(r#""title":"Tempo Perdido""#), "{j}");
        assert!(j.contains(r#""artist":"Legião Urbana""#), "{j}");
        assert!(j.contains(r#""key":"C""#), "{j}");
        assert!(j.contains(r#""capo":2"#), "{j}");
        assert!(j.contains("[Intro] C  Dm"), "{j}");
        assert!(j.contains("E|--3--&--|"), "{j}");
        assert!(j.contains("simplificada.html"));
        assert!(j.contains("versao-2.html"));
        assert!(!j.contains("imprimir"));
    }

    #[test]
    fn playlist_page() {
        let html = r#"<html><head><title>Ensaio - Cifra Club</title></head><body><h1>Ensaio</h1>
<a href="/legiao-urbana/tempo-perdido/"><img></a>
<a href="/legiao-urbana/tempo-perdido/">Tempo Perdido Legião Urbana</a>
<a href="https://www.site.com/djavan/oceano/">Oceano</a>
<a href="/estilos/rock/">Rock</a>
<a href="/legiao-urbana/">Legião</a>
<a href="/musico/123/playlists/456/">Outra lista</a>
<a href="https://evil.com/a/b/">x</a>
</body></html>"#;
        let j = playlist_links(html, "https://www.site.com/musico/123/playlists/789/");
        assert!(j.contains(r#"{"id":"legiao-urbana/tempo-perdido","label":"Tempo Perdido Legião Urbana"}"#), "{j}");
        assert!(j.contains(r#""id":"djavan/oceano""#), "{j}");
        assert!(!j.contains("estilos") && !j.contains("evil"), "{j}");
        assert!(j.contains(r#""url":"https://www.site.com/musico/123/playlists/456/""#), "{j}");
        assert!(j.contains(r#""title":"Ensaio""#), "{j}");
    }

    #[test]
    fn entities() {
        assert_eq!(decode_entities("a &amp; b &#233; &#xE9; &bogus; &"), "a & b é é &bogus; &");
    }
}
