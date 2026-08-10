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
    useJUnitPlatform()
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
