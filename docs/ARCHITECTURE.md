# Architecture

This page is for contributors and curious users. You do not need to understand it to use Kanvas.

## The short version

Kanvas connects a normal Kotlin desktop application to a native rendering runtime.

~~~text
Kotlin application
      ↓
Gradle + runtime integration
      ↓
drawing capture
      ↓
Kanvas renderer model
      ↓
native C runtime
      ↓
Metal / Vulkan / OpenGL / Direct3D
~~~

The application should not need to know which native graphics API is underneath it.

## Major pieces

### Gradle plugin

`gradle-plugin/` is the normal user entry point.

It provides setup checks, runtime builds, application build/run integration, packaging, and configuration.

### Java instrumentation agent

`integration-agent/` attaches to the JVM rendering path.

For Compose Desktop, it intercepts supported Skia/Skiko operations and forwards the work into Kanvas.

### Kotlin renderer

`renderer/` contains renderer-side Kotlin code and shared rendering contracts.

### Compose bridge

`compose-bridge/` contains direct Compose integration work and scene-capture support. It is related to the instrumentation-agent takeover path, but not every runtime flow passes linearly through both components.

### Display-list model

Kanvas uses an intermediate display-list representation for drawing operations.

That means higher-level drawing code can describe things such as transforms, clips, images, text, and shapes without depending directly on Vulkan, Metal, OpenGL, or Direct3D.

The native backend receives a validated rendering model instead of every Kotlin layer talking directly to a graphics API.

### Native runtime

`native/` contains the C runtime, JNI bridge, native window/input integration, and graphics backends.

Platform work is kept here when it depends on operating-system APIs or native graphics libraries.

### External-project adapter

`integration/` plus the root `kanvas` / `kanvas.ps1` wrappers let Kanvas be tested against repositories that have not permanently applied the Gradle plugin.

## Why the layers exist

The separation gives Kanvas room to:

- support multiple graphics APIs
- test rendering logic separately from a specific backend
- keep normal Kotlin/Compose code independent from native API details
- add platform-specific behavior without spreading it through the entire project
- validate captured operations before they reach native rendering code

## Renderer takeover

The current Compose Desktop path keeps the original application host alive and attaches Kanvas rendering to it.

Kanvas does not require every target application to replace its windowing model.

Exact attachment details differ between Windows, Linux, and macOS and are intentionally kept out of the normal user setup docs.

## Verification

Native rendering code is platform-dependent. A successful compile does not prove that a renderer works correctly on real hardware.

Changes involving presentation, GPU APIs, window attachment, DPI, input, or drivers should be tested on the target platform when possible.

The repository's [TODO](../TODO.md) distinguishes implemented work from hardware verification that is still pending.
