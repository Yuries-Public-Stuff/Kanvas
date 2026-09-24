package dev.yurie.display.gpudemo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.yurie.display.composebridge.ComposeGpuWindowHost

fun main() {
    ComposeGpuWindowHost(
        width = 960,
        height = 540,
        title = "Kotlin Display | Compose -> DisplayList -> GPU",
        strict = true,
    ).use { host ->
        host.setContent {
            val accent = remember { Color(0xFF5B8DEF) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF10131A))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = Color(0xFF202735),
                        topLeft = Offset(56f, 56f),
                        size = Size(420f, 260f),
                        cornerRadius = CornerRadius(24f, 24f),
                    )
                    drawCircle(
                        color = accent,
                        radius = 58f,
                        center = Offset(190f, 185f),
                    )
                    drawRect(
                        color = Color(0xCCFF5C7A),
                        topLeft = Offset(285f, 130f),
                        size = Size(120f, 110f),
                    )
                }
            }
        }
        host.run()
    }
}
