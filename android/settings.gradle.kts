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

rootProject.name = "ClaudeRemote"
include(":app")
include(":core:core:ssh")
include(":core:core:tmux")
include(":core:core:ui")
include(":features:features:chat")
include(":features:features:session")
include(":features:features:settings")
