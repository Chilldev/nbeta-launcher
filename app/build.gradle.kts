plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mali.newsfeed"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mali.newsfeed"
        minSdk = 29
        // 35 keeps KEYCODE_BACK delivery to our overlay window (36 forces predictive back).
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
