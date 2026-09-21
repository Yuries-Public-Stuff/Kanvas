package dev.yurie.display.demo

import dev.yurie.display.Rgba
import dev.yurie.display.ui.UiScene
import dev.yurie.display.ui.UiState
import dev.yurie.display.ui.uiScene

fun queueDeskScene(
    width: Int,
    height: Int,
    queued: UiState<Int>,
    paused: UiState<Boolean>,
    compact: UiState<Boolean>,
    spinning: UiState<Boolean>,
    rotation: UiState<Float>,
    commandCount: UiState<Int>,
    meter: FrameRateMeter,
): UiScene {
    val ink = Rgba(0.88f, 0.92f, 0.96f)
    val muted = Rgba(0.56f, 0.63f, 0.72f)
    val panel = Rgba(0.13f, 0.18f, 0.24f)
    val accent = Rgba(0.18f, 0.49f, 0.66f)
    return uiScene(width, height, background = Rgba(0.08f, 0.11f, 0.15f)) {
        box(width.toFloat(), height.toFloat(), padding = 16f) {
            column((width - 32).coerceAtLeast(0).toFloat(), 500f, spacing = 12f) {
                row(928f, 68f, background = panel, padding = 16f, spacing = 25f) {
                    text(235f, 35f, "QUEUE DESK", color = ink, scale = 4f)
                    text(435f, 35f, "RENDERER TEST / 01", color = muted, scale = 2f)
                }
                row(928f, 350f, spacing = 12f) {
                    column(210f, 350f, background = panel, padding = 16f, spacing = 10f) {
                        text(170f, 28f, "WORKSPACE", color = ink, scale = 3f)
                        box(178f, 2f, background = accent)
                        text(170f, 24f, "OVERVIEW", color = muted, scale = 2f)
                        text(170f, 24f, "QUEUE", color = ink, scale = 2f)
                        text(170f, 24f, "MODEL TEST", color = muted, scale = 2f)
                        box(178f, 2f, background = Rgba(0.25f, 0.31f, 0.38f))
                        button(178f, 42f, Rgba(0.22f, 0.30f, 0.39f), label = if (compact.value) "FULL VIEW" else "COMPACT", labelScale = 2f) {
                            compact.update { !it }
                        }
                        button(178f, 42f, Rgba(0.23f, 0.38f, 0.37f), label = if (spinning.value) "FREEZE" else "ROTATE", labelScale = 2f) {
                            spinning.update { !it }
                        }
                    }
                    column(706f, 350f, background = panel, padding = 16f, spacing = 6f) {
                        row(660f, 44f, spacing = 24f) {
                            text(285f, 35f, "JOB QUEUE", color = ink, scale = 4f)
                            text(265f, 30f, if (paused.value) "ON HOLD" else "RUNNING", color = if (paused.value) Rgba(0.93f, 0.68f, 0.38f) else Rgba(0.36f, 0.78f, 0.57f), scale = 3f)
                        }
                        box(660f, 2f, background = Rgba(0.29f, 0.35f, 0.42f))
                        row(660f, 75f, background = Rgba(0.18f, 0.24f, 0.31f), padding = 12f, spacing = 22f) {
                            text(220f, 45f, "ITEMS ${queued.value}", color = ink, scale = 4f)
                            text(340f, 45f, if (compact.value) "DETAILS OFF" else "READY FOR REVIEW", color = muted, scale = 2f)
                        }
                        row(660f, 100f, background = Rgba(0.10f, 0.16f, 0.22f), padding = 6f, spacing = 12f) {
                            wireframe(407f, 88f, rotation.value)
                            column(215f, 88f, spacing = 5f) {
                                text(210f, 22f, if (spinning.value) "3D CUBE LIVE" else "3D CUBE HOLD", color = ink, scale = 2f)
                                text(210f, 22f, meter.percentile.value, color = Rgba(0.47f, 0.87f, 0.88f), scale = 2f)
                                text(210f, 22f, "RECTS ${commandCount.value}", color = muted, scale = 2f)
                            }
                        }
                        row(660f, 58f, spacing = 10f) {
                            button(148f, 54f, accent, label = "ADD", labelScale = 3f) {
                                queued.update { (it + 1).coerceAtMost(99) }
                            }
                            button(148f, 54f, Rgba(0.32f, 0.39f, 0.47f), label = "REMOVE", labelScale = 3f) {
                                queued.update { (it - 1).coerceAtLeast(0) }
                            }
                            button(148f, 54f, Rgba(0.39f, 0.36f, 0.55f), label = if (paused.value) "RESUME" else "HOLD", labelScale = 3f) {
                                paused.update { !it }
                            }
                            button(148f, 54f, Rgba(0.30f, 0.34f, 0.41f), label = "RESET", labelScale = 3f) {
                                queued.value = 3
                                paused.value = false
                                compact.value = false
                                spinning.value = true
                                rotation.value = 0f
                            }
                        }
                    }
                }
                row(928f, 44f, background = panel, padding = 12f, spacing = 22f) {
                    text(320f, 22f, "SAME SCENE ALL BACKENDS", color = muted, scale = 2f)
                    text(560f, 22f, meter.display.value, color = ink, scale = 2f)
                }
            }
        }
    }
}
