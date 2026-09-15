import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = "com.github.asm0dey"
// Ported from the v1 codebase (../typewriter-plugin): version is driven by the pluginVersion
// gradle property (read by the release workflows to tag releases and pick a marketplace
// channel), defaulting to the version this rewrite ships as its first release.
version = providers.gradleProperty("pluginVersion").getOrElse("1.0.0")

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
        // Test-only: acceptance test 2 (Dockerfile golden test) needs Docker's FileType/formatter.
        testBundledPlugin("Docker")
        // Test-only: SnippetFileNameTest needs a real exact-only FileType (no ExtensionFileNameMatcher
        // at all) to test the stem-as-directory branch of SnippetFileNames.relativePath (spec
        // question 23) against actual platform behaviour rather than a hand-rolled FileType stub.
        testBundledPlugin("org.editorconfig.editorconfigjetbrains")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "252" }
    }

    // Ported from ../typewriter-plugin/build.gradle.kts. Signing and publishing read their
    // secrets from the environment (set as repository secrets on the marketplace-linked repo,
    // never committed); the release channel is derived from pluginVersion's pre-release label
    // (e.g. "1.1.0-beta.1" publishes to the "beta" channel), same as v1.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion")
            .map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            // Two IDEs, not recommended(). Measured: recommended() resolves to FIVE builds
            // (252, 253, 261, 262 and a 263 EAP), so every verify run downloaded ~22 GB of IDE
            // distributions -- on every push and pull request -- and at ~9.2 GB compressed it
            // could not be cached inside GitHub's 10 GB per-repository budget either. Pinning to
            // the two ends of the supported range keeps the signal that actually matters (the
            // oldest build we claim to support, and the newest release) while making the download
            // set small enough to cache: ~8.8 GB extracted, ~3.7 GB compressed.
            //
            // Each select takes the latest PATCH within its major, so routine patch releases are
            // picked up automatically and .github/workflows/build.yml's cache key follows them
            // (it hashes printProductsReleases' output, not this file).
            //
            // The floor tracks sinceBuild in gradle.properties -- change one and change the other.
            // The ceiling is bumped by hand when this plugin starts targeting a newer IDE; leaving
            // it is a deliberate, visible decision rather than silent drift back to five IDEs.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaUltimate)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "252"
                untilBuild = "252.*"
            }
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaUltimate)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "262"
                untilBuild = "262.*"
            }
        }
    }
}

// Ported from ../typewriter-plugin/build.gradle.kts. The org.jetbrains.changelog plugin is
// already applied above; this just configures it against our CHANGELOG.md.
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks.test {
    useJUnitPlatform()
    // A real X11 display is present on this machine; without headless mode AWT spins up a
    // real X11 toolkit thread that the platform's ThreadLeakTrackerExtension (JUnit5 test
    // framework) then reports as a leak on the first test in the JVM.
    systemProperty("java.awt.headless", "true")
    testLogging { showStandardStreams = true }
}
