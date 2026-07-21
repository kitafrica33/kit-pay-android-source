import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.security.MessageDigest

// Top-level build file. Plugin versions live in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

val verifyVendoredLiveKit = tasks.register<Exec>("verifyVendoredLiveKit") {
    group = "verification"
    description = "Verifies the exact checked-in JAIN-free LiveKit fork and source."
    commandLine(
        "python3",
        "-B",
        file("third_party/livekit/verify.py"),
        "--root",
        rootDir,
    )
}

/*
 * The product owner selected AGPL-3.0-only source distribution for official libsignal 0.97.4.
 * Only the two upstream Android coordinates recorded in NOTICE are
 * permitted. This remains a build/release control, not a cryptographic implementation or a
 * readiness approval.
 */
val approvedSignalVersion = "0.97.4"
val approvedSignalModules = mapOf(
    "org.signal:libsignal-android" to approvedSignalVersion,
    "org.signal:libsignal-client" to approvedSignalVersion,
)
val approvedSignalArtifactHashes = mapOf(
    "org.signal:libsignal-android:$approvedSignalVersion:aar" to
        "95830032dcf066979a55d451a91a549a7d5d216f07b6d2cde955c85f7658084f",
    "org.signal:libsignal-client:$approvedSignalVersion:jar" to
        "ce8f6b76c5b7fa3a8031ebe0e4abaf499f28e76b4eb9f6457d8a8d706d94b971",
)

data class ReviewedGoogleRuntimeArtifact(
    val coordinate: String,
    val aarSha256: String,
    val pomSha256: String,
)

data class ReviewedRuntimeGraphSnapshot(
    val componentCount: Int,
    val directComponentCount: Int,
    val dependencyEdgeCount: Int,
    val graphSha256: String,
)

data class ReviewedRuntimeGraphManifest(
    val reviewedRuntimeInventorySha256: String,
    val gradleResolution: ReviewedRuntimeGraphSnapshot,
    val aabDependenciesMetadata: ReviewedRuntimeGraphSnapshot,
    val cyclonedx: ReviewedRuntimeGraphSnapshot,
)

