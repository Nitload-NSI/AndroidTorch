plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.androidtorch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.androidtorch"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}