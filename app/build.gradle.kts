import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.cyclonedx.model.Component
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.security.MessageDigest
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

val bundledAboutAssetsDirectory = layout.buildDirectory.dir("generated/about-assets")
val bundledAboutDocuments = mapOf(
    rootProject.file("LICENSE") to "GPL-3.0-or-later.txt",
    rootProject.file("NOTICE") to "NOTICE.txt",
    rootProject.file("THIRD_PARTY_NOTICES.md") to "THIRD_PARTY_NOTICES.md",
    rootProject.file("PRIVACY.md") to "PRIVACY.md",
    rootProject.file("SECURITY.md") to "SECURITY.md",
)
val bundledWebRtcNotices = layout.projectDirectory
    .file("src/main/assets/open_source_licenses/WEBRTC.md")
    .asFile
val bundledWebRtcWrapperLicense = layout.projectDirectory
    .file("src/main/assets/open_source_licenses/WEBRTC_SDK_ANDROID_LICENSE.txt")
    .asFile
val bundledApacheLicense = layout.projectDirectory
    .file("src/main/assets/open_source_licenses/APACHE-2.0.txt")
    .asFile
val bundledProtobufLicense = layout.projectDirectory
    .file("src/main/assets/open_source_licenses/PROTOBUF-4.28.2-LICENSE.txt")
    .asFile
val bundledIjgNotice = layout.projectDirectory
    .file("src/main/assets/open_source_licenses/README.ijg")
    .asFile
val bundledRuntimeComponentLicenses = layout.projectDirectory
    .file("src/main/assets/open_source_licenses/RUNTIME_COMPONENT_LICENSES.md")
    .asFile
val expectedWebRtcNoticesSha256 =
    "d1f9382c6878ac024155fd6d44a5977329108bb8b0a01cea40e4a2f1d7de252e"
val expectedWebRtcWrapperLicenseSha256 =
    "e6b282fe6c0fb353928923470457f31b44cbab203effd60c0cde4a5bb96c8aec"
val expectedApacheLicenseSha256 =
    "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
val expectedProtobufLicenseSha256 =
    "6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d"
val expectedIjgNoticeSha256 =
    "75815e3bf6484201a3c3d17a1bbf10f2e8e3237f84df10a2357ea896db2a81d6"
val expectedRuntimeComponentLicensesSha256 =
    "536db15041cb08129b58a6ccab1d294dc691c27ef13b0cf0569b23d3a2890f8a"
val runtimeComponentLicenseIndexVersion = project.version.toString()

val generateBundledAboutAssets by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies the canonical project notices and policies into the app."
    into(bundledAboutAssetsDirectory)
    bundledAboutDocuments.forEach { (source, bundledName) ->
        from(source) {
            into("about")
            rename { bundledName }
        }
    }
}

