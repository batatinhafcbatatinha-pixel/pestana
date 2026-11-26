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
        // Looked for byte by byte: twelve bytes on may be the middle of a character.
        let end = rest.as_bytes()[..rest.len().min(12)].iter().position(|&b| b == b';');
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

const VERSION_WORDS: [&str; 16] = [
    "simplificada", "principal", "versão", "versao", "version", "tab", "violão", "violao", "guitarra", "cavaco",
    "ukulele", "baixo", "teclado", "viola", "acústic", "acustic",
];

/// The address the page gives as its own (`<link rel="canonical">`). A site
/// that answers a version it does not have with the main sheet says so here.
fn canonical(html: &str, low: &str) -> String {
    let mut pos = 0;
    while let Some(i) = low[pos..].find("<link") {
        let start = pos + i;
        let Some(end) = low[start..].find('>') else { break };
        let tag = &html[start..start + end];
        if attr(tag, "rel").is_some_and(|r| r.eq_ignore_ascii_case("canonical")) {
            return attr(tag, "href").map(decode_entities).unwrap_or_default();
        }
        pos = start + end;
    }
    String::new()
}

const VERSION_SKIP: [&str; 10] =
    ["imprimir", "letra", "video", "vídeo", "aula", "coment", "enviar", "corrigir", "tabspro", "tabs pro"];

/// Reads a JSON string literal starting right after its opening quote.
fn json_string(s: &str) -> Option<String> {
    let mut out = String::new();
    let mut it = s.chars();
    while let Some(c) = it.next() {
        match c {
            '"' => return Some(out),
            '\\' => match it.next()? {
                'n' => out.push('\n'),
                'r' => out.push('\r'),
                't' => out.push('\t'),
                'b' | 'f' => {}
                'u' => {
                    let hex: String = it.by_ref().take(4).collect();
                    let mut code = u32::from_str_radix(&hex, 16).ok()?;
                    if (0xD800..0xDC00).contains(&code) {
                        // Surrogate pair: "🎸"
                        let rest: String = it.by_ref().take(6).collect();
                        let low = rest.strip_prefix("\\u").and_then(|h| u32::from_str_radix(h, 16).ok())?;
                        code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                    }
                    out.push(char::from_u32(code).unwrap_or('\u{fffd}'));
                }
                other => out.push(other),
            },
            c => out.push(c),
        }
    }
    None
}

fn json_field<'a>(json: &'a str, from: usize, key: &str) -> Option<(usize, &'a str)> {
    let pat = format!("\"{key}\":");
    let i = from + json[from..].find(&pat)? + pat.len();
    Some((i, json[i..].trim_start()))
}

/// Ultimate Guitar pages carry the sheet in an embedded JSON store
/// (`<div class="js-store" data-content="...">`) using [ch]Am[/ch] and
/// [tab]...[/tab] markup. Returns the same JSON as `extract`.
fn ultimate_guitar(html: &str) -> Option<String> {
    let data = if let Some(i) = html.find("js-store") {
        let rest = &html[i..];
        let start = rest.find("data-content=\"")? + "data-content=\"".len();
        let end = rest[start..].find('"')?;
        decode_entities(&rest[start..start + end])
    } else if html.contains("\"wiki_tab\"") {
        html.to_string()
    } else {
        return None;
    };
    let (wiki, _) = json_field(&data, 0, "wiki_tab")?;
    let (_, content) = json_field(&data, wiki, "content")?;
    let raw = json_string(content.strip_prefix('"')?)?;
    let text = raw.replace("[ch]", "").replace("[/ch]", "").replace("[tab]", "").replace("[/tab]", "");
    let text = text.replace("\r\n", "\n");

    let string_field = |k: &str| -> String {
        json_field(&data, 0, k)
            .and_then(|(_, v)| v.strip_prefix('"').and_then(json_string))
            .unwrap_or_default()
    };
    let capo = json_field(&data, 0, "capo")
        .map(|(_, v)| v.chars().take_while(|c| c.is_ascii_digit()).collect::<String>())
        .and_then(|d| d.parse::<i64>().ok())
        .unwrap_or(0);
    let key = string_field("tonality_name");
    let tuning = json_field(&data, 0, "tuning")
        .and_then(|(i, _)| json_field(&data, i, "value"))
        .and_then(|(_, v)| v.strip_prefix('"').and_then(json_string))
        .unwrap_or_default();

    let mut j = Json::object();
    j.key("title").string(&string_field("song_name"));
    j.key("artist").string(&string_field("artist_name"));
    j.key("key").string(if Chord::parse(&key).is_some() { &key } else { "" });
    j.key("capo").int(capo);
    j.key("tuning").string(if tuning == "E A D G B E" { "" } else { &tuning });
    j.key("text").string(text.trim_matches('\n'));
    j.key("versions").begin_array();
    j.end_array();
    Some(j.finish())
}

