# AI.md

This file describes how AI coding tools and automated contributors should work in this repository.

## Project goal

Kanvas is a Gradle plugin and rendering runtime for Kotlin/JVM desktop applications.

The project should remain usable with normal Kotlin repositories. Compose Desktop is the main renderer-takeover target today, but Gradle integration should not assume every Kotlin project has the same module names, task names, or repository layout.

Do not redesign the project around one sample application.

## Contributing

Contributions must have both:

- a related issue describing the bug, change, or requested feature
- a merge request linked to that issue

Do not submit code changes without a related issue. Keep each merge request focused on the issue it resolves.

## Important project rules

- Keep Kanvas usable from ordinary Gradle/Kotlin repositories.
- Do not require an application to be rewritten around Kanvas.
- Keep platform-specific code isolated where practical.
- Do not silently claim hardware verification that has not happened.
- A committed implementation is not the same thing as a verified implementation.
- Keep source comments short and useful.
- Preserve compatibility aliases unless intentionally removing them.
- Prefer small, testable changes over broad rewrites.

## Major components

~~~text
gradle-plugin/       Gradle plugin and user-facing tasks
integration-agent/   Java instrumentation and Skia/Skiko interception
renderer/            Kotlin renderer-side logic
compose-bridge/      Compose integration and capture work
native/              C runtime, JNI, windows, input, native GPU backends
integration/         zero-edit Gradle adapter for external projects
scripts/             build, test, benchmark, and maintenance tools
docs/                user and contributor documentation
~~~

## Runtime flow

At a high level:

~~~text
Kotlin / Compose application
        ↓
Kanvas Gradle/runtime integration
        ↓
drawing capture
        ↓
Kanvas display commands
        ↓
native runtime
        ↓
selected graphics backend
~~~

The renderer uses an intermediate display-list model so higher-level rendering code does not depend directly on Metal, Vulkan, OpenGL, or Direct3D.

## User-facing behavior

The plugin ID is:

~~~text
org.yurie.kanvas
~~~

Important tasks include:

~~~text
kanvasDoctor
kanvasCompatibility
kanvasRuntime
kanvasRefreshRuntime
kanvasBuild
kanvasRun
kanvasPackage
~~~

The zero-edit wrappers are:

~~~text
./kanvas
.\kanvas.ps1
~~~

Legacy `kd` wrappers and older `kotlin.display.*` properties are compatibility paths. Do not casually remove them.

## Testing expectations

Run the narrowest relevant tests first.

### Gradle plugin

~~~bash
gradle -p gradle-plugin test
~~~

### Java instrumentation agent

~~~bash
gradle -p integration-agent test
~~~

### Full local release check

Linux / macOS:

~~~bash
./scripts/check-release.sh
~~~

Windows:

~~~powershell
.\scripts\check-release.ps1
~~~

Native graphics behavior must also be verified on the target operating system and hardware when the change depends on a real graphics API, window system, driver, DPI behavior, or input stack.

If that hardware test was not run, say so.

## Platform notes

### macOS

Metal is the primary native path. Attached Compose rendering uses the existing desktop host and a Metal-backed layer.

### Linux

Vulkan is the primary path. Attached rendering currently expects X11 or XWayland.

### Windows

Vulkan, OpenGL, and Direct3D 9 are supported renderer paths. GDI exists as a software/reference path and should not be described as GPU acceleration.

## Documentation changes

When user-facing behavior changes:

1. update `README.md` if it affects normal setup or usage
2. update the relevant file under `docs/`
3. update `TODO.md` only when implementation or verification state actually changes
4. keep examples copy-pasteable

Avoid turning the README into an internal architecture document. Entry-level setup belongs near the top; internals belong in `docs/ARCHITECTURE.md`.

## Merge requests and issues

Use the repository templates under `.github/`.

Bug reports involving rendering should include:

- operating system
- CPU architecture
- GPU
- selected backend
- JDK
- Kotlin version
- Compose Desktop version
- Skiko version if known
- Kanvas commit/version
- `renderer-audit.log` when available

When possible, the related issue should include the affected repository or a minimal project that reproduces the same functionality.

## Before committing

Check that:

- the change does not hardcode one user's local path
- the change does not assume every target module is named `desktop`
- examples work from a normal Gradle repository layout
- backend-specific changes do not break unrelated platforms
- documentation matches current behavior
- the merge request clearly states what was and was not tested
