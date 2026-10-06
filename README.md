<div align="center">

# Kanvas

### A native GPU rendering Gradle plugin for Kotlin desktop apps

Keep your Kotlin code. Keep your Compose UI. Apply one Gradle plugin and let Kanvas take over the rendering path.

<br>

![Kotlin](https://img.shields.io/badge/Kotlin-JVM-7F52FF?logo=kotlin&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Plugin-02303A?logo=gradle&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-17%2B-ED8B00?logo=openjdk&logoColor=white)
![macOS](https://img.shields.io/badge/macOS-Metal-111111?logo=apple&logoColor=white)
![Linux](https://img.shields.io/badge/Linux-Vulkan-FCC624?logo=linux&logoColor=black)
![Windows](https://img.shields.io/badge/Windows-Vulkan%20%7C%20OpenGL%20%7C%20D3D9-0078D4?logo=windows11&logoColor=white)
![Status](https://img.shields.io/badge/status-pre--release-yellow)

**Kanvas is a Gradle plugin that integrates a native GPU rendering runtime into Kotlin/JVM desktop applications.**

[Install](#install-the-plugin) · [Run](#build-and-run) · [Configure](#configuration) · [Platforms](#platforms-and-backends) · [Docs](docs/README.md)

</div>

---

## What Kanvas does

Kanvas is designed to be added to an existing Kotlin project rather than forcing the application into a custom engine or framework.

For a normal Compose Desktop app, the intended workflow is:

1. apply `org.yurie.kanvas`
2. point Kanvas at the application module
3. choose a renderer, or leave it on `auto`
4. run `kanvasDoctor`
5. build, run, or package through the Kanvas tasks

Your application code stays Kotlin. Your Compose UI stays Compose. Kanvas handles the renderer integration underneath it.

~~~text
Kotlin / Compose application
            |
            v
      Kanvas Gradle plugin
            |
            +--> runtime provisioning
            +--> compatibility checks
            +--> Java/Skiko interception
            +--> renderer capture/takeover
            |
            v
       native GPU runtime
            |
      +-----+-----+------+
      |     |     |      |
    Metal Vulkan OpenGL D3D9
~~~

The repository contains the plugin itself plus the implementation it installs and drives: the Java agent, renderer layer, native C/JNI runtime, Compose integration, diagnostics, packaging support, and graphics backends.

> [!WARNING]
> **Kanvas is pre-release software.** The plugin and renderer takeover path are functional, but full Compose rendering parity and complete cross-platform hardware verification are still in progress.

---

## Install the plugin

Until Kanvas is published through the Gradle Plugin Portal, use a Kanvas checkout as an included plugin build.

Clone Kanvas next to your project:

~~~bash
git clone https://github.com/Yuries-Public-Stuff/Kanvas.git
~~~

Example:

~~~text
projects/
├── Kanvas/
└── my-app/
~~~

Add Kanvas to your application's `settings.gradle.kts`:

~~~kotlin
pluginManagement {
    includeBuild("../Kanvas/gradle-plugin")
}
~~~

Then apply the plugin:

~~~kotlin
plugins {
    id("org.yurie.kanvas")
}
~~~

For a multi-module app, point Kanvas at the module that owns the desktop application:

~~~kotlin
kanvas {
    target = ":desktop"
}
~~~

If the application is in the root project, Kanvas can target the root project directly.

---

## Build and run

Start by checking the project:

~~~bash
./gradlew kanvasDoctor
./gradlew kanvasCompatibility
~~~

Then use the Kanvas lifecycle tasks:

~~~bash
./gradlew kanvasBuild
./gradlew kanvasRun
~~~

For a Compose Desktop distributable:

~~~bash
./gradlew kanvasPackage
~~~

Kanvas resolves the configured application tasks, prepares the native runtime when required, injects the renderer integration into the JVM process, and launches the app through the selected backend.

### Plugin tasks

| Task | Purpose |
| --- | --- |
| `kanvasDoctor` | Diagnose the host, JDK, native toolchain, backend, target project, application tasks, and runtime setup |
| `kanvasCompatibility` | Inspect detected Kotlin/Compose/Skiko runtime compatibility |
| `kanvasRuntime` | Build or provision the Kanvas native runtime |
| `kanvasRefreshRuntime` | Refresh the cached runtime and rebuild it |
| `kanvasBuild` | Run the configured application build task |
| `kanvasRun` | Launch the configured app with Kanvas renderer integration |
| `kanvasPackage` | Run the configured packaging/distributable task with Kanvas included |

---

## Configuration

Most applications only need:

~~~kotlin
kanvas {
    target = ":desktop"
    backend = "auto"
}
~~~

The full extension currently exposes:

~~~kotlin
kanvas {
    // Where Kanvas source/runtime artifacts come from.
    home = ""
    sourceUrl = "https://github.com/Yuries-Public-Stuff/Kanvas.git"
    sourceRef = ""

    // Renderer selection.
    backend = "auto"
    runtimeBuildType = "Release"

    // Application project/tasks.
    target = ":desktop"
    buildTask = ""
    runTask = ""
    packageTask = ""

    // Optional explicit runtime artifacts.
    agentJar = ""
    nativeLibrary = ""

    // Renderer/runtime behavior.
    strictRenderer = false
    takeover = true
    capture = true
    audit = true
    autoBuildRuntime = true
}
~~~

Normally you should let Kanvas detect the build, run, and package tasks instead of setting them manually.

For every option and task override, see **[Configuration](docs/CONFIGURATION.md)**.

---

## Renderer takeover

Kanvas is not just a launcher around the platform's normal renderer.

For supported Compose/Skiko paths, the runtime intercepts the final drawing flow, captures the frame, and sends it into the Kanvas native renderer.

The current modern Compose Desktop path includes support for Skiko picture-replay interception, allowing the final recorded Compose frame to pass through Kanvas rather than relying only on individual Canvas calls.

Renderer diagnostics can be enabled with:

~~~kotlin
kanvas {
    audit = true
    capture = true
}
~~~

For stricter development testing:

~~~kotlin
kanvas {
    strictRenderer = true
}
~~~

Strict mode is intended to expose unsupported rendering paths instead of quietly hiding them during development.

---

## Platforms and backends

| Platform | Primary backend | Additional implemented paths | Notes |
| :--- | :--- | :--- | :--- |
| **macOS** | Metal | — | Current macOS GPU path |
| **Linux** | Vulkan | — | Attached rendering currently targets X11/XWayland |
| **Windows** | Vulkan | Direct3D 9, OpenGL | GDI remains available as a software/reference path |

Backend values:

~~~text
auto
vulkan
metal
opengl
d3d9
gdi
~~~

For almost every project, start with:

~~~kotlin
kanvas {
    backend = "auto"
}
~~~

Current `auto` behavior:

- **macOS:** Metal
- **Linux:** Vulkan
- **Windows:** Vulkan, then Direct3D 9, then OpenGL, with GDI available as a reference/safety path

Backend availability still depends on the host OS, driver stack, and native toolchain.

See **[Backend Status](docs/BACKEND_STATUS.md)** for the implementation and verification matrix.

---

## Runtime requirements

Kanvas may need to build native runtime components for the host system.

Typical requirements are:

- JDK 17+
- Gradle or a Gradle wrapper
- Git
- CMake 3.21+
- a native C/C++ toolchain
- platform graphics/runtime libraries required by the selected backend

Run:

~~~bash
./gradlew kanvasDoctor
~~~

before debugging the renderer manually. Doctor checks the important host, project, runtime, and backend prerequisites and reports blocking failures separately from warnings.

Platform-specific prerequisites are documented in **[Getting Started](docs/GETTING_STARTED.md)**.

---

## Compatibility

Kanvas does not assume that every Compose or Skiko build exposes the same renderer internals.

The compatibility tooling inspects the runtime actually used by the target application and reports what Kanvas sees:

~~~bash
./gradlew kanvasCompatibility
~~~

Current source-verified renderer lines include:

- Compose Desktop 1.8.2
- Compose Desktop 1.11.1
- Skiko 0.9.4.2
- Skiko 0.144.6

Those versions represent renderer ABI lines that have been investigated by the project. A compatibility report is not a guarantee that every drawing operation or every GPU/driver combination is fully supported.

See **[Compatibility](docs/COMPATIBILITY.md)** for the full matrix and testing notes.

---

## Packaging

For Compose Desktop applications:

~~~bash
./gradlew kanvasPackage
~~~

Kanvas integrates with the detected distributable task and includes the renderer runtime needed by the packaged application.

Packaging remains pre-release. Test packaged builds on each operating system and architecture you intend to ship.

---

## Optional: test Kanvas without editing a project

The Gradle plugin is the normal integration path.

For compatibility testing, Kanvas also includes a zero-edit adapter that can inject the integration into another Gradle repository without permanently modifying that repository.

### macOS / Linux

~~~bash
./kanvas ../my-app --doctor --target :desktop
./kanvas ../my-app --compat --target :desktop
./kanvas ../my-app --run --target :desktop --backend metal
~~~

Verbose development diagnostics:

~~~bash
./kanvas ../my-app --run --target :desktop --backend metal --dev
~~~

Strict renderer testing:

~~~bash
./kanvas ../my-app --run --target :desktop --backend metal --strict-renderer
~~~

### Windows

~~~powershell
.\kanvas.ps1 ..\my-app -Mode Doctor
.\kanvas.ps1 ..\my-app -Mode Run
~~~

The adapter is mainly useful for answering:

> Can Kanvas integrate with this application before I add the plugin to its build?

---

## Diagnostics

If something fails, start with:

~~~bash
./gradlew kanvasDoctor
./gradlew kanvasCompatibility
~~~

Renderer runs can also produce audit/capture information used to verify whether Kanvas actually owns the rendering path.

Common failure categories include:

- missing native build tools
- an unsupported backend on the host platform
- an incorrectly detected application module/task
- a Compose/Skiko runtime ABI Kanvas does not recognize
- a drawing operation that is not yet handled by the renderer takeover
- missing runtime artifacts

See **[Troubleshooting](docs/TROUBLESHOOTING.md)** before opening an issue.

---

## Project status

Kanvas is now primarily a Gradle-plugin-driven integration rather than a standalone renderer experiment.

Implemented:

- [x] `org.yurie.kanvas` Gradle plugin
- [x] automatic runtime provisioning/build hooks
- [x] Doctor and compatibility tasks
- [x] configurable build/run/package task integration
- [x] Compose Desktop / Skiko interception
- [x] modern Skiko picture-replay capture path
- [x] Kotlin renderer/display-list layer
- [x] native C/JNI runtime
- [x] macOS Metal backend
- [x] Linux Vulkan backend
- [x] Windows Vulkan backend
- [x] Windows OpenGL backend
- [x] Windows Direct3D 9 backend
- [x] GDI software/reference path
- [x] packaging integration
- [x] renderer audit/capture tooling
- [x] zero-edit compatibility adapter

Still in progress:

- [ ] full Compose rendering parity
- [ ] complete native text/effect coverage
- [ ] broader GPU/driver validation
- [ ] native Wayland attached rendering
- [ ] complete ARM64 release verification
- [ ] Gradle Plugin Portal publication

The detailed implementation tracker is in **[TODO.md](TODO.md)**.

---

## Repository layout

~~~text
gradle-plugin/       org.yurie.kanvas plugin and Gradle tasks
integration-agent/   JVM instrumentation and Compose/Skiko interception
integration/         zero-edit Gradle adapter
renderer/            Kotlin renderer and display-list layer
compose-bridge/      Compose-facing renderer integration
native/              C/JNI runtime and platform graphics backends
examples/            example Gradle projects using Kanvas
docs/                setup, compatibility, architecture, and support docs
scripts/             build, verification, and maintenance tooling
~~~

The Gradle plugin is the entry point. The other modules are the runtime and implementation layers it coordinates.

---

## Documentation

- **[Getting Started](docs/GETTING_STARTED.md)** — install and apply the Gradle plugin
- **[Configuration](docs/CONFIGURATION.md)** — plugin DSL, tasks, backends, and overrides
- **[Compatibility](docs/COMPATIBILITY.md)** — Kotlin, Compose, Skiko, JDK, and platform compatibility
- **[Backend Status](docs/BACKEND_STATUS.md)** — renderer implementation status
- **[Architecture](docs/ARCHITECTURE.md)** — plugin/runtime/renderer architecture
- **[Known Limitations](docs/KNOWN_LIMITATIONS.md)** — current renderer and platform limits
- **[Troubleshooting](docs/TROUBLESHOOTING.md)** — setup and renderer diagnostics
- **[Examples](examples/README.md)** — example projects
- **[FAQ](docs/FAQ.md)** — common questions
- **[Changelog](CHANGELOG.md)** — user-facing changes
- **[Roadmap](TODO.md)** — active implementation work

---

## Contributing

See **[CONTRIBUTING.md](CONTRIBUTING.md)** for development setup and contribution requirements.

Repository-wide guidance for AI coding tools and automated contributors is in **[AI.md](AI.md)**.

When reporting renderer problems, include:

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

and attach the renderer audit output when available.

Kanvas is licensed under the **Apache License 2.0**. See **[LICENSE](LICENSE)**.

Security issues should follow **[SECURITY.md](SECURITY.md)**.

---

<div align="center">

### Apply the plugin. Keep the app. Replace the rendering path.

**[Get started](docs/GETTING_STARTED.md)** · **[Configuration](docs/CONFIGURATION.md)** · **[Roadmap](TODO.md)**

</div>
