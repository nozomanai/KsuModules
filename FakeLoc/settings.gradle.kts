pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.github.aerath-stuff.ZygoteLoader") {
                useModule("com.github.aerath-stuff.ZygoteLoader:plugin:${requested.version}")
            }
        }
    }
    repositories {
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroupByRegex("com\\.github\\..*") }
        }
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroupByRegex("com\\.github\\..*") }
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        google()
        mavenCentral()
    }
}

rootProject.name = "FakeLoc"
include(":probe")
