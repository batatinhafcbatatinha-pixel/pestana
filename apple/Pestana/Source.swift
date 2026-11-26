import Foundation
import WebKit

enum SourceError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let m): return m }
    }
}

/// Where chord sheets come from (same logic as Android): Cifra Club's public
/// search endpoint, and song pages read like a browser would — plain HTTP first,
/// WebKit as a fallback. Sheets are cached in the local database.
@MainActor
enum Source {
    private static let searchURL = "https://solr.sscdn.co/cc/c7/?q="

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 15
        c.timeoutIntervalForResource = 20
        c.httpAdditionalHeaders = ["Accept-Language": "pt-BR,pt;q=0.9,en;q=0.6"]
        return URLSession(configuration: c)
    }()

    private static func get(_ url: String) async throws -> (Int, String) {
        guard let u = URL(string: url) else { throw SourceError.message("Link inválido") }
        let (data, resp) = try await session.data(from: u)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        return (code, String(decoding: data, as: UTF8.self))
    }

    static func search(_ query: String) async throws -> SearchResults {
        let q = query.trimmingCharacters(in: .whitespaces).addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let (code, body) = try await get(searchURL + q)
        guard code == 200 else { throw SourceError.message("Busca indisponível (\(code))") }
        return parseSearch(body)
    }

    static func parseSearch(_ body: String) -> SearchResults {
        guard let a = body.firstIndex(of: "{"), let b = body.lastIndex(of: "}") else { return SearchResults() }
        let json = String(body[a...b])
        guard let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let docs = (o["response"] as? [String: Any])?["docs"] as? [[String: Any]] else { return SearchResults() }
        var r = SearchResults()
        var pics: [String: String] = [:]
        for d in docs {
            func s(_ k: String) -> String {
                if let v = d[k] as? String { return v }
                if let v = d[k] as? Int { return String(v) }
                return ""
            }
            let dns = s("dns")
            if dns.isEmpty { continue }
            let img = s("imgm")
            switch s("tipo") {
            case "1":
                r.artists.append(ArtistRef(slug: dns, name: s("art").isEmpty ? s("txt") : s("art"), img: img))
                if !img.isEmpty { pics[dns] = img }
            case "2":
                let url = s("url")
                if !url.isEmpty {
                    r.songs.append(SongRef(id: "\(dns)/\(url)", title: s("txt"), artist: s("art"), album: s("album_name"), albumSlug: s("url_album")))
                }
                if !img.isEmpty && pics[dns] == nil { pics[dns] = img }
            case "6":
                let a = AlbumRef(slug: s("url_album"), name: s("txt").trimmingCharacters(in: .whitespaces), artist: s("art"), artistSlug: dns, year: s("ano"), img: img)
                r.albums.append(a)
                if !img.isEmpty { pics[a.key] = img }
            default: break
            }
        }
        Images.shared.remember(pics)
        return r
    }

    // Lists already loaded stay in memory, so going back is instant (as in Spotifast).
    private static var artistCache: [String: ([SongRef], [AlbumRef])] = [:]
    private static var albumCache: [String: [SongRef]] = [:]

    static func cachedArtist(_ slug: String) -> ([SongRef], [AlbumRef])? { artistCache[slug] }
    static func cachedAlbum(_ key: String) -> [SongRef]? { albumCache[key] }

    /// Merges several queries and reports each partial result as it arrives.
    static func artistSongs(_ a: ArtistRef, onUpdate: ([SongRef], [AlbumRef]) -> Void) async {
        if let c = artistCache[a.slug] { onUpdate(c.0, c.1); return }
        var songs: [SongRef] = []
        var albums: [AlbumRef] = []
        for q in [a.name, "\(a.name) cifra", "\(a.name) ao vivo", "\(a.name) acústico"] {
            guard let r = try? await search(q) else { continue }
            for s in r.songs where s.artistSlug == a.slug && !songs.contains(where: { $0.id == s.id }) { songs.append(s) }
            for al in r.albums where al.artistSlug == a.slug && !albums.contains(where: { $0.slug == al.slug }) { albums.append(al) }
            onUpdate(songs, albums.sorted { $0.year > $1.year })
        }
        if !songs.isEmpty { artistCache[a.slug] = (songs, albums.sorted { $0.year > $1.year }) }
        onUpdate(songs, albums.sorted { $0.year > $1.year })
    }

    static func albumSongs(_ a: AlbumRef, onUpdate: ([SongRef]) -> Void) async {
        if let c = albumCache[a.key] { onUpdate(c); return }
        var songs: [SongRef] = []
        for q in ["\(a.artist) \(a.name)", a.name] {
            guard let r = try? await search(q) else { continue }
            for s in r.songs where s.artistSlug == a.artistSlug && s.albumSlug == a.slug && !songs.contains(where: { $0.id == s.id }) {
                songs.append(s)
            }
            onUpdate(songs)
        }
        if !songs.isEmpty { albumCache[a.key] = songs }
        onUpdate(songs)
    }

    static func fetchSheet(_ url: String, songId: String, title: String, artist: String) async throws -> Sheet {
        var html: String?
        if let r = try? await get(url), r.0 == 200, r.1.range(of: "<pre", options: .caseInsensitive) != nil || r.1.contains("wiki_tab") {
            html = r.1
        }
        if html == nil { html = await WebFetcher.fetch(url, wait: .chords) }
        guard let page = html else { throw SourceError.message("Não consegui abrir a cifra. Verifique a conexão.") }
        let json = await Task.detached { Core.extractPage(page, url: url) }.value
        let sheet = Sheet.fromExtract(json, url: url, songId: songId, title: title, artist: artist)
        if sheet.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw SourceError.message("A página não tem cifra para violão/guitarra.")
        }
        return sheet
    }

    struct PlaylistPage {
        var title: String
        var songs: [SongRef]
        var playlists: [Version]
    }

    /// A Cifra Club playlist page (its songs) or a profile page (its playlists).
    static func readPlaylistPage(_ url: String) async throws -> PlaylistPage {
        func parse(_ html: String) -> PlaylistPage {
            let o = (try? JSONSerialization.jsonObject(with: Data(Core.playlistLinks(html, url: url).utf8))) as? [String: Any] ?? [:]
            let songs = (o["songs"] as? [[String: String]] ?? []).compactMap { s -> SongRef? in
                guard let id = s["id"] else { return nil }
                return SongRef(id: id, title: s["label"] ?? id, artist: prettySlug(String(id.split(separator: "/").first ?? "")))
            }
            let lists = (o["playlists"] as? [[String: String]] ?? []).compactMap { p -> Version? in
                guard let l = p["label"], let u = p["url"] else { return nil }
                return Version(label: l, url: u)
            }
            return PlaylistPage(title: o["title"] as? String ?? "", songs: songs, playlists: lists)
        }
        if let r = try? await get(url), r.0 == 200 {
            let p = parse(r.1)
            if !p.songs.isEmpty || !p.playlists.isEmpty { return p }
        }
        guard let html = await WebFetcher.fetch(url, wait: .links) else {
            throw SourceError.message("Não consegui abrir o link. Verifique a conexão.")
        }
        let p = parse(html)
        if p.songs.isEmpty && p.playlists.isEmpty {
            throw SourceError.message("Não achei músicas nem playlists nessa página. A playlist é pública?")
        }
        return p
    }

    static func prettySlug(_ slug: String) -> String {
        slug.split(separator: "-").map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
    }

    static func isPlaylistLink(_ link: String) -> Bool {
        let l = link.lowercased()
        return l.contains("cifraclub") && (l.contains("playlist") || l.contains("/musico/"))
    }

    static func simplifiedURL(_ songURL: String) -> String {
        (songURL.hasSuffix("/") ? String(songURL.dropLast()) : songURL) + "/simplificada.html"
    }
}

