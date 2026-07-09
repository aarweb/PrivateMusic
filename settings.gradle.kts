pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "PrivateMusic"
include(":app")
// Esquema y consultas compartidos entre el móvil y el escritorio (Room KMP).
include(":core")
// Reproductor de escritorio (Windows y Linux).
include(":desktop")
