pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // ANT+ SDK 以本地 AAR 形式提供：app/libs/antpluginlib_3-8-0.aar
        flatDir { dirs("${rootProject.projectDir}/app/libs") }
    }
}

rootProject.name = "RRLogger"
include(":app")
