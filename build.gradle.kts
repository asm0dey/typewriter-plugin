import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = "com.github.asm0dey"
version = "1.0.0"

kotlin { jvmToolchain(21) }

dependencies {
    // test-framework-junit5 pulls junit-jupiter-engine as a runtime-only, transitively-stripped
    // dependency (its own pom excludes all of ITS transitives) - @Test/Assertions need the API
    // explicitly on the compile classpath, pinned to the same version the bundled engine resolves to.
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.12.2")
    // JUnit5 test framework still references JUnit4 classes at runtime (JetBrains FAQ).
    testRuntimeOnly("junit:junit:4.13.2")
    // Gradle's test task needs the JUnit Platform launcher on the runtime classpath to execute
    // JUnit5 tests; nothing in the bundled test-framework-junit5 pom brings it in.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        testFramework(TestFrameworkType.JUnit5)
        // Test-only: language plugins must never be reachable from the main compileClasspath.
        testBundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "252" }
    }
}

tasks.test {
    useJUnitPlatform()
    // A real X11 display is present on this machine; without headless mode AWT spins up a
    // real X11 toolkit thread that the platform's ThreadLeakTrackerExtension (JUnit5 test
    // framework) then reports as a leak on the first test in the JVM.
    systemProperty("java.awt.headless", "true")
    testLogging { showStandardStreams = true }
}
