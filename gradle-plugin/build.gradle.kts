plugins {
    id("com.gradle.plugin-publish") version "2.2.1"
    kotlin("jvm") version "2.4.20"
}

group = "org.yurie.kanvas"
version = providers.gradleProperty("kanvasVersion").orElse("0.1.0-SNAPSHOT").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

gradlePlugin {
    website.set("https://yurie.org")
    vcsUrl.set("https://github.com/Yur-ie/kotlin-display-but-fucked-and-suicidal")

    plugins {
        create("kanvas") {
            id = "org.yurie.kanvas"
            implementationClass = "org.yurie.kanvas.gradle.KanvasPlugin"
            displayName = "Kanvas"
            description = "Native GPU rendering integration for Kotlin/JVM desktop apps."
            tags.set(
                listOf(
                    "kotlin",
                    "compose",
                    "desktop",
                    "gpu",
                    "rendering",
                    "vulkan",
                    "metal"
                )
            )
        }
    }
}

publishing {
    repositories {
        maven {
            name = "kanvasBuild"
            url = layout.buildDirectory.dir("repo").get().asFile.toURI()
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Kanvas Gradle Plugin",
            "Implementation-Version" to project.version.toString()
        )
    }
}

dependencies {
    implementation(gradleApi())

    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}
