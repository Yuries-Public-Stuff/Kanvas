plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

val skikoRuntimeTarget = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("linux") -> "linux"
        else -> error("Unsupported test OS for Skiko runtime: $os")
    }
    val archPart = when {
        arch == "amd64" || arch == "x86_64" -> "x64"
        arch == "aarch64" || arch == "arm64" -> "arm64"
        else -> error("Unsupported test architecture for Skiko runtime: $arch")
    }
    "$osPart-$archPart"
}

dependencies {
    implementation(project(":renderer"))
    compileOnly("org.jetbrains.compose.ui:ui-graphics-desktop:1.8.2")
    compileOnly("org.jetbrains.compose.ui:ui-desktop:1.8.2")
    compileOnly("org.jetbrains.compose.foundation:foundation-desktop:1.8.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.compose.ui:ui-graphics-desktop:1.8.2")
    testImplementation("org.jetbrains.compose.ui:ui-desktop:1.8.2")
    testImplementation("org.jetbrains.compose.foundation:foundation-desktop:1.8.2")
    testRuntimeOnly(
        "org.jetbrains.skiko:skiko-awt-runtime-$skikoRuntimeTarget:0.9.4.2"
    )
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
