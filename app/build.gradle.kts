plugins {
    id("com.android.application")
}

// Version lives in BuildInfo.java so the Gradle and on-device (build.sh) builds always agree.
val buildInfo = file("src/main/java/app/piggy/desk/BuildInfo.java").readText()
val appVersionName = Regex("""VERSION = "([^"]+)"""").find(buildInfo)!!.groupValues[1]
val appVersionCode = Regex("""VERSION_CODE = (\d+)""").find(buildInfo)!!.groupValues[1].toInt()

android {
    // proot for the built-in Linux ships as native libraries and must be extracted to disk to run
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    namespace = "app.piggy.desk"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.piggy.desk"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Release signing comes from environment variables (set as GitHub Actions secrets).
    // Without them, assembleRelease produces an unsigned APK.
    val keystorePath = System.getenv("DESK_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("DESK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DESK_KEY_ALIAS") ?: "desk"
                keyPassword = System.getenv("DESK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = false
    }
}

