plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.wfdsink"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.wfdsink"
        minSdk = 22
        // Low targetSdk on purpose: no runtime permission dialogs (no remote to tap them)
        targetSdk = 25
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
