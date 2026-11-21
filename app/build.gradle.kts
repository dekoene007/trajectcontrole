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
        versionCode = 10
        versionName = "0.7.0"
        // enkel 64-bit ARM (alle recente gsm's, ook de S25): APK ±4x kleiner door de kaartbibliotheek
        ndk { abiFilters += "arm64-v8a" }
    }

    // Vaste sleutel in de repository: een APK uit GitHub Actions en een build uit Android Studio
    // zijn zo onderling uitwisselbaar (updaten zonder eerst te verwijderen). Enkel voor persoonlijk gebruik.
    signingConfigs {
        create("eigen") {
            storeFile = file("signing/trajectcontrole.jks")
            storePassword = "trajectcontrole"
            keyAlias = "trajectcontrole"
            keyPassword = "trajectcontrole"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("eigen") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("eigen")
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
    implementation("org.maplibre.gl:android-sdk:11.8.0")      // donkere kaart (OpenFreeMap-tegels, geen sleutel)
    testImplementation("junit:junit:4.13.2")
}
