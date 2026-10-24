plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nshd.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nshd.jarvis"
        minSdk = 26
        targetSdk = 28
        versionCode = 5
        versionName = "0.4.0"
    }

    // Permanent signing: when the key is supplied (GitHub Secrets → environment), every build is signed with the
    // same certificate, so a new APK installs over the old one without uninstalling.
    val zaraKeystore = System.getenv("ZARA_KEYSTORE_PATH")
    signingConfigs {
        create("zara") {
            if (!zaraKeystore.isNullOrBlank()) {
                storeFile = file(zaraKeystore)
                storeType = "pkcs12"
                storePassword = System.getenv("ZARA_KEY_PASSWORD")
                keyAlias = "zara"
                keyPassword = System.getenv("ZARA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (!zaraKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("zara")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // real org.json for JVM unit tests (Android's is a stub there)
}
