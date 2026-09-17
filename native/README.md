# Native graphics bootstrap

This directory implements the first real Vulkan discovery step, **not** a rendered frame. The C ABI (`include/kotlin_display.h`) is intended to be callable from Kotlin/Native using cinterop. `kd_probe_vulkan` initializes an actual Vulkan instance, enumerates physical devices and their graphics queues, then releases every allocated resource. Graphics queue support alone does not establish presentation support or surface/swapchain compatibility.

## Requirements

CMake 3.21+, a C11 compiler and a Vulkan development SDK (headers + loader library); install a functioning Vulkan driver to discover real hardware. On Windows, use the LunarG Vulkan SDK or an equivalent SDK and configure CMake with its environment. On Linux install the distribution's Vulkan development package. The macOS Vulkan SDK typically supplies MoltenVK; native Vulkan is not built into macOS.

## Build and smoke test

```sh
cmake -S native -B native/build
cmake --build native/build --config Release
ctest --test-dir native/build -C Release --output-on-failure
```

The smoke test accepts `KD_NO_GRAPHICS_DEVICE` on headless machines. Build configuration deliberately fails when the Vulkan SDK is missing rather than pretending the probe is functional. Next: cinterop `.def`, native window, present-capable queue and swapchain, a draw command pipeline, then fallback probing based on successful backend initialization.
