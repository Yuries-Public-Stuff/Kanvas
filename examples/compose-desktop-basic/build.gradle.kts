plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    application
    id("org.yurie.kanvas")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.compose.runtime:runtime-desktop:1.8.2")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:1.8.2")
    implementation("org.jetbrains.compose.ui:ui-desktop:1.8.2")
}

application {
    mainClass.set("example.MainKt")
}

kanvas {
    target = ":"
    backend = "auto"
}
