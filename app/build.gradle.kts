plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.kaveh.screenreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.kaveh.screenreader"
        minSdk = 30          // takeScreenshot() از سرویس دسترسی‌پذیری از API 30
        targetSdk = 35
        versionCode = 6
        versionName = "2.2"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // کلید ثابت تا نسخه‌های بعدی روی نسخه قبلی نصب شوند
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/debug.jks")
            storePassword = "android"
            keyAlias = "kaveh"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += "traineddata" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")
}
