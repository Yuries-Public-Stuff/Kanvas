# Plain Kotlin/JVM basic

This example shows that `org.yurie.kanvas` can be applied to a normal root Kotlin/JVM Gradle project without requiring Compose Desktop.

It is **not** a GPU-rendering demo. This application only prints to the console, so there is no supported desktop graphics surface for Kanvas to take over.

That is why this example uses:

~~~kotlin
kanvas {
    target = ":"
    autoBuildRuntime = false
}
~~~

Run:

~~~bash
gradle kanvasDoctor
gradle kanvasCompatibility
gradle kanvasBuild
~~~

This example is useful for checking Gradle integration separately from renderer compatibility.
