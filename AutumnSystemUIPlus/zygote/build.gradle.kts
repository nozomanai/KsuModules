plugins {
    id("com.android.application")
    id("com.github.aerath-stuff.ZygoteLoader")
}

android {
    namespace = "io.autumn.systemui.zygote"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.autumn.systemui.zygote"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        getByName("main") {
            java {
                // https://github.com/aerath-stuff/AndroidVMTools @ 6f5a8b7
                directories.add(rootDir.resolve("external/AndroidVMTools/src/main/java").path)
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

zygisk {
    packages(
        "com.android.systemui",
        "miui.systemui.plugin",
        "com.miui.systemui.plugin",
    )
    id = "autumn_systemui"
    name = "秋日状态栏"
    author = "Autumn"
    description = "隐藏状态栏 VoLTE，屏蔽滑条二级界面，熄屏指纹超时亮屏，控制中心用上下叠放的 WiFi 和移动数据替换播放卡片，锁屏右滑不进入功能页，锁屏触摸不延长亮屏"
    entrypoint = "io.autumn.systemui.zygote.ZygoteEntry"
    archiveName = "AutumnSystemUIPlus"
    isAddVariantToArchiveName = false
}

dependencies {
    implementation("com.github.aerath-stuff.PanamaPort:Core:ae4f24e")
    implementation("com.github.aerath-stuff.PanamaPort:Unsafe:ae4f24e")
    implementation("com.github.aerath-stuff.PanamaPort:LLVM:ae4f24e")
    implementation("io.github.vova7878:SunCleanerStub:v1.0.1")
    implementation("io.github.vova7878:R8Annotations:v1.0.1")
}