pub fn extract(html: &str, page_url: &str) -> String {
    if let Some(ug) = ultimate_guitar(html) {
        return ug;
    }
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
    j.key("canonical").string(&canonical(html, &low));
    j.key("version").string(&version_name(html, page_url));
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
const NOT_ARTIST: [&str; 40] = [
    "letra", "explorar", "videos", "mais", "mais-acessadas", "pedir-videoaula",
    "musico", "musicos", "playlist", "playlists", "estilos", "estilo", "aprenda", "cursos", "curso", "blog", "academy",
    "top", "novidades", "contribuicoes", "contribuicao", "app", "dicionario-de-acordes", "afinador", "metronomo",
    "ajuda", "enviar", "login", "radio", "videoaulas", "videoaula", "busca", "cifras", "artistas", "generos", "palco",
    "ranking", "termos", "privacidade", "assine",
];

/// A list's name out of its card on a profile page, which reads
/// "Favoritas Favoritas Fulano • 539 músicas": the name (twice when the card
/// has no cover), the owner, the size.
fn list_label(label: &str, owner: &str) -> String {
    let l = label.split('•').next().unwrap_or("").trim();
    let l = if owner.is_empty() { l } else { l.strip_suffix(owner).unwrap_or(l).trim() };
    // "Favoritas Favoritas", or "FavoritasFavoritas" when nothing separates the two.
    let c: Vec<char> = l.chars().collect();
    let half = c.len() / 2;
    if half > 0 {
        let (a, b): (String, String) = (c[..half].iter().collect(), c[c.len() - half..].iter().collect());
        if a.trim() == b.trim() && c[half..c.len() - half].iter().all(|x| *x == ' ') {
            return a.trim().to_string();
        }
    }
    l.to_string()
}

/// Links on a playlist or profile page: songs ("/artist/song/") and playlists.
pub fn playlist_links(html: &str, page_url: &str) -> String {
    let low = lower(html);
    let org = origin(page_url).to_string();
    let page_path = url_path(page_url).trim_end_matches('/').to_string();
    // The page's first heading that is not the site's own logo: the owner's
    // name, on a profile page and on a list page alike.
    let heading = elements(html, &low, "h1").iter().map(|(_, b)| clean(b)).find(|h| !h.is_empty() && !h.eq_ignore_ascii_case("cifra club"));
    // A list page names the list apart from its owner.
    let list_name = elements(html, &low, "span")
        .iter()
        .find(|(tag, _)| attr(tag, "class").is_some_and(|c| c.contains("songbook-title")))
        .map(|(_, b)| clean(b))
        .filter(|n| !n.is_empty());
    let owner = heading.clone().unwrap_or_default();
    let title = list_name
        .or(heading)
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
        // Lists: ".../playlists/456/" and, on newer pages, "/@123/repertorio/456/".
        if pl.contains("playlist") || pl.contains("/repertorio/") {
            let p = path.trim_end_matches('/').to_string();
            // The page itself, whichever way it is addressed ("/musico/123/..." or "/@123/...").
            let own = p == page_path || (p.contains("/repertorio/") && page_path.contains("/repertorio/") && p.rsplit('/').next() == page_path.rsplit('/').next());
            let label = list_label(&label, &owner);
            if !own && !label.is_empty() && label.chars().count() < 80 && !lists.iter().any(|(u, _)| *u == p) {
                lists.push((p, label));
            }
            continue;
        }
        // "/artist/song/", or one version of it: "/artist/song/simplificada.html".
        let segs = if segs.len() == 3 && segs[2].ends_with(".html") { &segs[..2] } else { &segs[..] };
        if segs.len() != 2 || NOT_ARTIST.contains(&segs[0]) || segs[1].contains('.') || label.is_empty() {
            continue;
        }
        // A song card holds more than the title (position, artist, key, a
        // menu button): take the title line when the card has one.
        let title = card_name(body);
        let label = if title.is_empty() { label } else { title };
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
        // Lists name each song "Title - Artist".
        let (name, artist) = label.rsplit_once(" - ").unwrap_or((label, ""));
        j.begin_object();
        j.key("id").string(id);
        j.key("label").string(name.trim());
        j.key("artist").string(artist.trim());
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

/// What the site calls the version a page such as ".../song/sjgsmzp.html"
/// holds ("Versão 7", "Simplificada"); empty for the song's own address.
/// The page's data names each version next to its address:
/// "label":{"name":"Versão 7","slug":"sjgsmzp"}, quotes escaped or not.
fn version_name(html: &str, page_url: &str) -> String {
    let path = url_path(page_url);
    let Some(slug) = path.rsplit('/').next().and_then(|s| s.strip_suffix(".html")).filter(|s| !s.is_empty()) else {
        return String::new();
    };
    for quote in ["\\\"", "\""] {
        let needle = format!("{quote}slug{quote}:{quote}{slug}{quote}");
        let Some(at) = html.find(&needle) else { continue };
        let Some(name) = html[..at].rfind("name").filter(|n| at - n < 120) else { continue };
        let value: String = html[name + 4..at]
            .trim_start_matches(|c| c == '\\' || c == '"' || c == ':')
            .chars()
            .take_while(|c| *c != '\\' && *c != '"')
            .collect();
        return decode_entities(value.trim()).to_string();
    }
    String::new()
}

/// The name written on a song or album card: the card's own title line, else
/// what its cover picture is described as ('Capa da música "X", de Y').
fn card_name(body: &str) -> String {
    let low = lower(body);
    let paragraphs = elements(body, &low, "p");
    let titled = paragraphs.iter().find(|(tag, _)| attr(tag, "class").is_some_and(|c| c.contains("primaryLabel")));
    // Song rows start with their position ("01"); the title is the first line that is not a number.
    let line = titled.or_else(|| paragraphs.iter().find(|(_, b)| !clean(b).chars().all(|c| c.is_ascii_digit())));
    if let Some((_, b)) = line {
        let name = clean(b);
        if !name.is_empty() {
            return name;
        }
    }
    if let Some(i) = low.find("<img") {
        let end = low[i..].find('>').map_or(body.len(), |e| i + e);
        let alt = attr(&body[i..end], "alt").map(decode_entities).unwrap_or_default();
        if let (Some(a), Some(b)) = (alt.find('"'), alt.rfind('"')) {
            if b > a + 1 {
                return alt[a + 1..b].trim().to_string();
            }
        }
    }
    String::new()
}

/// An artist's page, the discography page or an album page of Cifra Club:
/// the songs in the order the page lists them (the artist's most played, or
/// the album's tracks) and the albums it links to, as
/// {name, songs:[{id,label}], albums:[{slug,name,year,kind}]}.
pub fn artist_page(html: &str, page_url: &str) -> String {
    let low = lower(html);
    let artist = url_path(page_url).split('/').find(|s| !s.is_empty()).unwrap_or("").to_string();
    let name = elements(html, &low, "h1").iter().map(|(_, b)| clean(b)).find(|h| !h.is_empty() && !h.eq_ignore_ascii_case("cifra club"));
    let mut songs: Vec<(String, String)> = Vec::new();
    let mut albums: Vec<(String, String, String, &str)> = Vec::new();
    for (tag, body) in elements(html, &low, "a") {
        let Some(href) = attr(tag, "href") else { continue };
        let href = decode_entities(href);
        if href.contains('?') {
            continue; // "tabs-baixo/?autoplay", "?instrument=bass": other views of the page
        }
        let path = url_path(&href).to_string();
        let segs: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
        if !segs.iter().all(|s| s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')) {
            continue;
        }
        if segs.len() == 3 && segs[0] == artist && segs[1] == "discografia" {
            if albums.iter().any(|a| a.0 == segs[2]) {
                continue;
            }
            let title = card_name(body);
            // The slug ends with the year: "by-the-way-2002".
            let year = segs[2].rsplit('-').next().filter(|y| y.len() == 4 && y.chars().all(|c| c.is_ascii_digit())).unwrap_or("");
            let kind = if clean(body).to_lowercase().contains("single") { "single" } else { "album" };
            if !title.is_empty() {
                albums.push((segs[2].to_string(), title, year.to_string(), kind));
            }
            continue;
        }
        if segs.len() != 2 || NOT_ARTIST.contains(&segs[0]) {
            continue;
        }
        let id = format!("{}/{}", segs[0], segs[1]);
        let title = card_name(body);
        if title.is_empty() || songs.iter().any(|s| s.0 == id) {
            continue;
        }
        songs.push((id, title));
    }
    let mut j = Json::object();
    j.key("name").string(&name.unwrap_or_default());
    j.key("songs").begin_array();
    for (id, label) in &songs {
        j.begin_object();
        j.key("id").string(id);
        j.key("label").string(label);
        j.end_object();
    }
    j.end_array();
    j.key("albums").begin_array();
    for (slug, title, year, kind) in &albums {
        j.begin_object();
        j.key("slug").string(slug);
        j.key("name").string(title);
        j.key("year").string(year);
        j.key("kind").string(kind);
        j.end_object();
    }
    j.end_array();
    j.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    const PAGE: &str = r#"<html><head><title>Tempo Perdido - Legião Urbana - Cifra Club</title>
<link rel="stylesheet" href="/a.css"><link rel="canonical" href="https://www.site.com/legiao-urbana/tempo-perdido/"/></head>
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
        assert!(j.contains(r#""canonical":"https://www.site.com/legiao-urbana/tempo-perdido/""#), "{j}");
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
        assert!(j.contains(r#"{"id":"legiao-urbana/tempo-perdido","label":"Tempo Perdido Legião Urbana","artist":""}"#), "{j}");
        assert!(j.contains(r#""id":"djavan/oceano""#), "{j}");
        assert!(!j.contains("estilos") && !j.contains("evil"), "{j}");
        assert!(j.contains(r#""url":"https://www.site.com/musico/123/playlists/456/""#), "{j}");
        assert!(j.contains(r#""title":"Ensaio""#), "{j}");
    }

    // A list page as the site draws it now, and the page it sends before
    // drawing anything: only the site's own menu, with its A-Z index.
    #[test]
    fn repertoire_page() {
        let html = r#"<html><head><title>Perfil de Fulano - / - Cifra Club</title></head><body>
<h1><a href="/">Cifra Club</a></h1><h1> Fulano de Tal </h1>
<h3><span class="songbook-title" title="Favoritas 2">Favoritas 2</span></h3>
<a href="/letra/A/">A</a><a href="/letra/B/">B</a><a href="/explorar/musicas/">Em alta</a>
<a href="/eminem/stan/?songbookId=1&amp;key=C&amp;capo=4">Stan - Eminem</a>
<a href="/the-neighbourhood/sweater-weather/simplificada.html?songbookId=1">Sweater Weather - The Neighbourhood</a>
<a href="/lana-del-rey/brooklyn-baby/?songbookId=1"><p>03</p><div><p class="a primaryLabel"><span>Brooklyn Baby</span></p></div><span>Lana Del Rey</span><span>•</span><span>Db</span></a><button>Opções</button>
<a href="/@123/repertorio/456/">Capotraste Fulano de Tal • 45 músicas</a>
<a href="/@123/repertorio/789/">Favoritas Favoritas Fulano de Tal • 539 músicas</a>
<a href="/@123/repertorio/790/"><b>Quero aprender</b><b>Quero aprender</b> Fulano de Tal • 2 músicas</a>
<a href="/aviso-legal.html">Termos</a>
</body></html>"#;
        let j = playlist_links(html, "https://www.site.com/musico/123/repertorio/1/");
        assert!(j.contains(r#""title":"Favoritas 2""#), "{j}");
        assert!(j.contains(r#"{"id":"eminem/stan","label":"Stan","artist":"Eminem"}"#), "{j}");
        assert!(j.contains(r#"{"id":"the-neighbourhood/sweater-weather","label":"Sweater Weather","artist":"The Neighbourhood"}"#), "{j}");
        assert!(j.contains(r#"{"id":"lana-del-rey/brooklyn-baby","label":"Brooklyn Baby","artist":""}"#), "{j}");
        assert!(!j.contains("letra") && !j.contains("explorar") && !j.contains("aviso"), "{j}");
        assert!(j.contains(r#"{"url":"https://www.site.com/@123/repertorio/456/","label":"Capotraste"}"#), "{j}");
        assert!(j.contains(r#"{"url":"https://www.site.com/@123/repertorio/789/","label":"Favoritas"}"#), "{j}");
        assert!(j.contains(r#"{"url":"https://www.site.com/@123/repertorio/790/","label":"Quero aprender"}"#), "{j}");
        // The page is not one of its own lists, whichever way it is addressed.
        let j = playlist_links(html, "https://www.site.com/musico/123/repertorio/456/");
        assert!(!j.contains("repertorio/456"), "{j}");
        let empty = r#"<html><body><a href="/letra/A/">A</a><a href="/letra/1/">#</a><a href="/mais/">Mais</a></body></html>"#;
        assert!(playlist_links(empty, "https://www.site.com/musico/123/repertorio/456/").contains(r#""songs":[]"#));
    }

    #[test]
    fn ampersand_before_a_wide_character() {
        // The twelfth byte after the & falls inside the zero-width space.
        assert_eq!(decode_entities("a & bcdefghi\u{200b}j &amp; k"), "a & bcdefghi\u{200b}j & k");
        assert_eq!(decode_entities("&áéíóúâêô"), "&áéíóúâêô");
    }

    #[test]
    fn page_of_one_version_says_which() {
        let html = r#"<script>x({\"label\":{\"name\":\"Principal\",\"slug\":\"principal\"}},{\"label\":{\"name\":\"Versão 7\",\"slug\":\"sjgsmzp\"},\"metadata\":1})</script>"#;
        assert_eq!(version_name(html, "https://www.site.com/banda/musica/sjgsmzp.html"), "Versão 7");
        assert_eq!(version_name(html, "https://www.site.com/banda/musica/"), "");
        assert_eq!(version_name(html, "https://www.site.com/banda/musica/outra.html"), "");
        let plain = r#"{"label":{"name":"Simplificada","slug":"simplificada"}}"#;
        assert_eq!(version_name(plain, "https://www.site.com/banda/musica/simplificada.html?x=1"), "Simplificada");
    }

    #[test]
    fn artist_page_songs_and_albums() {
        let html = r#"<html><body><h1><a href="/">Cifra Club</a></h1><h1>Banda Tal</h1>
<a href="/banda-tal/">Cifra</a><a href="/banda-tal/?instrument=bass">Tabs de baixo</a>
<ol><li><a href="/banda-tal/primeira/"><img alt="Capa da música &quot;Primeira&quot;, de Banda Tal"><p>01</p>
<div><p class="x primaryLabel"><span>Primeira</span></p></div><span>19.264</span><span>Am</span></a><button>Opções</button></li>
<li><a href="/banda-tal/segunda-vez/"><p>02</p><p><span>Segunda Vez</span></p><span>9.788</span></a></li>
<li><a href="/banda-tal/primeira/"><p>01</p><p class="primaryLabel">Primeira</p></a></li></ol>
<a href="/banda-tal/discografia.html">Ver mais</a>
<a href="/banda-tal/discografia/disco-novo-2022/"><img alt="Capa do álbum &quot;Disco Novo&quot;, de Banda Tal"><p>Disco Novo</p><span><span>2022</span><span>•</span><span>Álbum</span></span></a>
<a href="/banda-tal/discografia/avulsa-2016/"><img alt="Capa do álbum &quot;Avulsa&quot;, de Banda Tal"><span><span>2016</span><span>•</span><span>Single/EP</span></span></a>
<a href="/banda-tal/segunda-vez/tabs-baixo/?autoplay=true">Como tocar no baixo</a>
<a href="/letra/A/">A</a><a href="/outra-banda/">Outra Banda</a>
</body></html>"#;
        let j = artist_page(html, "https://www.site.com/banda-tal/");
        assert!(j.contains(r#""name":"Banda Tal""#), "{j}");
        assert!(j.contains(r#""songs":[{"id":"banda-tal/primeira","label":"Primeira"},{"id":"banda-tal/segunda-vez","label":"Segunda Vez"}]"#), "{j}");
        assert!(
            j.contains(r#""albums":[{"slug":"disco-novo-2022","name":"Disco Novo","year":"2022","kind":"album"},{"slug":"avulsa-2016","name":"Avulsa","year":"2016","kind":"single"}]"#),
            "{j}"
        );
    }

    #[test]
    fn ultimate_guitar_store() {
        // JSON escapes as they appear in the page: \r\n and é.
        let content = "[Intro]\\r\\n[ch]Em7[/ch]   [ch]G[/ch]\\r\\n\\r\\n[Verse 1]\\r\\n[ch]Em7[/ch]          [ch]G[/ch]\\r\\nToday is gonna be the day \\u00e9\\r\\n[tab]e|--3--|\\r\\nB|--3--|[/tab]";
        let json = format!(
            r#"{{"store":{{"page":{{"data":{{"tab":{{"song_name":"Wonderwall","artist_name":"Oasis","tonality_name":"F#m"}},"tab_view":{{"meta":{{"capo":2,"tuning":{{"name":"Standard","value":"E A D G B E"}}}},"wiki_tab":{{"content":"{content}"}}}}}}}}}}}}"#
        );
        let page = format!(r#"<html><body><div class="js-store" data-content="{}"></div></body></html>"#, json.replace('"', "&quot;"));
        let j = extract(&page, "https://tabs.ultimate-guitar.com/tab/oasis/wonderwall-chords-27596");
        assert!(j.contains(r#""title":"Wonderwall""#), "{j}");
        assert!(j.contains(r#""artist":"Oasis""#), "{j}");
        assert!(j.contains(r#""key":"F#m""#), "{j}");
        assert!(j.contains(r#""capo":2"#), "{j}");
        assert!(j.contains(r#""tuning":"""#), "{j}");
        assert!(j.contains("Em7          G\\nToday is gonna be the day é"), "{j}");
        assert!(j.contains("e|--3--|") && !j.contains("[ch]") && !j.contains("[tab]"), "{j}");
    }

    #[test]
    fn entities() {
        assert_eq!(decode_entities("a &amp; b &#233; &#xE9; &bogus; &"), "a & b é é &bogus; &");
    }
}
