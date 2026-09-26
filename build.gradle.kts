plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}


tasks.register("testAllJvm") {
    group = "verification"
    description = "Runs all JVM/Kotlin unit tests and compiles the demo."
    dependsOn(
        ":renderer:jvmTest",
        ":compose-bridge:test",
        ":integration-agent:test",
        ":compose-gpu-demo:classes",
    )
}
