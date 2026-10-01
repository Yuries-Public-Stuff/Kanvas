package org.yurie.kanvas.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KanvasPluginTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `plugin registers tasks and detects app tasks`() {
        writeProject(
            """
            plugins {
                java
                application
                id("org.yurie.kanvas")
            }

            application {
                mainClass.set("example.Main")
            }

            kanvas {
                autoBuildRuntime = false
            }
            """.trimIndent()
        )

        val result = runner("kanvasDoctor").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":kanvasDoctor")?.outcome
        )
        assertTrue(result.output.contains("Kanvas"))
        assertTrue(result.output.contains("Build task : :build"))
        assertTrue(result.output.contains("Run task   : :run"))
    }

    @Test
    fun `kanvasBuild delegates to the selected build task`() {
        writeProject(
            """
            plugins {
                java
                id("org.yurie.kanvas")
            }

            kanvas {
                autoBuildRuntime = false
            }
            """.trimIndent()
        )

        val result = runner("kanvasBuild").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":kanvasBuild")?.outcome
        )
        assertTrue(result.task(":build") != null)
    }

    @Test
    fun `invalid backend is rejected`() {
        writeProject(
            """
            plugins {
                java
                id("org.yurie.kanvas")
            }

            kanvas {
                backend = "potato"
                autoBuildRuntime = false
            }
            """.trimIndent()
        )

        val result = runner("kanvasDoctor").buildAndFail()

        assertTrue(
            result.output.contains("Unsupported Kanvas backend 'potato'")
        )
    }


    @Test
    fun `kanvasPackage embeds runtime in Compose app image`() {
        val artifacts = File(projectDir, "kanvas-artifacts")
        artifacts.mkdirs()

        val nativeName = when {
            System.getProperty("os.name").lowercase().contains("win") ->
                "kanvas_native.dll"
            System.getProperty("os.name").lowercase().contains("mac") ->
                "libkanvas_native.dylib"
            else -> "libkanvas_native.so"
        }

        val nativeFile = File(artifacts, nativeName)
        val agentFile = File(artifacts, "kanvas-agent.jar")
        nativeFile.writeText("native")
        agentFile.writeText("agent")

        writeProject(
            """
            plugins {
                id("org.yurie.kanvas")
            }

            tasks.register("createDistributable") {
                doLast {
                    val app = layout.buildDirectory.dir(
                        "compose/binaries/main/app/Test/lib/app"
                    ).get().asFile
                    app.mkdirs()
                    file("${'$'}{app}/Test.cfg").writeText(
                        "[Application]\napp.mainmodule=test\n" +
                            "[JavaOptions]\njava-options=-Xmx512m\n"
                    )
                }
            }

            kanvas {
                autoBuildRuntime = false
                agentJar = "${agentFile.invariantSeparatorsPath}"
                nativeLibrary = "${nativeFile.invariantSeparatorsPath}"
                packageTask = ":createDistributable"
                backend = "vulkan"
                strictRenderer = true
            }
            """.trimIndent()
        )

        val result = runner("kanvasPackage").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":kanvasPackage")?.outcome
        )

        val app = File(
            projectDir,
            "build/compose/binaries/main/app/Test/lib/app"
        )
        assertTrue(File(app, "kanvas/kanvas-agent.jar").isFile)
        assertTrue(
            File(app, "kanvas/$nativeName").isFile
        )

        val cfg = File(app, "Test.cfg").readText()
        assertTrue(
            cfg.contains(
                "java-options=-javaagent:\$APPDIR/kanvas/kanvas-agent.jar"
            )
        )
        assertTrue(cfg.contains("-Dkanvas.backend=vulkan"))
        assertTrue(cfg.contains("-Dkanvas.strictRenderer=true"))
    }


    @Test
    fun `direct package task is patched too`() {
        val artifacts = File(projectDir, "kanvas-artifacts")
        artifacts.mkdirs()

        val nativeName = when {
            System.getProperty("os.name").lowercase().contains("win") ->
                "kanvas_native.dll"
            System.getProperty("os.name").lowercase().contains("mac") ->
                "libkanvas_native.dylib"
            else -> "libkanvas_native.so"
        }

        val nativeFile = File(artifacts, nativeName).apply {
            writeText("native")
        }
        val agentFile = File(artifacts, "kanvas-agent.jar").apply {
            writeText("agent")
        }

        writeProject(
            """
            plugins {
                id("org.yurie.kanvas")
            }

            tasks.register("createDistributable") {
                doLast {
                    val app = layout.buildDirectory.dir(
                        "compose/binaries/main/app/Test/lib/app"
                    ).get().asFile
                    app.mkdirs()
                    file("${'$'}{app}/Test.cfg").writeText(
                        "[JavaOptions]\njava-options=-Xmx512m\n"
                    )
                }
            }

            kanvas {
                autoBuildRuntime = false
                agentJar = "${agentFile.invariantSeparatorsPath}"
                nativeLibrary = "${nativeFile.invariantSeparatorsPath}"
                packageTask = ":createDistributable"
            }
            """.trimIndent()
        )

        val result = runner("createDistributable").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":createDistributable")?.outcome
        )

        val app = File(
            projectDir,
            "build/compose/binaries/main/app/Test/lib/app"
        )
        assertTrue(File(app, "kanvas/kanvas-agent.jar").isFile)
        assertTrue(File(app, "kanvas/$nativeName").isFile)
        assertTrue(
            File(app, "Test.cfg").readText()
                .contains("-javaagent:\$APPDIR/kanvas/kanvas-agent.jar")
        )
    }

    private fun writeProject(build: String) {
        File(projectDir, "settings.gradle.kts").writeText(
            "rootProject.name = \"kanvas-test\"\n"
        )
        File(projectDir, "build.gradle.kts").writeText(build)
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()
}
