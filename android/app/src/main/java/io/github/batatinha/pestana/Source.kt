package io.github.batatinha.pestana

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume

/**
 * Where chord sheets come from. The phone talks to the sites directly (no
 * server in between): search uses Cifra Club's public search endpoint, and a
 * song page is read like a browser would, with plain HTTP first and the
 * system WebView as a fallback. Pages are cached in the local database, so a
 * song opened once works offline.
 */
object Source {
    const val SITE = "https://www.cifraclub.com.br"
    private const val SEARCH = "https://solr.sscdn.co/cc/c7/?q="

    private var ua: String? = null
    private fun userAgent(ctx: Context): String =
        ua ?: runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrDefault("Mozilla/5.0 (Linux; Android)").also { ua = it }

    private fun get(ctx: Context, url: String, timeout: Int = 12000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", userAgent(ctx))
            c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.6")
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: return code to ""
            val input = if (c.contentEncoding == "gzip") GZIPInputStream(stream) else stream
            return code to input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun search(ctx: Context, query: String): SearchResults = withContext(Dispatchers.IO) {
        // connectTimeout does not cover DNS; bound the whole request.
        val (code, body) = kotlinx.coroutines.withTimeout(15_000) {
            kotlinx.coroutines.runInterruptible { get(ctx, SEARCH + URLEncoder.encode(query.trim(), "UTF-8")) }
        }
        if (code != 200) error("Busca indisponível ($code)")
        parseSearch(body)
    }

    fun parseSearch(body: String): SearchResults {
        // The endpoint may answer JSON or JSONP; keep what is between the outer braces.
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return SearchResults()
        val docs = JSONObject(body.substring(start, end + 1)).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        val artists = ArrayList<ArtistRef>()
        val songs = ArrayList<SongRef>()
        val albums = ArrayList<AlbumRef>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val dns = d.optString("dns")
            if (dns.isEmpty()) continue
            when (d.optString("tipo")) {
                "1" -> artists += ArtistRef(dns, d.optString("art").ifBlank { d.optString("txt") }, d.optString("imgm"))
                "2" -> {
                    val url = d.optString("url")
                    if (url.isNotEmpty()) songs += SongRef(
                        id = "$dns/$url", title = d.optString("txt"), artist = d.optString("art"),
                        artistSlug = dns, album = d.optString("album_name"), albumSlug = d.optString("url_album"),
                    )
                }
                "6" -> albums += AlbumRef(
                    d.optString("url_album"), d.optString("txt").trim(), d.optString("art"), dns, d.optString("ano"), d.optString("imgm"),
                )
            }
        }
        // Song results carry the artist's photo: remember it for every list.
        val pics = HashMap<String, String>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val img = d.optString("imgm")
            if (img.isEmpty()) continue
            when (d.optString("tipo")) {
                "1", "2" -> pics.putIfAbsent(d.optString("dns"), img)
                "6" -> pics["${d.optString("dns")}/${d.optString("url_album")}"] = img
            }
        }
        Images.remember(pics)
        return SearchResults(artists, songs, albums)
    }

    // Lists already loaded stay in memory, so going back to an artist or album is instant.
    private val artistCache = HashMap<String, Pair<List<SongRef>, List<AlbumRef>>>()
    private val albumCache = HashMap<String, List<SongRef>>()

    fun cachedArtist(slug: String) = artistCache[slug]
    fun cachedAlbum(key: String) = albumCache[key]

    /**
     * Songs of an artist the search knows about. Several queries are merged and
     * each partial result is handed to `onUpdate` as it arrives.
     */
    suspend fun artistSongs(ctx: Context, a: ArtistRef, onUpdate: (List<SongRef>, List<AlbumRef>) -> Unit) {
        artistCache[a.slug]?.let { onUpdate(it.first, it.second); return }
        val songs = LinkedHashMap<String, SongRef>()
        val albums = LinkedHashMap<String, AlbumRef>()
        for (q in listOf(a.name, "${a.name} cifra", "${a.name} ao vivo", "${a.name} acústico")) {
            val r = runCatching { search(ctx, q) }.getOrNull() ?: continue
            r.songs.filter { it.artistSlug == a.slug }.forEach { songs.putIfAbsent(it.id, it) }
            r.albums.filter { it.artistSlug == a.slug }.forEach { albums.putIfAbsent(it.slug, it) }
            onUpdate(songs.values.toList(), albums.values.sortedByDescending { it.year })
        }
        val result = songs.values.toList() to albums.values.sortedByDescending { it.year }
        if (result.first.isNotEmpty()) artistCache[a.slug] = result
        onUpdate(result.first, result.second)
    }

    /** The most played songs of an artist, in the site's order, and every album. */
    class ArtistPage(val top: List<SongRef>, val albums: List<AlbumRef>)

    private val artistPages = HashMap<String, ArtistPage>()

    fun cachedArtistPage(slug: String) = artistPages[slug]

    /** A page of the site as HTML: plain HTTP, or the WebView when the site refuses that. */
    private suspend fun pageHtml(ctx: Context, url: String): String? {
        val http = withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000) { runCatching { kotlinx.coroutines.runInterruptible { get(ctx, url) } }.getOrNull() }
        }
        if (http != null && http.first == 200) return http.second
        if (http != null && http.first == 404) return null
        return WebFetcher.fetch(ctx, url, WebFetcher.Wait.LINKS)
    }

    private suspend fun sitePage(ctx: Context, url: String): JSONObject? {
        val html = pageHtml(ctx, url) ?: return null
        return withContext(Dispatchers.Default) { runCatching { JSONObject(Native.artistPage(html, url)) }.getOrNull() }
    }

    private fun JSONObject.objects(key: String): List<JSONObject> =
        optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }.orEmpty()

    /**
     * Reads the artist's page (its most played songs) and the discography page
     * (every album and single). Null when neither could be read.
     */
    suspend fun artistPage(ctx: Context, a: ArtistRef, top: Int = 15): ArtistPage? {
        artistPages[a.slug]?.let { return it }
        val base = "$SITE/${a.slug}/"
        val main = sitePage(ctx, base) ?: return null
        val name = a.name.ifBlank { main.optString("name") }
        fun albums(o: JSONObject) = o.objects("albums").map {
            AlbumRef(it.getString("slug"), it.getString("name"), name, a.slug, it.optString("year"), kind = it.optString("kind"))
        }
        val songs = main.objects("songs").filter { it.getString("id").startsWith(a.slug + "/") }.take(top)
            .map { SongRef(it.getString("id"), it.getString("label"), name) }
        // The artist's page shows only the latest albums; the discography has them all.
        val all = sitePage(ctx, base + "discografia.html")?.let(::albums).orEmpty().ifEmpty { albums(main) }
        if (songs.isEmpty() && all.isEmpty()) return null
        return ArtistPage(songs, all).also { artistPages[a.slug] = it }
    }

    /** The tracks of an album, in order, from its page on the site. */
    private suspend fun albumTracks(ctx: Context, a: AlbumRef): List<SongRef> {
        if (a.slug.isBlank() || a.artistSlug.isBlank()) return emptyList()
        val page = sitePage(ctx, "$SITE/${a.artistSlug}/discografia/${a.slug}/") ?: return emptyList()
        return page.objects("songs").map {
            val id = it.getString("id")
            val artist = if (id.startsWith(a.artistSlug + "/")) a.artist else prettySlug(id.substringBefore('/'))
            SongRef(id, it.getString("label"), artist, album = a.name, albumSlug = a.slug)
        }
    }

    /**
     * The album a song came out on, as the search knows it. Blank fields when
     * the search answered and knew none; null when it could not be asked.
     */
    suspend fun songAlbum(ctx: Context, s: SongRef): Db.SongInfo? = withContext(Dispatchers.IO) {
        val answer = withTimeoutOrNull(15_000) {
            runCatching { kotlinx.coroutines.runInterruptible { get(ctx, SEARCH + URLEncoder.encode("${s.artist} ${s.title}".trim(), "UTF-8")) } }.getOrNull()
        } ?: return@withContext null
        if (answer.first != 200) return@withContext null
        val body = answer.second
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return@withContext null
        val docs = runCatching { JSONObject(body.substring(start, end + 1)).optJSONObject("response")?.optJSONArray("docs") }.getOrNull()
            ?: return@withContext null
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            if (d.optString("tipo") != "2" || "${d.optString("dns")}/${d.optString("url")}" != s.id) continue
            val slug = d.optString("url_album")
            // Album addresses end with the year: "by-the-way-2002".
            val year = slug.substringAfterLast('-', "").takeIf { it.length == 4 }?.toIntOrNull() ?: 0
            return@withContext Db.SongInfo(d.optString("album_name"), slug, year)
        }
        Db.SongInfo("", "", 0)
    }

    suspend fun albumSongs(ctx: Context, a: AlbumRef, onUpdate: (List<SongRef>) -> Unit) {
        albumCache[a.key]?.let { onUpdate(it); return }
        // The album's own page has every track, in order; the search is the fallback.
        val tracks = albumTracks(ctx, a)
        if (tracks.isNotEmpty()) {
            albumCache[a.key] = tracks
            onUpdate(tracks)
            return
        }
        val songs = LinkedHashMap<String, SongRef>()
        for (q in listOf("${a.artist} ${a.name}", a.name)) {
            val r = runCatching { search(ctx, q) }.getOrNull() ?: continue
            r.songs.filter { it.artistSlug == a.artistSlug && it.albumSlug == a.slug }.forEach { songs.putIfAbsent(it.id, it) }
            onUpdate(songs.values.toList())
        }
        val list = songs.values.toList()
        if (list.isNotEmpty()) albumCache[a.key] = list
        onUpdate(list)
    }

    /** Version pages the site answered with another page: not asked for again. */
    private val missingVersions = HashSet<String>()

    private fun samePage(a: String, b: String): Boolean {
        fun path(u: String) = u.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#').trimEnd('/')
        return path(a).equals(path(b), ignoreCase = true)
    }

    /**
     * Downloads and parses one sheet page. Throws with a readable message on failure.
     * `version`: the page is one version of a song (simplified, acoustic, bass).
     * Cifra Club answers a version the song does not have with the main sheet;
     * that is a failure here, not the main sheet under another name.
     */
    suspend fun fetchSheet(ctx: Context, url: String, songId: String, title: String, artist: String, version: Boolean = false): Sheet {
        if (version && url in missingVersions) error("A música não tem essa versão.")
        val html = withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000) { runCatching { kotlinx.coroutines.runInterruptible { get(ctx, url) } }.getOrNull() }?.takeIf { (code, body) -> code == 200 && hasSheet(body) }?.second
        } ?: WebFetcher.fetch(ctx, url, WebFetcher.Wait.CHORDS) ?: error("Não consegui abrir a cifra. Verifique a conexão.")
        val json = withContext(Dispatchers.Default) { Native.extractPage(html, url) }
        if (version) {
            val own = runCatching { JSONObject(json).optString("canonical") }.getOrDefault("")
            if (own.isNotBlank() && !samePage(own, url)) {
                missingVersions += url
                error("A música não tem essa versão.")
            }
        }
        val sheet = withContext(Dispatchers.Default) { Sheet.fromExtract(json, url, songId, title, artist) }
        if (sheet.text.isBlank()) error("A página não tem cifra para violão/guitarra.")
        return sheet
    }

    class PlaylistPage(val title: String, val songs: List<SongRef>, val playlists: List<Version>)

    /**
     * Reads a Cifra Club playlist page (its songs) or a profile page (its
     * playlists). Lists are often drawn by JavaScript, so when plain HTTP finds
     * nothing the page is loaded in the WebView and read once it settles.
     */
    suspend fun readPlaylistPage(ctx: Context, url: String): PlaylistPage {
        fun parse(html: String): PlaylistPage {
            val o = JSONObject(Native.playlistLinks(html, url))
            val ss = o.getJSONArray("songs")
            val ps = o.getJSONArray("playlists")
            val songs = (0 until ss.length()).map {
                val s = ss.getJSONObject(it)
                val id = s.getString("id")
                SongRef(id, s.getString("label"), s.optString("artist").ifBlank { prettySlug(id.substringBefore('/')) })
            }
            val lists = (0 until ps.length()).map { ps.getJSONObject(it).let { p -> Version(p.getString("label"), p.getString("url")) } }
            return PlaylistPage(o.optString("title"), songs, lists)
        }
        val http = withContext(Dispatchers.IO) { withTimeoutOrNull(15_000) { runCatching { kotlinx.coroutines.runInterruptible { get(ctx, url) } }.getOrNull() } }
        if (http != null && http.first == 200) {
            val p = withContext(Dispatchers.Default) { parse(http.second) }
            if (p.songs.isNotEmpty() || p.playlists.isNotEmpty()) return p
        }
        val html = WebFetcher.fetch(ctx, url, WebFetcher.Wait.LINKS) ?: error("Não consegui abrir o link. Verifique a conexão.")
        val p = withContext(Dispatchers.Default) { parse(html) }
        if (p.songs.isEmpty() && p.playlists.isEmpty()) error("Não achei músicas nem playlists nessa página. A playlist é pública?")
        return p
    }

    fun prettySlug(slug: String) = slug.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    fun isPlaylistLink(link: String): Boolean {
        val l = link.lowercase()
        return "cifraclub" in l && ("playlist" in l || "/musico/" in l || "/repertorio/" in l || "/@" in l)
    }

    /** A page with a chord sheet: a <pre> block, or Ultimate Guitar's embedded store. */
    fun hasSheet(body: String) = body.contains("<pre", true) || body.contains("wiki_tab")

    /** Cifra Club keeps the bass version at <song>/tabs-baixo/. */
    fun bassUrl(songUrl: String) = songUrl.trimEnd('/') + "/tabs-baixo/"

    /** The acoustic version, which the song page does not link to. */
    fun acousticUrl(songUrl: String) = songUrl.trimEnd('/') + "/acustica.html"
}

