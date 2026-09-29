package dev.yurie.display

import cnames.structs.kd_d3d9_frame
import cnames.structs.kd_gl_frame
import cnames.structs.kd_metal_frame
import cnames.structs.kd_vk_frame
import cnames.structs.kd_window
import dev.yurie.display.nativebridge.*
import dev.yurie.display.scene.DisplayList
import dev.yurie.display.scene.DisplayListRenderer
import dev.yurie.display.scene.GpuPrimitiveLowerer
import dev.yurie.display.ui.UiScene
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
class NativeGpuRenderer(
    width: Int,
    height: Int,
    title: String,
    override val api: GraphicsApi = GraphicsApi.VULKAN,
) : Renderer, DisplayListRenderer {
    private val window: CPointer<kd_window>
    private var vulkan: CPointer<kd_vk_frame>? = null
    private var gl: CPointer<kd_gl_frame>? = null
    private var d3d9: CPointer<kd_d3d9_frame>? = null
    private var metal: CPointer<kd_metal_frame>? = null
    private var closed = false

    init {
        require(width > 0 && height > 0)
        require(api in setOf(GraphicsApi.VULKAN, GraphicsApi.METAL, GraphicsApi.GDI, GraphicsApi.OPENGL, GraphicsApi.DIRECT3D9)) {
            "Unsupported native renderer: $api"
        }
        check(kd_abi_version() == KD_ABI_VERSION) { "Native bridge ABI mismatch" }
        window = memScoped {
            val out = alloc<CPointerVar<kd_window>>()
            val status = kd_window_create(width.toUInt(), height.toUInt(), title, out.ptr)
            check(status == KD_OK && out.value != null) { "Window creation failed: $status" }
            out.value!!
        }
        try {
            initializeBackend()
        } catch (failure: Throwable) {
            disposeBackend()
            kd_window_destroy(window)
            throw failure
        }
    }

    private fun initializeBackend() {
        memScoped {
            when (api) {
                GraphicsApi.VULKAN -> {
                    val out = alloc<CPointerVar<kd_vk_frame>>()
                    val status = kd_vk_frame_create(window, out.ptr)
                    check(status == KD_OK && out.value != null) { "Vulkan initialization failed: $status" }
                    vulkan = out.value
                }
                GraphicsApi.METAL -> {
                    val out = alloc<CPointerVar<kd_metal_frame>>()
                    val status = kd_metal_frame_create(window, out.ptr)
                    check(status == KD_OK && out.value != null) { "Metal initialization failed: $status" }
                    metal = out.value
                }
                GraphicsApi.OPENGL -> {
                    val out = alloc<CPointerVar<kd_gl_frame>>()
                    val status = kd_gl_frame_create(window, out.ptr)
                    check(status == KD_OK && out.value != null) { "OpenGL initialization failed: $status" }
                    gl = out.value
                }
                GraphicsApi.DIRECT3D9 -> {
                    val out = alloc<CPointerVar<kd_d3d9_frame>>()
                    val status = kd_d3d9_frame_create(window, out.ptr)
                    check(status == KD_OK && out.value != null) { "Direct3D9 initialization failed: $status" }
                    d3d9 = out.value
                }
                GraphicsApi.GDI -> Unit
                else -> error("Unsupported native renderer: $api")
            }
        }
    }

    private fun disposeBackend() {
        kd_vk_frame_destroy(vulkan)
        vulkan = null
        kd_gl_frame_destroy(gl)
        gl = null
        kd_d3d9_frame_destroy(d3d9)
        d3d9 = null
        kd_metal_frame_destroy(metal)
        metal = null
    }

    fun poll(): Boolean {
        check(!closed) { "Renderer is closed" }
        return memScoped {
            val shouldClose = alloc<IntVar>()
            val status = kd_window_poll(window, shouldClose.ptr)
            check(status == KD_OK) { "Window polling failed: $status" }
            shouldClose.value == 0
        }
    }

    fun dispatchPointerDown(onDown: (Float, Float) -> Boolean): Int {
        check(!closed) { "Renderer is closed" }
        return memScoped {
            val event = alloc<kd_pointer_event>()
            val pending = alloc<IntVar>()
            var consumed = 0
            while (true) {
                val status = kd_window_next_pointer(window, event.ptr, pending.ptr)
                check(status == KD_OK) { "Native pointer queue failed: $status" }
                if (pending.value == 0) break
                if (event.kind == KD_POINTER_DOWN && onDown(event.x.toFloat(), event.y.toFloat())) consumed++
            }
            consumed
        }
    }

    fun dispatchPointerDown(scene: UiScene): Int = dispatchPointerDown(scene::click)

    fun size(): Pair<Int, Int> {
        check(!closed) { "Renderer is closed" }
        return memScoped {
            val width = alloc<UIntVar>()
            val height = alloc<UIntVar>()
            val status = kd_window_get_size(window, width.ptr, height.ptr)
            check(status == KD_OK) { "Window size query failed: $status" }
            width.value.toInt() to height.value.toInt()
        }
    }

    private fun submitFrame(frame: Frame): kd_status = memScoped {
        val commands = allocArray<kd_draw_command>(frame.commands.size)
        frame.commands.forEachIndexed { index, draw ->
            val native = commands[index]
            when (draw) {
                is DrawCommand.Clear -> {
                    native.kind = 1u
                    native.color.red = draw.color.red
                    native.color.green = draw.color.green
                    native.color.blue = draw.color.blue
                    native.color.alpha = draw.color.alpha
                }
                is DrawCommand.FillRect -> {
                    native.kind = 2u
                    native.x = draw.bounds.x
                    native.y = draw.bounds.y
                    native.width = draw.bounds.width
                    native.height = draw.bounds.height
                    native.color.red = draw.color.red
                    native.color.green = draw.color.green
                    native.color.blue = draw.color.blue
                    native.color.alpha = draw.color.alpha
                }
            }
        }
        when (api) {
            GraphicsApi.GDI -> kd_gdi_frame_present_commands(window, commands, frame.commands.size.toUInt())
            GraphicsApi.VULKAN -> kd_vk_frame_present_commands(vulkan, commands, frame.commands.size.toUInt())
            GraphicsApi.METAL -> kd_metal_frame_present_commands(metal, commands, frame.commands.size.toUInt())
            GraphicsApi.OPENGL -> kd_gl_frame_present_commands(gl, commands, frame.commands.size.toUInt())
            GraphicsApi.DIRECT3D9 -> kd_d3d9_frame_present_commands(d3d9, commands, frame.commands.size.toUInt())
            else -> error("Unsupported native renderer: $api")
        }
    }

    fun present(frame: Frame): Boolean {
        check(!closed) { "Renderer is closed" }
        require(frame.commands.isNotEmpty() && frame.commands.size <= KD_DRAW_MAX_COMMANDS.toInt()) {
            "A frame requires 1..$KD_DRAW_MAX_COMMANDS drawing commands"
        }
        require(frame.commands.first() is DrawCommand.Clear &&
            frame.commands.drop(1).none { it is DrawCommand.Clear }) {
            "Exactly one Clear must precede all rectangles"
        }
        var status = submitFrame(frame)
        if (status == KD_SWAPCHAIN_OUT_OF_DATE) {
            val (width, height) = size()
            if (width == 0 || height == 0) return false
            if (api == GraphicsApi.VULKAN || api == GraphicsApi.DIRECT3D9 || api == GraphicsApi.METAL) {
                disposeBackend()
                initializeBackend()
                status = submitFrame(frame)
            }
        }
        if (status == KD_SWAPCHAIN_OUT_OF_DATE) return false
        check(status == KD_OK) { "$api draw submission failed: $status" }
        return true
    }

    override fun render(frame: Frame) {
        renderSubmitted(frame)
    }

    override fun render(displayList: DisplayList) {
        val lowered = GpuPrimitiveLowerer.lower(displayList)
        require(lowered.unsupported.isEmpty()) {
            "Display list uses primitives not implemented by $api yet: " +
                lowered.unsupported.joinToString()
        }
        render(lowered.frame)
    }

    @Deprecated("Use present(frame)")
    fun renderSubmitted(frame: Frame): Boolean = present(frame)

    override fun close() {
        if (closed) return
        closed = true
        disposeBackend()
        kd_window_destroy(window)
    }
}

@Deprecated("Use NativeGpuRenderer")
typealias NativeVulkanRenderer = NativeGpuRenderer
