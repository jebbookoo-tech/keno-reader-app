plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.kenoscreenreader"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.kenoscreenreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
