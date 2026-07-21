import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.gradle.CyclonedxPlugin
import org.cyclonedx.Version
import org.cyclonedx.model.Component
import org.cyclonedx.model.ExternalReference
import org.cyclonedx.model.License
import org.cyclonedx.model.LicenseChoice
import org.cyclonedx.parsers.JsonParser
import org.cyclonedx.parsers.XmlParser
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

// Release-only SBOM generator kept outside the normal application dependency graph.
// The plugin version is pinned so a tagged source tree can reproduce its release evidence.
initscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin:3.3.0")
    }
}

val releaseVersion = System.getProperty("kitPaySbomVersion")
    ?.takeIf { it.matches(Regex("[0-9]+([.][0-9]+){2}([-+][A-Za-z0-9.-]+)?")) }
    ?: throw GradleException("-DkitPaySbomVersion must identify the exact release version.")
val releaseCommit = System.getProperty("kitPaySbomCommit")
    ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
val releaseTag = System.getProperty("kitPaySbomTag")
    ?.takeIf { it.matches(Regex("v[0-9]+([.][0-9]+){2}([-+][A-Za-z0-9.-]+)?-code[1-9][0-9]*")) }

val projectLicense = License().apply {
    id = "AGPL-3.0-only"
    url = "https://spdx.org/licenses/AGPL-3.0-only.html"
}
val projectLicenseChoice = LicenseChoice().apply {
    addLicense(projectLicense)
}
val provenanceReferences = mutableListOf(
    ExternalReference().apply {
        type = ExternalReference.Type.VCS
        url = if (releaseCommit == null) {
            "https://github.com/kitafrica33/kit-pay-android-source"
        } else {
            "https://github.com/kitafrica33/kit-pay-android-source/tree/$releaseCommit"
        }
        comment = if (releaseCommit == null) {
            "Public source repository; no verified public source commit or tag was supplied."
        } else {
            "Exact clean source commit used to generate this SBOM."
        }
    },
)
if (releaseTag != null) {
    provenanceReferences += ExternalReference().apply {
        type = ExternalReference.Type.SOURCE_DISTRIBUTION
        url = "https://github.com/kitafrica33/kit-pay-android-source/releases/tag/$releaseTag"
        comment = "Annotated release tag and corresponding-source location."
    }
}

rootProject {
    apply<CyclonedxPlugin>()

    // The aggregate task walks every resolvable project configuration, including
    // development and test graphs. It must never be used as release evidence.
    tasks.named("cyclonedxBom").configure {
        enabled = false
    }
    tasks.named("cyclonedxDirectBom").configure {
        enabled = false
    }

    val appProject = project(":app")
    val reportDirectory = appProject.layout.buildDirectory.dir(
        "reports/cyclonedx-release-runtime",
    )

    appProject.tasks.withType(CyclonedxDirectTask::class.java).configureEach {
        includeConfigs.set(listOf("releaseRuntimeClasspath"))
        skipConfigs.set(emptyList())
        includeBuildEnvironment.set(false)
        // CycloneDX otherwise derives a run-specific build-system reference from CI
        // environment variables, making release evidence differ from local output.
        includeBuildSystem.set(false)
        includeBomSerialNumber.set(false)
        componentGroup.set("com.kit.wallet")
        componentName.set("kit-pay-android")
        componentVersion.set(releaseVersion)
        projectType.set(Component.Type.APPLICATION)
        licenseChoice.set(projectLicenseChoice)
        externalReferences.set(provenanceReferences)
        jsonOutput.set(reportDirectory.map { it.file("bom.json") })
        xmlOutput.set(reportDirectory.map { it.file("bom.xml") })
    }

    val coordinateOutput = reportDirectory.map { it.file("resolved-components.txt") }
    appProject.tasks.register("writeReleaseRuntimeCoordinates") {
        description = "Writes the exact releaseRuntimeClasspath module set for SBOM validation."
        outputs.file(coordinateOutput)
        doLast {
            val runtime = appProject.configurations.getByName("releaseRuntimeClasspath")
            val coordinates = runtime.incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .map { "${it.group}:${it.module}:${it.version}" }
                .distinct()
                .sorted()
            val output = coordinateOutput.get().asFile
            output.parentFile.mkdirs()
            output.writeText(
                buildString {
                    appendLine("configuration=releaseRuntimeClasspath")
                    coordinates.forEach { appendLine(it) }
                },
            )
        }
    }

    appProject.tasks.register("validateReleaseRuntimeSbomSchema") {
        description = "Validates the finalized release runtime SBOM against CycloneDX 1.6."
        doLast {
            val json = reportDirectory.get().file("bom.json").asFile
            val xml = reportDirectory.get().file("bom.xml").asFile
            if (!json.isFile || !xml.isFile) {
                throw GradleException("The finalized runtime SBOM files are missing.")
            }
            val jsonErrors = JsonParser().validate(json, Version.VERSION_16)
            if (jsonErrors.isNotEmpty()) {
                throw GradleException(
                    "The finalized JSON SBOM is invalid: ${jsonErrors.joinToString("; ")}",
                )
            }
            val xmlErrors = XmlParser().validate(xml, Version.VERSION_16)
            if (xmlErrors.isNotEmpty()) {
                throw GradleException(
                    "The finalized XML SBOM is invalid: ${xmlErrors.joinToString("; ")}",
                )
            }
        }
    }
}
