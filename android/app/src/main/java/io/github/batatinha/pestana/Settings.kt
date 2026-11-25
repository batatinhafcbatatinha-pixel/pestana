package io.github.batatinha.pestana

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-wide settings, stored in SharedPreferences and exposed as Compose state. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var theme by mutableStateOf(p.getString("theme", "auto")!!)
        private set
    var fontSize by mutableFloatStateOf(p.getFloat("fontSize", 15f))
        private set
    var lineSpacing by mutableFloatStateOf(p.getFloat("lineSpacing", 1.25f))
        private set
    var showTabs by mutableStateOf(p.getBoolean("showTabs", true))
        private set
    var showDiagrams by mutableStateOf(p.getBoolean("showDiagrams", true))
        private set
    /** 0 auto, 1 sharps, 2 flats */
    var accidentals by mutableIntStateOf(p.getInt("accidentals", 0))
        private set
    var a4 by mutableFloatStateOf(p.getFloat("a4", 440f))
        private set
    var keepScreenOn by mutableStateOf(p.getBoolean("keepScreenOn", true))
        private set
    var volumeKeys by mutableStateOf(p.getBoolean("volumeKeys", false))
        private set
    var leftHanded by mutableStateOf(p.getBoolean("leftHanded", false))
        private set
    var tuning by mutableStateOf(p.getString("tuning", "guitar_std")!!)
        private set
    var bpm by mutableIntStateOf(p.getInt("bpm", 90))
        private set
    var beats by mutableIntStateOf(p.getInt("beats", 4))
        private set
    var subdivision by mutableIntStateOf(p.getInt("subdivision", 1))
        private set
    var accent by mutableStateOf(p.getBoolean("accent", true))
        private set


    fun putTheme(v: String) { theme = v; p.edit().putString("theme", v).apply() }
    fun putFontSize(v: Float) { fontSize = v.coerceIn(10f, 32f); p.edit().putFloat("fontSize", fontSize).apply() }
    fun putLineSpacing(v: Float) { lineSpacing = v.coerceIn(1f, 2f); p.edit().putFloat("lineSpacing", lineSpacing).apply() }
    fun putShowTabs(v: Boolean) { showTabs = v; p.edit().putBoolean("showTabs", v).apply() }
    fun putShowDiagrams(v: Boolean) { showDiagrams = v; p.edit().putBoolean("showDiagrams", v).apply() }
    fun putAccidentals(v: Int) { accidentals = v; p.edit().putInt("accidentals", v).apply() }
    fun putA4(v: Float) { a4 = v.coerceIn(415f, 466f); p.edit().putFloat("a4", a4).apply() }
    fun putKeepScreenOn(v: Boolean) { keepScreenOn = v; p.edit().putBoolean("keepScreenOn", v).apply() }
    fun putVolumeKeys(v: Boolean) { volumeKeys = v; p.edit().putBoolean("volumeKeys", v).apply() }
    fun putLeftHanded(v: Boolean) { leftHanded = v; p.edit().putBoolean("leftHanded", v).apply() }
    fun putTuning(v: String) { tuning = v; p.edit().putString("tuning", v).apply() }
    fun putBpm(v: Int) { bpm = v.coerceIn(20, 300); p.edit().putInt("bpm", bpm).apply() }
    fun putBeats(v: Int) { beats = v.coerceIn(1, 12); p.edit().putInt("beats", beats).apply() }
    fun putSubdivision(v: Int) { subdivision = v.coerceIn(1, 4); p.edit().putInt("subdivision", subdivision).apply() }
    fun putAccent(v: Boolean) { accent = v; p.edit().putBoolean("accent", v).apply() }
}
