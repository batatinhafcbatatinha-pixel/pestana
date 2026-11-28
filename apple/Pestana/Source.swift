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

    /// `limit` is how many results (artists, songs and albums together) the endpoint sends; it sends 10 by default.
    static func search(_ query: String, limit: Int = 10) async throws -> SearchResults {
        let q = query.trimmingCharacters(in: .whitespaces).addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let (code, body) = try await get(searchURL + q + "&limit=\(limit)")
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

    /// The most played songs of an artist, in the site's order, and every album.
    struct ArtistPage {
        var top: [SongRef]
        var albums: [AlbumRef]
    }

    private static var artistPages: [String: ArtistPage] = [:]

    static func cachedArtistPage(_ slug: String) -> ArtistPage? { artistPages[slug] }

    /// An artist, discography or album page of the site, as the core reads it.
    private static func sitePage(_ url: String) async -> [String: Any]? {
        var html: String?
        if let r = try? await get(url) {
            if r.0 == 404 { return nil }
            if r.0 == 200 { html = r.1 }
        }
        if html == nil { html = await WebFetcher.fetch(url, wait: .links) }
        guard let page = html else { return nil }
        let json = await Task.detached { Core.artistPage(page, url: url) }.value
        return (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any]
    }

    private static func rows(_ o: [String: Any], _ key: String) -> [[String: String]] {
        o[key] as? [[String: String]] ?? []
    }

    /// Reads the artist's page (its most played songs) and the discography page
    /// (every album and single). Nil when neither could be read.
    static func artistPage(_ a: ArtistRef, top: Int = 15) async -> ArtistPage? {
        if let c = artistPages[a.slug] { return c }
        let base = "\(siteURL)/\(a.slug)/"
        guard let main = await sitePage(base) else { return nil }
        let name = a.name.isEmpty ? (main["name"] as? String ?? "") : a.name
        func albums(_ o: [String: Any]) -> [AlbumRef] {
            rows(o, "albums").compactMap { d -> AlbumRef? in
                guard let slug = d["slug"], let title = d["name"] else { return nil }
                return AlbumRef(slug: slug, name: title, artist: name, artistSlug: a.slug, year: d["year"] ?? "", kind: d["kind"] ?? "")
            }
        }
        var songs: [SongRef] = []
        for d in rows(main, "songs") {
            guard let id = d["id"], id.hasPrefix(a.slug + "/"), songs.count < top else { continue }
            songs.append(SongRef(id: id, title: d["label"] ?? id, artist: name))
        }
        // The artist's page shows only the latest albums; the discography has them all.
        var all: [AlbumRef] = []
        if let disc = await sitePage(base + "discografia.html") { all = albums(disc) }
        if all.isEmpty { all = albums(main) }
        if songs.isEmpty && all.isEmpty { return nil }
        let page = ArtistPage(top: songs, albums: all)
        artistPages[a.slug] = page
        return page
    }

    /// The tracks of an album, in order, from its page on the site.
    private static func albumTracks(_ a: AlbumRef) async -> [SongRef] {
        if a.slug.isEmpty || a.artistSlug.isEmpty { return [] }
        guard let page = await sitePage("\(siteURL)/\(a.artistSlug)/discografia/\(a.slug)/") else { return [] }
        return rows(page, "songs").compactMap { d -> SongRef? in
            guard let id = d["id"] else { return nil }
            let artist = id.hasPrefix(a.artistSlug + "/") ? a.artist : prettySlug(String(id.split(separator: "/").first ?? ""))
            return SongRef(id: id, title: d["label"] ?? id, artist: artist, album: a.name, albumSlug: a.slug)
        }
    }

    /// The album a song came out on, as the search knows it. Blank fields when
    /// the search answered and knew none; nil when it could not be asked.
    static func songAlbum(_ s: SongRef) async -> Store.SongInfo? {
        let q = "\(s.artist) \(s.title)".trimmingCharacters(in: .whitespaces).addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        guard let r = try? await get(searchURL + q), r.0 == 200 else { return nil }
        let body = r.1
        guard let a = body.firstIndex(of: "{"), let b = body.lastIndex(of: "}"), a < b else { return nil }
        let json = String(body[a...b])
        guard let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let docs = (o["response"] as? [String: Any])?["docs"] as? [[String: Any]] else { return nil }
        for d in docs {
            func str(_ k: String) -> String {
                if let v = d[k] as? String { return v }
                if let v = d[k] as? Int { return String(v) }
                return ""
            }
            if str("tipo") != "2" || "\(str("dns"))/\(str("url"))" != s.id { continue }
            let slug = str("url_album")
            // Album addresses end with the year: "by-the-way-2002".
            let tail = slug.split(separator: "-").last.map(String.init) ?? ""
            let year = tail.count == 4 ? (Int(tail) ?? 0) : 0
            return Store.SongInfo(album: str("album_name"), albumSlug: slug, year: year)
        }
        return Store.SongInfo(album: "", albumSlug: "", year: 0)
    }

    static func albumSongs(_ a: AlbumRef, onUpdate: ([SongRef]) -> Void) async {
        if let c = albumCache[a.key] { onUpdate(c); return }
        // The album's own page has every track, in order; the search is the fallback.
        let tracks = await albumTracks(a)
        if !tracks.isEmpty {
            albumCache[a.key] = tracks
            onUpdate(tracks)
            return
        }
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

    /// Version pages the site answered with another page: not asked for again.
    private static var missingVersions = Set<String>()

    private static func samePage(_ a: String, _ b: String) -> Bool {
        func path(_ u: String) -> String {
            var p = URLComponents(string: u)?.path.lowercased() ?? u.lowercased()
            while p.hasSuffix("/") { p.removeLast() }
            return p
        }
        return path(a) == path(b)
    }

    /// `version`: the page is one version of a song (simplified, acoustic, bass).
    /// Cifra Club answers a version the song does not have with the main sheet;
    /// that is a failure here, not the main sheet under another name.
    static func fetchSheet(_ url: String, songId: String, title: String, artist: String, version: Bool = false) async throws -> Sheet {
        if version && missingVersions.contains(url) { throw SourceError.message("A música não tem essa versão.") }
        var html: String?
        if let r = try? await get(url), r.0 == 200, r.1.range(of: "<pre", options: .caseInsensitive) != nil || r.1.contains("wiki_tab") {
            html = r.1
        }
        if html == nil { html = await WebFetcher.fetch(url, wait: .chords) }
        guard let page = html else { throw SourceError.message("Não consegui abrir a cifra. Verifique a conexão.") }
        let json = await Task.detached { Core.extractPage(page, url: url) }.value
        if version {
            let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
            let own = o["canonical"] as? String ?? ""
            if !own.isEmpty && !samePage(own, url) {
                missingVersions.insert(url)
                throw SourceError.message("A música não tem essa versão.")
            }
        }
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
                let artist = s["artist"] ?? ""
                return SongRef(id: id, title: s["label"] ?? id, artist: artist.isEmpty ? prettySlug(String(id.split(separator: "/").first ?? "")) : artist)
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
        return l.contains("cifraclub") && (l.contains("playlist") || l.contains("/musico/") || l.contains("/repertorio/") || l.contains("/@"))
    }

    /// Cifra Club keeps the bass version at <song>/tabs-baixo/.
    static func bassURL(_ songURL: String) -> String {
        (songURL.hasSuffix("/") ? String(songURL.dropLast()) : songURL) + "/tabs-baixo/"
    }

    /// The acoustic version, which the song page does not link to.
    static func acousticURL(_ songURL: String) -> String {
        (songURL.hasSuffix("/") ? String(songURL.dropLast()) : songURL) + "/acustica.html"
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
        var steady = 0
        while Date() < deadline {
            try? await Task.sleep(nanoseconds: 700_000_000)
            switch wait {
            case .chords:
                if let html = await eval(web, "(function(){var h=document.documentElement.outerHTML;return (document.querySelector('pre')||h.indexOf('wiki_tab')>=0)?h:null})()") as? String {
                    return html
                }
            case .links:
                // Lists render late, after the page says it is complete: wait
                // until the number of links has stopped changing for a while.
                if let n = await eval(web, "document.readyState == 'complete' ? document.querySelectorAll('a[href]').length : -1") as? Int {
                    steady = n > 0 && n == lastCount ? steady + 1 : 0
                    if steady >= 3 {
                        return await eval(web, "document.documentElement.outerHTML") as? String
                    }
                    lastCount = n
                }
            }
        }
        return nil
    }
}
