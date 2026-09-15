import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "typewriter2"

pluginManagement {
    plugins {
        // Bound to the TARGET IDE's bundled Kotlin, not free to float. gradle.properties sets
        // kotlin.stdlib.default.dependency=false, so what this compiles runs against the
        // kotlin-stdlib inside the IntelliJ Platform. A newer compiler emits coroutine debug
        // metadata that stdlib cannot read ("Debug metadata version mismatch. Expected: 1,
        // got 2"), which throws inside the coroutines machinery on resume and HANGS the tests
        // instead of failing them. Renovate proposed 2.4.20 here and did exactly that, so it is
        // disabled for Kotlin in renovate.json. Raise this only with the platform version.
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform { defaultRepositories() }
    }
}
