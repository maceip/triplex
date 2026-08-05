package dev.triplex.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small product-owned icon set so the design system does not require the
 * entire material-icons-extended artifact for one semantic symbol. */
object TriplexIcons {
    val Microphone: ImageVector
        get() = microphone ?: ImageVector.Builder(
            name = "TriplexMicrophone",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                verticalLineTo(5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                verticalLineTo(11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                close()
                moveTo(17.3f, 11f)
                curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
                curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
                horizontalLineTo(5f)
                curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
                verticalLineTo(21f)
                horizontalLineTo(8f)
                verticalLineTo(23f)
                horizontalLineTo(16f)
                verticalLineTo(21f)
                horizontalLineTo(13f)
                verticalLineTo(17.72f)
                curveTo(16.28f, 17.24f, 19f, 14.42f, 19f, 11f)
                close()
            }
        }.build().also { microphone = it }

    private var microphone: ImageVector? = null
}