/// Loads a page in an off-screen WKWebView (Safari's engine, like opening it in
/// the browser) and returns the HTML once the chords — or the list — are there.
@MainActor
enum WebFetcher {
    enum Wait { case chords, links }

    private static var rules: WKContentRuleList?

    /// Blocks third-party requests (ads, trackers) except the site's CDN and challenge pages.
    private static func ruleList() async -> WKContentRuleList? {
        if let rules { return rules }
        let json = """
        [{"trigger":{"url-filter":".*","load-type":["third-party"]},"action":{"type":"block"}},
         {"trigger":{"url-filter":"(sscdn|cloudflare|akamai|gstatic|googleapis|cifraclub|ultimate-guitar|ugstatic)"},"action":{"type":"ignore-previous-rules"}}]
        """
        rules = try? await WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "pestana-block", encodedContentRuleList: json)
        return rules
    }

    private static func eval(_ web: WKWebView, _ js: String) async -> Any? {
        await withCheckedContinuation { c in
            web.evaluateJavaScript(js) { r, _ in c.resume(returning: r) }
        }
    }

    static func fetch(_ url: String, wait: Wait, timeout: TimeInterval = 25) async -> String? {
        guard let u = URL(string: url) else { return nil }
        let cfg = WKWebViewConfiguration()
        if let r = await ruleList() { cfg.userContentController.add(r) }
        let web = WKWebView(frame: CGRect(x: 0, y: 0, width: 400, height: 800), configuration: cfg)
        defer { web.stopLoading() }
        web.load(URLRequest(url: u))
        let deadline = Date().addingTimeInterval(timeout)
        var lastCount = -1
        while Date() < deadline {
            try? await Task.sleep(nanoseconds: 700_000_000)
            switch wait {
            case .chords:
                if let html = await eval(web, "(function(){var h=document.documentElement.outerHTML;return (document.querySelector('pre')||h.indexOf('wiki_tab')>=0)?h:null})()") as? String {
                    return html
                }
            case .links:
                // Lists render late: wait until the number of links stops changing.
                if let n = await eval(web, "document.readyState == 'complete' ? document.querySelectorAll('a[href]').length : -1") as? Int {
                    if n > 0 && n == lastCount {
                        return await eval(web, "document.documentElement.outerHTML") as? String
                    }
                    lastCount = n
                }
            }
        }
        return nil
    }
}
