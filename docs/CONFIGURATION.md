# Configuration

Apply `org.yurie.kanvas` to the **root Gradle project**, then select the application module with `target`.

Most projects should start with:

~~~kotlin
kanvas {
    target = ":desktop"
    backend = "auto"
}
~~~

For a single-project application, the root target is valid:

~~~kotlin
kanvas {
    target = ":"
}
~~~

## Core options

### `target`

Selects the Gradle project that owns the application tasks.

~~~kotlin
target = ":desktop"
target = ":app"
target = ":client"
target = ":"
~~~

Kanvas normalizes the root target so `target = ":"` resolves `:build`, `:run`, and `:createDistributable` correctly.

### `backend`

Selects the native rendering backend.

~~~text
auto
vulkan
metal
opengl
d3d9
gdi
~~~

Implemented platform choices currently are:

- **macOS:** `auto`, `metal`
- **Linux:** `auto`, `vulkan`
- **Windows:** `auto`, `vulkan`, `d3d9`, `opengl`, `gdi`

`auto` currently means Metal on macOS, Vulkan on Linux, and Vulkan → D3D9 → OpenGL on Windows. GDI is a software/reference path.

### `strictRenderer`

~~~kotlin
strictRenderer = false
~~~

When enabled, unsupported primary-frame rendering operations fail clearly instead of quietly escaping the Kanvas takeover path. Use it when developing or verifying renderer coverage.

### `takeover`

~~~kotlin
takeover = true
~~~

Controls whether the runtime attempts to take over supported desktop rendering. Set it to `false` only when debugging capture/instrumentation without native presentation.

### `capture`

~~~kotlin
capture = true
~~~

Enables Compose/Skia capture support and the `build/kanvas/compose.kdcap` output path used by the integration layer. Disable it only when specifically debugging without capture.

### `audit`

~~~kotlin
audit = true
~~~

Enables renderer audit output:

~~~text
build/kanvas/renderer-audit.log
~~~

## Task selection

Kanvas attempts to detect normal application tasks automatically.

You can override them:

~~~kotlin
kanvas {
    buildTask = ":desktop:build"
    runTask = ":desktop:run"
    packageTask = ":desktop:createDistributable"
}
~~~

Leave an override empty to use auto-detection.

`runTask` must resolve to a JVM `JavaExec` task for Kanvas to inject the Java agent.

## Runtime build

~~~kotlin
kanvas {
    runtimeBuildType = "Release"
    autoBuildRuntime = true
}
~~~

Supported native build types are:

~~~text
Debug
Release
RelWithDebInfo
MinSizeRel
~~~

With `autoBuildRuntime = true`, Kanvas builds the native runtime and Java agent before run/build/package tasks that require them.

Set `autoBuildRuntime = false` when you intentionally manage runtime artifacts yourself or only want to exercise Gradle integration.

## Local runtime overrides

### `home`

~~~kotlin
home = "/path/to/Kanvas"
~~~

Points Kanvas at an existing source checkout. The directory must contain at least `native/` and `integration-agent/`.

You can also set:

~~~text
-Dkanvas.home=/path/to/Kanvas
KANVAS_HOME=/path/to/Kanvas
~~~

### `agentJar`

~~~kotlin
agentJar = "/path/to/kanvas-agent.jar"
~~~

Overrides the Java instrumentation agent used by `kanvasRun` and packaging.

### `nativeLibrary`

~~~kotlin
nativeLibrary = "/path/to/libkanvas_native.so"
~~~

Overrides the native runtime library. The filename is platform-specific.

When both `agentJar` and `nativeLibrary` are supplied with `autoBuildRuntime = false`, Kanvas can run/package without resolving a source checkout. The Gradle plugin itself carries the project license and third-party notices so packaged runtime files still receive them.

## Runtime source

When no local `home` is available, Kanvas can provision its runtime source:

~~~kotlin
kanvas {
    sourceUrl = "https://github.com/Yuries-Public-Stuff/Kanvas.git"
    sourceRef = ""
}
~~~

### `sourceUrl`

Git repository used to provision Kanvas source.

### `sourceRef`

Git ref to fetch.

When empty:

- a released plugin version resolves the matching `vX.Y.Z` tag
- snapshot/development builds resolve `main`

A resolved checkout is cached under the Gradle user home.

## Diagnostics

### `kanvasDoctor`

Performs the blocking environment/integration check. It validates or reports:

- host OS and architecture
- Gradle compatibility range
- JDK version and JNI headers
- Kanvas source/home resolution
- Git when source provisioning is needed
- CMake version
- C compiler
- Ninja/Make
- platform-specific prerequisites
- backend availability
- Vulkan loader where required
- build/run/package task selection
- whether the run task is `JavaExec`
- detected Compose/Skiko runtime JARs
- manually configured runtime artifacts

`ERROR` results make the task fail. `WARN` results are non-blocking.

### `kanvasCompatibility`

Reports the Compose/Skiko JARs on the selected run classpath and compares their names with the current development baseline.

It is deliberately **not** a renderer pass/fail test. Real compatibility still requires application testing and, for renderer claims, real hardware verification.

## Full example

~~~kotlin
kanvas {
    target = ":desktop"
    backend = "auto"

    strictRenderer = false
    takeover = true
    capture = true
    audit = true

    runtimeBuildType = "Release"
    autoBuildRuntime = true

    buildTask = ""
    runTask = ""
    packageTask = ""

    home = ""
    agentJar = ""
    nativeLibrary = ""

    sourceUrl = "https://github.com/Yuries-Public-Stuff/Kanvas.git"
    sourceRef = ""
}
~~~

## Tasks

~~~text
kanvasDoctor
kanvasCompatibility
kanvasRuntime
kanvasRefreshRuntime
kanvasBuild
kanvasRun
kanvasPackage
~~~
