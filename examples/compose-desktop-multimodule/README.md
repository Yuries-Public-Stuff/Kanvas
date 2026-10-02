# Compose Desktop multi-module

This example shows the required Kanvas layout for a repository with multiple Gradle modules.

Kanvas is applied to the **root project**:

~~~kotlin
plugins {
    id("org.yurie.kanvas")
}

kanvas {
    target = ":desktop"
    backend = "auto"
}
~~~

The actual Compose application remains in `:desktop`.

Run:

~~~bash
gradle kanvasDoctor
gradle kanvasCompatibility
gradle kanvasBuild
~~~

Launch manually with:

~~~bash
gradle kanvasRun
~~~
