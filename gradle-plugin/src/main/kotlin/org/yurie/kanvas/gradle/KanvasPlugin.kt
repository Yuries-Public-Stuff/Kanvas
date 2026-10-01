package org.yurie.kanvas.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.JavaExec
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class KanvasPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "Apply org.yurie.kanvas to the root project."
        }

        val extension = project.extensions.create(
            "kanvas",
            KanvasExtension::class.java
        )

        val runtime = project.tasks.register("kanvasRuntime") { task ->
            task.group = "kanvas"
            task.description = "Build the Kanvas native runtime and Java agent."

            task.doLast {
                if (extension.autoBuildRuntime) {
                    buildRuntime(project, extension)
                }
            }
        }

        val refreshRuntime = project.tasks.register("kanvasRefreshRuntime") { task ->
            task.group = "kanvas"
            task.description = "Refresh cached Kanvas source and rebuild the runtime."

            task.doLast {
                if (extension.home.isBlank()) {
                    val checkout = sourceCheckout(project, extension)
                    if (checkout.exists()) {
                        project.logger.lifecycle(
                            "Kanvas cache -> remove {}",
                            checkout.absolutePath
                        )
                        checkout.deleteRecursively()
                    }
                }
                buildRuntime(project, extension)
            }
        }

        val doctor = project.tasks.register("kanvasDoctor") { task ->
            task.group = "kanvas"
            task.description = "Show the app tasks and Kanvas runtime configuration."
        }

        val compatibility = project.tasks.register("kanvasCompatibility") { task ->
            task.group = "kanvas"
            task.description = "Show detected Compose and Skiko runtime versions."
        }

        val build = project.tasks.register("kanvasBuild") { task ->
            task.group = "kanvas"
            task.description = "Build the selected desktop app."
        }

        val run = project.tasks.register("kanvasRun") { task ->
            task.group = "kanvas"
            task.description = "Run the selected desktop app through Kanvas."
        }

        val pack = project.tasks.register("kanvasPackage") { task ->
            task.group = "kanvas"
            task.description = "Build a desktop app image with Kanvas embedded."
        }

        project.gradle.projectsEvaluated {
            validateBackend(extension.backend)
            validateBuildType(extension.runtimeBuildType)

            val buildTask = chooseBuild(project, extension)
            val runTask = chooseRun(project, extension)
            val packageTask = choosePackage(project, extension)

            if (buildTask == null) {
                build.configure { task ->
                    task.doFirst {
                        throw GradleException(
                            "No build task found. Set kanvas.buildTask."
                        )
                    }
                }
            } else {
                build.configure { task ->
                    task.dependsOn(buildTask)
                    if (extension.autoBuildRuntime) {
                        task.dependsOn(runtime)
                    }
                    task.doFirst {
                        project.logger.lifecycle(
                            "Kanvas -> {}",
                            buildTask.path
                        )
                    }
                }
            }

            doctor.configure { task ->
                task.doLast {
                    project.logger.lifecycle("")
                    project.logger.lifecycle("Kanvas")
                    project.logger.lifecycle("------")
                    project.logger.lifecycle("Backend    : {}", extension.backend)
                    project.logger.lifecycle("Target     : {}", extension.target.ifBlank { "auto" })
                    project.logger.lifecycle(
                        "Build task : {}",
                        buildTask?.path ?: "not found"
                    )
                    project.logger.lifecycle("Run task   : {}", runTask?.path ?: "not found")
                    project.logger.lifecycle(
                        "Package    : {}",
                        packageTask?.path ?: "not found"
                    )
                    project.logger.lifecycle(
                        "Home       : {}",
                        extension.home.ifBlank { "auto" }
                    )
                    if (extension.home.isBlank()) {
                        project.logger.lifecycle(
                            "Source     : {} @ {}",
                            extension.sourceUrl,
                            effectiveSourceRef(extension)
                        )
                    }
                    project.logger.lifecycle("Runtime    : {}", if (extension.autoBuildRuntime) "auto" else "manual")
                    project.logger.lifecycle("Build type : {}", extension.runtimeBuildType)
                    project.logger.lifecycle("Strict     : {}", extension.strictRenderer)
                    printToolchain(project)
                    project.logger.lifecycle("")
                }
            }

            compatibility.configure { task ->
                task.doLast {
                    printCompatibility(project, runTask)
                }
            }

            if (runTask == null) {
                run.configure { task ->
                    task.doFirst {
                        throw GradleException(
                            "No runnable desktop task found. Set kanvas.runTask."
                        )
                    }
                }
            } else {
                if (extension.autoBuildRuntime) {
                    runTask.dependsOn(runtime)
                }

                configureRun(project, extension, runTask)

                run.configure { task ->
                    task.dependsOn(runTask)
                    task.doFirst {
                        project.logger.lifecycle(
                            "Kanvas -> {} ({})",
                            runTask.path,
                            extension.backend
                        )
                    }
                }
            }

            if (packageTask == null) {
                pack.configure { task ->
                    task.doFirst {
                        throw GradleException(
                            "No Compose Desktop packaging task found. " +
                                "Set kanvas.packageTask."
                        )
                    }
                }
            } else {
                if (extension.autoBuildRuntime) {
                    packageTask.dependsOn(runtime)
                }

                packageTask.doLast {
                    embedPackage(project, extension, packageTask)
                }

                pack.configure { task ->
                    task.dependsOn(packageTask)
                }
            }
        }
    }

    private fun chooseBuild(
        root: Project,
        extension: KanvasExtension
    ): Task? {
        explicitTask(root, extension.buildTask)?.let { return it }

        if (extension.target.isNotBlank()) {
            task(root, "${extension.target}:build")?.let { return it }
            task(root, "${extension.target}:assemble")?.let { return it }
            return null
        }

        listOf(
            ":desktop:build",
            ":composeApp:build",
            ":app:build",
            ":build"
        ).forEach { path ->
            task(root, path)?.let { return it }
        }

        return root.allprojects
            .asSequence()
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.name == "build" || it.name == "assemble" }
    }

    private fun chooseRun(root: Project, extension: KanvasExtension): Task? {
        explicitTask(root, extension.runTask)?.let { return it }

        if (extension.target.isNotBlank()) {
            listOf("run", "runDistributable").forEach { name ->
                task(root, "${extension.target}:$name")?.let { return it }
            }
        }

        listOf(
            ":desktop:run",
            ":composeApp:run",
            ":run"
        ).forEach { path ->
            task(root, path)?.let { return it }
        }

        return root.allprojects
            .asSequence()
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.name == "run" && it is JavaExec }
    }

    private fun choosePackage(
        root: Project,
        extension: KanvasExtension
    ): Task? {
        explicitTask(root, extension.packageTask)?.let { return it }

        if (extension.target.isNotBlank()) {
            task(
                root,
                "${extension.target}:createDistributable"
            )?.let { return it }
        }

        listOf(
            ":desktop:createDistributable",
            ":composeApp:createDistributable",
            ":app:createDistributable",
            ":createDistributable"
        ).forEach { path ->
            task(root, path)?.let { return it }
        }

        return root.allprojects
            .asSequence()
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.name == "createDistributable" }
    }

    private fun explicitTask(root: Project, path: String): Task? {
        if (path.isBlank()) return null
        return task(root, path)
            ?: throw GradleException("Task not found: $path")
    }

    private fun task(root: Project, path: String): Task? =
        try {
            root.tasks.getByPath(path)
        } catch (_: UnknownTaskException) {
            null
        }

    private fun configureRun(
        root: Project,
        extension: KanvasExtension,
        task: Task
    ) {
        if (task !is JavaExec) {
            task.doFirst {
                throw GradleException(
                    "${task.path} is not a JavaExec task. " +
                        "Set kanvas.runTask to the JVM desktop run task."
                )
            }
            return
        }

        task.doFirst {
            val home = runtimeHome(root, extension)
            val agent = resolveAgent(home, extension)

            if (!agent.isFile) {
                throw GradleException(
                    "Kanvas agent not found: ${agent.absolutePath}"
                )
            }

            val agentArg = "-javaagent:${agent.absolutePath}"
            if (!(task.jvmArgs ?: emptyList<String>()).contains(agentArg)) {
                task.jvmArgs(agentArg)
            }

            val outputDir = File(root.layout.buildDirectory.get().asFile, "kanvas")
            outputDir.mkdirs()

            val auditFile = File(outputDir, "renderer-audit.log")
            val captureFile = File(outputDir, "compose.kdcap")

            if (home != null) {
                task.environment("KANVAS_HOME", home.absolutePath)
                task.environment("KOTLIN_DISPLAY_HOME", home.absolutePath)
                task.systemProperty("kanvas.home", home.absolutePath)
                task.systemProperty("kotlin.display.home", home.absolutePath)
            }
            task.environment("KANVAS_BACKEND", extension.backend)
            task.environment("KD_BACKEND", extension.backend)

            task.systemProperty("kanvas.backend", extension.backend)
            task.systemProperty(
                "kanvas.strictRenderer",
                extension.strictRenderer.toString()
            )
            task.systemProperty(
                "kanvas.takeover",
                extension.takeover.toString()
            )
            task.systemProperty(
                "kanvas.capture",
                extension.capture.toString()
            )

            // Old runtime keys stay for compatibility.
            task.systemProperty("kotlin.display.backend", extension.backend)
            task.systemProperty(
                "kotlin.display.strictDefaultRenderer",
                extension.strictRenderer.toString()
            )
            task.systemProperty(
                "kotlin.display.takeover",
                extension.takeover.toString()
            )
            task.systemProperty(
                "kotlin.display.captureSkiaCanvas",
                extension.capture.toString()
            )

            if (extension.audit) {
                task.systemProperty("kanvas.auditPath", auditFile.absolutePath)
                task.systemProperty(
                    "kotlin.display.auditPath",
                    auditFile.absolutePath
                )
            }

            if (extension.capture) {
                task.systemProperty("kanvas.capturePath", captureFile.absolutePath)
                task.systemProperty(
                    "kotlin.display.capturePath",
                    captureFile.absolutePath
                )
            }

            if (extension.nativeLibrary.isNotBlank()) {
                val native = File(extension.nativeLibrary).absolutePath
                task.systemProperty("kanvas.nativeLibrary", native)
                task.systemProperty("kotlin.display.nativeLibrary", native)
            }

            root.logger.lifecycle("Kanvas agent: {}", agent.absolutePath)
            if (extension.audit) {
                root.logger.lifecycle("Kanvas audit: {}", auditFile.absolutePath)
            }
            if (extension.capture) {
                root.logger.lifecycle("Kanvas capture: {}", captureFile.absolutePath)
            }
        }
    }

    private fun buildRuntime(root: Project, extension: KanvasExtension) {
        val home = home(root, extension)
        val gradle = gradleExecutable(root)

        val os = System.getProperty("os.name")
            .lowercase(Locale.ROOT)

        val command = when {
            os.contains("win") -> listOf(
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                File(home, "scripts/build-runtime.ps1").absolutePath,
                "-GradleLauncher",
                gradle.absolutePath,
                "-BuildType",
                extension.runtimeBuildType
            )
            os.contains("mac") || os.contains("darwin") || os.contains("linux") ->
                listOf(
                    "bash",
                    File(home, "scripts/build-runtime.sh").absolutePath,
                    "--gradle-launcher",
                    gradle.absolutePath,
                    "--build-type",
                    extension.runtimeBuildType
                )
            else -> throw GradleException(
                "Unsupported Kanvas host: ${System.getProperty("os.name")}"
            )
        }

        root.logger.lifecycle("Kanvas runtime -> {}", home.absolutePath)
        run(command, home)

        val agent = resolveAgent(home, extension)
        if (!agent.isFile) {
            throw GradleException(
                "Kanvas runtime built without an agent jar: ${agent.absolutePath}"
            )
        }
    }

    private fun resolveAgent(
        home: File?,
        extension: KanvasExtension
    ): File {
        if (extension.agentJar.isNotBlank()) {
            return File(extension.agentJar).absoluteFile
        }

        val requiredHome = home ?: throw GradleException(
            "Kanvas home is required when agentJar is not set."
        )

        val kanvas = File(
            requiredHome,
            "integration-agent/build/libs/kanvas-agent.jar"
        )
        if (kanvas.isFile) return kanvas

        return File(
            requiredHome,
            "integration-agent/build/libs/kotlin-display-agent.jar"
        )
    }

    private fun runtimeHome(
        root: Project,
        extension: KanvasExtension
    ): File? {
        if (extension.home.isNotBlank()) {
            return home(root, extension)
        }

        val completeManualRuntime =
            !extension.autoBuildRuntime &&
                extension.agentJar.isNotBlank() &&
                extension.nativeLibrary.isNotBlank()

        if (completeManualRuntime) return null

        detectLocalCheckout()?.let { return it }
        return home(root, extension)
    }

    private fun home(
        root: Project,
        extension: KanvasExtension
    ): File {
        val raw = extension.home.trim()
        val home = if (raw.isNotEmpty()) {
            File(raw).absoluteFile
        } else {
            detectLocalCheckout() ?: provisionSource(root, extension)
        }

        if (!File(home, "native").isDirectory ||
            !File(home, "integration-agent").isDirectory) {
            throw GradleException(
                "Not a Kanvas checkout: ${home.absolutePath}"
            )
        }
        return home
    }

    private fun detectLocalCheckout(): File? {
        val location = runCatching {
            File(
                javaClass.protectionDomain.codeSource.location.toURI()
            ).absoluteFile
        }.getOrNull() ?: return null

        var current: File? = if (location.isDirectory) {
            location
        } else {
            location.parentFile
        }

        repeat(8) {
            val candidate = current ?: return null
            if (File(candidate, "native").isDirectory &&
                File(candidate, "integration-agent").isDirectory &&
                File(candidate, "gradle-plugin").isDirectory) {
                return candidate
            }
            current = candidate.parentFile
        }
        return null
    }

    private fun provisionSource(
        root: Project,
        extension: KanvasExtension
    ): File {
        val ref = effectiveSourceRef(extension)
        val checkout = sourceCheckout(root, extension)

        if (File(checkout, ".git").isDirectory) {
            return checkout
        }

        checkout.parentFile.mkdirs()
        if (checkout.exists()) checkout.deleteRecursively()
        checkout.mkdirs()

        root.logger.lifecycle(
            "Kanvas source -> {} ({})",
            extension.sourceUrl,
            ref
        )

        run(listOf("git", "init"), checkout)
        run(
            listOf(
                "git",
                "remote",
                "add",
                "origin",
                extension.sourceUrl
            ),
            checkout
        )
        run(
            listOf(
                "git",
                "fetch",
                "--depth",
                "1",
                "origin",
                ref
            ),
            checkout
        )
        run(
            listOf(
                "git",
                "checkout",
                "--detach",
                "FETCH_HEAD"
            ),
            checkout
        )

        return checkout
    }

    private fun sourceCheckout(
        root: Project,
        extension: KanvasExtension
    ): File {
        val ref = effectiveSourceRef(extension)
        val safeRef = ref.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val sourceKey = sha256(extension.sourceUrl).take(12)

        return File(
            root.gradle.gradleUserHomeDir,
            "caches/kanvas/source/$sourceKey/$safeRef"
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun effectiveSourceRef(extension: KanvasExtension): String {
        val explicit = extension.sourceRef.trim()
        if (explicit.isNotEmpty()) return explicit

        val version = javaClass.getPackage().implementationVersion
            ?.trim()
            .orEmpty()

        return if (version.isNotEmpty() &&
            !version.endsWith("-SNAPSHOT", ignoreCase = true)) {
            "v$version"
        } else {
            "main"
        }
    }

    private fun embedPackage(
        root: Project,
        extension: KanvasExtension,
        packageTask: Task
    ) {
        val home = runtimeHome(root, extension)
        val agent = resolveAgent(home, extension)
        val native = resolveNativeLibrary(home, extension)

        if (!agent.isFile) {
            throw GradleException("Kanvas agent not found: ${agent.absolutePath}")
        }
        if (!native.isFile) {
            throw GradleException(
                "Kanvas native library not found: ${native.absolutePath}"
            )
        }

        val projectDir = packageTask.project.layout.buildDirectory
            .get()
            .asFile

        val cfgFiles = projectDir.walkTopDown()
            .filter {
                it.isFile &&
                    it.extension == "cfg" &&
                    it.invariantSeparatorsPath.contains(
                        "/compose/binaries/main/app/"
                    )
            }
            .toList()

        if (cfgFiles.isEmpty()) {
            throw GradleException(
                "Kanvas could not find the Compose app image under " +
                    projectDir.absolutePath
            )
        }

        cfgFiles.forEach { cfg ->
            val appLib = cfg.parentFile
            val kanvasDir = File(appLib, "kanvas")
            kanvasDir.mkdirs()

            agent.copyTo(File(kanvasDir, "kanvas-agent.jar"), overwrite = true)
            native.copyTo(File(kanvasDir, native.name), overwrite = true)

            patchLauncherConfig(cfg, native.name, extension)

            root.logger.lifecycle(
                "Kanvas package -> {}",
                cfg.parentFile.parentFile.parentFile.absolutePath
            )
        }
    }

    private fun resolveNativeLibrary(
        home: File?,
        extension: KanvasExtension
    ): File {
        if (extension.nativeLibrary.isNotBlank()) {
            return File(extension.nativeLibrary).absoluteFile
        }

        val requiredHome = home ?: throw GradleException(
            "Kanvas home is required when nativeLibrary is not set."
        )

        val names = when {
            isWindows() -> listOf(
                "kanvas_native.dll",
                "kotlin_display_native.dll"
            )
            isMac() -> listOf(
                "libkanvas_native.dylib",
                "libkotlin_display_native.dylib"
            )
            else -> listOf(
                "libkanvas_native.so",
                "libkotlin_display_native.so"
            )
        }

        val build = File(requiredHome, "native/build")
        return build.walkTopDown()
            .firstOrNull { it.isFile && it.name in names }
            ?: File(build, names.first())
    }

    private fun patchLauncherConfig(
        cfg: File,
        nativeName: String,
        extension: KanvasExtension
    ) {
        val old = cfg.readLines()
        val options = listOf(
            "java-options=-javaagent:\$APPDIR/kanvas/kanvas-agent.jar",
            "java-options=-Dkanvas.nativeLibrary=\$APPDIR/kanvas/$nativeName",
            "java-options=-Dkanvas.backend=${extension.backend}",
            "java-options=-Dkanvas.takeover=${extension.takeover}",
            "java-options=-Dkanvas.capture=${extension.capture}",
            "java-options=-Dkanvas.strictRenderer=${extension.strictRenderer}"
        )

        val cleaned = old.filterNot { line ->
            line.contains("/kanvas/kanvas-agent.jar") ||
                line.contains("-Dkanvas.")
        }.toMutableList()

        val section = cleaned.indexOfFirst { it.trim() == "[JavaOptions]" }
        if (section >= 0) {
            cleaned.addAll(section + 1, options)
        } else {
            if (cleaned.isNotEmpty() && cleaned.last().isNotBlank()) {
                cleaned.add("")
            }
            cleaned.add("[JavaOptions]")
            cleaned.addAll(options)
        }

        cfg.writeText(
            cleaned.joinToString(System.lineSeparator()) +
                System.lineSeparator()
        )
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")
            .lowercase(Locale.ROOT)
            .contains("win")

    private fun isMac(): Boolean {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        return os.contains("mac") || os.contains("darwin")
    }

    private fun gradleExecutable(root: Project): File {
        val home = root.gradle.gradleHomeDir
            ?: throw GradleException("Gradle home is unavailable.")

        val windows = System.getProperty("os.name")
            .lowercase(Locale.ROOT)
            .contains("win")

        val executable = File(
            home,
            if (windows) "bin/gradle.bat" else "bin/gradle"
        )

        if (!executable.isFile) {
            throw GradleException(
                "Gradle executable not found: ${executable.absolutePath}"
            )
        }
        return executable
    }

    private fun run(command: List<String>, directory: File) {
        val process = ProcessBuilder(command)
            .directory(directory)
            .inheritIO()
            .start()

        val code = process.waitFor()
        if (code != 0) {
            throw GradleException(
                "Kanvas command failed ($code): ${command.joinToString(" ")}"
            )
        }
    }

    private fun validateBuildType(buildType: String) {
        if (buildType !in setOf(
                "Debug",
                "Release",
                "RelWithDebInfo",
                "MinSizeRel"
            )) {
            throw GradleException(
                "Unsupported Kanvas runtimeBuildType '$buildType'."
            )
        }
    }

    private fun validateBackend(backend: String) {
        val supported = setOf(
            "auto",
            "vulkan",
            "metal",
            "opengl",
            "d3d9",
            "gdi"
        )
        if (backend !in supported) {
            throw GradleException(
                "Unsupported Kanvas backend '$backend'. " +
                    "Use ${supported.joinToString()}."
            )
        }
    }

    private fun printToolchain(root: Project) {
        val compiler = if (isWindows()) "gcc" else "cc"
        val builder = when {
            commandAvailable("ninja") -> "ninja"
            isWindows() && commandAvailable("mingw32-make") -> "mingw32-make"
            commandAvailable("make") -> "make"
            else -> null
        }

        root.logger.lifecycle(
            "Tools      : git={} cmake={} java={} {}={} builder={}",
            status(commandAvailable("git")),
            status(commandAvailable("cmake")),
            status(commandAvailable("java")),
            compiler,
            status(commandAvailable(compiler)),
            builder ?: "missing"
        )
    }

    private fun status(value: Boolean): String =
        if (value) "ok" else "missing"

    private fun commandAvailable(command: String): Boolean =
        try {
            val process = ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.close()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }

    private fun printCompatibility(root: Project, runTask: Task?) {
        root.logger.lifecycle("")
        root.logger.lifecycle("Kanvas compatibility")
        root.logger.lifecycle("--------------------")

        if (runTask !is JavaExec) {
            root.logger.lifecycle("No JavaExec desktop task selected.")
            return
        }

        val names = runTask.classpath.files
            .map { it.name }
            .filter {
                it.contains("compose", ignoreCase = true) ||
                    it.contains("skiko", ignoreCase = true)
            }
            .sorted()

        if (names.isEmpty()) {
            root.logger.lifecycle("No Compose/Skiko jars found on the run classpath.")
        } else {
            names.forEach { root.logger.lifecycle(it) }
        }
    }
}
