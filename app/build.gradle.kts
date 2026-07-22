import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.zip.ZipFile

abstract class GenerateBundledRuntimeLegalAssets : DefaultTask() {
    private data class BundledNotice(
        val content: ByteArray,
        val projectNames: MutableSet<String> = sortedSetOf(),
        val sourceArchives: MutableSet<String> = sortedSetOf(),
    )

    @get:Classpath
    abstract val runtimeArtifacts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val outputRoot = outputDirectory.get().asFile
        outputRoot.deleteRecursively()
        outputRoot.mkdirs()

        val googleNoticesByDigest = sortedMapOf<String, BundledNotice>()
        val archives = runtimeArtifacts.files
            .filter { it.extension.lowercase() in setOf("aar", "jar") }
            .sortedBy { it.name }
        val duplicateArchiveNames = archives.groupBy { it.name }
            .filterValues { candidates -> candidates.size > 1 }
            .keys
            .sorted()
        check(duplicateArchiveNames.isEmpty()) {
            "Runtime archive names collide in the legal-asset output: " +
                duplicateArchiveNames.joinToString()
        }

        archives.forEach { archive ->
                val archiveDirectory = archive.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ZipFile(archive).use { zip ->
                    val googleNoticeIndex = zip.getEntry("third_party_licenses.json")
                    val googleNoticeText = zip.getEntry("third_party_licenses.txt")
                    if (googleNoticeIndex != null && googleNoticeText != null) {
                        val index = zip.getInputStream(googleNoticeIndex).bufferedReader().use {
                            @Suppress("UNCHECKED_CAST")
                            JsonSlurper().parse(it) as Map<String, Map<String, Number>>
                        }
                        val completeText = zip.getInputStream(googleNoticeText).use { it.readBytes() }
                        index.toSortedMap().forEach { (projectName, position) ->
                            val start = runCatching {
                                checkNotNull(position["start"])
                                    .toString()
                                    .toBigDecimal()
                                    .longValueExact()
                            }.getOrElse {
                                throw IllegalArgumentException(
                                    "Non-integral or out-of-range bundled-notice start in " +
                                        "${archive.name} for $projectName",
                                    it,
                                )
                            }
                            val length = runCatching {
                                checkNotNull(position["length"])
                                    .toString()
                                    .toBigDecimal()
                                    .longValueExact()
                            }.getOrElse {
                                throw IllegalArgumentException(
                                    "Non-integral or out-of-range bundled-notice length in " +
                                        "${archive.name} for $projectName",
                                    it,
                                )
                            }
                            val completeTextSize = completeText.size.toLong()
                            check(start >= 0L && length >= 0L &&
                                start <= completeTextSize &&
                                length <= completeTextSize &&
                                start <= completeTextSize - length
                            ) {
                                "Invalid bundled-notice offsets in ${archive.name} for $projectName"
                            }
                            val startIndex = start.toInt()
                            val endIndex = (start + length).toInt()
                            val content = completeText.copyOfRange(startIndex, endIndex)
                            val digest = MessageDigest.getInstance("SHA-256")
                                .digest(content)
                                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                            val notice = googleNoticesByDigest.getOrPut(digest) {
                                BundledNotice(content = content)
                            }
                            check(notice.content.contentEquals(content)) {
                                "SHA-256 collision while collecting bundled notices"
                            }
                            notice.projectNames += projectName
                            notice.sourceArchives += archive.name
                        }
                    }

                    zip.entries().asSequence()
                        .filterNot { it.isDirectory }
                        .filter { entry ->
                            val path = entry.name
                            val basename = path.substringAfterLast('/')
                            path != "third_party_licenses.json" &&
                                path != "third_party_licenses.txt" &&
                                !(path == "LICENSE" && googleNoticeText != null) &&
                                (path == "okhttp3/internal/publicsuffix/NOTICE" ||
                                (path.startsWith("META-INF/") &&
                                    (basename.startsWith("LICENSE", ignoreCase = true) ||
                                        basename.startsWith("NOTICE", ignoreCase = true) ||
                                        basename == "AL2.0" ||
                                        basename == "LGPL2.1")) ||
                                (path.count { it == '/' } == 0 &&
                                    (basename.startsWith("LICENSE", ignoreCase = true) ||
                                        basename.startsWith("NOTICE", ignoreCase = true))))
                        }
                        .sortedBy { it.name }
                        .forEach { entry ->
                            require(!entry.name.startsWith('/') &&
                                entry.name.split('/').none { it == ".." } &&
                                entry.name.none { it == '\\' || it == '\u0000' || it == ':' }
                            ) {
                                "Unsafe legal-resource path in ${archive.name}: ${entry.name}"
                            }
                            val archiveOutputRoot = outputRoot.resolve(
                                "legal/runtime-bundled/$archiveDirectory",
                            ).canonicalFile
                            archiveOutputRoot.mkdirs()
                            val target = archiveOutputRoot.resolve(entry.name).canonicalFile
                            require(target.toPath().startsWith(archiveOutputRoot.toPath()) &&
                                target != archiveOutputRoot
                            ) {
                                "Legal-resource path escapes its output root in " +
                                    "${archive.name}: ${entry.name}"
                            }
                            target.parentFile.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                }
            }

        check(googleNoticesByDigest.isNotEmpty()) {
            "No Google AAR third-party notice blocks were found in the release runtime."
        }
        val aggregate = outputRoot.resolve("legal/GOOGLE_BUNDLED_THIRD_PARTY_NOTICES.txt")
        aggregate.parentFile.mkdirs()
        aggregate.outputStream().buffered().use { output ->
            output.write(
                (
                    "Google Android dependency bundled third-party notices\n" +
                        "======================================================\n\n" +
                        "These are the exact unique notice blocks embedded in the Google AARs " +
                        "used by this build. Project and source-archive labels come from those " +
                        "AARs. Duplicate blocks are included once.\n\n"
                ).toByteArray(Charsets.UTF_8),
            )
            googleNoticesByDigest.forEach { (digest, notice) ->
                output.write(
                    (
                        "================================================================================\n" +
                            "Projects: ${notice.projectNames.joinToString()}\n" +
                            "Source archives: ${notice.sourceArchives.joinToString()}\n" +
                            "SHA-256: $digest\n" +
                            "--------------------------------------------------------------------------------\n"
                    ).toByteArray(Charsets.UTF_8),
                )
                output.write(notice.content)
                output.write("\n\n".toByteArray(Charsets.UTF_8))
            }
        }
    }
}

