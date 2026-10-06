# Getting Started

This guide is for someone who already has a Kotlin repository and wants to try Kanvas.

## What Kanvas expects

Kanvas is built as Gradle tooling, so your project should use Gradle or a Gradle wrapper.

Compose Desktop is the main rendering-takeover target. Other Kotlin/JVM projects can still use the Gradle tooling, but renderer takeover depends on there being a supported desktop rendering path to attach to.

## 1. Install prerequisites

All platforms need:

- JDK 17 or newer
- Git
- Gradle or the project's Gradle wrapper
- CMake
- Ninja or Make
- a native compiler/toolchain

### Windows

Use 64-bit Windows with MinGW-w64 GCC available to the build.

### Linux

Install X11 development libraries plus the Vulkan loader/runtime and a working Vulkan-capable GPU driver. Vulkan SDK headers are optional: when a loader is available but SDK headers are missing, the native build can fetch the pinned Vulkan-Headers source automatically.

### macOS

Install Xcode Command Line Tools:

~~~bash
xcode-select --install
~~~

The Metal SDK comes from the macOS/Xcode toolchain.

## 2. Put Kanvas beside your project

Example:

~~~text
projects/
├── Kanvas/
└── my-app/
~~~

If you are using the public repository:

~~~bash
git clone https://github.com/Yuries-Public-Stuff/Kanvas.git
~~~

## 3. Add the plugin build

In your application's `settings.gradle.kts`:

~~~kotlin
pluginManagement {
    includeBuild("../Kanvas/gradle-plugin")
}
~~~

If the relative path is different on your machine, change only that path.

## 4. Apply Kanvas

In the repository's root `build.gradle.kts`:

~~~kotlin
plugins {
    id("org.yurie.kanvas")
}

kanvas {
    target = ":desktop"
}
~~~

The Kanvas plugin is applied to the root project. `:desktop` is only the target module example. If your application module has a different Gradle path, use that path instead.

## 5. Check the machine and project

Run:

~~~bash
./gradlew kanvasDoctor
./gradlew kanvasCompatibility
~~~

`kanvasDoctor` performs the full environment/integration check. Fix every `ERROR` item before troubleshooting renderer behavior. `WARN` items are non-blocking but should still be reviewed.

## 6. Run the application

~~~bash
./gradlew kanvasRun
~~~

Kanvas builds its runtime first by default.

If you only want to build:

~~~bash
./gradlew kanvasBuild
~~~

## Backend selection

Start with:

~~~kotlin
kanvas {
    backend = "auto"
}
~~~

You can also choose a backend directly:

~~~kotlin
kanvas {
    backend = "metal"
}
~~~

Available names are:

~~~text
auto
vulkan
metal
opengl
d3d9
gdi
~~~

Not every backend is available on every operating system. Current implemented choices are Metal on macOS, Vulkan on Linux, and Vulkan/Direct3D 9/OpenGL/GDI on Windows.

## Try Kanvas without editing the target project

From the Kanvas checkout:

### Linux / macOS

~~~bash
./kanvas ../my-app --build
./kanvas ../my-app --run
~~~

### Windows

~~~powershell
.\kanvas.ps1 ..\my-app -Mode Build
.\kanvas.ps1 ..\my-app -Mode Run
~~~

If auto-detection chooses the wrong module, specify it:

~~~bash
./kanvas ../my-app --run --target :desktop
~~~

You can also select the backend:

~~~bash
./kanvas ../my-app --run --target :desktop --backend metal
~~~

## Packaging

For a Compose Desktop application:

~~~bash
./gradlew kanvasPackage
~~~

This builds the detected Compose distributable and embeds the Kanvas runtime.

If Kanvas cannot determine the package task, set it explicitly in the plugin configuration. See [Configuration](CONFIGURATION.md).

## More information

- [Configuration](CONFIGURATION.md)
- [Compatibility](COMPATIBILITY.md)
- [Known Limitations](KNOWN_LIMITATIONS.md)
- [Troubleshooting](TROUBLESHOOTING.md)
- [Examples](../examples/README.md)
- [Architecture](ARCHITECTURE.md)
