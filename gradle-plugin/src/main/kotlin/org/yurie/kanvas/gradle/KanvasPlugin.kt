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
    companion object {
        private const val BASELINE_COMPOSE = "1.8.2"
        private const val BASELINE_SKIKO = "0.9.4.2"
        private const val MIN_GRADLE = "7.6.3"
        private const val MAX_TESTED_GRADLE = "9.8.0"
        private const val MIN_CMAKE = "3.21"
    }

    private enum class DoctorLevel { OK, WARN, ERROR }

    private data class DoctorCheck(
        val level: DoctorLevel,
        val name: String,
        val detail: String,
        val fix: String? = null
    )

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
            task.description = "Diagnose Kanvas configuration, toolchain, platform, runtime, and app integration."
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
                    runDoctor(
                        project,
                        extension,
                        buildTask,
                        runTask,
                        packageTask
                    )
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
            task(root, targetTaskPath(extension.target, "build"))?.let { return it }
            task(root, targetTaskPath(extension.target, "assemble"))?.let { return it }
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
                task(root, targetTaskPath(extension.target, name))?.let { return it }
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
                targetTaskPath(extension.target, "createDistributable")
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
            if (!task.jvmArgs.contains(agentArg)) {
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

            listOf("LICENSE", "THIRD_PARTY_NOTICES.md").forEach { name ->
                copyPackagedNotice(home, kanvasDir, name)
            }

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

    private fun isLinux(): Boolean =
        System.getProperty("os.name")
            .lowercase(Locale.ROOT)
            .contains("linux")

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

    private fun targetTaskPath(target: String, taskName: String): String {
        val normalized = target.trim().trimEnd(':')
        return if (normalized.isEmpty()) {
            ":$taskName"
        } else if (normalized.startsWith(":")) {
            "$normalized:$taskName"
        } else {
            ":$normalized:$taskName"
        }
    }

    private fun runDoctor(
        root: Project,
        extension: KanvasExtension,
        buildTask: Task?,
        runTask: Task?,
        packageTask: Task?
    ) {
        val checks = mutableListOf<DoctorCheck>()

        fun ok(name: String, detail: String) {
            checks += DoctorCheck(DoctorLevel.OK, name, detail)
        }
        fun warn(name: String, detail: String, fix: String? = null) {
            checks += DoctorCheck(DoctorLevel.WARN, name, detail, fix)
        }
        fun error(name: String, detail: String, fix: String? = null) {
            checks += DoctorCheck(DoctorLevel.ERROR, name, detail, fix)
        }

        val osName = System.getProperty("os.name")
        val osArch = System.getProperty("os.arch")
        val gradleJavaVersion = System.getProperty("java.version")
        val javaSpec = System.getProperty("java.specification.version")
        val javaMajor = javaSpec.substringAfterLast('.').toIntOrNull() ?: 0
        val gradleJavaHome = File(System.getProperty("java.home"))
        val envJavaHome = System.getenv("JAVA_HOME")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
        val appJavaHome = envJavaHome ?: gradleJavaHome
        val jniHome = listOfNotNull(appJavaHome, appJavaHome.parentFile)
            .firstOrNull { File(it, "include/jni.h").isFile }
        val appJavaVersion = javaVersionFromHome(appJavaHome)

        ok("Host", "$osName / $osArch")

        val gradleVersion = org.gradle.util.GradleVersion.current()
        val minGradle = org.gradle.util.GradleVersion.version(MIN_GRADLE)
        val maxGradle = org.gradle.util.GradleVersion.version(MAX_TESTED_GRADLE)
        when {
            gradleVersion < minGradle -> error(
                "Gradle",
                "$gradleVersion is below the Kotlin 2.4.20 baseline.",
                "Use Gradle $MIN_GRADLE or newer."
            )
            gradleVersion > maxGradle -> warn(
                "Gradle",
                "$gradleVersion is newer than the fully tested Kotlin 2.4.20 range.",
                "Prefer Gradle $MIN_GRADLE through $MAX_TESTED_GRADLE until verified."
            )
            else -> ok("Gradle", gradleVersion.version)
        }

        if (javaMajor < 17) {
            error(
                "Gradle JVM",
                "Java $gradleJavaVersion is too old.",
                "Run Gradle with JDK 17 or newer."
            )
        } else {
            ok(
                "Gradle JVM",
                "Java $gradleJavaVersion (${gradleJavaHome.absolutePath})"
            )
        }

        if (extension.autoBuildRuntime &&
            envJavaHome != null &&
            !File(envJavaHome, "include/jni.h").isFile) {
            error(
                "App JDK/JNI",
                "${envJavaHome.absolutePath} does not contain JNI headers.",
                "Point JAVA_HOME to a full JDK 17+ installation or unset it so Kanvas can use the active JDK."
            )
        } else if (jniHome == null && extension.autoBuildRuntime) {
            error(
                "App JDK/JNI",
                "JNI headers were not found under ${appJavaHome.absolutePath}.",
                "Point JAVA_HOME to a full JDK 17+ installation."
            )
        } else {
            ok(
                "App JDK/JNI",
                "Java ${appJavaVersion ?: "unknown"} (${appJavaHome.absolutePath})" +
                    if (envJavaHome != null) " via JAVA_HOME" else " via Gradle JVM"
            )
        }

        val localHome = if (extension.home.isNotBlank()) {
            File(extension.home).absoluteFile
        } else {
            detectLocalCheckout()
        }

        if (extension.home.isNotBlank()) {
            val requiredHome = localHome!!
            if (File(requiredHome, "native").isDirectory &&
                File(requiredHome, "integration-agent").isDirectory) {
                ok("Kanvas home", requiredHome.absolutePath)
            } else {
                error(
                    "Kanvas home",
                    "${requiredHome.absolutePath} is not a Kanvas checkout.",
                    "Set kanvas.home/KANVAS_HOME to a checkout containing native/ and integration-agent/."
                )
            }
        } else if (localHome != null) {
            ok("Kanvas source", "local checkout: ${localHome.absolutePath}")
        } else {
            ok(
                "Kanvas source",
                "${extension.sourceUrl} @ ${effectiveSourceRef(extension)}"
            )
        }

        if (extension.autoBuildRuntime) {
            if (localHome == null) {
                if (!commandAvailable("git")) {
                    error(
                        "Git",
                        "Git is required to provision Kanvas source.",
                        "Install Git or set kanvas.home to an existing checkout."
                    )
                } else {
                    ok("Git", commandVersion("git") ?: "available")
                }
            } else if (commandAvailable("git")) {
                ok("Git", commandVersion("git") ?: "available")
            } else {
                ok("Git", "not required; a local Kanvas checkout is already selected")
            }

            val cmakeLine = commandVersion("cmake")
            if (cmakeLine == null) {
                error("CMake", "CMake was not found.", "Install CMake $MIN_CMAKE or newer.")
            } else {
                val cmakeVersion = Regex("\\d+(?:\\.\\d+){1,2}")
                    .find(cmakeLine)?.value
                if (cmakeVersion != null && !versionAtLeast(cmakeVersion, MIN_CMAKE)) {
                    error(
                        "CMake",
                        "$cmakeVersion is too old.",
                        "Install CMake $MIN_CMAKE or newer."
                    )
                } else {
                    ok("CMake", cmakeLine)
                }
            }

            val compiler = when {
                isWindows() -> windowsTool("gcc.exe")?.absolutePath ?: "gcc"
                commandAvailable("cc") -> "cc"
                commandAvailable("clang") -> "clang"
                else -> "gcc"
            }
            val compilerLine = commandVersion(compiler)
            if (compilerLine == null) {
                error(
                    "C compiler",
                    "$compiler was not found.",
                    if (isWindows()) {
                        "Install MinGW-w64 GCC (MSYS2 UCRT64/MINGW64 is supported)."
                    } else if (isMac()) {
                        "Install Xcode Command Line Tools."
                    } else {
                        "Install a C compiler such as GCC or Clang."
                    }
                )
            } else {
                ok("C compiler", compilerLine)
            }

            val windowsMake = if (isWindows()) windowsTool("mingw32-make.exe") else null
            val builder = when {
                commandAvailable("ninja") -> "ninja"
                isWindows() && windowsMake != null -> windowsMake.absolutePath
                commandAvailable("make") -> "make"
                else -> null
            }
            if (builder == null) {
                error(
                    "Native builder",
                    "No supported build tool was found.",
                    if (isWindows()) "Install Ninja or mingw32-make." else "Install Ninja or Make."
                )
            } else {
                ok("Native builder", commandVersion(builder) ?: builder)
            }

            when {
                isWindows() -> {
                    if (!commandSucceeds(
                            listOf(
                                "powershell",
                                "-NoProfile",
                                "-Command",
                                "\$PSVersionTable.PSVersion.ToString()"
                            )
                        )) {
                        error(
                            "PowerShell",
                            "PowerShell was not found.",
                            "Install/enable PowerShell or place powershell.exe on PATH."
                        )
                    } else {
                        ok("PowerShell", "available")
                    }
                }
                isMac() -> {
                    if (!commandSucceeds(listOf("xcrun", "--find", "clang"))) {
                        error(
                            "Xcode tools",
                            "xcrun could not find clang.",
                            "Run xcode-select --install."
                        )
                    } else {
                        ok(
                            "Xcode tools",
                            commandOutput(listOf("xcode-select", "-p"))?.trim() ?: "available"
                        )
                    }
                }
                isLinux() -> {
                    if (!linuxX11DevelopmentPresent()) {
                        error(
                            "X11 development",
                            "X11 development headers were not detected.",
                            "Install your distribution's X11/Xlib development package."
                        )
                    } else {
                        ok("X11 development", "detected")
                    }
                }
            }
        } else {
            ok("Runtime build", "automatic native runtime build is disabled")
        }

        val rendererExpected = runTask is JavaExec &&
            composeRuntimeNames(runTask).isNotEmpty()

        if (!isWindows() && !isMac() && !isLinux()) {
            error(
                "Host platform",
                "${System.getProperty("os.name")} is not supported.",
                "Use Windows, macOS, or Linux."
            )
        }

        val backend = extension.backend.lowercase(Locale.ROOT)
        val backendProblem = backendPlatformProblem(backend)
        if (backendProblem != null) {
            error(
                "Backend",
                backendProblem,
                "Use backend = \"auto\" or a backend implemented for this host."
            )
        } else {
            ok("Backend", backendAutoDescription(backend))
        }

        if (isLinux() && (backend == "auto" || backend == "vulkan")) {
            if (!linuxVulkanLoaderPresent()) {
                if (rendererExpected) {
                    error(
                        "Vulkan loader",
                        "libvulkan.so.1 was not detected. Linux auto mode requires Vulkan.",
                        "Install the Vulkan loader/runtime and a working GPU driver."
                    )
                } else {
                    warn(
                        "Vulkan loader",
                        "libvulkan.so.1 was not detected, but no Compose/Skiko renderer target was detected.",
                        "Install Vulkan before running a supported desktop renderer."
                    )
                }
            } else {
                ok("Vulkan loader", "detected")
            }
        } else if (isWindows() && backend == "vulkan") {
            if (!windowsVulkanLoaderPresent()) {
                error(
                    "Vulkan loader",
                    "vulkan-1.dll/VULKAN_SDK was not detected.",
                    "Install a Vulkan-capable GPU driver or Vulkan runtime."
                )
            } else {
                ok("Vulkan loader", "detected")
            }
        } else if (isWindows() && backend == "auto" && !windowsVulkanLoaderPresent()) {
            warn(
                "Vulkan loader",
                "Vulkan was not detected; auto mode can still fall back to D3D9/OpenGL.",
                "Install/update the GPU driver if Vulkan is expected."
            )
        }

        if (buildTask == null) {
            error(
                "Build task",
                "No application build/assemble task was found.",
                "Set kanvas.target or kanvas.buildTask explicitly."
            )
        } else {
            ok("Build task", buildTask.path)
        }

        when {
            runTask == null -> warn(
                "Run task",
                "No runnable JVM desktop task was found.",
                "Set kanvas.target or kanvas.runTask if this project should run through Kanvas."
            )
            runTask !is JavaExec -> error(
                "Run task",
                "${runTask.path} is ${runTask.javaClass.simpleName}, not JavaExec.",
                "Set kanvas.runTask to the JVM desktop JavaExec task."
            )
            else -> ok("Run task", runTask.path)
        }

        if (packageTask == null) {
            warn(
                "Package task",
                "No Compose createDistributable task was found.",
                "This is fine if packaging is not needed; otherwise set kanvas.packageTask."
            )
        } else {
            ok("Package task", packageTask.path)
        }

        if (runTask is JavaExec) {
            val runtimeNames = composeRuntimeNames(runTask)
            if (runtimeNames.isEmpty()) {
                warn(
                    "Compose/Skiko",
                    "No Compose/Skiko JARs were detected on ${runTask.path}.",
                    "For GPU takeover, select the Compose Desktop JavaExec task."
                )
            } else {
                ok("Compose/Skiko", runtimeNames.joinToString(", "))
            }
        }

        if (!extension.autoBuildRuntime) {
            if (extension.agentJar.isNotBlank()) {
                val agent = File(extension.agentJar).absoluteFile
                if (agent.isFile) {
                    ok("Agent JAR", agent.absolutePath)
                } else {
                    error(
                        "Agent JAR",
                        "Configured agent does not exist: ${agent.absolutePath}",
                        "Build the agent or correct kanvas.agentJar."
                    )
                }
            } else if (localHome != null) {
                val agent = resolveAgent(localHome, extension)
                if (agent.isFile) {
                    ok("Agent JAR", agent.absolutePath)
                } else {
                    warn(
                        "Agent JAR",
                        "No built agent was found under ${localHome.absolutePath}.",
                        "Build :integration-agent:jar before running Kanvas."
                    )
                }
            }

            if (extension.nativeLibrary.isNotBlank()) {
                val native = File(extension.nativeLibrary).absoluteFile
                if (native.isFile) {
                    ok("Native library", native.absolutePath)
                } else {
                    error(
                        "Native library",
                        "Configured native library does not exist: ${native.absolutePath}",
                        "Build the native runtime or correct kanvas.nativeLibrary."
                    )
                }
            } else if (localHome != null) {
                val native = resolveNativeLibrary(localHome, extension)
                if (native.isFile) {
                    ok("Native library", native.absolutePath)
                } else {
                    warn(
                        "Native library",
                        "No built native runtime was found under ${localHome.absolutePath}.",
                        "Build kanvasRuntime before running/packaging."
                    )
                }
            }
        }

        root.logger.lifecycle("")
        root.logger.lifecycle("Kanvas Doctor")
        root.logger.lifecycle("=============")
        root.logger.lifecycle("Requested backend : {}", extension.backend)
        root.logger.lifecycle("Target            : {}", extension.target.ifBlank { "auto" })
        root.logger.lifecycle(
            "Runtime build     : {}",
            if (extension.autoBuildRuntime) "automatic" else "manual"
        )
        root.logger.lifecycle("Runtime build type: {}", extension.runtimeBuildType)
        root.logger.lifecycle("Takeover          : {}", extension.takeover)
        root.logger.lifecycle("Capture           : {}", extension.capture)
        root.logger.lifecycle("Audit             : {}", extension.audit)
        root.logger.lifecycle("Strict renderer   : {}", extension.strictRenderer)
        root.logger.lifecycle("")
        root.logger.lifecycle("Checks")
        root.logger.lifecycle("------")

        checks.forEach { check ->
            val tag = when (check.level) {
                DoctorLevel.OK -> "OK"
                DoctorLevel.WARN -> "WARN"
                DoctorLevel.ERROR -> "ERROR"
            }
            root.logger.lifecycle("[{}] {}: {}", tag, check.name, check.detail)
            check.fix?.let { root.logger.lifecycle("       Fix: {}", it) }
        }

        val errors = checks.count { it.level == DoctorLevel.ERROR }
        val warnings = checks.count { it.level == DoctorLevel.WARN }
        val passed = checks.count { it.level == DoctorLevel.OK }

        root.logger.lifecycle("")
        root.logger.lifecycle(
            "Summary: {} error(s), {} warning(s), {} check(s) passed.",
            errors,
            warnings,
            passed
        )

        if (errors > 0) {
            throw GradleException(
                "Kanvas Doctor found $errors blocking problem(s). " +
                    "Fix the ERROR items above and run kanvasDoctor again."
            )
        }
    }

    private fun backendPlatformProblem(backend: String): String? {
        if (backend == "auto") return null
        return when {
            isWindows() && backend in setOf("vulkan", "opengl", "d3d9", "gdi") -> null
            isMac() && backend == "metal" -> null
            isLinux() && backend == "vulkan" -> null
            else -> "Backend '$backend' is not implemented for ${System.getProperty("os.name")}."
        }
    }

    private fun backendAutoDescription(backend: String): String {
        if (backend != "auto") return "$backend selected"
        return when {
            isMac() -> "auto -> Metal"
            isLinux() -> "auto -> Vulkan"
            isWindows() ->
                "auto -> Vulkan, then D3D9, then OpenGL; GDI is the initial creation safety fallback"
            else -> "auto on unsupported host"
        }
    }

    private fun windowsTool(name: String): File? {
        val fromPath = commandOutput(listOf("where", name))
            ?.lineSequence()
            ?.map { File(it.trim()) }
            ?.firstOrNull { it.isFile }
        if (fromPath != null) return fromPath

        return listOf(
            File("C:\\msys64\\ucrt64\\bin", name),
            File("C:\\msys64\\mingw64\\bin", name)
        ).firstOrNull { it.isFile }
    }

    private fun linuxX11DevelopmentPresent(): Boolean =
        commandSucceeds(listOf("pkg-config", "--exists", "x11")) ||
            File("/usr/include/X11/Xlib.h").isFile

    private fun linuxVulkanLoaderPresent(): Boolean {
        val common = listOf(
            "/usr/lib/libvulkan.so.1",
            "/usr/lib64/libvulkan.so.1",
            "/usr/lib/x86_64-linux-gnu/libvulkan.so.1",
            "/usr/lib/aarch64-linux-gnu/libvulkan.so.1",
            "/lib/x86_64-linux-gnu/libvulkan.so.1",
            "/lib/aarch64-linux-gnu/libvulkan.so.1"
        )
        if (common.any { File(it).isFile }) return true
        return commandOutput(listOf("ldconfig", "-p"))
            ?.contains("libvulkan.so.1") == true
    }

    private fun windowsVulkanLoaderPresent(): Boolean {
        if (!System.getenv("VULKAN_SDK").isNullOrBlank()) return true
        val windir = System.getenv("WINDIR") ?: "C:\\Windows"
        return File(windir, "System32/vulkan-1.dll").isFile
    }

    private fun versionAtLeast(current: String, required: String): Boolean {
        val left = current.split('.').mapNotNull { it.toIntOrNull() }
        val right = required.split('.').mapNotNull { it.toIntOrNull() }
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    private fun javaVersionFromHome(home: File): String? {
        val release = File(home, "release")
        if (release.isFile) {
            runCatching {
                release.useLines { lines ->
                    lines.firstOrNull { it.startsWith("JAVA_VERSION=") }
                        ?.substringAfter('=')
                        ?.trim()
                        ?.trim('"')
                }
            }.getOrNull()?.let { return it }
        }

        val executable = File(
            home,
            if (isWindows()) "bin/java.exe" else "bin/java"
        )
        if (!executable.isFile) return null

        val output = commandOutput(listOf(executable.absolutePath, "-version"))
            ?: return null
        return Regex("""version\s+"([^"]+)"""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun commandVersion(command: String): String? =
        commandOutput(listOf(command, "--version"))
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun commandAvailable(command: String): Boolean =
        commandSucceeds(listOf(command, "--version"))

    private fun commandSucceeds(command: List<String>): Boolean =
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }

    private fun commandOutput(command: List<String>): String? =
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0) output else null
        } catch (_: Exception) {
            null
        }

    private fun composeRuntimeNames(runTask: JavaExec): List<String> =
        runTask.classpath.files
            .map { it.name }
            .filter(::isComposeOrSkikoJar)
            .sorted()

    private fun isComposeOrSkikoJar(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.contains("skiko") ||
            lower.contains("compose") ||
            lower.startsWith("runtime-desktop-") ||
            lower.startsWith("ui-desktop-") ||
            lower.startsWith("foundation-desktop-") ||
            lower.startsWith("material-desktop-") ||
            lower.startsWith("material3-desktop-")
    }

    private fun copyPackagedNotice(
        home: File?,
        destination: File,
        name: String
    ) {
        val source = home?.let { File(it, name) }
        val target = File(destination, name)

        if (source?.isFile == true) {
            source.copyTo(target, overwrite = true)
            return
        }

        val resource = javaClass.getResourceAsStream("/kanvas/$name")
            ?: return
        resource.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun printCompatibility(root: Project, runTask: Task?) {
        root.logger.lifecycle("")
        root.logger.lifecycle("Kanvas Compatibility Report")
        root.logger.lifecycle("===========================")
        root.logger.lifecycle("Gradle baseline : {} - {}", MIN_GRADLE, MAX_TESTED_GRADLE)
        root.logger.lifecycle("Compose baseline: {}", BASELINE_COMPOSE)
        root.logger.lifecycle("Skiko baseline  : {}", BASELINE_SKIKO)
        root.logger.lifecycle("")

        if (runTask !is JavaExec) {
            root.logger.lifecycle("Run task        : not a JavaExec task")
            root.logger.lifecycle("Result          : no Compose/Skiko runtime can be inspected")
            root.logger.lifecycle("")
            root.logger.lifecycle(
                "This task reports detected runtime versions; it does not prove renderer compatibility."
            )
            return
        }

        root.logger.lifecycle("Run task        : {}", runTask.path)
        val names = composeRuntimeNames(runTask)

        if (names.isEmpty()) {
            root.logger.lifecycle("Detected        : no Compose/Skiko JARs on the run classpath")
        } else {
            names.forEach { root.logger.lifecycle("Detected        : {}", it) }
        }

        val composeMatch = names.any {
            it.contains(BASELINE_COMPOSE) &&
                !it.contains("skiko", ignoreCase = true)
        }
        val skikoMatch = names.any {
            it.contains(BASELINE_SKIKO) &&
                it.contains("skiko", ignoreCase = true)
        }

        if (names.isNotEmpty()) {
            root.logger.lifecycle(
                "Compose status  : {}",
                if (composeMatch) "baseline detected" else "version is unverified"
            )
            root.logger.lifecycle(
                "Skiko status    : {}",
                if (skikoMatch) "baseline detected" else "version is unverified"
            )
        }

        root.logger.lifecycle("")
        root.logger.lifecycle(
            "This task reports detected runtime versions; it does not prove renderer compatibility."
        )
        root.logger.lifecycle(
            "Use real application tests and strictRenderer for renderer verification."
        )
    }
}
