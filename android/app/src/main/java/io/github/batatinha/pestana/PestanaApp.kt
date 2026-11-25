package io.github.batatinha.pestana

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

class PestanaApp : Application(), ImageLoaderFactory {
    /** Photos live in memory only (same approach as Spotifast), so storage never grows. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache(null as DiskCache?)
        .respectCacheHeaders(false)
        .crossfade(120)
        .build()
}
