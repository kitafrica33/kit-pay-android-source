import com.android.bundle.AppDependenciesOuterClass.AppDependencies
import com.android.tools.build.bundletool.model.AppBundle
import com.android.tools.build.bundletool.validation.AppBundleValidator
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

// Release-only AAB parser. The caller supplies a checksum-verified bundletool-all JAR;
// aapt2's APK badging command cannot parse Android App Bundles.
initscript {
    dependencies {
        classpath(
            files(
                System.getProperty("kitPayBundletoolJar")
                    ?: throw IllegalArgumentException("-DkitPayBundletoolJar is required."),
            ),
        )
    }
}

val bundletoolJar = System.getProperty("kitPayBundletoolJar")
    ?.let(::File)
    ?.takeIf { it.isFile }
    ?: throw GradleException("-DkitPayBundletoolJar must identify the verified bundletool JAR.")
val aab = System.getProperty("kitPayReleaseAab")
    ?.let(::File)
    ?.takeIf { it.isFile }
    ?: throw GradleException("-DkitPayReleaseAab must identify the release AAB.")
val expectedPackage = System.getProperty("kitPayExpectedPackage")
    ?.takeIf { it.matches(Regex("[a-z][a-z0-9_]*(?:[.][a-z][a-z0-9_]*)+")) }
    ?: throw GradleException("-DkitPayExpectedPackage is invalid.")
val expectedVersionCode = System.getProperty("kitPayExpectedVersionCode")
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: throw GradleException("-DkitPayExpectedVersionCode is invalid.")
val expectedVersionName = System.getProperty("kitPayExpectedVersionName")
    ?.takeIf { it.isNotBlank() }
    ?: throw GradleException("-DkitPayExpectedVersionName is invalid.")
val reviewedRuntimeGraphFile = System.getProperty("kitPayReviewedRuntimeGraph")
    ?.let(::File)
    ?.takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }
    ?: throw GradleException(
        "-DkitPayReviewedRuntimeGraph must identify the reviewed runtime graph manifest.",
    )
val runtimeLicenseRecordFile = System.getProperty("kitPayRuntimeLicenseRecord")
    ?.let(::File)
    ?.takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }
    ?: throw GradleException(
        "-DkitPayRuntimeLicenseRecord must identify the canonical runtime licence record.",
    )

data class RuntimeGraphSnapshot(
    val componentCount: Int,
    val directComponentCount: Int,
    val dependencyEdgeCount: Int,
    val graphSha256: String,
)

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun requiredPositiveInt(record: Map<*, *>, field: String, source: String): Int =
    (record[field] as? Number)
        ?.toInt()
        ?.takeIf { it > 0 }
        ?: throw GradleException("$source $field must be a positive integer.")

fun parseRuntimeGraphSnapshot(value: Any?, source: String): RuntimeGraphSnapshot {
    val record = value as? Map<*, *>
        ?: throw GradleException("$source must be an object.")
    check(record.keys.map(Any?::toString).toSet() == setOf(
        "component_count",
        "dependency_edge_count",
        "direct_component_count",
        "graph_sha256",
    )) {
        "$source has unexpected or missing fields."
    }
    val graphSha256 = record["graph_sha256"] as? String
    check(graphSha256 != null && Regex("^[0-9a-f]{64}$").matches(graphSha256)) {
        "$source graph_sha256 is invalid."
    }
    return RuntimeGraphSnapshot(
        componentCount = requiredPositiveInt(record, "component_count", source),
        directComponentCount = requiredPositiveInt(record, "direct_component_count", source),
        dependencyEdgeCount = requiredPositiveInt(record, "dependency_edge_count", source),
        graphSha256 = graphSha256,
    )
}

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
            "The reviewed runtime graph manifest has an invalid inventory digest.",
        )
val reviewedAabGraph = parseRuntimeGraphSnapshot(
    reviewedRuntimeGraphDocument["aab_dependencies_metadata"],
    "${reviewedRuntimeGraphFile.invariantSeparatorsPath} aab_dependencies_metadata",
)

val runtimeLicenseRecord = JsonSlurper().parse(runtimeLicenseRecordFile) as? Map<*, *>
    ?: throw GradleException("The canonical runtime licence record is invalid.")
val distributionReview = runtimeLicenseRecord["distribution_review"] as? Map<*, *>
    ?: throw GradleException("The canonical runtime licence record lacks its review object.")
check(distributionReview["reviewed_runtime_inventory_sha256"] ==
    reviewedRuntimeInventorySha256
) {
    "The final-AAB graph is not bound to the signed runtime-inventory digest."
}
check(distributionReview["reviewed_runtime_graph_manifest_sha256"] ==
    sha256(reviewedRuntimeGraphFile)
) {
    "The final-AAB graph differs from its attested manifest digest."
}
val runtimeComponents = runtimeLicenseRecord["components"] as? List<*>
    ?: throw GradleException("The canonical runtime licence record lacks its component inventory.")
val reviewedRuntimeRelationships = mutableMapOf<String, String>()
val inventoryLines = runtimeComponents.map { rawComponent ->
    val component = rawComponent as? Map<*, *>
        ?: throw GradleException("The canonical runtime component inventory is malformed.")
    val coordinate = component["coordinate"] as? String
        ?: throw GradleException("A canonical runtime component lacks its coordinate.")
    val relationship = component["relationship"] as? String
        ?: throw GradleException("A canonical runtime component lacks its relationship.")
    val evidence = component["declared_evidence"] as? String
        ?: throw GradleException("A canonical runtime component lacks its licence evidence.")
    if (relationship == "direct" || relationship == "transitive") {
        check(reviewedRuntimeRelationships.put(coordinate, relationship) == null) {
            "The canonical runtime inventory contains duplicate coordinates."
        }
    } else {
        check(relationship == "coreLibraryDesugaring") {
            "The canonical runtime inventory contains an unsupported relationship."
        }
    }
    "$coordinate\t$relationship\t$evidence\n"
}
check(sha256(inventoryLines.sorted().joinToString(separator = "")) ==
    reviewedRuntimeInventorySha256
) {
    "The canonical runtime component inventory differs from its signed digest."
}

