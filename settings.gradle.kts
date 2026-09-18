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

rootProject.name = "aio-mdm-client-lite"
include(":app")
// The library, built straight from the sibling checkout: clone aio-mdm-lite next to this
// repo (../aio-mdm-lite). A library change is in the next app build, no AAR copying.
include(":mdm-lite")
project(":mdm-lite").projectDir = file("../aio-mdm-lite/mdm-lite")
