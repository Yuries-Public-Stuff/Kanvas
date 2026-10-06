# Compatibility

This page documents the **current development baseline**, not a promise that every adjacent version is unsupported.

Versions outside this table may work, but they should be treated as unverified until tested.

## Current baseline

| Component | Current baseline | Meaning |
| --- | --- | --- |
| JDK | 17+ | Kanvas build/toolchain minimum used by the project |
| Gradle | 7.6.3–9.8.0 | Kanvas Gradle-plugin tests pass through 9.8.0; upstream Kotlin compatibility may be narrower |
| Kotlin Gradle plugin | 2.4.20 | Version currently pinned by the Kanvas build/examples |
| Compose Desktop libraries | 1.8.2 | Compose version currently targeted by the bridge/examples |
| Skiko runtime | 0.9.4.2 | Runtime used by current Compose bridge tests |
| Gradle Plugin Publish plugin | 2.2.1 | Build-time publication tooling |

## Compose / Skiko policy

Kanvas intercepts implementation details in the Compose Desktop / Skiko rendering path. Binary changes can matter even when application source code is unchanged.

`kanvasCompatibility` reports which Compose/Skiko JARs are present on the selected run task. It does **not** prove renderer compatibility.

Before claiming support for a new Compose/Skiko combination:

1. run `kanvasCompatibility` and record the detected versions
2. run the integration-agent tests
3. run the Compose bridge tests
4. launch at least one real Compose Desktop application
5. verify strict mode does not expose unexpected renderer escapes
6. record the tested combination

## JDK policy

JDK 17 is the current minimum toolchain target used by the project.

Newer JDKs should be tested before being listed as verified. A successful Gradle configuration alone is not enough to claim runtime verification.

## Gradle policy

Kanvas Gradle-plugin tests now pass on Gradle 9.8.0. The Doctor accepts 7.6.3 through 9.8.0 as the current Kanvas-tested range and reports newer versions as unverified. This is a Kanvas test result, not a blanket claim that every Kotlin/Compose combination is fully supported across that entire range. Kanvas currently declares Configuration Cache and Isolated Projects as unsupported until those modes are explicitly refactored and tested.

## Platform baseline

| Platform | Development target | Primary renderer |
| --- | --- | --- |
| Windows | 64-bit Windows | Vulkan / OpenGL / Direct3D 9 |
| macOS | Apple Silicon and Intel targets | Metal |
| Linux | x64 today, ARM64 target | Vulkan |

Linux attached rendering currently expects X11 or XWayland.

See [Backend Status](BACKEND_STATUS.md) for implementation and hardware-verification state.

## Reporting compatibility

If a version combination fails, include:

~~~text
JDK:
Gradle:
Kotlin:
Compose Desktop:
Skiko:
OS:
Architecture:
GPU:
Backend:
Kanvas commit/version:
~~~

Whenever possible, include the repository that reproduces the problem.
