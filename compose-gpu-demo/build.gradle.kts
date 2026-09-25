plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":compose-bridge"))
    implementation("org.jetbrains.compose.runtime:runtime-desktop:1.8.2")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:1.8.2")
    implementation("org.jetbrains.compose.ui:ui-desktop:1.8.2")
}

application {
    mainClass.set("dev.yurie.display.gpudemo.MainKt")
}
