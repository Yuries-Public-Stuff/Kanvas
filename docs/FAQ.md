# FAQ

## What is Kanvas?

Kanvas is a Gradle plugin plus a native rendering runtime for Kotlin/JVM desktop applications.

Its main renderer-takeover target today is Compose Desktop.

## Does Kanvas replace Skia?

Not completely today.

For supported Compose Desktop drawing, Kanvas intercepts the normal Skia/Skiko paint path and presents that work through its own native backend.

Unsupported and compatibility paths still exist while rendering coverage is being completed.

## Does it work with any Kotlin app?

The **Gradle plugin** is intended to be usable from normal Kotlin/JVM repositories without requiring a specific project layout.

GPU takeover still requires a supported desktop rendering path. Today that primarily means Compose Desktop / Skiko.

A plain console Kotlin app can use the Gradle integration, but there is no graphics surface for Kanvas to replace.

## Do I need to rewrite my Compose UI?

No. Avoiding that is one of the main project goals.

The normal integration changes the Gradle build, not the application's Compose UI source.

## Do I need Vulkan installed?

Not on every platform.

Use `backend = "auto"` unless you are intentionally testing a specific backend.

macOS uses Metal for the current native renderer path. Linux uses Vulkan. Windows supports Vulkan, Direct3D 9, and OpenGL, with GDI retained as a software/reference path.

## Is GDI a GPU backend?

No.

GDI is a Windows software/reference path and is kept for comparison and development.

## Does Kanvas install compilers or SDKs?

No.

The host machine needs its normal JDK/native build prerequisites. `kanvasDoctor` checks selected tasks, JDK/JNI headers, Gradle, native tools, platform prerequisites, backend availability, runtime artifacts, and the detected Compose/Skiko classpath, then fails when it finds blocking problems.

## Why does Kanvas build native code locally?

The runtime connects to operating-system windowing and native graphics APIs. Building locally lets Kanvas produce the correct runtime for the host platform.

## Does Kanvas support ARM64?

ARM64 is a project target.

Apple Silicon is the main ARM64 path today. Broader ARM64 runtime verification is still required before making blanket support claims.

## Is Kanvas production ready?

No. It is pre-release software.

Real applications can be used for development and compatibility testing, but rendering parity and cross-platform hardware verification are still in progress.

## What should I do when something renders incorrectly?

Run:

~~~bash
./gradlew kanvasDoctor
./gradlew kanvasCompatibility
~~~

Then check `build/kanvas/renderer-audit.log` and read [Troubleshooting](TROUBLESHOOTING.md).

When filing an issue, share the affected repository when possible.

## What does strict mode do?

Strict mode turns unsupported primary-frame rendering into a clear compatibility failure.

It is useful when developing Kanvas support because unsupported operations cannot quietly hide behind fallback behavior.

## Can Kanvas package my application?

Compose Desktop packaging integration exists, but it is still pre-release and should be tested on the exact OS/architecture you plan to distribute.
