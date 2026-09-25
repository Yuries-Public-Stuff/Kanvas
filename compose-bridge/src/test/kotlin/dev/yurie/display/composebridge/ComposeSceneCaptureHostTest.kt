package dev.yurie.display.composebridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.yurie.display.scene.DisplayCommand
import kotlin.test.Test
import kotlin.test.assertTrue

class ComposeSceneCaptureHostTest {
    @Test
    fun realComposeSceneDrawsIntoKotlinDisplayCanvas() {
        ComposeSceneCaptureHost(
            width = 320,
            height = 200,
            strictCanvas = false,
        ).use { host ->
            host.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF224466))
                )
            }

            val result = host.render(1_000_000L)
            assertTrue(
                result.displayList.commands.any { it is DisplayCommand.DrawImage },
                "Compose scene should snapshot into the Kotlin Display display list",
            )
        }
    }
}