/**
 * Loads a page in an off-screen WebView, like opening it in the phone's
 * browser, and returns the HTML once the chord block is there.
 */
internal object WebFetcher {
    enum class Wait { CHORDS, LINKS }

    private val allowedHosts = listOf("cifraclub", "sscdn", "cloudflare", "akamai", "gstatic", "googleapis", "ultimate-guitar", "ugstatic")

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(ctx: Context, url: String, wait: Wait, timeoutMs: Long = 25_000): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val wv = WebView(ctx.applicationContext)
                var done = false
                fun finish(result: String?) {
                    if (done) return
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    wv.stopLoading()
                    wv.destroy()
                    cont.resume(result)
                }
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    mediaPlaybackRequiresUserGesture = true
                }
                wv.webViewClient = object : WebViewClient() {
                    // Skip ads and trackers: only the site, its CDN and challenge pages load.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val host = request.url.host ?: return null
                        if (allowedHosts.any { host.contains(it) }) return null
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                var lastCount = -1
                var steady = 0
                val poll = object : Runnable {
                    override fun run() {
                        if (done) return
                        val js = when (wait) {
                            Wait.CHORDS -> "(function(){var h=document.documentElement.outerHTML;return (document.querySelector('pre')||h.indexOf('wiki_tab')>=0)?h:null})()"
                            // Lists render late, after the page says it is complete: wait
                            // until the number of links has stopped changing for a while.
                            Wait.LINKS -> "(function(){return document.readyState=='complete'?String(document.querySelectorAll('a[href]').length):null})()"
                        }
                        wv.evaluateJavascript(js) { v ->
                            if (v == null || v == "null") {
                                handler.postDelayed(this, 600)
                            } else if (wait == Wait.CHORDS) {
                                finish(runCatching { JSONArray("[$v]").getString(0) }.getOrNull())
                            } else {
                                val n = v.trim('"').toIntOrNull() ?: 0
                                steady = if (n > 0 && n == lastCount) steady + 1 else 0
                                if (steady >= 2) {
                                    wv.evaluateJavascript("document.documentElement.outerHTML") { h ->
                                        finish(runCatching { JSONArray("[$h]").getString(0) }.getOrNull())
                                    }
                                } else {
                                    lastCount = n
                                    handler.postDelayed(this, 1200)
                                }
                            }
                        }
                    }
                }
                cont.invokeOnCancellation { handler.post { finish(null) } }
                wv.loadUrl(url)
                handler.postDelayed(poll, 800)
            }
        }
    }
}
