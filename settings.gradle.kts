pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // مخزن Maven محلی که در CI ساخته می‌شود (AAR سایفون با حذف ران‌تایم تکراری go.*)
        maven { url = uri("$rootDir/local-maven") }
    }
}
rootProject.name = "vex-android"
include(":app")
