pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // کتابخانه‌ی رسمی Psiphon (موتور «سایفون») — AAR باینری از مخزن Maven خود Psiphon-Labs
        maven { url = uri("https://raw.github.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master") }
    }
}
rootProject.name = "vex-android"
include(":app")
