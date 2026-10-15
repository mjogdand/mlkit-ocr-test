plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mlkitocrtest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mlkitocrtest"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // ML Kit Text Recognition v2, Devanagari model bundled in the APK
    // (also reads Latin, so voter IDs and digits are covered).
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
