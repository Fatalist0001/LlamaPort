package com.llamaport.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A couple of small hand-drawn icons so the app does not have to depend on the
 * (very large) material-icons-extended artifact.
 */
object AppIcons {

    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "Stop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 6f)
                lineTo(18f, 6f)
                lineTo(18f, 18f)
                lineTo(6f, 18f)
                close()
            }
        }.build()
    }

    val Robot: ImageVector by lazy {
        ImageVector.Builder(
            name = "Robot",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                // antenna
                moveTo(11.2f, 1.5f)
                lineTo(12.8f, 1.5f)
                lineTo(12.8f, 4f)
                lineTo(11.2f, 4f)
                close()
                // body with side "ears"
                moveTo(4.5f, 4f)
                lineTo(19.5f, 4f)
                lineTo(19.5f, 6.5f)
                lineTo(21.5f, 6.5f)
                lineTo(21.5f, 15f)
                lineTo(19.5f, 15f)
                lineTo(19.5f, 17f)
                lineTo(15f, 17f)
                lineTo(15f, 20f)
                lineTo(13.2f, 20f)
                lineTo(13.2f, 17f)
                lineTo(10.8f, 17f)
                lineTo(10.8f, 20f)
                lineTo(9f, 20f)
                lineTo(9f, 17f)
                lineTo(4.5f, 17f)
                lineTo(4.5f, 15f)
                lineTo(2.5f, 15f)
                lineTo(2.5f, 6.5f)
                lineTo(4.5f, 6.5f)
                close()
                // eyes
                moveTo(7f, 9f)
                lineTo(10f, 9f)
                lineTo(10f, 12f)
                lineTo(7f, 12f)
                close()
                moveTo(14f, 9f)
                lineTo(17f, 9f)
                lineTo(17f, 12f)
                lineTo(14f, 12f)
                close()
            }
        }.build()
    }

    val Document: ImageVector by lazy {
        ImageVector.Builder(
            name = "Document",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                // page outline
                moveTo(5f, 2f)
                lineTo(14f, 2f)
                lineTo(19f, 7f)
                lineTo(19f, 22f)
                lineTo(5f, 22f)
                close()
                // folded corner
                moveTo(14f, 4.5f)
                lineTo(14f, 7f)
                lineTo(16.5f, 7f)
                close()
                // text lines
                moveTo(7.5f, 11f)
                lineTo(16.5f, 11f)
                lineTo(16.5f, 12.2f)
                lineTo(7.5f, 12.2f)
                close()
                moveTo(7.5f, 14.2f)
                lineTo(16.5f, 14.2f)
                lineTo(16.5f, 15.4f)
                lineTo(7.5f, 15.4f)
                close()
                moveTo(7.5f, 17.4f)
                lineTo(13f, 17.4f)
                lineTo(13f, 18.6f)
                lineTo(7.5f, 18.6f)
                close()
            }
        }.build()
    }

    val Chat: ImageVector by lazy {
        ImageVector.Builder(
            name = "Chat",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 2f)
                lineTo(4f, 2f)
                curveTo(2.9f, 2f, 2f, 2.9f, 2f, 4f)
                lineTo(2f, 22f)
                lineTo(6f, 18f)
                lineTo(20f, 18f)
                curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
                lineTo(22f, 4f)
                curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
                close()
                moveTo(6f, 7f)
                lineTo(18f, 7f)
                lineTo(18f, 9f)
                lineTo(6f, 9f)
                close()
                moveTo(6f, 11f)
                lineTo(18f, 11f)
                lineTo(18f, 13f)
                lineTo(6f, 13f)
                close()
            }
        }.build()
    }

    val Attach: ImageVector by lazy {
        ImageVector.Builder(
            name = "Attach",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16.5f, 6f)
                lineTo(16.5f, 17.5f)
                curveTo(16.5f, 19.71f, 14.71f, 21.5f, 12.5f, 21.5f)
                curveTo(10.29f, 21.5f, 8.5f, 19.71f, 8.5f, 17.5f)
                lineTo(8.5f, 5f)
                curveTo(8.5f, 3.62f, 9.62f, 2.5f, 11f, 2.5f)
                curveTo(12.38f, 2.5f, 13.5f, 3.62f, 13.5f, 5f)
                lineTo(13.5f, 15.5f)
                curveTo(13.5f, 16.05f, 13.05f, 16.5f, 12.5f, 16.5f)
                curveTo(11.95f, 16.5f, 11.5f, 16.05f, 11.5f, 15.5f)
                lineTo(11.5f, 6f)
                lineTo(10f, 6f)
                lineTo(10f, 15.5f)
                curveTo(10f, 16.88f, 11.12f, 18f, 12.5f, 18f)
                curveTo(13.88f, 18f, 15f, 16.88f, 15f, 15.5f)
                lineTo(15f, 5f)
                curveTo(15f, 2.79f, 13.21f, 1f, 11f, 1f)
                curveTo(8.79f, 1f, 7f, 2.79f, 7f, 5f)
                lineTo(7f, 17.5f)
                curveTo(7f, 20.54f, 9.46f, 23f, 12.5f, 23f)
                curveTo(15.54f, 23f, 18f, 20.54f, 18f, 17.5f)
                lineTo(18f, 6f)
                close()
            }
        }.build()
    }

    val ArrowDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowDown",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 12f)
                lineTo(18.59f, 10.59f)
                lineTo(13f, 16.17f)
                lineTo(13f, 4f)
                lineTo(11f, 4f)
                lineTo(11f, 16.17f)
                lineTo(5.41f, 10.58f)
                lineTo(4f, 12f)
                lineTo(12f, 20f)
                close()
            }
        }.build()
    }

    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "Play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 5f)
                lineTo(8f, 19f)
                lineTo(19f, 12f)
                close()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 19f)
                lineTo(10f, 19f)
                lineTo(10f, 5f)
                lineTo(6f, 5f)
                close()
                moveTo(14f, 5f)
                lineTo(14f, 19f)
                lineTo(18f, 19f)
                lineTo(18f, 5f)
                close()
            }
        }.build()
    }

    val Image: ImageVector by lazy {
        ImageVector.Builder(
            name = "Image",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21f, 19f)
                lineTo(21f, 5f)
                curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
                lineTo(5f, 3f)
                curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
                lineTo(3f, 19f)
                curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
                lineTo(19f, 21f)
                curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
                close()
                moveTo(8.5f, 13.5f)
                lineTo(11f, 16.51f)
                lineTo(14.5f, 12f)
                lineTo(19f, 18f)
                lineTo(5f, 18f)
                close()
            }
        }.build()
    }

    val Audio: ImageVector by lazy {
        ImageVector.Builder(
            name = "Audio",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 3f)
                curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
                lineTo(3f, 19f)
                curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
                lineTo(7f, 21f)
                lineTo(7f, 14f)
                lineTo(5f, 14f)
                lineTo(5f, 12f)
                curveTo(5f, 8.13f, 8.13f, 5f, 12f, 5f)
                curveTo(15.87f, 5f, 19f, 8.13f, 19f, 12f)
                lineTo(19f, 14f)
                lineTo(17f, 14f)
                lineTo(17f, 21f)
                lineTo(19f, 21f)
                curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
                lineTo(21f, 12f)
                curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
                close()
            }
        }.build()
    }
}