fun parseReviewedGoogleRuntimeArtifacts(value: Any?, source: String): List<ReviewedGoogleRuntimeArtifact> {
    val records = value as? List<*>
        ?: throw GradleException("$source must contain a reviewed_artifacts array.")
    val coordinatePattern = Regex("^[^: ]+:[^: ]+:[^: ]+$")
    val sha256Pattern = Regex("^[0-9a-f]{64}$")
    val artifacts = records.mapIndexed { index, rawRecord ->
        val record = rawRecord as? Map<*, *>
            ?: throw GradleException("$source reviewed artifact $index must be an object.")
        val keys = record.keys.map(Any?::toString).toSet()
        check(keys == setOf("coordinate", "aar_sha256", "pom_sha256")) {
            "$source reviewed artifact $index has unexpected or missing fields: ${keys.sorted()}."
        }
        val coordinate = record["coordinate"] as? String
        val aarSha256 = record["aar_sha256"] as? String
        val pomSha256 = record["pom_sha256"] as? String
        check(coordinate != null && coordinatePattern.matches(coordinate)) {
            "$source reviewed artifact $index has an invalid Maven coordinate."
        }
        check(aarSha256 != null && sha256Pattern.matches(aarSha256)) {
            "$source reviewed artifact $coordinate has an invalid AAR SHA-256."
        }
        check(pomSha256 != null && sha256Pattern.matches(pomSha256)) {
            "$source reviewed artifact $coordinate has an invalid POM SHA-256."
        }
        ReviewedGoogleRuntimeArtifact(coordinate, aarSha256, pomSha256)
    }
    check(artifacts.size == 6 && artifacts.map { it.coordinate }.toSet().size == 6) {
        "$source must identify exactly six unique reviewed Google/Firebase artifacts."
    }
    return artifacts
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun parseReviewedRuntimeGraphSnapshot(
    value: Any?,
    source: String,
): ReviewedRuntimeGraphSnapshot {
    val record = value as? Map<*, *>
        ?: throw GradleException("$source must be an object.")
    val expectedKeys = setOf(
        "component_count",
        "dependency_edge_count",
        "direct_component_count",
        "graph_sha256",
    )
    check(record.keys.map(Any?::toString).toSet() == expectedKeys) {
        "$source has unexpected or missing fields."
    }
    fun positiveInt(field: String): Int = (record[field] as? Number)
        ?.toInt()
        ?.takeIf { it > 0 }
        ?: throw GradleException("$source $field must be a positive integer.")
    val graphSha256 = record["graph_sha256"] as? String
    check(graphSha256 != null && Regex("^[0-9a-f]{64}$").matches(graphSha256)) {
        "$source graph_sha256 is invalid."
    }
    return ReviewedRuntimeGraphSnapshot(
        componentCount = positiveInt("component_count"),
        directComponentCount = positiveInt("direct_component_count"),
        dependencyEdgeCount = positiveInt("dependency_edge_count"),
        graphSha256 = graphSha256,
    )
}

fun moduleCoordinate(component: ResolvedComponentResult): String? =
    (component.id as? ModuleComponentIdentifier)?.let { id ->
        "${id.group}:${id.module}:${id.version}"
    }

fun resolvedModuleDependencies(component: ResolvedComponentResult): Set<String> =
    component.dependencies.map { dependency ->
        val resolved = dependency as? ResolvedDependencyResult
            ?: throw GradleException(
                "The release runtime contains an unresolved dependency from " +
                    "${moduleCoordinate(component) ?: component.id.displayName}.",
            )
        moduleCoordinate(resolved.selected)
            ?: throw GradleException(
                "The release runtime graph contains a non-Maven dependency from " +
                    "${moduleCoordinate(component) ?: component.id.displayName}.",
            )
    }.toSet()

fun runtimeGraphSha256(
    coordinates: Set<String>,
    directCoordinates: Set<String>,
    dependencies: Map<String, Set<String>>,
): String {
    check(coordinates.containsAll(directCoordinates)) {
        "The release runtime direct-dependency set contains an unknown component."
    }
    check(dependencies.keys == coordinates &&
        dependencies.values.all(coordinates::containsAll)
    ) {
        "The release runtime graph contains a missing node or dangling dependency edge."
    }
    val canonical = coordinates.sorted().joinToString(separator = "") { coordinate ->
        val relationship = if (coordinate in directCoordinates) "direct" else "transitive"
        "$coordinate\t$relationship\t" +
            "${dependencies.getValue(coordinate).sorted().joinToString(separator = ",")}\n"
    }
    return sha256(canonical)
}

val reviewedGoogleArtifactInventoryFile = file("gradle/google-fcm-reviewed-artifacts.json")
val reviewedGoogleArtifactInventory = (JsonSlurper().parse(reviewedGoogleArtifactInventoryFile)
    as? Map<*, *>) ?: throw GradleException("The reviewed Google artifact inventory is invalid.")
check(reviewedGoogleArtifactInventory["schema_version"].toString() == "1" &&
    reviewedGoogleArtifactInventory["review_scope"] == "GOOGLE_ANDROID_SDK_AGPL_COMPATIBILITY"
) {
    "The reviewed Google artifact inventory has an unsupported identity or schema."
}
val reviewedGoogleRuntimeArtifacts = parseReviewedGoogleRuntimeArtifacts(
    reviewedGoogleArtifactInventory["artifacts"],
    reviewedGoogleArtifactInventoryFile.invariantSeparatorsPath,
)
val reviewedGoogleRuntimeArtifactsByCoordinate = reviewedGoogleRuntimeArtifacts.associateBy {
    it.coordinate
}
val reviewedRuntimeGraphFile = file("gradle/reviewed-runtime-graph.json")
val reviewedRuntimeGraphDocument = JsonSlurper().parse(reviewedRuntimeGraphFile) as? Map<*, *>
    ?: throw GradleException("The reviewed runtime graph manifest is invalid.")
check(reviewedRuntimeGraphDocument.keys.map(Any?::toString).toSet() == setOf(
    "aab_dependencies_metadata",
    "configuration",
    "cyclonedx",
    "gradle_resolution",
    "reviewed_runtime_inventory_sha256",
    "schema_version",
) && reviewedRuntimeGraphDocument["schema_version"].toString() == "1" &&
    reviewedRuntimeGraphDocument["configuration"] == "releaseRuntimeClasspath"
) {
    "The reviewed runtime graph manifest has an unsupported identity or schema."
}
val reviewedRuntimeInventorySha256 =
    (reviewedRuntimeGraphDocument["reviewed_runtime_inventory_sha256"] as? String)
        ?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
        ?: throw GradleException(
            "The reviewed runtime graph manifest has an invalid inventory SHA-256.",
        )
val reviewedRuntimeGraph = ReviewedRuntimeGraphManifest(
    reviewedRuntimeInventorySha256 = reviewedRuntimeInventorySha256,
    gradleResolution = parseReviewedRuntimeGraphSnapshot(
        reviewedRuntimeGraphDocument["gradle_resolution"],
        "${reviewedRuntimeGraphFile.invariantSeparatorsPath} gradle_resolution",
    ),
    aabDependenciesMetadata = parseReviewedRuntimeGraphSnapshot(
        reviewedRuntimeGraphDocument["aab_dependencies_metadata"],
        "${reviewedRuntimeGraphFile.invariantSeparatorsPath} aab_dependencies_metadata",
    ),
    cyclonedx = parseReviewedRuntimeGraphSnapshot(
        reviewedRuntimeGraphDocument["cyclonedx"],
        "${reviewedRuntimeGraphFile.invariantSeparatorsPath} cyclonedx",
    ),
)
val canonicalRuntimeLicenseRecordFile = listOf(
    file("public-source/THIRD_PARTY_LICENSES.json"),
    file("THIRD_PARTY_LICENSES.json"),
).firstOrNull(File::isFile) ?: file("public-source/THIRD_PARTY_LICENSES.json")
val reviewedGooglePomArtifacts = configurations.create("reviewedGooglePomArtifacts") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    description = "Exact POMs covered by the Google FCM/AGPL distribution review."
}
dependencies {
    reviewedGoogleRuntimeArtifacts.forEach { artifact ->
        add(reviewedGooglePomArtifacts.name, "${artifact.coordinate}@pom")
    }
}

