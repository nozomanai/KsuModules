plugins {
    id("com.android.application") version "9.4.1"
    id("com.github.aerath-stuff.ZygoteLoader") version "2a2160f"
}

android {
    namespace = "local.persistfreeze"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "local.persistfreeze"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            excludes += setOf("**/x86/**", "**/x86_64/**", "**/armeabi-v7a/**")
        }
    }

    sourceSets {
        getByName("main") {
            java.directories.add("external/AndroidVMTools/src/main/java")
        }
    }
}

zygisk {
    packages(com.v7878.zygisk.gradle.ZygoteLoader.PACKAGE_SYSTEM_SERVER)
    id = "persist-freeze"
    name = "persist-freeze"
    author = "local"
    description = "适配小米 15（dada）OS3.0.307.0.WOCCNXM（Android 16）。挂钩 system_server 的 ProcessList.startProcessLocked 和 killPackageProcessesLSP。pm disable-user 冻不住带 PERSISTENT 的系统应用（如系统质量服务）：禁用时不杀常驻进程，开机和进程死后又会被拉起。挂钩后这类应用按普通应用冻结，不再启动。需要 NeoZygisk 或 ZygiskNext。方法签名只对应该系统。"
    entrypoint = "local.persistfreeze.ZygoteEntry"
    archiveName = "persist-freeze"
    isAddVariantToArchiveName = false
}

dependencies {
    implementation("com.github.aerath-stuff.PanamaPort:Core:ae4f24e")
    implementation("com.github.aerath-stuff.PanamaPort:Unsafe:ae4f24e")
    implementation("com.github.aerath-stuff.PanamaPort:LLVM:ae4f24e")
    implementation("io.github.vova7878:SunCleanerStub:v1.0.1")
    implementation("io.github.vova7878:R8Annotations:v1.0.1")
}
