package io.github.batatinha.pestana.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A small hand-drawn icon set (24x24, stroked), so the app needs no icon library. */
object Ic {
    private fun icon(name: String, vararg paths: String, filled: Set<Int> = emptySet()): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEachIndexed { i, d ->
            val nodes = PathParser().parsePathString(d).toNodes()
            if (i in filled) {
                b.addPath(nodes, fill = SolidColor(Color.Black))
            } else {
                b.addPath(
                    nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }

    val Search = icon("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M16.5 16.5L21 21")
    val Back = icon("back", "M15 5l-7 7l7 7")
    val Close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Minus = icon("minus", "M5 12h14")
    val More = icon("more", "M12 5.5a1 1 0 1 0 0.01 0z", "M12 12a1 1 0 1 0 0.01 0z", "M12 18.5a1 1 0 1 0 0.01 0z")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val Heart = icon(
        "heart",
        "M12 20s-7.5-4.6-7.5-10.1A4.4 4.4 0 0 1 12 7.2a4.4 4.4 0 0 1 7.5 2.7C19.5 15.4 12 20 12 20z",
    )
    val HeartFilled = icon(
        "heartFilled",
        "M12 20s-7.5-4.6-7.5-10.1A4.4 4.4 0 0 1 12 7.2a4.4 4.4 0 0 1 7.5 2.7C19.5 15.4 12 20 12 20z",
        filled = setOf(0),
    )
    val Play = icon("play", "M8 5.5v13l10.5-6.5z", filled = setOf(0))
    val Pause = icon("pause", "M7 5h3.5v14H7z", "M13.5 5H17v14h-3.5z", filled = setOf(0, 1))
    val Settings = icon(
        "settings",
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        "M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7",
    )
    val Library = icon("library", "M5 4v16", "M9.5 4v16", "M14 5l4.5 14.5")
    val Note = icon("note", "M9 18V6l10-2v12", "M9 18a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z", "M19 16a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z")
    val Tuner = icon("tuner", "M4 17a8 8 0 0 1 16 0", "M12 17l3.5-6", "M12 17.2a0.6 0.6 0 1 0 0.01 0z")
    val Metronome = icon("metronome", "M8.5 4h7l3.5 16H5z", "M12 15l5-8", "M7 16h10")
    val Home = icon("home", "M4 11l8-7l8 7", "M6 9.5V20h12V9.5")
    val Person = icon("person", "M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M4.5 20a7.5 7.5 0 0 1 15 0")
    val Album = icon("album", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z")
    val Playlist = icon("playlist", "M4 6h12", "M4 11h12", "M4 16h7", "M17 14v6", "M14 17h6")
    val Trash = icon("trash", "M5 7h14", "M9 7V4.5h6V7", "M7 7l1 13h8l1-13")
    val Edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Text = icon("text", "M4 18L9 6l5 12", "M5.8 14h6.4", "M15 18l3-7l3 7", "M16 16h4")
    val Key = icon("key", "M7 18V6", "M7 6l4 4", "M17 6v12", "M17 18l-4-4")
    val Tab = icon("tab", "M3 6h18M3 10h18M3 14h18M3 18h18", "M8 4v16")
    val Up = icon("up", "M6 15l6-6l6 6")
    val Down = icon("down", "M6 9l6 6l6-6")
    val Import = icon("import", "M12 4v11", "M7.5 10.5L12 15l4.5-4.5", "M5 19h14")
    val Grid = icon("grid", "M7 4v16M11 4v16M15 4v16M19 4v16", "M5 8h16", "M9 13a1 1 0 1 0 0.01 0z")
    val Refresh = icon("refresh", "M19 12a7 7 0 1 1-2-5", "M19 4v4h-4")
    val Speed = icon("speed", "M5 12h10", "M11 8l4 4l-4 4", "M19 6v12")
}
