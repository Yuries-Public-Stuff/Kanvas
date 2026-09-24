package dev.yurie.display

enum class HostPlatform { WINDOWS, LINUX, MACOS }

object PlatformBackendSelector {
    fun select(
        platform: HostPlatform,
        availability: BackendAvailability,
        preferred: GraphicsApi? = null,
        allowRecording: Boolean = false,
    ): GraphicsApi {
        val windows = platform == HostPlatform.WINDOWS
        val macos = platform == HostPlatform.MACOS
        val windowsOnly = setOf(
            GraphicsApi.GDI, GraphicsApi.DIRECT3D12, GraphicsApi.DIRECT3D11,
            GraphicsApi.DIRECT3D10, GraphicsApi.DIRECT3D9EX, GraphicsApi.DIRECT3D9,
        )
        if (!windows && preferred in windowsOnly) error("GDI and Direct3D are Windows-only")
        if (!macos && preferred == GraphicsApi.METAL) error("Metal is macOS-only")

        val supported = availability.copy(
            metal = macos && availability.metal,
            gdi = windows && availability.gdi,
            direct3d12 = windows && availability.direct3d12,
            direct3d11 = windows && availability.direct3d11,
            direct3d10 = windows && availability.direct3d10,
            direct3d9Ex = windows && availability.direct3d9Ex,
            direct3d9 = windows && availability.direct3d9,
        )
        return BackendSelector.select(supported, preferred, allowRecording)
    }
}
