rootProject.name = "commerce-backend"

pluginManagement {
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
    }
}

// Enable Java toolchain auto-download from Foojay (Eclipse Temurin distribution)
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}
