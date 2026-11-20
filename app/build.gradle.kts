plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bikeeyes.gps"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bikeeyes.gps"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
