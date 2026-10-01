pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "kanvas"
include(":renderer")

include(":integration-agent")

include(":compose-bridge")


include(":gradle-plugin")
