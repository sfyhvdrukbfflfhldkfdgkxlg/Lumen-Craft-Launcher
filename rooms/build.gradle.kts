plugins { id("com.android.application") }
android {
    namespace = "dev.lumencraft.rooms"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.lumencraft.rooms"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "Alpha1.0.0"
    }
    sourceSets["main"].java.apply {
        srcDir("../core/src/main/java")
        include("dev/lumencraft/rooms/**","dev/lumencraft/core/ServerProbe.java","dev/lumencraft/core/SrvLookup.java")
    }
    packaging.jniLibs.useLegacyPackaging = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
