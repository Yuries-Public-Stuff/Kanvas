plugins {
    kotlin("jvm") version "2.4.20"
    application
    id("org.yurie.kanvas")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("example.MainKt")
}

kanvas {
    target = ":"
    autoBuildRuntime = false
}
