import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.cyclonedx.model.Component
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.cyclonedx.bom)
}

val developmentKeystorePath = providers
    .gradleProperty("nanokvm.developmentKeystore")
    .orElse(providers.environmentVariable("NANOKVM_DEVELOPMENT_KEYSTORE"))
val developmentStorePassword = providers
    .gradleProperty("nanokvm.developmentStorePassword")
    .orElse(providers.environmentVariable("NANOKVM_DEVELOPMENT_STORE_PASSWORD"))
    .orElse("android")
val developmentKeyAlias = providers
    .gradleProperty("nanokvm.developmentKeyAlias")
    .orElse(providers.environmentVariable("NANOKVM_DEVELOPMENT_KEY_ALIAS"))
    .orElse("androiddebugkey")
val developmentKeyPassword = providers
    .gradleProperty("nanokvm.developmentKeyPassword")
    .orElse(providers.environmentVariable("NANOKVM_DEVELOPMENT_KEY_PASSWORD"))
    .orElse("android")

@CacheableTask
abstract class NormalizeCycloneDxSbom : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun normalize() {
        val parsed = JsonSlurper().parse(inputFile.get().asFile) as Map<*, *>
        val stableRoot = parsed.entries
            .filterNot { it.key.toString() == "serialNumber" }
            .associate { (key, value) ->
                key.toString() to if (key.toString() == "metadata" && value is Map<*, *>) {
                    value.entries
                        .filterNot { it.key.toString() == "timestamp" }
                        .associate { it.key.toString() to it.value }
                } else {
                    value
                }
            }
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        val canonicalJson = JsonOutput.prettyPrint(JsonOutput.toJson(canonicalize(stableRoot)))
            .replace("\r\n", "\n") + "\n"
        output.writeText(canonicalJson, Charsets.UTF_8)
    }

    private fun canonicalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .associate { it.key.toString() to canonicalize(it.value) }
        is Collection<*> -> value
            .map(::canonicalize)
            .sortedBy(JsonOutput::toJson)
        else -> value
    }
}

@DisableCachingByDefault(because = "Verifies generated source profiles and assembled APK/AAB packaging")
abstract class VerifyReleaseProfiles : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineProfile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val startupProfile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseApk: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseBundle: RegularFileProperty

    @TaskAction
    fun verify() {
        val baselineRules = profileRules(baselineProfile.get().asFile)
        val startupRules = profileRules(startupProfile.get().asFile)
        if (baselineRules.none(::isNanoKvmRule)) {
            throw GradleException("The generated Baseline Profile contains no NanoKVM app rules.")
        }
        if (startupRules.none(::isNanoKvmRule)) {
            throw GradleException("The generated Startup Profile contains no NanoKVM app rules.")
        }
        val baselineSignatures = baselineRules.map(::profileSignature).toSet()
        val startupSignatures = startupRules.map(::profileSignature).toSet()
        if (!baselineSignatures.containsAll(startupSignatures)) {
            throw GradleException("The Baseline Profile must contain every Startup Profile rule.")
        }
        if ((baselineSignatures - startupSignatures).isEmpty()) {
            throw GradleException("The Baseline Profile must also cover a non-startup critical journey.")
        }

        ZipFile(releaseApk.get().asFile).use { apk ->
            val binaryProfile = apk.getEntry("assets/dexopt/baseline.prof")
                ?: throw GradleException("Release APK does not contain assets/dexopt/baseline.prof.")
            val binaryMetadata = apk.getEntry("assets/dexopt/baseline.profm")
                ?: throw GradleException("Release APK does not contain assets/dexopt/baseline.profm.")
            verifyBinaryProfileSizes(binaryProfile.size, binaryMetadata.size, "Release APK")
            if (binaryProfile.method != ZipEntry.STORED || binaryMetadata.method != ZipEntry.STORED) {
                throw GradleException("Compiled Baseline Profile entries must be stored uncompressed.")
            }
        }

        ZipFile(releaseBundle.get().asFile).use { bundle ->
            val binaryProfile = bundle.getEntry(BUNDLE_PROFILE_PATH)
                ?: throw GradleException("Release AAB does not contain $BUNDLE_PROFILE_PATH.")
            val binaryMetadata = bundle.getEntry(BUNDLE_METADATA_PATH)
                ?: throw GradleException("Release AAB does not contain $BUNDLE_METADATA_PATH.")
            verifyBinaryProfileSizes(binaryProfile.size, binaryMetadata.size, "Release AAB")
            logger.lifecycle(
                "Verified {} Baseline rules, {} Startup rules, and packaged APK/AAB binary profiles ({} bytes).",
                baselineRules.size,
                startupRules.size,
                binaryProfile.size,
            )
        }
    }

    private fun verifyBinaryProfileSizes(profileSize: Long, metadataSize: Long, artifact: String) {
        if (profileSize <= 0L || profileSize >= MAX_BINARY_PROFILE_BYTES) {
            throw GradleException("$artifact Baseline Profile size $profileSize is outside the allowed range.")
        }
        if (metadataSize <= 0L || metadataSize >= MAX_BINARY_PROFILE_BYTES) {
            throw GradleException("$artifact Baseline Profile metadata size $metadataSize is outside the allowed range.")
        }
    }

    private fun profileRules(file: File): List<String> = file.readLines(Charsets.UTF_8)
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun profileSignature(rule: String): String = rule.trimStart('H', 'S', 'P')

    private fun isNanoKvmRule(rule: String): Boolean = "Lorg/nanokvm/mobile/" in rule

    private companion object {
        const val MAX_BINARY_PROFILE_BYTES = 1_500_000L
        const val BUNDLE_PROFILE_PATH =
            "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof"
        const val BUNDLE_METADATA_PATH =
            "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.profm"
    }
}

