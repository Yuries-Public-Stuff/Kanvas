package dev.yurie.display.probe

import dev.yurie.display.nativebridge.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val version = kd_abi_version()
    check(version == KD_ABI_VERSION) { "Unsupported native bridge ABI: $version" }

    memScoped {
        val probe = alloc<kd_vulkan_probe>()
        val status = kd_probe_vulkan(probe.ptr)
        println("Kotlin Display native Vulkan probe")
        println("status=$status")
        println("abi=${probe.abi_version}")
        println("instanceApiVersion=${probe.instance_api_version}")
        println("physicalDevices=${probe.physical_device_count}")
        println("graphicsDevices=${probe.graphics_device_count}")
        println("nativeResult=${probe.native_result}")
    }
}
