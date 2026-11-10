package app.aurora.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Aurora's own line icons: 24dp grid, 1.75 stroke, round caps and joins, no fills.
 * Drawn here rather than pulled from an icon library so the set stays small and consistent.
 */
object AuroraIcons {
    /** Home: a roof line over walls and a door. */
    val Home = icon("home") {
        moveTo(4.5f, 11f); lineTo(12f, 4.5f); lineTo(19.5f, 11f)
        moveTo(6.5f, 9.5f); verticalLineTo(19.5f); horizontalLineTo(17.5f); verticalLineTo(9.5f)
        moveTo(10f, 19.5f); verticalLineTo(14.5f); horizontalLineTo(14f); verticalLineTo(19.5f)
    }
    /** Intent: a door held open, with the decision crossing it. */
    val Intent = icon("intent") {
        moveTo(14f, 4.5f); horizontalLineTo(18.5f); verticalLineTo(19.5f); horizontalLineTo(14f)
        moveTo(4f, 12f); horizontalLineTo(13f)
        moveTo(10f, 8.5f); lineTo(13.5f, 12f); lineTo(10f, 15.5f)
    }
    /** Track: rising bars with the point of light at the top. */
    val Track = icon("track") {
        moveTo(6f, 19.5f); verticalLineTo(14f)
        moveTo(12f, 19.5f); verticalLineTo(10.5f)
        moveTo(18f, 19.5f); verticalLineTo(12f)
        circle(12f, 5.5f, 1.25f)
    }
    /** A clock face: when something ends. */
    val Clock = icon("clock") {
        circle(12f, 12f, 8.5f)
        moveTo(12f, 7.5f); verticalLineTo(12f); lineTo(15f, 14f)
    }
        val Protect = icon("protect") {
        moveTo(12f, 3.5f); lineTo(19f, 6.2f); verticalLineTo(11.5f)
        curveTo(19f, 15.8f, 16f, 19.1f, 12f, 20.5f)
        curveTo(8f, 19.1f, 5f, 15.8f, 5f, 11.5f)
        verticalLineTo(6.2f); close()
    }
    val Insights = icon("insights") {
        moveTo(6f, 19f); verticalLineTo(13f)
        moveTo(12f, 19f); verticalLineTo(5f)
        moveTo(18f, 19f); verticalLineTo(10f)
    }
    val Settings = icon("settings") {
        moveTo(4f, 8f); horizontalLineTo(13f)
        moveTo(19f, 8f); horizontalLineTo(20f)
        circle(16f, 8f, 2.25f)
        moveTo(4f, 16f); horizontalLineTo(5f)
        moveTo(11f, 16f); horizontalLineTo(20f)
        circle(8f, 16f, 2.25f)
    }
    val Focus = icon("focus") {
        circle(12f, 13f, 7f)
        moveTo(12f, 9.5f); verticalLineTo(13f); lineTo(14.5f, 14.5f)
        moveTo(10f, 3f); horizontalLineTo(14f)
    }
    val Close = icon("close") {
        moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
        moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
    }
    /** Start: a soft-cornered play triangle. */
    val Play = icon("play") {
        moveTo(8f, 5.8f); lineTo(18f, 12f); lineTo(8f, 18.2f); close()
    }
    val Back = icon("back") { moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) }
    val Chevron = icon("chevron") { moveTo(10f, 6f); lineTo(16f, 12f); lineTo(10f, 18f) }
    val Check = icon("check") { moveTo(5f, 12.5f); lineTo(9.75f, 17f); lineTo(19f, 7.5f) }
    val Search = icon("search") {
        circle(11f, 11f, 6.25f)
        moveTo(15.75f, 15.75f); lineTo(20f, 20f)
    }
    val Lock = icon("lock") {
        moveTo(6.5f, 10.5f); horizontalLineTo(17.5f); verticalLineTo(19.5f); horizontalLineTo(6.5f); close()
        moveTo(8.75f, 10.5f); verticalLineTo(7.75f)
        arcTo(3.25f, 3.25f, 0f, false, true, 15.25f, 7.75f); verticalLineTo(10.5f)
    }
    /** An open padlock: a task's unlock. */
    val Unlock = icon("unlock") {
        moveTo(6.5f, 10.5f); horizontalLineTo(17.5f); verticalLineTo(19.5f); horizontalLineTo(6.5f); close()
        moveTo(8.75f, 10.5f); verticalLineTo(7.75f)
        arcTo(3.25f, 3.25f, 0f, false, true, 15f, 6.25f)
    }
    val Briefcase = icon("briefcase") {
        moveTo(4.5f, 8.5f); horizontalLineTo(19.5f); verticalLineTo(18.5f); horizontalLineTo(4.5f); close()
        moveTo(9f, 8.5f); verticalLineTo(6f); horizontalLineTo(15f); verticalLineTo(8.5f)
        moveTo(4.5f, 13f); horizontalLineTo(19.5f)
    }
    val Pause = icon("pause") {
        circle(12f, 12f, 8f)
        moveTo(10f, 9.5f); verticalLineTo(14.5f)
        moveTo(14f, 9.5f); verticalLineTo(14.5f)
    }
    val Calendar = icon("calendar") {
        moveTo(5f, 6.5f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(5f); close()
        moveTo(5f, 10.5f); horizontalLineTo(19f)
        moveTo(9f, 4.5f); verticalLineTo(8f)
        moveTo(15f, 4.5f); verticalLineTo(8f)
    }
    val Globe = icon("globe") {
        circle(12f, 12f, 8f)
        moveTo(4f, 12f); horizontalLineTo(20f)
        moveTo(12f, 4f); curveTo(9.5f, 6.5f, 9.5f, 17.5f, 12f, 20f)
        moveTo(12f, 4f); curveTo(14.5f, 6.5f, 14.5f, 17.5f, 12f, 20f)
    }
    /** Apps: four tiles, as on a home screen. */
    val Apps = icon("apps") {
        moveTo(5f, 5f); horizontalLineTo(10.5f); verticalLineTo(10.5f); horizontalLineTo(5f); close()
        moveTo(13.5f, 5f); horizontalLineTo(19f); verticalLineTo(10.5f); horizontalLineTo(13.5f); close()
        moveTo(5f, 13.5f); horizontalLineTo(10.5f); verticalLineTo(19f); horizontalLineTo(5f); close()
        moveTo(13.5f, 13.5f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(13.5f); close()
    }
    /** Reels: a clapper frame over a play mark. Generic, never a brand's logo. */
    val Reels = icon("reels") {
        moveTo(4.5f, 4.5f); horizontalLineTo(19.5f); verticalLineTo(19.5f); horizontalLineTo(4.5f); close()
        moveTo(4.5f, 9f); horizontalLineTo(19.5f)
        moveTo(8.5f, 4.5f); lineTo(10.5f, 9f)
        moveTo(13.5f, 4.5f); lineTo(15.5f, 9f)
        moveTo(10.5f, 12f); lineTo(15f, 14.5f); lineTo(10.5f, 17f); close()
    }
    /** Shorts: a tall screen with a play mark. Generic, never a brand's logo. */
    val Shorts = icon("shorts") {
        moveTo(7f, 3.5f); horizontalLineTo(17f); verticalLineTo(20.5f); horizontalLineTo(7f); close()
        moveTo(10.5f, 9.5f); lineTo(14.5f, 12f); lineTo(10.5f, 14.5f); close()
    }
    val Minus = icon("minus") {
        moveTo(5.5f, 12f); horizontalLineTo(18.5f)
    }
    val Plus = icon("plus") {
        moveTo(12f, 5.5f); verticalLineTo(18.5f)
        moveTo(5.5f, 12f); horizontalLineTo(18.5f)
    }
    val Trash = icon("trash") {
        moveTo(5f, 7f); horizontalLineTo(19f)
        moveTo(9.5f, 7f); verticalLineTo(5f); horizontalLineTo(14.5f); verticalLineTo(7f)
        moveTo(6.75f, 7f); lineTo(7.5f, 19.5f); horizontalLineTo(16.5f); lineTo(17.25f, 7f)
    }
    /** Daily limits: an hourglass. */
    val Hourglass = icon("hourglass") {
        moveTo(7f, 4f); horizontalLineTo(17f)
        moveTo(7f, 20f); horizontalLineTo(17f)
        moveTo(8f, 4f); curveTo(8f, 9f, 12f, 10f, 12f, 12f); curveTo(12f, 14f, 8f, 15f, 8f, 20f)
        moveTo(16f, 4f); curveTo(16f, 9f, 12f, 10f, 12f, 12f); curveTo(12f, 14f, 16f, 15f, 16f, 20f)
    }
    /** Goals: a point inside two rings. */
    val Target = icon("target") {
        circle(12f, 12f, 8f)
        circle(12f, 12f, 4f)
        circle(12f, 12f, 0.6f)
    }
    /** Groups: stacked layers. */
    val Layers = icon("layers") {
        moveTo(12f, 4.5f); lineTo(20f, 8.75f); lineTo(12f, 13f); lineTo(4f, 8.75f); close()
        moveTo(4f, 12.75f); lineTo(12f, 17f); lineTo(20f, 12.75f)
        moveTo(4f, 16.25f); lineTo(12f, 20.5f); lineTo(20f, 16.25f)
    }
    /** Intent: a speech mark, the person's own words. */
    val Quote = icon("quote") {
        moveTo(5f, 6f); horizontalLineTo(19f); verticalLineTo(16f); horizontalLineTo(11f); lineTo(7f, 19.5f); verticalLineTo(16f); horizontalLineTo(5f); close()
    }
    /** Friction: a bolt, the effort it takes to get through. (Not a star: Aurora uses no celestial imagery.) */
    val Spark = icon("spark") {
        moveTo(13f, 3.5f); lineTo(5.5f, 13.5f); horizontalLineTo(11.5f); lineTo(11f, 20.5f)
        lineTo(18.5f, 10.5f); horizontalLineTo(12.5f); close()
    }
    val Bell = icon("bell") {
        moveTo(6f, 16.5f); verticalLineTo(11f)
        arcTo(6f, 6f, 0f, false, true, 18f, 11f); verticalLineTo(16.5f)
        moveTo(4.5f, 16.5f); horizontalLineTo(19.5f)
        moveTo(10f, 19.5f); horizontalLineTo(14f)
    }
    val Help = icon("help") {
        circle(12f, 12f, 8f)
        moveTo(9.75f, 9.75f); arcTo(2.25f, 2.25f, 0f, true, true, 12f, 12f); verticalLineTo(13.25f)
        moveTo(12f, 16.25f); verticalLineTo(16.3f)
    }
    /** Info: a point of light over its line, in a circle. */
    val Info = icon("info") {
        circle(12f, 12f, 8f)
        moveTo(12f, 11f); verticalLineTo(16f)
        moveTo(12f, 8f); verticalLineTo(8.05f)
    }
    val Data = icon("data") {
        moveTo(5f, 6.5f); arcTo(7f, 2.5f, 0f, true, false, 19f, 6.5f); arcTo(7f, 2.5f, 0f, true, false, 5f, 6.5f)
        moveTo(5f, 6.5f); verticalLineTo(17.5f); arcTo(7f, 2.5f, 0f, false, false, 19f, 17.5f); verticalLineTo(6.5f)
        moveTo(5f, 12f); arcTo(7f, 2.5f, 0f, false, false, 19f, 12f)
    }
    val Edit = icon("edit") {
        moveTo(5f, 19f); lineTo(5.75f, 15.5f); lineTo(15.5f, 5.75f); lineTo(18.25f, 8.5f); lineTo(8.5f, 18.25f); close()
        moveTo(13.5f, 7.75f); lineTo(16.25f, 10.5f)
    }
    /** More: three points on the horizon line. */
    val More = icon("more") {
        moveTo(5.5f, 12f); horizontalLineTo(5.55f)
        moveTo(12f, 12f); horizontalLineTo(12.05f)
        moveTo(18.5f, 12f); horizontalLineTo(18.55f)
    }
    val Copy = icon("copy") {
        moveTo(9f, 9f); horizontalLineTo(18f); verticalLineTo(19f); horizontalLineTo(9f); close()
        moveTo(6f, 15f); verticalLineTo(5f); horizontalLineTo(15f)
    }
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}

private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
        .build()
