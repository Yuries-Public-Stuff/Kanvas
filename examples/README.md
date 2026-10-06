# Kanvas Examples

These examples are intentionally small. They show how Kanvas fits into normal Gradle/Kotlin repositories without requiring application code to depend on Kanvas internals.

## Compose Desktop basic

[compose-desktop-basic](compose-desktop-basic/) is the smallest renderer example.

It demonstrates a single-module Compose Desktop app with Kanvas applied to the root project.

Run from that directory:

~~~bash
gradle kanvasDoctor
gradle kanvasCompatibility
gradle kanvasBuild
~~~

Launch the UI manually with:

~~~bash
gradle kanvasRun
~~~

## Compose Desktop multi-module

[compose-desktop-multimodule](compose-desktop-multimodule/) shows the more common layout where the root build applies Kanvas and `:desktop` is the application module.

This is the example to copy when your repository has several modules.

## Plain Kotlin/JVM

[kotlin-jvm-basic](kotlin-jvm-basic/) proves that the Gradle plugin can be applied to a normal Kotlin/JVM application that is not Compose Desktop.

It intentionally disables automatic native runtime building because a console application has no supported graphics surface to take over.

This example demonstrates **Gradle integration**, not GPU rendering.

## Verifying examples

The examples do not ship their own Gradle wrappers. Use a system Gradle in the supported range, or set `KANVAS_GRADLE` to the Gradle executable you want the verification scripts to use.

From the Kanvas repository root:

~~~bash
./scripts/verify-examples.sh
~~~

Windows:

~~~powershell
.\scripts\verify-examples.ps1
~~~

For a true clean-clone check, use the clean-clone scripts documented in [the release checklist](../docs/RELEASE_CHECKLIST.md).

The examples use the repository's current development baseline. See [Compatibility](../docs/COMPATIBILITY.md).
