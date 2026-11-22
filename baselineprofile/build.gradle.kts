import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

// Generates the startup profile of the app: ./gradlew :app:generateBaselineProfile (an emulator managed by Gradle)
android {
    namespace = "io.github.sim7b.tele.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"

    testOptions.managedDevices.allDevices {
        create<ManagedVirtualDevice>("tvApi34") {
            device = "Television (1080p)"
            apiLevel = 34
            systemImageSource = "aosp-atd"
        }
    }
}

baselineProfile {
    managedDevices += "tvApi34"
    useConnectedDevices = false
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.1")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
