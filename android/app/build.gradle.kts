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
        versionName = "0.2.$buildNumber"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        val ksPath = System.getenv("STREAMSYNC_KEYSTORE_PATH")
        if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
            signingConfigs {
                create("ciRelease") {
                    storeFile = file(ksPath)
                    storePassword = System.getenv("STREAMSYNC_KEYSTORE_PASS") ?: "streamsync"
                    keyAlias = System.getenv("STREAMSYNC_KEY_ALIAS") ?: "streamsync"
                    keyPassword = System.getenv("STREAMSYNC_KEY_PASS") ?: "streamsync"
                }
            }
            getByName("debug") {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val media3Version = "1.4.1"

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Native Dual-Track & Adaptive Streaming Engine (ExoPlayer + MergingMediaSource)
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