fun verifyFinalAabRuntimeGraph(archive: ZipFile) {
    val metadataPath =
        "BUNDLE-METADATA/com.android.tools.build.libraries/dependencies.pb"
    val matchingEntries = archive.entries().asSequence()
        .filter { it.name == metadataPath }
        .toList()
    check(matchingEntries.size == 1 && !matchingEntries.single().isDirectory) {
        "The final AAB must contain exactly one $metadataPath file."
    }
    val dependencies = archive.getInputStream(matchingEntries.single()).use {
        AppDependencies.parseFrom(it)
    }
    val coordinates = dependencies.libraryList.mapIndexed { index, library ->
        check(library.hasMavenLibrary()) {
            "The final AAB dependency at index $index is not a Maven component."
        }
        val maven = library.mavenLibrary
        check(maven.groupId.isNotBlank() && maven.artifactId.isNotBlank() &&
            maven.version.isNotBlank() && !maven.hasPackaging() && !maven.hasClassifier()
        ) {
            "The final AAB dependency at index $index has an incomplete or qualified identity."
        }
        "${maven.groupId}:${maven.artifactId}:${maven.version}"
    }
    check(coordinates.size == coordinates.toSet().size) {
        "The final AAB dependency inventory contains duplicate Maven coordinates."
    }
    check(coordinates.toSet() == reviewedRuntimeRelationships.keys) {
        "The final AAB dependency coordinates differ from the reviewed runtime inventory. " +
            "Missing: ${(reviewedRuntimeRelationships.keys - coordinates.toSet()).sorted()}; " +
            "unexpected: ${(coordinates.toSet() - reviewedRuntimeRelationships.keys).sorted()}."
    }

    check(dependencies.moduleDependenciesCount == 1 &&
        dependencies.moduleDependenciesList.single().moduleName == "base"
    ) {
        "The final AAB must contain exactly one base-module dependency root."
    }
    val moduleDependencies = dependencies.moduleDependenciesList.single()
    val directIndices = moduleDependencies.dependencyIndexList
    check(directIndices.all { it in coordinates.indices } &&
        directIndices.size == directIndices.toSet().size
    ) {
        "The final AAB base-module dependency indices are invalid or duplicated."
    }
    val directCoordinates = directIndices.map(coordinates::get).toSet()
    val expectedDirectCoordinates = reviewedRuntimeRelationships
        .filterValues { it == "direct" }
        .keys
    check(directCoordinates == expectedDirectCoordinates) {
        "The final AAB direct dependencies differ from the reviewed runtime inventory."
    }

    val dependenciesByIndex = mutableMapOf<Int, MutableSet<Int>>()
    dependencies.libraryDependenciesList.forEach { record ->
        check(record.libraryIndex in coordinates.indices) {
            "The final AAB dependency graph contains an invalid source index."
        }
        check(dependenciesByIndex.put(record.libraryIndex, mutableSetOf()) == null) {
            "The final AAB dependency graph contains duplicate source records."
        }
        check(record.libraryDepIndexList.all { it in coordinates.indices }) {
            "The final AAB dependency graph contains a dangling dependency index."
        }
        dependenciesByIndex.getValue(record.libraryIndex)
            .addAll(record.libraryDepIndexList)
    }
    val graphLines = coordinates.indices.map { index ->
        val coordinate = coordinates[index]
        val relationship = if (index in directIndices) "direct" else "transitive"
        val children = dependenciesByIndex[index]
            .orEmpty()
            .map(coordinates::get)
            .sorted()
            .joinToString(separator = ",")
        "$coordinate\t$relationship\t$children\n"
    }
    val graphSha256 = sha256(graphLines.sorted().joinToString(separator = ""))
    val edgeCount = dependenciesByIndex.values.sumOf { it.size }
    check(reviewedAabGraph.componentCount == coordinates.size &&
        reviewedAabGraph.directComponentCount == directCoordinates.size &&
        reviewedAabGraph.dependencyEdgeCount == edgeCount &&
        reviewedAabGraph.graphSha256 == graphSha256
    ) {
        "The final AAB dependency graph differs from the reviewed metadata: " +
            "components=${coordinates.size}, direct=${directCoordinates.size}, " +
            "edges=$edgeCount, SHA-256=$graphSha256."
    }
}

rootProject {
    tasks.register("verifyReleaseAabIdentity") {
        description = "Verifies the release AAB protobuf manifest identity with pinned bundletool."
        inputs.file(bundletoolJar)
        inputs.file(aab)
        inputs.file(reviewedRuntimeGraphFile)
        inputs.file(runtimeLicenseRecordFile)
        doLast {
            ZipFile(aab).use { archive ->
                val validator = AppBundleValidator.create()
                validator.validateFile(archive)
                val bundle = AppBundle.buildFromZip(archive)
                validator.validate(bundle)
                val manifest = bundle.baseModule.androidManifest
                val versionCode = manifest.versionCode.orElse(-1)
                val versionName = manifest.versionName.orElse("")
                if (bundle.packageName != expectedPackage ||
                    versionCode != expectedVersionCode ||
                    versionName != expectedVersionName
                ) {
                    throw GradleException(
                        "The release AAB manifest identity does not match the candidate.",
                    )
                }
                verifyFinalAabRuntimeGraph(archive)
            }
        }
    }
}
