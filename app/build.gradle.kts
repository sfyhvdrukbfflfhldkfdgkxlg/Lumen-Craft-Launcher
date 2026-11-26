plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lumencraft"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.lumencraft.android"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "Alpha1.0.0"
    }
    sourceSets["main"].java.srcDir("../core/src/main/java")
    packaging.jniLibs.useLegacyPackaging = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies { implementation("org.tukaani:xz:1.12") }
