import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersion = Properties().apply {
    rootProject.file("release.properties").inputStream().use { load(it) }
}

android {
    namespace = "dev.lumencraft"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.lumencraft.android"
        minSdk = 23
        targetSdk = 35
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
    }
    splits.abi {
        isEnable = providers.gradleProperty("releaseSplits").orNull == "true"
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        isUniversalApk = true
    }
    buildTypes.getByName("release") {
        // A staged, signed APK and an independent checkout must have identical payloads.
        vcsInfo.include = false
    }
    sourceSets["main"].apply {
        java.srcDirs("../core/src/main/java", "../rooms/src/main/java")
        assets.srcDir("../rooms/src/main/assets")
        jniLibs.srcDir("../rooms/src/main/jniLibs")
    }
    packaging.jniLibs.useLegacyPackaging = true
    packaging.jniLibs.keepDebugSymbols.add("**/*.so")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies { implementation("org.tukaani:xz:1.12") }
