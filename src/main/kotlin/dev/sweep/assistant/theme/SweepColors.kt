package dev.sweep.assistant.theme

import com.intellij.ui.JBColor
import java.awt.Color

fun Color.withAlpha(alpha: Float) = Color(red, green, blue, (255 * alpha).toInt().coerceIn(0, 255))

fun Color.withAlpha(alpha: Int) = Color(red, green, blue, alpha.coerceIn(0, 255))

fun JBColor.withAlpha(alpha: Float) =
    JBColor(
        (this as Color).withAlpha(alpha),
        (this as Color).withAlpha(alpha),
    )

fun JBColor.withAlpha(alpha: Int) =
    JBColor(
        (this as Color).withAlpha(alpha),
        (this as Color).withAlpha(alpha),
    )

fun JBColor.withAlpha(
    lightAlpha: Float,
    darkAlpha: Float,
) = JBColor(
    (this as Color).withAlpha(lightAlpha),
    (this as Color).withAlpha(darkAlpha),
)

fun Color.withAlpha(
    lightAlpha: Float,
    darkAlpha: Float,
) = JBColor(
    this.withAlpha(lightAlpha),
    this.withAlpha(darkAlpha),
)

object SweepColors {
    val transparent = Color(0, 0, 0, 0)
    val acceptedGlowColor = Color(117, 197, 144, 31)
    val acceptedHighlightColor = Color(117, 197, 144, 31)
    val whitespaceHighlightColor = Color(87, 255, 137, (255 * 0.06).toInt())
    val additionHighlightColor = Color(87, 255, 137, (255 * 0.14).toInt())
    val deletionHighlightColor get() = Color(255, 86, 91, (255 * 0.18).toInt())
    val foregroundColor: Color get() = JBColor.foreground()
}
