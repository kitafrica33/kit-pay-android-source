pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The Kit Pay LiveKit fork is an immutable checked-in publication. Exclusive
        // content prevents this private coordinate from falling through to a remote
        // repository (or being shadowed by a developer-local Maven publication).
        exclusiveContent {
            forRepository {
                maven {
                    name = "KitPayVendoredLiveKit"
                    url = uri(rootDir.resolve("third_party/livekit/maven"))
                    metadataSources {
                        gradleMetadata()
                        mavenPom()
                        artifact()
                    }
                }
            }
            filter { includeGroup("africa.kit.livekit") }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.davidliu") }
        }
        maven {
            name = "SignalBuildArtifacts"
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
            content { includeGroup("org.signal") }
        }
    }
}

rootProject.name = "kit-wallet"
include(":app")
