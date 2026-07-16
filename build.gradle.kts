plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform")
}

group = "com.github.vgirotto"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        val platformType = providers.gradleProperty("platformType")
        val platformVersion = providers.gradleProperty("platformVersion")

        create(platformType, platformVersion)

        bundledPlugin("org.jetbrains.plugins.terminal")

        // Platform's own JUnit5 test framework — needed for JCEF browser-level tests
        // (@TestApplication provides an Application). Part of the IntelliJ platform, not
        // a new external dependency.
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.JUnit5)

        pluginVerifier()
        zipSigner()
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2") // Required by IntelliJ test framework
}

intellijPlatform {
    pluginVerification {
        // Stated explicitly instead of taking the plugin's default, which also fails on
        // DEPRECATED_API_USAGES. That default turns the build red the moment JetBrains
        // deprecates anything in an EAP — outside this repo's control, and the reason the
        // verifier step was muted with continue-on-error in the first place. These levels
        // mean the plugin is actually broken for a user, so the step can gate for real.
        failureLevel.set(
            listOf(
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            )
        )
        ides {
            recommended()
        }
    }
}

tasks.test {
    useJUnitPlatform {
        // Browser-level (JCEF) tests are tagged "browser". They need a display, so they
        // are excluded from the default headless `./gradlew test` (local dev + the plain
        // CI job) and opted in with `-PbrowserTests` under Xvfb (see ui-test.yml).
        // Chat-shell browser tests additionally self-skip when JBCefApp.isSupported() is
        // false, so enabling the tag on a headless box is harmless.
        if (!project.hasProperty("browserTests")) {
            excludeTags("browser")
        }
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set(provider { null })
    }

    buildSearchableOptions {
        enabled = false
    }

    named<org.jetbrains.intellij.platform.gradle.tasks.PrepareJarSearchableOptionsTask>("prepareJarSearchableOptions") {
        enabled = false
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
