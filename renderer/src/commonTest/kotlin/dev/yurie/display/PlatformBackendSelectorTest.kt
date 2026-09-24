package dev.yurie.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformBackendSelectorTest {
    @Test fun windowsUsesVulkanFirst() {
        val available = BackendAvailability(vulkan = true, gdi = true, openGl = true, direct3d12 = true)
        assertEquals(GraphicsApi.VULKAN, PlatformBackendSelector.select(HostPlatform.WINDOWS, available))
    }

    @Test fun windowsCanUseDirect3d() {
        val available = BackendAvailability(direct3d12 = true)
        assertEquals(GraphicsApi.DIRECT3D12, PlatformBackendSelector.select(HostPlatform.WINDOWS, available))
    }

    @Test fun windowsCanUseGdiWhenRequestedOrAsFallback() {
        val available = BackendAvailability(gdi = true)
        assertEquals(GraphicsApi.GDI, PlatformBackendSelector.select(HostPlatform.WINDOWS, available))
        assertEquals(GraphicsApi.GDI,
            PlatformBackendSelector.select(HostPlatform.WINDOWS, BackendAvailability(vulkan = true, gdi = true), GraphicsApi.GDI))
    }

    @Test fun linuxNeverUsesWindowsOnlyBackends() {
        val available = BackendAvailability(openGl = true, gdi = true, direct3d12 = true)
        assertEquals(GraphicsApi.OPENGL, PlatformBackendSelector.select(HostPlatform.LINUX, available))
        assertFailsWith<IllegalStateException> {
            PlatformBackendSelector.select(HostPlatform.LINUX, available, GraphicsApi.DIRECT3D12)
        }
        assertFailsWith<IllegalStateException> {
            PlatformBackendSelector.select(HostPlatform.LINUX, available, GraphicsApi.GDI)
        }
    }

    @Test fun macPrefersMetalWhenAvailable() {
        val available = BackendAvailability(metal = true, vulkan = true, openGl = true)
        assertEquals(GraphicsApi.METAL, PlatformBackendSelector.select(HostPlatform.MACOS, available))
    }

    @Test fun macNeverUsesWindowsOnlyBackends() {
        assertFailsWith<IllegalStateException> {
            PlatformBackendSelector.select(HostPlatform.MACOS, BackendAvailability(direct3d12 = true))
        }
        assertFailsWith<IllegalStateException> {
            PlatformBackendSelector.select(HostPlatform.MACOS, BackendAvailability(gdi = true))
        }
    }
}