@CacheableTask
abstract class VerifyBundledAboutAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceDocuments: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedDocumentsDirectory: DirectoryProperty

    @get:Input
    abstract val bundledDocumentNames: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pinnedLicenceFiles: ConfigurableFileCollection

    @get:Input
    abstract val expectedSha256ByFileName: MapProperty<String, String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeComponentLicenceIndex: RegularFileProperty

    @get:Input
    abstract val runtimeComponentLicenceIndexVersion: Property<String>

    @get:Input
    abstract val resolvedRuntimeCoordinates: SetProperty<String>

    @TaskAction
    fun verify() {
        fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }

        val pinnedByName = pinnedLicenceFiles.files.associateBy(File::getName)
        val expectedHashes = expectedSha256ByFileName.get()
        require(pinnedByName.keys == expectedHashes.keys) {
            "Pinned licence/notice files do not match the reviewed hash inventory."
        }
        expectedHashes.forEach { (fileName, expectedHash) ->
            val file = pinnedByName.getValue(fileName)
            require(file.isFile) { "A pinned licence/notice file is missing: $file" }
            val actualHash = sha256(file)
            require(actualHash == expectedHash) {
                "$fileName has SHA-256 $actualHash; expected $expectedHash."
            }
        }

        val runtimeIndex = runtimeComponentLicenceIndex.get().asFile
        val runtimeIndexText = runtimeIndex.readText(Charsets.UTF_8)
        val expectedVersion = runtimeComponentLicenceIndexVersion.get()
        require("for version $expectedVersion." in runtimeIndexText) {
            "The runtime component licence index does not identify project version $expectedVersion."
        }
        val coordinatePattern = Regex("^- `([^`]+:[^`]+:[^`]+)`$")
        val documentedCoordinates = runtimeIndexText
            .lineSequence()
            .mapNotNull { line -> coordinatePattern.matchEntire(line)?.groupValues?.get(1) }
            .toList()
        require(documentedCoordinates.size == documentedCoordinates.toSet().size) {
            "The runtime component licence index contains duplicate coordinates."
        }
        val documentedCoordinateSet = documentedCoordinates.toSet()
        val resolvedCoordinateSet = resolvedRuntimeCoordinates.get()
        require(documentedCoordinateSet == resolvedCoordinateSet) {
            val missing = (resolvedCoordinateSet - documentedCoordinateSet).sorted()
            val stale = (documentedCoordinateSet - resolvedCoordinateSet).sorted()
            "Runtime component licence index drift. Missing: $missing; stale: $stale"
        }

        val bundledNames = bundledDocumentNames.get()
        val outputDirectory = generatedDocumentsDirectory.get().asFile.resolve("about")
        sourceDocuments.files.forEach { source ->
            val bundledName = bundledNames.getValue(source.name)
            val bundled = outputDirectory.resolve(bundledName)
            require(source.isFile && bundled.isFile && source.readBytes().contentEquals(bundled.readBytes())) {
                "Bundled About document $bundledName does not exactly match $source."
            }
        }
    }
}

val resolvedRuntimeCoordinateProvider = providers.provider {
    configurations
        .getByName("releaseRuntimeClasspath")
        .incoming
        .resolutionResult
        .allComponents
        .mapNotNull { component ->
            (component.id as? ModuleComponentIdentifier)?.let { identifier ->
                "${identifier.group}:${identifier.module}:${identifier.version}"
            }
        }
        .toSortedSet()
}

val verifyBundledAboutAssets = tasks.register<VerifyBundledAboutAssets>("verifyBundledAboutAssets") {
    group = "verification"
    description = "Verifies bundled project documents and complete runtime licence materials."
    dependsOn(generateBundledAboutAssets)
    sourceDocuments.from(bundledAboutDocuments.keys)
    generatedDocumentsDirectory.set(bundledAboutAssetsDirectory)
    bundledDocumentNames.set(bundledAboutDocuments.mapKeys { (source, _) -> source.name })
    pinnedLicenceFiles.from(
        bundledWebRtcNotices,
        bundledWebRtcWrapperLicense,
        bundledApacheLicense,
        bundledProtobufLicense,
        bundledIjgNotice,
        bundledRuntimeComponentLicenses,
    )
    expectedSha256ByFileName.set(
        mapOf(
            bundledWebRtcNotices.name to expectedWebRtcNoticesSha256,
            bundledWebRtcWrapperLicense.name to expectedWebRtcWrapperLicenseSha256,
            bundledApacheLicense.name to expectedApacheLicenseSha256,
            bundledProtobufLicense.name to expectedProtobufLicenseSha256,
            bundledIjgNotice.name to expectedIjgNoticeSha256,
            bundledRuntimeComponentLicenses.name to expectedRuntimeComponentLicensesSha256,
        ),
    )
    runtimeComponentLicenceIndex.set(
        layout.projectDirectory.file("src/main/assets/open_source_licenses/RUNTIME_COMPONENT_LICENSES.md"),
    )
    runtimeComponentLicenceIndexVersion.set(runtimeComponentLicenseIndexVersion)
    resolvedRuntimeCoordinates.set(resolvedRuntimeCoordinateProvider)
}

