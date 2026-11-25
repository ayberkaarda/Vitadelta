// Maven Central rate-limits shared CI runners (HTTP 429). The Google-hosted mirror of Maven Central
// is tried first; artifacts it does not hold fall through to the original repositories.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}

rootProject.name = "vitadelta-api"
