package org.yurie.kanvas.gradle

open class KanvasExtension {
    var home: String = System.getProperty("kanvas.home")
        ?: System.getenv("KANVAS_HOME")
        ?: ""

    var sourceUrl: String = System.getProperty(
        "kanvas.sourceUrl",
        "https://github.com/Yuries-Public-Stuff/Kanvas.git"
    )

    var sourceRef: String = System.getProperty(
        "kanvas.sourceRef",
        ""
    )

    var backend: String = "auto"
    var runtimeBuildType: String = "Release"
    var target: String = ""
    var buildTask: String = ""
    var runTask: String = ""
    var packageTask: String = ""

    var agentJar: String = ""
    var nativeLibrary: String = ""

    var strictRenderer: Boolean = false
    var takeover: Boolean = true
    var capture: Boolean = true
    var audit: Boolean = true
    var autoBuildRuntime: Boolean = true
}
