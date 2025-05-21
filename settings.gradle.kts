// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral() // Make sure this is present
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral() // <<<< ENSURE THIS LINE EXISTS
        // You might have other repositories here too (like jitpack)
    }
}
rootProject.name = "ExerciseHome" // Or your project name
include(":app")