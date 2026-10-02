plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.yurie.kanvas")
}

kanvas {
    target = ":desktop"
    backend = "auto"
}
