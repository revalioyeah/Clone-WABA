
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
        versionCode = 2
        versionName = "0.2.0"
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

    // Google ML Kit Text Recognition v2
    // Bundled Latin OCR model
    implementation("com.google.mlkit:text-recognition:16.0.1")

}
