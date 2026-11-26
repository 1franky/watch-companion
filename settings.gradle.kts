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
    }
}

rootProject.name = "watch-companion"
include(":app-watch")
include(":app-phone")
include(":core:phone-communication")
include(":core:data-layer")
include(":core:permissions")
include(":core:session")
include(":core:diagnostics")
include(":integration:velora-contract")
include(":integration:nexora-contract")