fun isSignalFamilyModule(group: String?, module: String): Boolean {
    val normalizedGroup = group.orEmpty().lowercase()
    val normalizedModule = module.lowercase()
    return normalizedGroup == "org.signal" ||
        normalizedGroup == "org.whispersystems" ||
        normalizedModule.contains("libsignal") ||
        normalizedModule.contains("signal-protocol") ||
        normalizedModule.contains("signal_protocol")
}

val secureMessagingBuildDefinitions = fileTree(rootDir) {
    include("**/*.gradle", "**/*.gradle.kts", "gradle/libs.versions.toml")
    exclude("build.gradle.kts", ".gradle/**", "**/build/**")
}
val secureMessagingLocalArchives = fileTree(rootDir) {
    include("**/*.aar", "**/*.jar")
    exclude(".gradle/**", "**/build/**", "gradle/wrapper/**")
}
val secureMessagingComplianceFiles = files(
    "LICENSE",
    "NOTICE",
    "app/src/main/assets/legal/AGPL-3.0-only.txt",
    "app/src/main/assets/legal/THIRD_PARTY_NOTICES.txt",
    "app/src/main/java/com/kit/wallet/feature/legal/OpenSourceLicence.kt",
)
val secureMessagingRuntimeSources = files(
    "app/src/main/java/com/kit/wallet/di/RepositoryModule.kt",
    "app/src/main/java/com/kit/wallet/di/StorageModule.kt",
    "app/src/main/java/com/kit/wallet/di/SecureMessagingChatModule.kt",
    "app/src/main/java/com/kit/wallet/data/repository/" +
        "EncryptedMessagingUnavailableRepository.kt",
    "app/src/main/java/com/kit/wallet/data/messaging/" +
        "SecureMessagingSyncEngine.kt",
)

val approvedSignalArtifacts = configurations.create("approvedSignalArtifacts") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    description = "Exact official libsignal artifacts approved for Android distribution."
}
dependencies {
    add(approvedSignalArtifacts.name, "org.signal:libsignal-android:$approvedSignalVersion@aar")
    add(approvedSignalArtifacts.name, "org.signal:libsignal-client:$approvedSignalVersion@jar")
}

