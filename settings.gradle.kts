pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            // 只允許從 JitPack 取得 libsu，避免其他相依套件被誤導到這個來源
            content { includeGroup("com.github.topjohnwu.libsu") }
        }
    }
}

rootProject.name = "AdvanceHotspot"
include(":app")