@CacheableTask
abstract class NormalizeCycloneDxSbom : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val webRtcNoticeFile: RegularFileProperty

    @get:Input
    abstract val releaseVersion: Property<String>

    @get:Input
    abstract val releaseVcsUrl: Property<String>

    @get:Input
    abstract val webRtcVcsUrl: Property<String>

    @get:Input
    abstract val webRtcVersion: Property<String>

    @get:Input
    abstract val bundledWebRtcNoticePath: Property<String>

    @get:Input
    abstract val bundledWebRtcNoticeUrl: Property<String>

    @get:Input
    abstract val upstreamWebRtcNoticeUrl: Property<String>

    @get:Input
    abstract val expectedWebRtcNoticeSha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun normalize() {
        val parsed = JsonSlurper().parse(inputFile.get().asFile) as Map<*, *>
        val enriched = enrichReleaseMetadata(parsed)
        val stableRoot = enriched.entries
            .filterNot { it.key == "serialNumber" }
            .associate { (key, value) ->
                key to if (key == "metadata" && value is Map<*, *>) {
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

    private fun enrichReleaseMetadata(parsed: Map<*, *>): Map<String, Any?> {
        val root = parsed.toMutableStringMap("CycloneDX document")
        val metadata = (root["metadata"] as? Map<*, *>)
            ?.toMutableStringMap("CycloneDX metadata")
            ?: throw GradleException("The CycloneDX document has no metadata object.")
        val rootComponent = (metadata["component"] as? Map<*, *>)
            ?.toMutableStringMap("CycloneDX root component")
            ?: throw GradleException("The CycloneDX document has no root component.")
        assertComponentIdentity(rootComponent, "org.nanokvm", "nanokvm-mobile")
        metadata["component"] = enrichProjectComponent(rootComponent)
        root["metadata"] = metadata

        val components = root["components"] as? Collection<*>
            ?: throw GradleException("The CycloneDX document has no component collection.")
        val missingProjectComponents = mutableSetOf("protocol", "video")
        var foundWebRtc = false
        root["components"] = components.map { rawComponent ->
            val component = (rawComponent as? Map<*, *>)
                ?.toMutableStringMap("CycloneDX component")
                ?: throw GradleException("The CycloneDX component collection contains a non-object value.")
            val group = component["group"]?.toString()
            val name = component["name"]?.toString()
            when {
                group == "org.nanokvm" && name in missingProjectComponents -> {
                    assertComponentIdentity(component, "org.nanokvm", name!!)
                    missingProjectComponents.remove(name)
                    enrichProjectComponent(component)
                }
                group == "io.github.webrtc-sdk" && name == "android-prefixed-stripped" -> {
                    if (foundWebRtc) {
                        throw GradleException("The CycloneDX document contains duplicate WebRTC components.")
                    }
                    if (component["version"]?.toString() != webRtcVersion.get()) {
                        throw GradleException(
                            "Unexpected WebRTC version ${component["version"]}; expected ${webRtcVersion.get()}.",
                        )
                    }
                    foundWebRtc = true
                    enrichWebRtcComponent(component)
                }
                else -> component
            }
        }
        if (missingProjectComponents.isNotEmpty()) {
            throw GradleException(
                "The CycloneDX document is missing internal components: " +
                    missingProjectComponents.sorted().joinToString(", "),
            )
        }
        if (!foundWebRtc) {
            throw GradleException("The CycloneDX document is missing the pinned WebRTC component.")
        }
        return root
    }

    private fun assertComponentIdentity(
        component: Map<String, Any?>,
        expectedGroup: String,
        expectedName: String,
    ) {
        val actualVersion = component["version"]?.toString()
        if (
            component["group"]?.toString() != expectedGroup ||
            component["name"]?.toString() != expectedName ||
            actualVersion != releaseVersion.get()
        ) {
            throw GradleException(
                "Unexpected CycloneDX identity for $expectedGroup:$expectedName: " +
                    "${component["group"]}:${component["name"]}:$actualVersion.",
            )
        }
    }

    private fun enrichProjectComponent(component: MutableMap<String, Any?>): Map<String, Any?> {
        component["licenses"] = listOf(spdxLicense("GPL-3.0-or-later"))
        component["externalReferences"] = externalReferences(component["externalReferences"])
            .filterNot { it["type"]?.toString() == "vcs" } +
            mapOf(
                "type" to "vcs",
                "url" to releaseVcsUrl.get(),
            )
        return component
    }

    private fun enrichWebRtcComponent(component: MutableMap<String, Any?>): Map<String, Any?> {
        val actualNoticeHash = sha256(webRtcNoticeFile.get().asFile)
        val expectedNoticeHash = expectedWebRtcNoticeSha256.get().lowercase()
        if (actualNoticeHash != expectedNoticeHash) {
            throw GradleException(
                "The bundled WebRTC notice SHA-256 is $actualNoticeHash; expected $expectedNoticeHash.",
            )
        }

        component["licenses"] = listOf(
            spdxLicense("MIT"),
            spdxLicense("BSD-3-Clause"),
        )
        component["externalReferences"] = externalReferences(component["externalReferences"])
            .filterNot { reference ->
                reference["type"]?.toString() == "vcs" ||
                    reference["url"]?.toString() == bundledWebRtcNoticeUrl.get()
            } + listOf(
            mapOf(
                "type" to "vcs",
                "url" to webRtcVcsUrl.get(),
            ),
            mapOf(
                "type" to "license",
                "url" to bundledWebRtcNoticeUrl.get(),
                "hashes" to listOf(
                    mapOf(
                        "alg" to "SHA-256",
                        "content" to actualNoticeHash,
                    ),
                ),
            ),
        )
        component["properties"] = properties(component["properties"])
            .filterNot { it["name"]?.toString()?.startsWith(BUNDLED_NOTICE_PROPERTY_PREFIX) == true } +
            listOf(
                mapOf(
                    "name" to "${BUNDLED_NOTICE_PROPERTY_PREFIX}path",
                    "value" to bundledWebRtcNoticePath.get(),
                ),
                mapOf(
                    "name" to "${BUNDLED_NOTICE_PROPERTY_PREFIX}sha256",
                    "value" to actualNoticeHash,
                ),
                mapOf(
                    "name" to "${BUNDLED_NOTICE_PROPERTY_PREFIX}upstream",
                    "value" to upstreamWebRtcNoticeUrl.get(),
                ),
            )
        return component
    }

    private fun externalReferences(value: Any?): List<Map<String, Any?>> = when (value) {
        null -> emptyList()
        is Collection<*> -> value.map { reference ->
            (reference as? Map<*, *>)
                ?.toMutableStringMap("CycloneDX external reference")
                ?: throw GradleException("A CycloneDX external reference is not an object.")
        }
        else -> throw GradleException("CycloneDX externalReferences is not a collection.")
    }

    private fun properties(value: Any?): List<Map<String, Any?>> = when (value) {
        null -> emptyList()
        is Collection<*> -> value.map { property ->
            (property as? Map<*, *>)
                ?.toMutableStringMap("CycloneDX property")
                ?: throw GradleException("A CycloneDX property is not an object.")
        }
        else -> throw GradleException("CycloneDX properties is not a collection.")
    }

    private fun spdxLicense(identifier: String): Map<String, Any?> = mapOf(
        "license" to mapOf("id" to identifier),
    )

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun Map<*, *>.toMutableStringMap(description: String): MutableMap<String, Any?> {
        if (keys.any { it !is String }) {
            throw GradleException("$description contains a non-string key.")
        }
        return entries.associateTo(linkedMapOf()) { (key, value) -> key.toString() to value }
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

    private companion object {
        const val BUNDLED_NOTICE_PROPERTY_PREFIX = "org.nanokvm:bundled-notice:"
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
        versionCode = 14
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
        debug {
            buildConfigField(
                "String",
                "SOURCE_URL",
                "\"https://github.com/andrewginns/nanokvm-mobile/tree/main\"",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField(
                "String",
                "SOURCE_URL",
                "\"https://github.com/andrewginns/nanokvm-mobile/tree/v${project.version}\"",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
            buildConfigField(
                "String",
                "SOURCE_URL",
                "\"https://github.com/andrewginns/nanokvm-mobile/tree/main\"",
            )
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

    sourceSets.getByName("main").assets.srcDir(bundledAboutAssetsDirectory.get().asFile)

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

tasks.named("preBuild").configure {
    dependsOn(verifyBundledAboutAssets)
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
val releaseVersionForSbom = project.version.toString()
val releaseVcsUrlForSbom =
    "https://github.com/andrewginns/nanokvm-mobile/tree/v$releaseVersionForSbom"
val webRtcVersionForSbom = libs.versions.webrtc.get()
val webRtcVcsUrlForSbom =
    "https://github.com/webrtc-sdk/android/tree/v$webRtcVersionForSbom"
val bundledWebRtcNoticeRepositoryPath =
    "app/src/main/assets/open_source_licenses/WEBRTC.md"
val bundledWebRtcNoticeUrlForSbom =
    "https://github.com/andrewginns/nanokvm-mobile/blob/v$releaseVersionForSbom/" +
        bundledWebRtcNoticeRepositoryPath
val upstreamWebRtcNoticeUrlForSbom =
    "https://github.com/webrtc-sdk/android/blob/v$webRtcVersionForSbom/Licenses/WEBRTC.md"

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

val reproducibleSbom = tasks.register<NormalizeCycloneDxSbom>("reproducibleSbom") {
    group = "verification"
    description = "Generates a canonical release-runtime CycloneDX JSON SBOM."
    dependsOn(tasks.cyclonedxDirectBom)
    inputFile.set(rawCycloneDxSbom)
    webRtcNoticeFile.set(
        layout.projectDirectory.file("src/main/assets/open_source_licenses/WEBRTC.md"),
    )
    releaseVersion.set(releaseVersionForSbom)
    releaseVcsUrl.set(releaseVcsUrlForSbom)
    webRtcVersion.set(webRtcVersionForSbom)
    webRtcVcsUrl.set(webRtcVcsUrlForSbom)
    bundledWebRtcNoticePath.set(bundledWebRtcNoticeRepositoryPath)
    bundledWebRtcNoticeUrl.set(bundledWebRtcNoticeUrlForSbom)
    upstreamWebRtcNoticeUrl.set(upstreamWebRtcNoticeUrlForSbom)
    expectedWebRtcNoticeSha256.set(expectedWebRtcNoticesSha256)
    outputFile.set(layout.buildDirectory.file("reports/cyclonedx/nanokvm-mobile.cdx.json"))
}

tasks.register("verifyReproducibleSbomMetadata") {
    group = "verification"
    description = "Verifies canonical release identity, licences, and WebRTC notice provenance in the SBOM."
    dependsOn(reproducibleSbom)
    val normalizedSbom = reproducibleSbom.flatMap { it.outputFile }
    inputs.file(normalizedSbom)
    inputs.property("releaseVcsUrl", releaseVcsUrlForSbom)
    inputs.property("webRtcVcsUrl", webRtcVcsUrlForSbom)
    inputs.property("bundledWebRtcNoticeUrl", bundledWebRtcNoticeUrlForSbom)
    inputs.property("expectedWebRtcNoticeSha256", expectedWebRtcNoticesSha256)

    doLast {
        val sbomText = normalizedSbom.get().asFile.readText(Charsets.UTF_8)
        check(!Regex("\"(?:serialNumber|timestamp)\"\\s*:").containsMatchIn(sbomText)) {
            "The canonical SBOM contains a volatile serial number or timestamp."
        }
        val sbom = JsonSlurper().parseText(sbomText) as Map<*, *>
        val metadata = sbom["metadata"] as? Map<*, *>
            ?: error("The canonical SBOM has no metadata object.")
        val rootComponent = metadata["component"] as? Map<*, *>
            ?: error("The canonical SBOM has no root component.")
        val components = (sbom["components"] as? Collection<*>)
            ?.map { it as? Map<*, *> ?: error("A canonical SBOM component is not an object.") }
            ?: error("The canonical SBOM has no components collection.")

        fun component(group: String, name: String): Map<*, *> = components.single {
            it["group"]?.toString() == group && it["name"]?.toString() == name
        }

        fun licenceIds(value: Map<*, *>): Set<String> =
            ((value["licenses"] as? Collection<*>) ?: emptyList<Any?>()).mapNotNull { choice ->
                val licenceChoice = choice as? Map<*, *> ?: return@mapNotNull null
                val licence = licenceChoice["license"] as? Map<*, *> ?: return@mapNotNull null
                licence["id"]?.toString()
            }.toSet()

        fun references(value: Map<*, *>): List<Map<*, *>> =
            ((value["externalReferences"] as? Collection<*>) ?: emptyList<Any?>()).map {
                it as? Map<*, *> ?: error("A canonical SBOM external reference is not an object.")
            }

        fun assertProjectComponent(value: Map<*, *>, expectedName: String) {
            check(value["group"]?.toString() == "org.nanokvm")
            check(value["name"]?.toString() == expectedName)
            check(value["version"]?.toString() == releaseVersionForSbom)
            check(licenceIds(value) == setOf("GPL-3.0-or-later"))
            val vcsUrls = references(value)
                .filter { it["type"]?.toString() == "vcs" }
                .map { it["url"]?.toString() }
            check(vcsUrls == listOf(releaseVcsUrlForSbom))
        }

        assertProjectComponent(rootComponent, "nanokvm-mobile")
        assertProjectComponent(component("org.nanokvm", "protocol"), "protocol")
        assertProjectComponent(component("org.nanokvm", "video"), "video")

        val webRtc = component("io.github.webrtc-sdk", "android-prefixed-stripped")
        check(webRtc["version"]?.toString() == webRtcVersionForSbom)
        check(licenceIds(webRtc) == setOf("MIT", "BSD-3-Clause"))
        val webRtcReferences = references(webRtc)
        val webRtcVcsUrls = webRtcReferences
            .filter { it["type"]?.toString() == "vcs" }
            .map { it["url"]?.toString() }
        check(webRtcVcsUrls == listOf(webRtcVcsUrlForSbom))
        val bundledNoticeReference = webRtcReferences.single {
            it["type"]?.toString() == "license" &&
                it["url"]?.toString() == bundledWebRtcNoticeUrlForSbom
        }
        val bundledNoticeHashes = bundledNoticeReference["hashes"] as? Collection<*>
            ?: error("The bundled WebRTC notice reference has no hashes.")
        check(bundledNoticeHashes.any { rawHash ->
            val hash = rawHash as? Map<*, *> ?: return@any false
            hash["alg"]?.toString() == "SHA-256" &&
                hash["content"]?.toString() == expectedWebRtcNoticesSha256
        })

        val webRtcProperties = ((webRtc["properties"] as? Collection<*>) ?: emptyList<Any?>())
            .associate { rawProperty ->
                val property = rawProperty as? Map<*, *>
                    ?: error("A canonical SBOM property is not an object.")
                property["name"].toString() to property["value"].toString()
            }
        check(
            webRtcProperties["org.nanokvm:bundled-notice:path"] ==
                bundledWebRtcNoticeRepositoryPath,
        )
        check(
            webRtcProperties["org.nanokvm:bundled-notice:sha256"] ==
                expectedWebRtcNoticesSha256,
        )
        check(
            webRtcProperties["org.nanokvm:bundled-notice:upstream"] ==
                upstreamWebRtcNoticeUrlForSbom,
        )
    }
}
