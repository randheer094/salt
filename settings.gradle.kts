rootProject.name = "salt"

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

include(":shared", ":server", ":webApp")