val verifyApprovedSignalArtifactHashes = tasks.register("verifyApprovedSignalArtifactHashes") {
    group = "verification"
    description = "Resolves and SHA-256 verifies the two approved official libsignal artifacts."
    notCompatibleWithConfigurationCache(
        "This release audit resolves artifacts and hashes their files at execution time.",
    )

    doLast {
        val resolvedArtifacts = approvedSignalArtifacts.resolvedConfiguration.resolvedArtifacts
        val resolvedByCoordinate = resolvedArtifacts.associateBy { artifact ->
            val id = artifact.moduleVersion.id
            "${id.group}:${artifact.name}:${id.version}:${artifact.file.extension.lowercase()}"
        }
        check(resolvedByCoordinate.keys == approvedSignalArtifactHashes.keys) {
            "Resolved libsignal artifact set differs from the exact approved set. Resolved: " +
                resolvedByCoordinate.keys.sorted().joinToString()
        }

        approvedSignalArtifactHashes.forEach { (coordinate, expectedHash) ->
            val artifact = checkNotNull(resolvedByCoordinate[coordinate]) {
                "Approved artifact $coordinate was not resolved."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualHash = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            check(actualHash == expectedHash) {
                "$coordinate failed SHA-256 verification: expected $expectedHash, got $actualHash."
            }
        }
    }
}

val verifyReviewedGoogleRuntimeArtifacts = tasks.register("verifyReviewedGoogleRuntimeArtifacts") {
    group = "verification"
    description = "Verifies the release runtime uses the exact reviewed Google AAR and POM bytes."
    notCompatibleWithConfigurationCache(
        "This release audit resolves release artifacts and hashes their files at execution time.",
    )
    inputs.file(reviewedGoogleArtifactInventoryFile)
        .withPropertyName("reviewedGoogleArtifactInventory")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(reviewedRuntimeGraphFile)
        .withPropertyName("reviewedRuntimeGraph")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(canonicalRuntimeLicenseRecordFile)
        .withPropertyName("runtimeLicenseRecord")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val runtimeLicenseRecord = JsonSlurper().parse(canonicalRuntimeLicenseRecordFile)
            as? Map<*, *>
            ?: throw GradleException("The canonical runtime licence record is invalid.")
        val distributionReview = runtimeLicenseRecord["distribution_review"] as? Map<*, *>
            ?: throw GradleException("The canonical runtime licence record lacks a review object.")
        val isCleared = runtimeLicenseRecord["distribution_clearance"] == true ||
            distributionReview["disposition"] == "CLEARED"
        val recordArtifactsValue = distributionReview["reviewed_artifacts"]
        if (isCleared && recordArtifactsValue == null) {
            throw GradleException(
                "A CLEARED runtime licence record must identify the exact reviewed artifacts.",
            )
        }
        if (recordArtifactsValue != null) {
            val recordArtifacts = parseReviewedGoogleRuntimeArtifacts(
                recordArtifactsValue,
                "${canonicalRuntimeLicenseRecordFile.invariantSeparatorsPath} " +
                    "distribution_review",
            )
            check(recordArtifacts.toSet() == reviewedGoogleRuntimeArtifacts.toSet()) {
                "The signed review artifact list differs from the release-gate inventory."
            }
        }
        check(
            distributionReview["reviewed_runtime_inventory_sha256"] ==
                reviewedRuntimeGraph.reviewedRuntimeInventorySha256,
        ) {
            "The reviewed runtime graph is not bound to the signed inventory digest."
        }
        check(
            distributionReview["reviewed_runtime_graph_manifest_sha256"] ==
                sha256(reviewedRuntimeGraphFile),
        ) {
            "The reviewed runtime graph differs from its attested manifest digest."
        }

        val expectedCoordinates = reviewedGoogleRuntimeArtifactsByCoordinate.keys
        val expectedByModule = expectedCoordinates.associateBy { it.substringBeforeLast(':') }
        val releaseRuntime = project(":app").configurations.getByName("releaseRuntimeClasspath")
        val resolution = releaseRuntime.incoming.resolutionResult
        val selectedRuntimeComponents = resolution.allComponents
            .mapNotNull { component -> moduleCoordinate(component)?.let { it to component } }
            .toMap()
        val selectedRuntimeCoordinates = selectedRuntimeComponents.keys
        val selectedDirectCoordinates = resolution.root.dependencies.map { dependency ->
            val resolved = dependency as? ResolvedDependencyResult
                ?: throw GradleException("The release runtime has an unresolved direct dependency.")
            moduleCoordinate(resolved.selected)
                ?: throw GradleException("The release runtime has a non-Maven direct dependency.")
        }.toSet()
        val selectedRuntimeDependencies = selectedRuntimeComponents.mapValues { (_, component) ->
            resolvedModuleDependencies(component)
        }
        val recordedComponents = runtimeLicenseRecord["components"] as? List<*>
            ?: throw GradleException("The canonical runtime component inventory is invalid.")
        val recordedRuntimeRelationships = recordedComponents.mapNotNull { rawComponent ->
            val component = rawComponent as? Map<*, *>
                ?: throw GradleException("The canonical runtime component inventory is invalid.")
            val coordinate = component["coordinate"] as? String
                ?: throw GradleException("A canonical runtime component lacks its coordinate.")
            when (val relationship = component["relationship"]) {
                "direct", "transitive" -> coordinate to relationship
                "coreLibraryDesugaring" -> null
                else -> throw GradleException(
                    "The canonical runtime component has an unsupported relationship.",
                )
            }
        }.toMap()
        val recordedRuntimeCoordinates = recordedRuntimeRelationships.keys
        val recordedDirectCoordinates = recordedRuntimeRelationships
            .filterValues { it == "direct" }
            .keys
        check(recordedRuntimeCoordinates.size == 188 &&
            selectedRuntimeCoordinates == recordedRuntimeCoordinates
        ) {
            "releaseRuntimeClasspath differs from the complete reviewed runtime inventory. " +
                "Missing: ${(recordedRuntimeCoordinates - selectedRuntimeCoordinates).sorted()}; " +
                "unexpected: ${(selectedRuntimeCoordinates - recordedRuntimeCoordinates).sorted()}."
        }
        check(selectedDirectCoordinates == recordedDirectCoordinates) {
            "releaseRuntimeClasspath direct dependencies differ from the reviewed inventory. " +
                "Expected direct: ${recordedDirectCoordinates.sorted()}; selected direct: " +
                "${selectedDirectCoordinates.sorted()}."
        }
        val selectedGraphSha256 = runtimeGraphSha256(
            selectedRuntimeCoordinates,
            selectedDirectCoordinates,
            selectedRuntimeDependencies,
        )
        val expectedGraph = reviewedRuntimeGraph.gradleResolution
        val selectedEdgeCount = selectedRuntimeDependencies.values.sumOf { it.size }
        check(expectedGraph.componentCount == selectedRuntimeCoordinates.size &&
            expectedGraph.directComponentCount == selectedDirectCoordinates.size &&
            expectedGraph.dependencyEdgeCount == selectedEdgeCount &&
            expectedGraph.graphSha256 == selectedGraphSha256
        ) {
            "releaseRuntimeClasspath dependency edges differ from the reviewed graph: " +
                "components=${selectedRuntimeCoordinates.size}, " +
                "direct=${selectedDirectCoordinates.size}, edges=$selectedEdgeCount, " +
                "SHA-256=$selectedGraphSha256."
        }

        val selectedReviewedCoordinates = selectedRuntimeCoordinates
            .mapNotNull { coordinate ->
                val module = coordinate.substringBeforeLast(':')
                coordinate.takeIf { module in expectedByModule }
            }
            .toSet()
        check(selectedReviewedCoordinates == expectedCoordinates) {
            "releaseRuntimeClasspath differs from the six reviewed Google/Firebase coordinates. " +
                "Expected: ${expectedCoordinates.sorted()}; selected: " +
                "${selectedReviewedCoordinates.sorted()}."
        }

        val selectedAars = releaseRuntime.resolvedConfiguration.resolvedArtifacts
            .filter { artifact ->
                val id = artifact.moduleVersion.id
                "${id.group}:${id.name}" in expectedByModule
            }
        val selectedAarsByCoordinate = selectedAars.associateBy { artifact ->
            val id = artifact.moduleVersion.id
            "${id.group}:${id.name}:${id.version}:${artifact.file.extension.lowercase()}"
        }
        val expectedAarCoordinates = expectedCoordinates.map { "$it:aar" }.toSet()
        check(selectedAars.size == selectedAarsByCoordinate.size &&
            selectedAarsByCoordinate.keys == expectedAarCoordinates
        ) {
            "The reviewed release-runtime AAR set is incomplete, duplicated, or has the wrong type: " +
                selectedAarsByCoordinate.keys.sorted().joinToString()
        }
        reviewedGoogleRuntimeArtifacts.forEach { reviewed ->
            val resolved = checkNotNull(selectedAarsByCoordinate["${reviewed.coordinate}:aar"])
            val actualHash = sha256(resolved.file)
            check(actualHash == reviewed.aarSha256) {
                "${reviewed.coordinate} AAR failed SHA-256 verification: expected " +
                    "${reviewed.aarSha256}, got $actualHash."
            }
        }

        val resolvedPoms = reviewedGooglePomArtifacts.resolvedConfiguration.resolvedArtifacts
        val resolvedPomsByCoordinate = resolvedPoms.associateBy { artifact ->
            val id = artifact.moduleVersion.id
            "${id.group}:${id.name}:${id.version}:${artifact.file.extension.lowercase()}"
        }
        val expectedPomCoordinates = expectedCoordinates.map { "$it:pom" }.toSet()
        check(resolvedPoms.size == resolvedPomsByCoordinate.size &&
            resolvedPomsByCoordinate.keys == expectedPomCoordinates
        ) {
            "The reviewed POM set is incomplete, duplicated, or has the wrong type: " +
                resolvedPomsByCoordinate.keys.sorted().joinToString()
        }
        reviewedGoogleRuntimeArtifacts.forEach { reviewed ->
            val resolved = checkNotNull(resolvedPomsByCoordinate["${reviewed.coordinate}:pom"])
            val actualHash = sha256(resolved.file)
            check(actualHash == reviewed.pomSha256) {
                "${reviewed.coordinate} POM failed SHA-256 verification: expected " +
                    "${reviewed.pomSha256}, got $actualHash."
            }
        }
    }
}