abstract class GenerateBuildProvenanceAsset : DefaultTask() {
    @get:Input
    abstract val applicationIdValue: Property<String>

    @get:Input
    abstract val versionNameValue: Property<String>

    @get:Input
    abstract val versionCodeValue: Property<Int>

    @get:Input
    abstract val internalCommit: Property<String>

    @get:Input
    abstract val sourceDirty: Property<Boolean>

    @get:Input
    abstract val sourceOrigin: Property<String>

    @get:Input
    abstract val firebaseConfigured: Property<Boolean>

    @get:Input
    abstract val firebaseClientSha256: Property<String>

    @get:Input
    abstract val artifactChannel: Property<String>

    @get:Input
    abstract val nativeAbis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val commit = internalCommit.get()
        val origin = sourceOrigin.get()
        check(
            (origin in setOf("internal-git", "public-corresponding-source") &&
                commit.matches(Regex("^[0-9a-f]{40}$"))) ||
                (origin == "unversioned-source" && commit.isEmpty()),
        ) {
            "The build provenance commit is invalid."
        }
        check(origin != "unversioned-source" || sourceDirty.get()) {
            "An unversioned source build cannot claim a clean source state."
        }
        val configured = firebaseConfigured.get()
        val firebaseSha = firebaseClientSha256.get()
        check(
            (configured && firebaseSha.matches(Regex("^[0-9a-f]{64}$"))) ||
                (!configured && firebaseSha.isEmpty()),
        ) { "The build provenance FCM configuration fingerprint is invalid." }
        val channel = artifactChannel.get()
        val abis = nativeAbis.get()
        check(
            (channel == "play" && abis ==
                listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) ||
                (channel == "sideload" && abis == listOf("arm64-v8a")),
        ) { "The build provenance artifact channel/ABI selection is invalid." }

