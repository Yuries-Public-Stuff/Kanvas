package dev.yurie.display.composebridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.yurie.display.scene.DisplayCommand
import kotlin.test.Test
import kotlin.test.assertTrue

class WakeVisualFixtureTest {
    @Test
    fun wakeInspiredTransportAndOverlayStayInsideStrictDisplayList() {
        ComposeSceneCaptureHost(
            width = 640,
            height = 360,
            density = 1f,
            strictCanvas = true,
        ).use { host ->
            host.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101318))
                ) {
                    Column(
                        Modifier
                            .padding(24.dp)
                            .width(360.dp)
                    ) {
                        Box(
                            Modifier
                                .width(300.dp)
                                .height(160.dp)
                                .background(Color(0xFF242A33))
                        )

                        Canvas(
                            Modifier
                                .width(320.dp)
                                .height(56.dp)
                        ) {
                            val mid = size.height / 2f
                            val path = Path().apply {
                                moveTo(0f, mid)
                                var x = 0f
                                while (x <= size.width * 0.72f) {
                                    val wave =
                                        kotlin.math.sin(x / 18f) * 5f
                                    lineTo(x, mid + wave)
                                    x += 4f
                                }
                            }

                            drawPath(
                                path = path,
                                color = Color(0xFF7BC6FF),
                                style = Stroke(
                                    width = 5f,
                                    cap = StrokeCap.Round,
                                ),
                            )
                            drawCircle(
                                color = Color(0xFF7BC6FF),
                                radius = 9f,
                                center = Offset(
                                    size.width * 0.72f,
                                    mid,
                                ),
                            )
                        }
                    }

                    Box(
                        Modifier
                            .padding(start = 400.dp, top = 40.dp)
                            .width(190.dp)
                            .height(180.dp)
                            .background(Color(0xEE252A32))
                    )
                }
            }

            val result = host.render(10_000_000L)
            val commands = result.displayList.commands

            assertTrue(
                result.unsupportedOperations.isEmpty(),
                "Wake-inspired fixture must not escape the strict Compose canvas",
            )
            assertTrue(commands.any { it is DisplayCommand.DrawImage })
        }
    }

    @Test
    fun wakeInspiredScrollableLibraryAndOverlaysStayInsideStrictDisplayList() {
        ComposeSceneCaptureHost(
            width = 900,
            height = 600,
            density = 1f,
            strictCanvas = true,
        ).use { host ->
            host.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D1015))
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Column(
                            Modifier
                                .width(220.dp)
                                .fillMaxSize()
                                .background(Color(0xFF181D25))
                                .padding(12.dp)
                        ) {
                            repeat(5) { index ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height((52 + index * 2).dp)
                                        .padding(4.dp)
                                        .background(Color(0xFF252C36))
                                )
                            }
                        }

                        LazyColumn(
                            Modifier
                                .width(420.dp)
                                .fillMaxSize()
                                .padding(start = 16.dp)
                        ) {
                            items((0 until 24).toList()) { index ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .padding(vertical = 3.dp)
                                        .background(
                                            if (index % 2 == 0) {
                                                Color(0xFF202630)
                                            } else {
                                                Color(0xFF171C23)
                                            }
                                        )
                                )
                            }
                        }
                    }

                    Box(
                        Modifier
                            .padding(start = 660.dp, top = 50.dp)
                            .width(180.dp)
                            .height(210.dp)
                            .background(Color(0xF02A313C))
                    )

                    Box(
                        Modifier
                            .padding(start = 270.dp, top = 150.dp)
                            .width(360.dp)
                            .height(260.dp)
                            .background(Color(0xF7333944))
                    )
                }
            }

            val result = host.render(20_000_000L)
            val frames = result.displayList.commands.count {
                it is DisplayCommand.DrawImage
            }

            assertTrue(
                result.unsupportedOperations.isEmpty(),
                "Wake library/menu/dialog fixture must stay inside strict Canvas capture",
            )
            assertTrue(frames == 1, "Fixture should emit one captured scene frame")
        }
    }

}
