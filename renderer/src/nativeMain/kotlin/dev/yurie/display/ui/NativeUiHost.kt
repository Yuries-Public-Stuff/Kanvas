package dev.yurie.display.ui

import dev.yurie.display.NativeGpuRenderer

fun NativeGpuRenderer.dispatchPointerDown(host: UiSceneHost, width: Int, height: Int): Int {
    host.frame(width, height)
    return dispatchPointerDown { x, y ->
        host.frame(width, height)
        host.click(x, y)
    }
}
