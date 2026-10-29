plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vex.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vex.client"
        minSdk = 21
        targetSdk = 34
        versionCode = 12
        versionName = "3.4.0"
    }

    signingConfigs {
        create("vex") {
            storeFile = file("vex.keystore")
            storePassword = "vexVex2024"
            keyAlias = "vex"
            keyPassword = "vexVex2024"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("vex") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("vex")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // دوربین برای اسکنر QR
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // دیکودر QR به‌صورت آفلاین (مدل داخل APK؛ بدون نیاز به گوگل‌پلی)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