val verifySecureMessagingDependencyGate = tasks.register("verifySecureMessagingDependencyGate") {
    group = "verification"
    description = "Allows only official libsignal 0.97.4 and verifies AGPL distribution controls."
    dependsOn(verifyApprovedSignalArtifactHashes, verifyReviewedGoogleRuntimeArtifacts)
    notCompatibleWithConfigurationCache(
        "This release audit reads build definitions, legal files, and secure-messaging bindings.",
    )
    inputs.files(secureMessagingBuildDefinitions)
        .withPropertyName("buildDefinitions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(secureMessagingLocalArchives)
        .withPropertyName("localArchives")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(secureMessagingComplianceFiles)
        .withPropertyName("complianceFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(secureMessagingRuntimeSources)
        .withPropertyName("runtimeSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val prohibitedLegacyDefinition = Regex(
            pattern = "(org[.]whispersystems|signal[-_.]?protocol)",
            option = RegexOption.IGNORE_CASE,
        )
        val prohibitedDefinitions = secureMessagingBuildDefinitions.files
            .filter { prohibitedLegacyDefinition.containsMatchIn(it.readText()) }
            .map { it.invariantSeparatorsPath }
            .sorted()
        val prohibitedLocalArchives = secureMessagingLocalArchives.files
            .filter {
                isSignalFamilyModule(group = null, module = it.nameWithoutExtension) ||
                    prohibitedLegacyDefinition.containsMatchIn(it.name)
            }
            .map { it.invariantSeparatorsPath }
            .sorted()

        if (prohibitedDefinitions.isNotEmpty() || prohibitedLocalArchives.isNotEmpty()) {
            val details = buildList {
                addAll(prohibitedDefinitions.map { "build definition: $it" })
                addAll(prohibitedLocalArchives.map { "local archive: $it" })
            }.joinToString(separator = "\n - ", prefix = "\n - ")
            throw GradleException(
                "Unapproved secure-messaging dependency detected.$details\n" +
                    "Only the pinned official org.signal libsignal artifacts are approved; " +
                    "legacy coordinates and local protocol archives remain forbidden. " +
                    "See BUILDING.md in the public corresponding-source release and NOTICE.",
            )
        }

        val versionCatalog = file("gradle/libs.versions.toml").readText()
        val declaredSignalCoordinates = Regex("org[.]signal:([A-Za-z0-9_.-]+)")
            .findAll(versionCatalog)
            .map { match -> "org.signal:${match.groupValues[1]}" }
            .toList()
        check(declaredSignalCoordinates.size == approvedSignalModules.size &&
            declaredSignalCoordinates.toSet() == approvedSignalModules.keys
        ) {
            "The version catalog must declare only the two approved libsignal modules: " +
                approvedSignalModules.keys.sorted().joinToString().plus(".")
        }
        check(
            Regex("(?m)^libsignal = \"${Regex.escape(approvedSignalVersion)}\"$")
                .findAll(versionCatalog)
                .count() == 1,
        ) {
            "libsignal must be pinned exactly once to $approvedSignalVersion in the version catalog."
        }
        approvedSignalModules.keys.forEach { coordinate ->
            check(versionCatalog.contains("module = \"$coordinate\", version.ref = \"libsignal\"")) {
                "$coordinate must use the single pinned libsignal version catalog entry."
            }
        }

        val settingsDefinition = file("settings.gradle.kts").readText()
        check(
            settingsDefinition.contains(
                "url = uri(\"https://build-artifacts.signal.org/libraries/maven/\")",
            ) && settingsDefinition.contains("content { includeGroup(\"org.signal\") }"),
        ) {
            "Official libsignal artifacts must come from the group-filtered Signal repository."
        }

        val appDefinition = file("app/build.gradle.kts").readText()
        listOf(
            "implementation(libs.libsignal.android)",
            "implementation(libs.libsignal.client)",
        ).forEach { declaration ->
            check(Regex(Regex.escape(declaration)).findAll(appDefinition).count() == 1) {
                "The app must declare $declaration exactly once."
            }
        }

        val projectLicence = file("LICENSE").readText()
        val projectNotice = file("NOTICE").readText()
        check(projectLicence.contains("GNU AFFERO GENERAL PUBLIC LICENSE") &&
            projectLicence.contains("Version 3, 19 November 2007") &&
            projectLicence.contains("END OF TERMS AND CONDITIONS")
        ) {
            "LICENSE must contain the complete GNU AGPL version 3 text."
        }
        check(file("app/src/main/assets/legal/AGPL-3.0-only.txt").readText() == projectLicence) {
            "The AGPL text bundled in the APK must exactly match the project LICENSE."
        }
        check(projectNotice.contains("org.signal:libsignal-android:$approvedSignalVersion") &&
            projectNotice.contains("org.signal:libsignal-client:$approvedSignalVersion") &&
            projectNotice.contains("https://github.com/kitafrica33/kit-pay-android-source") &&
            projectNotice.contains("Copyright (C) 2026 KIT POS UGANDA LIMITED") &&
            projectNotice.contains("WITHOUT ANY WARRANTY")
        ) {
            "NOTICE must identify Kit's copyright/warranty/source and both libsignal artifacts."
        }
        check(file("app/src/main/assets/legal/THIRD_PARTY_NOTICES.txt").readText() == projectNotice) {
            "The notices bundled in the APK must exactly match the project NOTICE."
        }
        val legalNoticeUi = file(
            "app/src/main/java/com/kit/wallet/feature/legal/OpenSourceLicence.kt",
        ).readText()
        check(legalNoticeUi.contains("OpenSourceLicenceDialog") &&
            legalNoticeUi.contains("Copyright (C) 2026 KIT POS UGANDA LIMITED") &&
            legalNoticeUi.contains("without any warranty") &&
            legalNoticeUi.contains("Read full GNU AGPL") &&
            legalNoticeUi.contains("releases/tag/v\$versionName-code\$versionCode")
        ) {
            "The interactive AGPL copyright, warranty, licence and exact-source notice is missing."
        }

        val repositoryBinding = inputs.files.files
            .single { it.name == "RepositoryModule.kt" }
            .readText()
        val storageBinding = inputs.files.files
            .single { it.name == "StorageModule.kt" }
            .readText()
        val chatRuntimeBinding = inputs.files.files
            .single { it.name == "SecureMessagingChatModule.kt" }
            .readText()
        val unavailableRepository = inputs.files.files
            .single { it.name == "EncryptedMessagingUnavailableRepository.kt" }
            .readText()
        val unavailableSyncEngine = inputs.files.files
            .single { it.name == "SecureMessagingSyncEngine.kt" }
            .readText()
        check(repositoryBinding.contains("implementation: EncryptedChatRepository")) {
            "ChatRepository must remain bound to the reviewed encrypted implementation."
        }
        check(repositoryBinding.contains("implementation: RealSecureMessagingSyncEngine")) {
            "SecureMessagingSyncEngine must remain bound to the reviewed encrypted sync engine."
        }
        check(storageBinding.contains("implementation: LibSignalSecureMessagingCryptoEngine") &&
            storageBinding.contains("implementation: LibSignalSecureMessagingKeyActivation") &&
            storageBinding.contains("implementation: RealSecureMessagingInitialSyncActivation")
        ) {
            "Secure-messaging crypto, key activation, and initial sync must use the reviewed implementations."
        }
        check(chatRuntimeBinding.contains("implementation: DefaultSecureMessagingChatRuntime")) {
            "The encrypted chat repository must retain its reviewed runtime binding."
        }
        check(!repositoryBinding.contains("implementation: EncryptedMessagingUnavailableRepository") &&
            !repositoryBinding.contains("implementation: SecureMessagingUnavailableSyncEngine")
        ) {
            "Production secure-messaging bindings must not silently regress to unavailable stubs."
        }
        check(unavailableRepository.contains("MutableStateFlow(false)")) {
            "The retained unavailable chat stub must remain fail closed."
        }
        check(unavailableSyncEngine.contains("override val isReady: Boolean = false")) {
            "The retained unavailable sync stub must remain fail closed."
        }
    }
}

/*
 * Production phone sign-in uses Kit's server OTP and local SMS provider. Keep the dormant
 * Firebase Phone Auth/Google Identity/Integrity stack out of every Android configuration so a
 * future transitive dependency cannot silently restore a second phone-identity authority.
 */
val prohibitedFirebasePhoneModules = setOf(
    "androidx.credentials:credentials-play-services-auth",
    "com.google.android.gms:play-services-auth",
    "com.google.android.gms:play-services-auth-api-phone",
    "com.google.android.gms:play-services-auth-base",
    "com.google.android.gms:play-services-fido",
    "com.google.android.libraries.identity.googleid:googleid",
    "com.google.android.play:core-common",
    "com.google.android.play:integrity",
    "com.google.android.recaptcha:recaptcha",
    "com.google.firebase:firebase-appcheck-interop",
    "com.google.firebase:firebase-auth",
    "com.google.firebase:firebase-auth-interop",
)
val localSmsPolicyFiles = files(
    "app/build.gradle.kts",
    "gradle/libs.versions.toml",
    "app/src/main/java/com/kit/wallet/data/auth/AuthRepository.kt",
    "app/src/main/java/com/kit/wallet/data/auth/RemoteAuthRepository.kt",
    "app/src/main/java/com/kit/wallet/data/remote/ApiModels.kt",
    "app/src/main/java/com/kit/wallet/data/remote/KitWalletApi.kt",
    "app/src/main/java/com/kit/wallet/feature/auth/AuthViewModel.kt",
)
val verifyLocalSmsAuthPolicy = tasks.register("verifyLocalSmsAuthPolicy") {
    group = "verification"
    description = "Enforces local server-OTP phone auth and excludes dormant Firebase Auth."
    inputs.files(localSmsPolicyFiles)
        .withPropertyName("localSmsPolicyFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        check(!file("app/src/main/java/com/kit/wallet/data/auth/FirebasePhoneAuthClient.kt").exists()) {
            "FirebasePhoneAuthClient must remain absent; production phone sign-in uses server OTP."
        }
        val forbiddenSourceMarkers = listOf(
            "com.google.firebase.auth",
            "PhoneAuthProvider",
            "FirebasePhoneLoginRequest",
            "AuthChallengeKind.FIREBASE_PHONE",
            "api/kit-wallet/v1/auth/firebase/phone",
        )
        localSmsPolicyFiles.files.forEach { source ->
            val contents = source.readText()
            forbiddenSourceMarkers.forEach { marker ->
                check(!contents.contains(marker)) {
                    "${source.invariantSeparatorsPath} restores prohibited phone-auth marker $marker."
                }
            }
        }
        val authViewModel = file(
            "app/src/main/java/com/kit/wallet/feature/auth/AuthViewModel.kt",
        ).readText()
        check(authViewModel.contains("check(capabilities.serverPhoneOtp)")) {
            "Phone sign-in must fail closed unless the backend advertises server phone OTP."
        }
        val api = file("app/src/main/java/com/kit/wallet/data/remote/KitWalletApi.kt").readText()
        check(api.contains("api/kit-wallet/v1/auth/otp/request") &&
            api.contains("api/kit-wallet/v1/auth/otp/verify")
        ) {
            "The local SMS request/verify API contract is missing."
        }
    }
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val requestedCoordinate = "${requested.group}:${requested.name}"
            val requestedGroup = requested.group.orEmpty().lowercase()
            val requestedModule = requested.name.lowercase()
            if (
                requestedGroup == "javax.sip" ||
                requestedGroup.startsWith("gov.nist") ||
                requestedModule.contains("jain-sip") ||
                requestedModule.contains("jain_sip")
            ) {
                throw GradleException(
                    "$requestedCoordinate is prohibited. Kit Pay uses the reviewed " +
                        "JAIN-free LiveKit fork.",
                )
            }
            if (
                requestedModule == "livekit-android" &&
                (requested.group != "africa.kit.livekit" ||
                    requested.version != "2.27.0-kitpay.1")
            ) {
                throw GradleException(
                    "${requested.group}:${requested.name}:${requested.version} is not approved. " +
                        "Only africa.kit.livekit:livekit-android:2.27.0-kitpay.1 may resolve.",
                )
            }
            if (requestedCoordinate in prohibitedFirebasePhoneModules) {
                throw GradleException(
                    "$requestedCoordinate is prohibited by the local server-OTP phone-auth policy.",
                )
            }
            if (isSignalFamilyModule(requested.group, requested.name)) {
                val coordinate = "${requested.group}:${requested.name}"
                val approvedVersion = approvedSignalModules[coordinate]
                if (approvedVersion != requested.version) {
                    throw GradleException(
                        "$coordinate:${requested.version} is not approved. Only " +
                            approvedSignalModules.entries
                                .sortedBy { it.key }
                                .joinToString { "${it.key}:${it.value}" } +
                            " may be resolved; legacy, alternate-version, forked, and local " +
                            "Signal dependencies remain blocked.",
                    )
                }
            }
        }
        resolutionStrategy.componentSelection {
            all {
                if (isSignalFamilyModule(candidate.group, candidate.module)) {
                    val coordinate = "${candidate.group}:${candidate.module}"
                    val approvedVersion = approvedSignalModules[coordinate]
                    if (approvedVersion != candidate.version) {
                        reject(
                            "$coordinate:${candidate.version} is outside the exact approved " +
                                "libsignal dependency set.",
                        )
                    }
                }
            }
        }
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(
            verifySecureMessagingDependencyGate,
            verifyLocalSmsAuthPolicy,
            verifyVendoredLiveKit,
        )
    }
}
