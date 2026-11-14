plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.warung.autobag"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.warung.autobag"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Fondasi awal sengaja memakai Android SDK bawaan agar build pertama sederhana.
    // ML Kit OCR akan ditambahkan pada tahap integrasi OCR.
}
