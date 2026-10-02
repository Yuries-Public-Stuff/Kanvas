# Dependencies and Licenses

This is a lightweight dependency/license inventory for the dependencies Kanvas directly declares or fetches.

It is not a replacement for a release-time SBOM or legal review.

## Runtime / bundled dependencies

### OW2 ASM 9.7.1

Declared by `integration-agent`:

~~~text
org.ow2.asm:asm:9.7.1
org.ow2.asm:asm-commons:9.7.1
~~~

License: **BSD-3-Clause**

The integration-agent JAR expands its runtime classpath into `kanvas-agent.jar`, so ASM is redistributed with that artifact.

Its notice must remain available with distributed Kanvas agent binaries. See [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

### Vulkan-Headers v1.4.309

When a system Vulkan loader exists but SDK headers are unavailable, the native CMake build can fetch:

~~~text
KhronosGroup/Vulkan-Headers v1.4.309
~~~

Vulkan headers use SPDX licensing including **Apache-2.0 OR MIT** on the generated headers used here.

The headers are build inputs; Kanvas links against the host Vulkan loader.

## Compile/test ecosystem

These dependencies are used to compile or test Kanvas and are not automatically equivalent to bundled runtime code.

| Dependency | Pinned version | Role | License |
| --- | --- | --- | --- |
| Kotlin Gradle/plugin ecosystem | 2.4.20 | build/compiler | Apache-2.0 |
| Compose Multiplatform/Desktop | 1.8.2 | bridge compile/test and examples | Apache-2.0 for JetBrains-owned code; Compose also documents third-party components separately |
| Skiko runtime | 0.9.4.2 | Compose bridge tests/runtime target | Apache-2.0 |
| JUnit Jupiter | 5.11.4 | tests | EPL-2.0 |
| JUnit Platform Launcher | 1.11.4 | tests | EPL-2.0 |
| Gradle Plugin Publish plugin | 2.2.1 | publication build tooling | build-time only; review current Gradle distribution/portal terms before release |

## System libraries and SDKs

Kanvas also uses platform APIs supplied by the operating system or developer SDK:

- Windows: Win32/User32, GDI, OpenGL, Direct3D 9, IMM
- macOS: Cocoa, QuartzCore, Metal
- Linux: X11
- cross-platform: JNI/JAWT
- Vulkan loader when Vulkan is enabled

These are not vendored into this repository by the Gradle dependency declarations above.

## Release rule

Before a release:

1. inspect every new direct runtime dependency
2. determine whether it is bundled, linked dynamically, fetched only for building, or test-only
3. record its version and license
4. update `THIRD_PARTY_NOTICES.md` when redistribution requires notices
5. review target application packaging separately because the application's own dependencies may also be redistributed

See [Release Checklist](RELEASE_CHECKLIST.md).
