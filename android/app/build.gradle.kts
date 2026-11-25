plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber: Int = (System.getenv("STREAMSYNC_BUILD_NUMBER")?.toIntOrNull()
    ?: project.findProperty("buildNumber")?.toString()?.toIntOrNull()
    ?: 1)

android {
    namespace = "com.streamsync.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.streamsync.app"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
