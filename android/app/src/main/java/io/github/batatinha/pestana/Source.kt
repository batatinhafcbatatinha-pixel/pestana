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

    suspend fun albumSongs(ctx: Context, a: AlbumRef, onUpdate: (List<SongRef>) -> Unit) {
        albumCache[a.key]?.let { onUpdate(it); return }
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

    /** Downloads and parses one sheet page. Throws with a readable message on failure. */
    suspend fun fetchSheet(ctx: Context, url: String, songId: String, title: String, artist: String): Sheet {
        val html = withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000) { runCatching { kotlinx.coroutines.runInterruptible { get(ctx, url) } }.getOrNull() }?.takeIf { (code, body) -> code == 200 && hasSheet(body) }?.second
        } ?: WebFetcher.fetch(ctx, url, WebFetcher.Wait.CHORDS) ?: error("Não consegui abrir a cifra. Verifique a conexão.")
        val sheet = withContext(Dispatchers.Default) {
            Sheet.fromExtract(Native.extractPage(html, url), url, songId, title, artist)
        }
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
                SongRef(id, s.getString("label"), prettySlug(id.substringBefore('/')))
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
        return "cifraclub" in l && ("playlist" in l || "/musico/" in l)
    }

    /** A page with a chord sheet: a <pre> block, or Ultimate Guitar's embedded store. */
    fun hasSheet(body: String) = body.contains("<pre", true) || body.contains("wiki_tab")

    fun simplifiedUrl(songUrl: String) = songUrl.trimEnd('/') + "/simplificada.html"
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
                val poll = object : Runnable {
                    override fun run() {
                        if (done) return
                        val js = when (wait) {
                            Wait.CHORDS -> "(function(){var h=document.documentElement.outerHTML;return (document.querySelector('pre')||h.indexOf('wiki_tab')>=0)?h:null})()"
                            // Lists render late: wait until the number of links stops changing.
                            Wait.LINKS -> "(function(){return document.readyState=='complete'?String(document.querySelectorAll('a[href]').length):null})()"
                        }
                        wv.evaluateJavascript(js) { v ->
                            if (v == null || v == "null") {
                                handler.postDelayed(this, 600)
                            } else if (wait == Wait.CHORDS) {
                                finish(runCatching { JSONArray("[$v]").getString(0) }.getOrNull())
                            } else {
                                val n = v.trim('"').toIntOrNull() ?: 0
                                if (n > 0 && n == lastCount) {
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
