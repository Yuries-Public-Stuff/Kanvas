# Compose Desktop basic

This is a deliberately small single-module Compose Desktop project using Kanvas.

The important parts are:

~~~kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../../gradle-plugin")
}
~~~

and:

~~~kotlin
plugins {
    id("org.yurie.kanvas")
}

kanvas {
    target = ":"
    backend = "auto"
}
~~~

Because this example lives inside the Kanvas repository, the plugin path points back to `../../gradle-plugin`.

If you copy the example into another repository, update that path to point at your Kanvas checkout.

Run:

~~~bash
gradle kanvasDoctor
gradle kanvasCompatibility
gradle kanvasRun
~~~

Use a specific backend only when testing one intentionally.