android {
    namespace = "org.nanokvm.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.nanokvm.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = project.version.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs.named("debug") {
        developmentKeystorePath.orNull?.let { configuredPath ->
            val configuredKeystore = rootProject.file(configuredPath)
            require(configuredKeystore.isFile) {
                "The configured NanoKVM development keystore does not exist: $configuredKeystore"
            }
            storeFile = configuredKeystore
            storePassword = developmentStorePassword.get()
            keyAlias = developmentKeyAlias.get()
            keyPassword = developmentKeyPassword.get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":video"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    // Biometric 1.1.0 still declares Fragment 1.2.5. Fragment 1.3+ is required for
    // Activity Result permission request codes; use the current stable line explicitly.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    baselineProfile(project(":macrobenchmark"))
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    mustRunAfter("generateBaselineProfile")
}

tasks.register<VerifyReleaseProfiles>("verifyReleaseProfiles") {
    group = "verification"
    description = "Verifies generated Baseline/Startup Profiles and release APK packaging."
    dependsOn("assembleRelease", "bundleRelease")
    baselineProfile.set(layout.projectDirectory.file("src/main/generated/baselineProfiles/baseline-prof.txt"))
    startupProfile.set(layout.projectDirectory.file("src/main/generated/baselineProfiles/startup-prof.txt"))
    releaseApk.set(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk"))
    releaseBundle.set(layout.buildDirectory.file("outputs/bundle/release/app-release.aab"))
}

val rawCycloneDxSbom = layout.buildDirectory.file("reports/cyclonedx/raw.cdx.json")

tasks.cyclonedxDirectBom {
    includeConfigs = listOf("releaseRuntimeClasspath")
    projectType = Component.Type.APPLICATION
    includeBomSerialNumber = false
    includeLicenseText = false
    includeMetadataResolution = true
    includeBuildEnvironment = false
    includeBuildSystem = false
    componentGroup = "org.nanokvm"
    componentName = "nanokvm-mobile"
    componentVersion = project.version.toString()
    jsonOutput.set(rawCycloneDxSbom)
    xmlOutput.unsetConvention()
}

tasks.register<NormalizeCycloneDxSbom>("reproducibleSbom") {
    group = "verification"
    description = "Generates a canonical release-runtime CycloneDX JSON SBOM."
    dependsOn(tasks.cyclonedxDirectBom)
    inputFile.set(rawCycloneDxSbom)
    outputFile.set(layout.buildDirectory.file("reports/cyclonedx/nanokvm-mobile.cdx.json"))
}
