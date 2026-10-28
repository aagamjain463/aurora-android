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
 * aurora's own line icons: 24dp grid, 1.75 stroke, round caps and joins, no fills.
 * Drawn here rather than pulled from an icon library so the set stays small and consistent.
 */
object AuroraIcons {
    /** Today: the mark itself, a horizon arc with a rising point. */
    val Today = icon("today") {
        moveTo(5.5f, 18f); arcTo(6.5f, 6.5f, 0f, false, true, 18.5f, 18f)
        circle(12f, 6.5f, 1.1f)
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
