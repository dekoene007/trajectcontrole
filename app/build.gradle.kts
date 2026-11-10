import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "be.koen.trajectcontrole"
    compileSdk = 36

    defaultConfig {
        applicationId = "be.koen.trajectcontrole"
        minSdk = 29          // Android 10
        targetSdk = 36
        versionCode = 8
        versionName = "0.5.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Voor eigen gebruik: release ondertekenen met de debug-sleutel, zodat je zonder keystore kunt installeren.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }   // vervangt kotlinOptions (verouderd sinds Kotlin 2.2)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")   // Android Auto (telefoonprojectie)
    testImplementation("junit:junit:4.13.2")
}
