import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: keystore.properties (not versioned) at the project root,
// with storeFile, storePassword, keyAlias and keyPassword. Without this file, the release is not signed.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Version set by semantic-release in gradle.properties; versionCode = MAJOR * 1000000 + MINOR * 1000 + PATCH.
val appVersion = providers.gradleProperty("appVersion").get()
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    major * 1_000_000 + minor * 1_000 + patch
}.coerceAtLeast(1)

android {
    namespace = "io.github.sim7b.tele"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.sim7b.tele"
        minSdk = 26
        // 35, not 36 yet: from 36 predictive back is on by default and the Back key no longer reaches
        // the app as a key event (side panels, favorite move mode handle it as such)
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion

        vectorDrawables {
            useSupportLibrary = true
        }
        // Only native code: Conscrypt. Android TV devices are ARM; x86_64 for the emulator
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android & Lifecycle
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Jetpack Compose for TV (Official modern TV UI)
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")

    // AndroidX Media3 (ExoPlayer - Modern Video Player engine)
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    // Player HTTP transport through OkHttp: connections shared with the API, headers controlled per host
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    // Image loading for Channel Logos (Coil)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Coroutines for background M3U streaming & network operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // HTTP Client (OkHttp for resilient IPTV streams)
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    // TLS 1.3 on Android 9 and older (their system TLS stops at 1.2), see TlsCompat
    implementation("org.conscrypt:conscrypt-android:2.8.0")

    // Streaming JSON reader (okio-based, shared with OkHttp) for the large provider lists
    implementation("com.squareup.moshi:moshi:1.15.2")

    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation: the Android stub throws in local unit tests
    testImplementation("org.json:json:20260814")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
}
