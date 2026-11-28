import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.androidx.baselineprofile.producer) apply false
    alias(libs.plugins.cyclonedx.bom) apply false
}

val releaseTarget = providers.gradleProperty("releaseTarget").orElse("nano").get()
require(releaseTarget in setOf("nano", "comet"))
val releaseMetadata = Properties().apply {
    rootProject.file("release/targets/$releaseTarget.properties").inputStream().use { load(it) }
}
allprojects {
    group = "org.nanokvm"
    version = releaseMetadata.getProperty("versionName")
}

tasks.register("verifyDeviceBuilds") {
    group = "verification"
    description = "Build both release APKs and run their unit tests and Android lint."
    dependsOn(":protocol:testDebugUnitTest", ":video:testDebugUnitTest", ":protocol:lintRelease", ":video:lintRelease")
    for (target in listOf("Nano", "Comet")) {
        dependsOn(":app:assemble${target}Release", ":app:test${target}DebugUnitTest", ":app:lint${target}Release")
    }
}
