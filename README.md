<div align="center">

# Kanvas

### Native GPU rendering for Kotlin desktop apps

Use your existing Kotlin project. Keep your Compose UI. Let Kanvas handle the rendering path.

<br>

![Kotlin](https://img.shields.io/badge/Kotlin-JVM-7F52FF?logo=kotlin&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-17%2B-ED8B00?logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Plugin-02303A?logo=gradle&logoColor=white)
![macOS](https://img.shields.io/badge/macOS-Metal-111111?logo=apple&logoColor=white)
![Linux](https://img.shields.io/badge/Linux-Vulkan-FCC624?logo=linux&logoColor=black)
![Windows](https://img.shields.io/badge/Windows-Vulkan%20%7C%20OpenGL%20%7C%20D3D9-0078D4?logo=windows11&logoColor=white)
![Status](https://img.shields.io/badge/status-pre--release-yellow)

**Kanvas is an experimental Gradle plugin and rendering runtime for Kotlin/JVM desktop applications.**

[Get started](#get-started) · [Try it on an existing project](#try-it-on-an-existing-project) · [Supported platforms](#supported-platforms) · [Docs](docs/README.md) · [Roadmap](TODO.md)

</div>

---

## What is Kanvas?

Kanvas lets a Kotlin desktop application use native graphics backends without requiring the application to be rebuilt around a custom engine.

For a normal Compose Desktop project, the goal is:

1. keep your existing Kotlin and Compose code
2. add Kanvas to the build
3. choose a backend, or leave it on `auto`
4. run the application normally

Kanvas handles the rendering takeover at runtime.

> **Your Kotlin app stays Kotlin. Your Compose UI stays Compose. Kanvas handles the pixels.**

Kanvas is more than a graphics demo. The repository contains the Gradle integration, Kotlin renderer, native runtime, Compose bridge, Java agent, platform window code, diagnostics, packaging support, and native graphics backends used to make that takeover work.

> [!WARNING]
> **Kanvas is pre-release software.** It is ready for development and compatibility testing, but full rendering parity and hardware validation are still in progress.

---

## Get started

### Requirements

You need:

- **JDK 17+**
- **Gradle** or a Gradle wrapper
- **Git**
- **CMake**
- a native compiler/toolchain for your operating system

Platform-specific setup is covered in [Getting Started](docs/GETTING_STARTED.md).

### Add Kanvas to a project

Until the plugin is published to the Gradle Plugin Portal, use a local Kanvas checkout.

Clone Kanvas beside your app:

~~~bash
git clone https://github.com/Yuries-Public-Stuff/Kanvas.git
~~~

Example layout:

~~~text
projects/
├── Kanvas/
└── my-app/
~~~

In your app's `settings.gradle.kts`:

~~~kotlin
pluginManagement {
    includeBuild("../Kanvas/gradle-plugin")
}
~~~

Then apply the plugin in your root `build.gradle.kts` and point it at the desktop module:

~~~kotlin
plugins {
    id("org.yurie.kanvas")
}

kanvas {
    target = ":desktop"
}
~~~

Check your setup:

~~~bash
./gradlew kanvasDoctor
./gradlew kanvasCompatibility
~~~

Build and run:

~~~bash
./gradlew kanvasBuild
./gradlew kanvasRun
~~~

That's the normal path.

For more setup examples, backend selection, packaging, and non-Compose projects, see **[Getting Started](docs/GETTING_STARTED.md)**.

---

## Try it on an existing project

You can also test Kanvas against another Kotlin repository without permanently editing that project's build files.

### Linux / macOS

~~~bash
./kanvas ../my-app --build
./kanvas ../my-app --run
~~~

Example with an explicit target and backend:

~~~bash
./kanvas ../my-app --run --target :desktop --backend metal
~~~

### Windows

~~~powershell
.\kanvas.ps1 ..\my-app -Mode Build
.\kanvas.ps1 ..\my-app -Mode Run
~~~

This is useful when you want to answer one question first:

> **Can Kanvas run this project?**

The zero-edit adapter injects the Kanvas Gradle integration for that run without requiring permanent source changes in the target repository.

---

## Supported platforms

| Platform | Primary / implemented path | Other paths | Notes |
| :--- | :--- | :--- | :--- |
| **macOS** | Metal | — | Apple Silicon and Intel are targets |
| **Linux** | Vulkan | — | X11 or XWayland currently required for attached rendering |
| **Windows** | Vulkan | Direct3D 9, OpenGL, GDI reference | 64-bit Windows is the main development target |

Backend names:

~~~text
auto
vulkan
metal
opengl
d3d9
gdi
~~~

For most users:

~~~kotlin
kanvas {
    backend = "auto"
}
~~~

is the right place to start.

---

## Common commands

| Command | Purpose |
| --- | --- |
| `kanvasDoctor` | Diagnose configuration, toolchain, platform prerequisites, runtime artifacts, and selected app tasks |
| `kanvasCompatibility` | Report detected Compose/Skiko runtime versions against the current development baseline |
| `kanvasBuild` | Build the configured Kotlin project |
| `kanvasRun` | Run the project with Kanvas |
| `kanvasPackage` | Build a distributable with Kanvas included |
| `kanvasRuntime` | Build only the Kanvas runtime |
| `kanvasRefreshRuntime` | Refresh and rebuild the cached runtime |

Example:

~~~bash
./gradlew kanvasDoctor kanvasCompatibility
./gradlew kanvasRun
~~~

---

## Basic configuration

Most projects should start small:

~~~kotlin
kanvas {
    target = ":desktop"
    backend = "auto"
}
~~~

Useful development options:

~~~kotlin
kanvas {
    target = ":desktop"
    backend = "auto"

    strictRenderer = false
    capture = true
    audit = true
}
~~~

See **[Configuration](docs/CONFIGURATION.md)** for the full option list and examples.

---

## Packaging

For Compose Desktop projects, Kanvas can package the runtime with the application:

~~~bash
./gradlew kanvasPackage
~~~

Kanvas hooks into the detected Compose Desktop distributable task and includes the Java agent and native runtime in the generated application image.

Packaging is still pre-release. Test the generated application on the same operating system and architecture you plan to distribute.

---

## If something does not work

Start with:

~~~bash
./gradlew kanvasDoctor
./gradlew kanvasCompatibility
~~~

Then check:

~~~text
build/kanvas/renderer-audit.log
build/kanvas/compose.kdcap
~~~

The most common problems are missing native build tools, unsupported rendering operations, a wrong Gradle target, or a backend that is not available on the current platform.

See **[Troubleshooting](docs/TROUBLESHOOTING.md)** before opening an issue.

---

## Project status

The main pieces are already present:

- [x] Gradle plugin
- [x] zero-edit project adapter
- [x] Compose Desktop interception
- [x] Kotlin renderer layer
- [x] native C runtime
- [x] macOS Metal path
- [x] Linux Vulkan path
- [x] Windows Vulkan / OpenGL / Direct3D 9 paths
- [x] packaging integration
- [x] diagnostics and runtime-version reporting
- [ ] full Compose rendering parity
- [ ] complete native text and effect coverage
- [ ] full cross-platform hardware verification
- [ ] ARM64 release verification
- [ ] public Gradle Plugin Portal release

The detailed engineering tracker is in **[TODO.md](TODO.md)**.

---

## Documentation

- **[Getting Started](docs/GETTING_STARTED.md)** — install Kanvas and use it in a Kotlin repository
- **[Configuration](docs/CONFIGURATION.md)** — plugin options, backends, tasks, and overrides
- **[Troubleshooting](docs/TROUBLESHOOTING.md)** — common setup and renderer problems
- **[Known Limitations](docs/KNOWN_LIMITATIONS.md)** — current platform and renderer limitations
- **[Examples](examples/README.md)** — small projects showing Kanvas in use
- **[Compatibility](docs/COMPATIBILITY.md)** — current Kotlin, Compose, Skiko, JDK, and platform baseline
- **[Backend Status](docs/BACKEND_STATUS.md)** — implementation and verification state for each renderer
- **[FAQ](docs/FAQ.md)** — common questions about what Kanvas does and does not do
- **[Architecture](docs/ARCHITECTURE.md)** — how the major pieces fit together
- **[Support](SUPPORT.md)** — where to report bugs, security issues, and usage questions
- **[Changelog](CHANGELOG.md)** — user-facing changes
- **[Roadmap](TODO.md)** — current implementation and verification status

---

## Examples

Examples are available under **[examples/](examples/README.md)**, including a minimal Compose Desktop app, a multi-module Compose app, and a plain Kotlin/JVM Gradle integration example.

The basic Compose example shows the smallest useful setup: include the Kanvas plugin build, apply `org.yurie.kanvas`, target the application module, and run with `kanvasRun`.

---

## For contributors


Repository-wide instructions for AI coding tools and automated contributors are in **[AI.md](AI.md)**.

Kanvas is licensed under the **Apache License 2.0**. See **[LICENSE](LICENSE)**.

Security issues should follow **[SECURITY.md](SECURITY.md)**. Community expectations are in **[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)**.

When reporting renderer problems, include your:

~~~text
OS:
Architecture:
GPU:
Backend:
JDK:
Kotlin:
Compose Desktop:
Skiko:
Kanvas commit:
~~~

and attach `build/kanvas/renderer-audit.log` when available.

---

<div align="center">

### Build your Kotlin app. Let Kanvas handle the pixels.

**[Get started](docs/GETTING_STARTED.md)** · **[Docs](docs/README.md)** · **[Roadmap](TODO.md)**

</div>
