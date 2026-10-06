@file:Suppress("DEPRECATION")

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(17)
    jvm()

    val hostOs = System.getProperty("os.name").lowercase()
    val hostArch = System.getProperty("os.arch").lowercase()
    when {
        hostOs.contains("windows") && hostArch in setOf("amd64", "x86_64") -> mingwX64()
        hostOs.contains("linux") && hostArch in setOf("amd64", "x86_64") -> linuxX64()
        hostOs.contains("mac") && hostArch in setOf("aarch64", "arm64") -> macosArm64()
        hostOs.contains("mac") && hostArch in setOf("amd64", "x86_64") -> macosX64()
    }

    sourceSets {
        val nativeMain = create("nativeMain") {
            dependsOn(commonMain.get())
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    val nativeMainSourceSet = sourceSets.getByName("nativeMain")
    val nativeLibraryDir = "${rootProject.projectDir}/native/build"

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main") {
            defaultSourceSet.dependsOn(nativeMainSourceSet)
            cinterops {
                create("kotlin_display") {
                    definitionFile.set(project.file("src/nativeInterop/cinterop/kotlin_display.def"))
                    compilerOpts("-I${rootProject.projectDir}/native/include")
                }
            }
        }
        binaries.executable {
            entryPoint = "dev.yurie.display.demo.main"
            linkerOpts(
                "-L$nativeLibraryDir",
                "-L$nativeLibraryDir/Debug",
                "-L$nativeLibraryDir/Release",
                "-lkanvas_native"
            )
        }
        binaries.executable("captureReplay") {
            entryPoint = "dev.yurie.display.replay.main"
            linkerOpts(
                "-L$nativeLibraryDir",
                "-L$nativeLibraryDir/Debug",
                "-L$nativeLibraryDir/Release",
                "-lkanvas_native"
            )
        }
    }
}