        val outputRoot = outputDirectory.get().asFile
        outputRoot.deleteRecursively()
        outputRoot.mkdirs()
        val document = linkedMapOf<String, Any?>(
            "schema_version" to 1,
            "application_id" to applicationIdValue.get(),
            "version_name" to versionNameValue.get(),
            "version_code" to versionCodeValue.get(),
            "internal_commit" to commit.takeIf { it.isNotEmpty() },
            "source_dirty" to sourceDirty.get(),
            "source_origin" to origin,
            "firebase_configured" to configured,
            "firebase_android_client_sha256" to firebaseSha.takeIf { configured },
            "firebase_purpose" to "fcm",
            "artifact_channel" to channel,
            "native_abis" to abis,
        )
        outputRoot.resolve("provenance/KIT_PAY_RELEASE_PROVENANCE.json").apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n")
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val kitWalletBaseUrl = providers.gradleProperty("KIT_WALLET_BASE_URL")
    .orElse("https://pay.kit.africa/")
    .map { raw -> if (raw.endsWith('/')) raw else "$raw/" }
    .map { normalized ->
        require(normalized.startsWith("https://")) {
            "KIT_WALLET_BASE_URL must use HTTPS"
        }
        normalized
    }
val kitPrivacyNoticeVersion = providers.gradleProperty("KIT_PRIVACY_NOTICE_VERSION")
    .orElse("kit-privacy-2026-07")
val kitPayApplicationId = "com.kit.wallet"
val kitPayPlayAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val kitPayApprovedSideloadAbi = "arm64-v8a"
val kitPaySideloadAbi = providers.gradleProperty("KIT_PAY_SIDELOAD_ABI").orNull
val explicitlyRequestedTasks = gradle.startParameter.taskNames
    .map { requested -> requested.substringAfterLast(':') }
if (kitPaySideloadAbi != null) {
    require(kitPaySideloadAbi == kitPayApprovedSideloadAbi) {
        "KIT_PAY_SIDELOAD_ABI must be exactly $kitPayApprovedSideloadAbi."
    }
    require(
        explicitlyRequestedTasks.contains("assembleRelease") &&
            explicitlyRequestedTasks.all { task -> task in setOf("clean", "assembleRelease") },
    ) {
        "KIT_PAY_SIDELOAD_ABI is accepted only for an explicit assembleRelease invocation; " +
            "build bundleRelease separately without this property."
    }
}

val firebaseConfigPath = providers.gradleProperty("KIT_PAY_FIREBASE_CONFIG")
    .orElse(providers.environmentVariable("KIT_PAY_FIREBASE_CONFIG"))
    .orElse("${System.getProperty("user.home")}/.config/kit-pay/firebase/google-services.json")
val firebaseConfigFile = file(firebaseConfigPath.get())
val firebaseValues: Map<String, String> = if (firebaseConfigFile.isFile) {
    val root = JsonSlurper().parse(firebaseConfigFile) as Map<*, *>
    val project = root["project_info"] as Map<*, *>
    val client = (root["client"] as List<*>)
        .map { it as Map<*, *> }
        .first { candidate ->
            val info = candidate["client_info"] as Map<*, *>
            val android = info["android_client_info"] as Map<*, *>
            android["package_name"] == "com.kit.wallet"
        }
    val clientInfo = client["client_info"] as Map<*, *>
    val apiKey = (client["api_key"] as List<*>).first() as Map<*, *>
    mapOf(
        "projectId" to project["project_id"].toString(),
        "senderId" to project["project_number"].toString(),
        "applicationId" to clientInfo["mobilesdk_app_id"].toString(),
        "apiKey" to apiKey["current_key"].toString(),
    )
} else {
    emptyMap()
}

val firebaseClientConfigSha256 = if (firebaseValues.isNotEmpty()) {
    val projectId = firebaseValues.getValue("projectId")
    val senderId = firebaseValues.getValue("senderId")
    val applicationId = firebaseValues.getValue("applicationId")
    val apiKey = firebaseValues.getValue("apiKey")
    require(projectId == "kit-pay-africa") { "Unexpected Firebase project ID." }
    require(senderId.matches(Regex("^[0-9]{6,20}$"))) { "Invalid Firebase sender ID." }
    require(
        applicationId.matches(Regex("^1:[0-9]{6,20}:android:[0-9a-f]{16,64}$")) &&
            applicationId.split(':')[1] == senderId,
    ) { "Invalid Firebase Android application ID." }
    require(apiKey.matches(Regex("^AIza[0-9A-Za-z_-]{35}$"))) {
        "Invalid Firebase Android API key."
    }
    val minimalClientJson = """
        {
          "project_info": {
            "project_number": "$senderId",
            "project_id": "$projectId"
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "$applicationId",
                "android_client_info": {
                  "package_name": "$kitPayApplicationId"
                }
              },
              "api_key": [
                {
                  "current_key": "$apiKey"
                }
              ]
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent() + "\n"
    MessageDigest.getInstance("SHA-256")
        .digest(minimalClientJson.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
} else {
    ""
}

val gitMetadataAvailable = rootDir.resolve(".git").exists()
val publicSourceProvenanceFile = rootDir.resolve("SOURCE_PROVENANCE.json")

fun quotedBuildValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.kit.wallet"
    compileSdk = 36

    defaultConfig {
        applicationId = kitPayApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.2.1"

        if (kitPaySideloadAbi != null) {
            ndk { abiFilters += kitPaySideloadAbi }
        }

        buildConfigField(
            "String",
            "KIT_WALLET_BASE_URL",
            "\"${kitWalletBaseUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
        buildConfigField(
            "String",
            "KIT_PRIVACY_NOTICE_VERSION",
            quotedBuildValue(kitPrivacyNoticeVersion.get()),
        )
        // Photo messaging ships dormant in 0.2.0 (code 12): the backend's v2 content profile
        // still rejects encrypted_attachment sends (MESSAGING_V2_CONTENT_PROFILE_UNAVAILABLE),
        // and the server-side client-version rollout exposes new wire shapes to 0.2.1 (code 13)
        // and later. Flip alongside that rollout; the implementation and its tests stay compiled.
        buildConfigField("boolean", "MEDIA_MESSAGING_ENABLED", "true")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseValues.isNotEmpty().toString())
        buildConfigField("String", "FIREBASE_PROJECT_ID", quotedBuildValue(firebaseValues["projectId"].orEmpty()))
        buildConfigField("String", "FIREBASE_SENDER_ID", quotedBuildValue(firebaseValues["senderId"].orEmpty()))
        buildConfigField("String", "FIREBASE_APPLICATION_ID", quotedBuildValue(firebaseValues["applicationId"].orEmpty()))
        buildConfigField("String", "FIREBASE_API_KEY", quotedBuildValue(firebaseValues["apiKey"].orEmpty()))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            excludes += "**/libsignal_jni_testing.so"
        }
        resources {
            merges += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
            )
            excludes += setOf(
                "libsignal_jni*.dylib",
                "signal_jni*.dll",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

val configuredVersionName = requireNotNull(android.defaultConfig.versionName) {
    "The Android defaultConfig versionName is required."
}
val configuredVersionCode = requireNotNull(android.defaultConfig.versionCode) {
    "The Android defaultConfig versionCode is required."
}
val publicSourceProvenance: Map<*, *>? = if (!gitMetadataAvailable &&
    publicSourceProvenanceFile.isFile
) {
    (JsonSlurper().parse(publicSourceProvenanceFile) as? Map<*, *>)?.also { provenance ->
        val verifiedFirebase = requireNotNull(
            provenance["firebase_android_client"] as? Map<*, *>,
        ) { "The public corresponding-source FCM provenance is missing." }
        require(
            provenance["schema_version"] == 1 &&
                provenance["package_name"] == kitPayApplicationId &&
                provenance["version_name"] == configuredVersionName &&
                provenance["version_code"] == configuredVersionCode &&
                provenance["release_tag"] ==
                    "v${configuredVersionName}-code${configuredVersionCode}" &&
                provenance["public_repository"] ==
                    "https://github.com/kitafrica33/kit-pay-android-source" &&
                (provenance["internal_release_commit"] as? String)
                    ?.matches(Regex("^[0-9a-f]{40}$")) == true &&
                provenance["contents_manifest"] == "SHA256SUMS" &&
                verifiedFirebase["project_id"] == "kit-pay-africa" &&
                verifiedFirebase["package_name"] == kitPayApplicationId &&
                verifiedFirebase["contains_server_credentials"] == false &&
                (verifiedFirebase["sha256"] as? String)
                    ?.matches(Regex("^[0-9a-f]{64}$")) == true,
        ) { "The public corresponding-source provenance is invalid." }
        if (firebaseValues.isNotEmpty()) {
            require(verifiedFirebase["sha256"] == firebaseClientConfigSha256) {
                "The configured FCM client differs from public source provenance."
            }
        }
    }
} else {
    null
}

val bundledRuntimeLegalAssets = tasks.register<GenerateBundledRuntimeLegalAssets>(
    "generateBundledRuntimeLegalAssets",
) {
    description = "Preserves licence and notice files from packaged runtime AARs and JARs."
    runtimeArtifacts.from(configurations.named("releaseRuntimeClasspath"))
    outputDirectory.set(layout.buildDirectory.dir("generated/runtime-legal-assets"))
}

val buildProvenanceAsset = tasks.register<GenerateBuildProvenanceAsset>(
    "generateBuildProvenanceAsset",
) {
    description = "Embeds non-secret source and FCM-input provenance in built artifacts."
    applicationIdValue.set(kitPayApplicationId)
    versionNameValue.set(configuredVersionName)
    versionCodeValue.set(configuredVersionCode)
    if (gitMetadataAvailable) {
        internalCommit.set(
            providers.exec {
                workingDir(rootDir)
                commandLine("git", "rev-parse", "--verify", "HEAD")
            }.standardOutput.asText.map { it.trim() },
        )
        sourceDirty.set(
            providers.exec {
                workingDir(rootDir)
                commandLine("git", "status", "--porcelain", "--untracked-files=all")
            }.standardOutput.asText.map { it.isNotBlank() },
        )
        sourceOrigin.set("internal-git")
    } else if (publicSourceProvenance != null) {
        internalCommit.set(publicSourceProvenance["internal_release_commit"] as String)
        // A source archive has no Git index with which to prove that it remains unmodified.
        sourceDirty.set(true)
        sourceOrigin.set("public-corresponding-source")
    } else {
        internalCommit.set("")
        sourceDirty.set(true)
        sourceOrigin.set("unversioned-source")
    }
    firebaseConfigured.set(firebaseValues.isNotEmpty())
    firebaseClientSha256.set(firebaseClientConfigSha256)
    artifactChannel.set(if (kitPaySideloadAbi == null) "play" else "sideload")
    nativeAbis.set(
        if (kitPaySideloadAbi == null) kitPayPlayAbis else listOf(kitPaySideloadAbi),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/build-provenance-assets"))
}

android.sourceSets.getByName("main").assets.srcDir(
    bundledRuntimeLegalAssets.flatMap { it.outputDirectory },
)
android.sourceSets.getByName("main").assets.srcDir(
    buildProvenanceAsset.flatMap { it.outputDirectory },
)

tasks.matching { task ->
    (task.name.startsWith("merge") && task.name.endsWith("Assets")) ||
        task.name.contains("Lint", ignoreCase = true)
}.configureEach {
    dependsOn(bundledRuntimeLegalAssets)
    dependsOn(buildProvenanceAsset)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.core)
    implementation(libs.moshi.kotlin)
    implementation(libs.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging) {
        exclude(group = "com.google.firebase", module = "firebase-measurement-connector")
    }
    implementation(libs.livekit.android)
    implementation(libs.libsignal.android)
    implementation(libs.libsignal.client)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
