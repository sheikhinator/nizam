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
        // LibretroDroid (Atlas Arcade's emulator front-end) publishes only here, not to Maven Central
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Nizam"
include(":app")
