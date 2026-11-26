package io.github.batatinha.pestana

/** Bridge to the Rust core (core/src/jni_api.rs). */
object Native {
    init {
        System.loadLibrary("pestana")
    }

    /** Page HTML -> JSON {title, artist, key, capo, tuning, text, versions:[{label,url}]} */
    @JvmStatic external fun extractPage(html: String, url: String): String

    /** Playlist/profile page -> {title, songs:[{id,label}], playlists:[{url,label}]} */
    @JvmStatic external fun playlistLinks(html: String, url: String): String

    /** Artist, discography or album page -> {name, songs:[{id,label}], albums:[{slug,name,year,kind}]} */
    @JvmStatic external fun artistPage(html: String, url: String): String

    /** Chord sheet text -> rendered JSON (see core/src/sheet.rs). */
    @JvmStatic external fun render(
        text: String,
        key: String,
        transpose: Int,
        simplify: Boolean,
        view: Int,
        showTabs: Boolean,
        accidentals: Int,
        cols: Int,
    ): String

    /** Chord name -> {"frets":[6],"barre":n,"barreFrom":i} or "{}". */
    @JvmStatic external fun chordShape(name: String): String

    /** Chord name -> 4-string bass box {"frets":[4],"root":i,...} or "{}". */
    @JvmStatic external fun bassShape(name: String): String

    @JvmStatic external fun newDetector(): Long
    @JvmStatic external fun freeDetector(ptr: Long)

    /** Returns [frequency (-1 if none), clarity 0..1, rms]. */
    @JvmStatic external fun detect(ptr: Long, samples: FloatArray, count: Int, sampleRate: Int, minHz: Float, maxHz: Float): FloatArray
}
