plugins {
    id("com.android.application")
}

android {
    namespace = "dev.mike.flipspot"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mike.flipspot"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.0.1-m0"

        ndk {
            // No native code yet; this pins the ABIs once the Spotify .aar files arrive.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // M4: turn on R8
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 30 is deliberate: the TCL Flip 2 runs Android 11 and this is never shipped to Play.
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

// M0 has zero library dependencies: plain android.app.Activity + Views keeps the APK tiny.
dependencies {
}
