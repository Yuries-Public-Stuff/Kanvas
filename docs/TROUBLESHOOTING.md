# Troubleshooting

Start with these two commands:

~~~bash
./gradlew kanvasDoctor
./gradlew kanvasCompatibility
~~~

`kanvasDoctor` is the blocking environment/integration diagnostic. `kanvasCompatibility` records the Compose/Skiko runtime JARs selected for the app; it does not certify renderer compatibility.

## Kanvas cannot find my application module

Set the target explicitly:

~~~kotlin
kanvas {
    target = ":desktop"
}
~~~

Replace `:desktop` with the actual Gradle path of your application module.

To inspect available projects:

~~~bash
./gradlew projects
~~~

## Native runtime will not build

Check that the host has:

- JDK 17+
- CMake
- Ninja or Make
- a native compiler
- the platform-specific SDK/development libraries

Then run `kanvasDoctor` again.

### Windows

Confirm MinGW-w64 GCC is installed and available on `PATH`.

### macOS

Confirm Xcode Command Line Tools are installed:

~~~bash
xcode-select -p
~~~

### Linux

Confirm X11 development packages and the Vulkan loader/runtime are installed. Vulkan SDK headers are optional because Kanvas can fetch its pinned Vulkan-Headers source when needed.

## The app starts but nothing renders

Run `kanvasDoctor` first and resolve every `ERROR`, then run `kanvasCompatibility` to record the selected Compose/Skiko versions.

Then inspect:

~~~text
build/kanvas/renderer-audit.log
~~~

Try the default backend first:

~~~kotlin
kanvas {
    backend = "auto"
}
~~~

If you selected a backend manually, verify that backend exists on the current operating system.

## The app flashes, clears, or renders only part of the UI

This usually needs renderer-level debugging.

Include these details in a bug report:

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

Also attach:

~~~text
build/kanvas/renderer-audit.log
~~~

If a `compose.kdcap` file was generated and is safe to share, mention that as well.

## Strict mode stops the application

Strict mode is doing its job: Kanvas encountered a primary-frame operation it does not currently support.

Disable it for normal compatibility testing:

~~~kotlin
kanvas {
    strictRenderer = false
}
~~~

Keep it enabled when the goal is to find missing renderer coverage.

## Zero-edit adapter chooses the wrong task

Specify the target:

~~~bash
./kanvas ../my-app --run --target :desktop
~~~

If needed, use the Gradle plugin directly and configure `buildTask` or `runTask` explicitly.

## Packaging fails

First confirm the application itself can create a Compose Desktop distributable without Kanvas.

Then try:

~~~bash
./gradlew kanvasPackage
~~~

If auto-detection fails, configure:

~~~kotlin
kanvas {
    packageTask = ":desktop:createDistributable"
}
~~~

## Before opening an issue

If possible, include a link to the repository that reproduces the problem.

If you cannot share the repository, provide a minimal test project or clear reproduction that lets us exercise the same functionality and hit the same failure.

Please also include:

- the command you ran
- the complete error message
- operating system and architecture
- selected backend
- relevant Kotlin/Compose versions
- Kanvas commit/version
- `renderer-audit.log` for rendering problems

Use the GitHub bug-report form so the required information is not missed.
