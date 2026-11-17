plugins {
    id("com.android.application")
    id("com.github.aerath-stuff.ZygoteLoader") version "2a2160f"
}

android {
    namespace = "io.github.cobylinweiqi.fakeloc"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.cobylinweiqi.fakeloc.zygote"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
            java.directories.add(
                rootDir.resolve("../AutumnSystemUIPlus/external/AndroidVMTools/src/main/java").path,
            )
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
    // Empty on purpose. ZygoteLoader treats files in packages/ as the allow-list
    // and re-reads them on every fork, so the manager app adds names at runtime.
    id = "fakeloc"
    name = "FakeLoc"
    author = "cobylinweiqi"
    description = "按名单注入的虚拟定位。名单为空时不加载 dex。"
    entrypoint = "io.github.cobylinweiqi.fakeloc.zygote.ZygoteEntry"
    archiveName = "FakeLoc"
    isAddVariantToArchiveName = false
}

dependencies {
    implementation("com.github.aerath-stuff.PanamaPort:Core:ae4f24e")
    implementation("com.github.aerath-stuff.PanamaPort:Unsafe:ae4f24e")
    implementation("com.github.aerath-stuff.PanamaPort:LLVM:ae4f24e")
    implementation("io.github.vova7878:SunCleanerStub:v1.0.1")
    implementation("io.github.vova7878:R8Annotations:v1.0.1")
}
