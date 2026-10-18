pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "InkLedger"
include(":app", ":ledger-core")
// Emulator-only synthetic receipt producer. Never distribute or install on a personal phone.
include(":capture-fixture")
