package io.github.batatinha.pestana

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Picture URLs for artists ("artist-slug") and albums ("artist-slug/album-slug").
 * Every search result feeds it, so favorites, playlists and history show photos
 * too. Only URLs are stored (a few bytes each); the pictures themselves live in
 * Coil's memory cache, like Spotifast, so the app's storage never grows.
 */
object Images {
    private val urls = mutableStateMapOf<String, String>()
    private var db: Db? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tried = HashSet<String>()
    private val lookups = Semaphore(2)

    fun init(db: Db) {
        this.db = db
        io.launch {
            val all = db.allImages()
            launch(Dispatchers.Main) { all.forEach { (k, v) -> urls.putIfAbsent(k, v) } }
        }
    }

    fun artist(slug: String): String? = urls[slug]
    fun album(key: String): String? = urls[key]

    fun remember(map: Map<String, String>) {
        val fresh = map.filter { (k, v) -> v.isNotEmpty() && urls[k] != v }
        if (fresh.isEmpty()) return
        io.launch {
            db?.saveImages(fresh)
            launch(Dispatchers.Main) { urls.putAll(fresh) }
        }
    }

    /** Looks up an artist's photo once (e.g. songs imported from a playlist). */
    fun ensureArtist(ctx: Context, slug: String) {
        if (slug.isEmpty() || urls.containsKey(slug) || slug == "local" || slug == "web") return
        synchronized(tried) { if (!tried.add(slug)) return }
        io.launch {
            lookups.withPermit { runCatching { Source.search(ctx, Source.prettySlug(slug)) } }
        }
    }
}
